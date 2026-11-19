import { mkdir, mkdtemp, readdir, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { createServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { flowFiles, type FlowFilesOptions } from './flowFiles';
import { etagOf, MAX_FILE_BYTES } from '../../../server/workingFolder';

const origins = ['http://localhost:5173'];
const page = { 'sec-fetch-site': 'same-origin', origin: 'http://localhost:5173' };

const caller = `# calls the partner flow
schemaVersion: 1
kind: flow
id: caller
version: 0.1.0
from: { endpoint: http-in }
delivery: { mode: sync }
limits: { maxBodyBytes: 1024 }
steps:
  - type: to
    endpoint: call-partner
`;
const partner = `schemaVersion: 1
kind: flow
id: partner
version: 0.1.0
from: { endpoint: partner-in }
delivery: { mode: at-least-once }
limits: { maxBodyBytes: 1024 }
steps:
  - { type: to, endpoint: partner-out }
`;
const endpoint = (id: string, scheme: string, direction: string, config: string, rest = '') =>
  `schemaVersion: 1\nkind: endpoint\nid: ${id}\nscheme: ${scheme}\ndirection: ${direction}\nconfig:\n${config}${rest}`;

let root: string;
let outside: string;

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'rail8-workspace-'));
  outside = await mkdtemp(join(tmpdir(), 'rail8-outside-'));
  for (const folder of ['demo/flows', 'demo/endpoints', 'other/flows', 'other/endpoints', '.git']) await mkdir(join(root, folder), { recursive: true });
  await writeFile(join(root, 'demo', 'package.yaml'), 'schemaVersion: 1\nkind: package\nname: demo\nversion: 0.1.0\n');
  await writeFile(join(root, 'demo', 'flows', 'caller.yaml'), caller);
  await writeFile(join(root, 'demo', 'flows', 'partner.yml'), partner);
  await writeFile(join(root, 'demo', 'flows', 'caller.test.yaml'), 'schemaVersion: 1\nkind: test\nflow: caller\n');
  await writeFile(join(root, 'demo', 'flows', 'broken.yaml'), 'kind: flow\nid: [unclosed\n');
  await writeFile(join(root, 'demo', 'flows', 'nameless.yaml'), 'kind: flow\nsteps: []\n');
  await writeFile(join(root, 'demo', 'notes.yaml'), 'just: notes\n');
  await writeFile(join(root, 'demo', 'endpoints', 'http-in.yaml'), endpoint('http-in', 'http', 'in', '  port: 8080\n'));
  await writeFile(join(root, 'demo', 'endpoints', 'call-partner.yaml'), endpoint('call-partner', 'direct', 'out', '  address: /partner\n'));
  await writeFile(join(root, 'demo', 'endpoints', 'partner-in.yaml'), endpoint('partner-in', 'direct', 'in', '  address: /partner\n'));
  await writeFile(
    join(root, 'demo', 'endpoints', 'partner-out.yaml'),
    endpoint('partner-out', 'direct', 'out', '  address: /far\n', 'environments:\n  prod:\n    config:\n      address: /farther\n'),
  );
  await writeFile(join(root, 'other', 'package.yaml'), 'schemaVersion: 1\nkind: package\nname: other\nversion: 0.1.0\n');
  await writeFile(join(root, 'other', 'endpoints', 'elsewhere.yaml'), endpoint('elsewhere', 'http', 'out', '  url: http://x\n'));
  await writeFile(join(root, '.git', 'hidden.yaml'), caller);
  await writeFile(join(outside, 'secret.yaml'), caller);
  await symlink(join(outside, 'secret.yaml'), join(root, 'demo', 'flows', 'linked.yaml'));
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
  await rm(outside, { recursive: true, force: true });
});

describe('flowFiles', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
  });

  async function serve(options: Partial<FlowFilesOptions> = {}): Promise<string> {
    const middleware = flowFiles({ workspace: root, editable: true, origins, ...options });
    const server = createServer((req, res) => middleware(req, res, () => res.writeHead(404).end('next')));
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  }

  function call(url: string, method = 'GET', headers: Record<string, string> = page, body?: string): Promise<{ status: number; etag?: string; body: Record<string, unknown> }> {
    return new Promise((done, failed) => {
      const req = request(url, { method, headers: body === undefined ? headers : { ...headers, 'content-type': 'application/json' } }, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          done({ status: res.statusCode ?? 0, etag: res.headers.etag, body: text.startsWith('{') ? JSON.parse(text) : { text } });
        });
      });
      req.on('error', failed);
      req.end(body);
    });
  }

  it('lists every yaml and yml file of the kind flow, and names one it cannot read', async () => {
    const answer = await call(`${await serve({ editable: false })}/files/flows`);
    expect(answer.status).toBe(200);
    expect(answer.body).toEqual({
      configured: true,
      editable: false,
      truncated: false,
      items: [
        { path: 'demo/flows/broken.yaml', problem: expect.stringContaining('the file is not YAML') },
        { path: 'demo/flows/caller.yaml', id: 'caller' },
        { path: 'demo/flows/nameless.yaml', problem: 'a flow file names its id' },
        { path: 'demo/flows/partner.yml', id: 'partner' },
      ],
    });
  });

  it('answers a flow file with its text, its ETag and its context', async () => {
    const answer = await call(`${await serve()}/files/flows/demo/flows/caller.yaml`);
    expect(answer.status).toBe(200);
    expect(answer.etag).toBe(etagOf(caller));
    expect(answer.body).toEqual({
      path: 'demo/flows/caller.yaml',
      text: caller,
      editable: true,
      context: {
        endpoints: [
          { id: 'call-partner', path: 'demo/endpoints/call-partner.yaml', scheme: 'direct', direction: 'out', address: '/partner' },
          { id: 'http-in', path: 'demo/endpoints/http-in.yaml', scheme: 'http', direction: 'in' },
          { id: 'partner-in', path: 'demo/endpoints/partner-in.yaml', scheme: 'direct', direction: 'in', address: '/partner' },
          { id: 'partner-out', path: 'demo/endpoints/partner-out.yaml', scheme: 'direct', direction: 'out', address: '/far', overlaid: true, addressIn: { prod: '/farther' } },
        ],
        flows: [
          { path: 'demo/flows/caller.yaml', id: 'caller', mode: 'sync' },
          { path: 'demo/flows/partner.yml', id: 'partner', mode: 'at-least-once', address: '/partner' },
        ],
        partial: true,
      },
    });
  });

  it('answers a flow file that cannot be drawn with its text', async () => {
    const answer = await call(`${await serve()}/files/flows/demo/flows/broken.yaml`);
    expect(answer.status).toBe(200);
    expect(answer.body.text).toBe('kind: flow\nid: [unclosed\n');
  });

  it('a package whose files can all be read is no partial context', async () => {
    await rm(join(root, 'demo', 'flows', 'broken.yaml'));
    await rm(join(root, 'demo', 'flows', 'nameless.yaml'));
    const answer = await call(`${await serve()}/files/flows/demo/flows/partner.yml`);
    expect((answer.body.context as { partial: boolean }).partial).toBe(false);
  });

  it('refuses what is no flow file of the working folder', async () => {
    const base = await serve();
    expect((await call(`${base}/files/flows/demo/notes.yaml`)).status).toBe(404);
    expect((await call(`${base}/files/flows/demo/flows/caller.test.yaml`)).status).toBe(404);
    expect((await call(`${base}/files/flows/demo/flows/missing.yaml`)).status).toBe(404);
    expect((await call(`${base}/files/flows/demo/flows/linked.yaml`)).status).toBe(403);
    expect((await call(`${base}/files/flows/demo/%2E%2E%2Fx.yaml`)).status).toBe(400);
    expect((await call(`${base}/files/flows/demo/package.json`)).status).toBe(400);
  });

  it('refuses a request from elsewhere, and every method but GET and a PUT of one file', async () => {
    const base = await serve();
    expect((await call(`${base}/files/flows`, 'GET', { 'sec-fetch-site': 'cross-site', origin: 'http://evil.example' })).status).toBe(403);
    expect((await call(`${base}/files/flows/demo/flows/caller.yaml`, 'PUT', { 'sec-fetch-site': 'cross-site', origin: 'http://evil.example', 'if-match': etagOf(caller) }, JSON.stringify({ text: caller }))).status).toBe(403);
    expect((await call(`${base}/files/flows/demo/flows/caller.yaml`, 'DELETE')).status).toBe(405);
    expect((await call(`${base}/files/flows`, 'PUT')).status).toBe(405);
  });

  describe('a save', () => {
    const edited = caller.replace('  - type: to\n', '  - type: validate\n    required: "Amount > 0"\n  - type: to\n');
    const put = (base: string, text: unknown, etag: string | null = etagOf(caller), path = 'demo/flows/caller.yaml') =>
      call(`${base}/files/flows/${path}`, 'PUT', etag === null ? page : { ...page, 'if-match': etag }, JSON.stringify({ text }));
    const onDisk = () => readFile(join(root, 'demo', 'flows', 'caller.yaml'), 'utf8');

    it('writes the text and answers as GET, with the new ETag', async () => {
      const base = await serve();
      const answer = await put(base, edited);
      expect(answer.status).toBe(200);
      expect(answer.etag).toBe(etagOf(edited));
      expect(answer.body).toMatchObject({ path: 'demo/flows/caller.yaml', text: edited, editable: true, context: { flows: expect.any(Array) } });
      expect(await onDisk()).toBe(edited);
      expect((await readdir(join(root, 'demo', 'flows'))).filter((f) => f.endsWith('.tmp'))).toEqual([]);
      expect((await call(`${base}/files/flows/demo/flows/caller.yaml`)).etag).toBe(etagOf(edited));
    });

    it('does not write a text equal to the file, so its ETag stays', async () => {
      const before = (await stat(join(root, 'demo', 'flows', 'caller.yaml'))).mtimeMs;
      const answer = await put(await serve(), caller);
      expect(answer.status).toBe(200);
      expect(answer.etag).toBe(etagOf(caller));
      expect((await stat(join(root, 'demo', 'flows', 'caller.yaml'))).mtimeMs).toBe(before);
    });

    it('refuses a save on a read-only server, without If-Match, or of a file changed since it was read', async () => {
      expect((await put(await serve({ editable: false }), edited)).status).toBe(403);
      const base = await serve();
      expect(await put(base, edited, null)).toMatchObject({ status: 428, body: { error: 'a save names the version it changes in If-Match, or creates the file with If-None-Match: *' } });
      expect(await put(base, edited, etagOf('something else'))).toMatchObject({ status: 412, body: { error: 'the file changed since it was read; nothing was saved' } });
      expect(await onDisk()).toBe(caller);
    });

    it('refuses a text that is no YAML or no flow, as the browser reads it, and one over 1 MiB', async () => {
      const base = await serve();
      expect(await put(base, 'kind: flow\nid: [unclosed\n')).toMatchObject({ status: 422, body: { error: expect.stringContaining('the file is not YAML') } });
      expect(await put(base, 'kind: endpoint\nid: x\n')).toMatchObject({ status: 422, body: { error: 'the text is not a flow' } });
      expect(await put(base, 'kind: flow\nid: caller\nsteps: {}\n')).toMatchObject({ status: 422, body: { error: 'the flow has no list of steps under steps' } });
      expect((await put(base, 42)).status).toBe(400);
      expect((await put(base, `${caller}#${'x'.repeat(MAX_FILE_BYTES)}\n`)).status).toBe(413);
      expect(await onDisk()).toBe(caller);
    });

    it('refuses a save of what is no flow file of the working folder', async () => {
      const base = await serve();
      expect((await put(base, edited, etagOf(caller), 'demo/flows/missing.yaml')).status).toBe(404);
      expect((await put(base, edited, etagOf('just: notes\n'), 'demo/notes.yaml')).status).toBe(404);
      expect((await put(base, edited, etagOf(caller), 'demo/flows/linked.yaml')).status).toBe(403);
    });

    it('makes saves one at a time, so the second of two with the same ETag is refused', async () => {
      const base = await serve();
      const [first, second] = await Promise.all([put(base, edited), put(base, edited.replace('caller', 'other'))]);
      expect([first.status, second.status]).toEqual([200, 412]);
      expect(await onDisk()).toBe(edited);
    });
  });

  describe('a create', () => {
    const fresh = caller.replace('id: caller', 'id: fresh');
    const create = (base: string, path: string, text: unknown = fresh, headers: Record<string, string> = { ...page, 'if-none-match': '*' }) =>
      call(`${base}/files/flows/${path}`, 'PUT', headers, JSON.stringify({ text }));

    it('writes a new flow file and answers as GET, with its ETag', async () => {
      const base = await serve();
      const answer = await create(base, 'demo/flows/fresh.yaml');
      expect(answer.status).toBe(201);
      expect(answer.etag).toBe(etagOf(fresh));
      expect(answer.body).toMatchObject({ path: 'demo/flows/fresh.yaml', text: fresh, editable: true, context: { endpoints: expect.any(Array) } });
      expect(await readFile(join(root, 'demo', 'flows', 'fresh.yaml'), 'utf8')).toBe(fresh);
      expect((await readdir(join(root, 'demo', 'flows'))).filter((f) => f.endsWith('.tmp'))).toEqual([]);
      expect((await call(`${base}/files/flows`)).body.items).toContainEqual({ path: 'demo/flows/fresh.yaml', id: 'fresh' });
    });

    it('refuses to write over a file that exists, a link included', async () => {
      const base = await serve();
      expect(await create(base, 'demo/flows/caller.yaml')).toMatchObject({ status: 412, body: { error: 'the file exists already; nothing was written' } });
      expect((await create(base, 'demo/flows/linked.yaml')).status).toBe(412);
      expect(await readFile(join(root, 'demo', 'flows', 'caller.yaml'), 'utf8')).toBe(caller);
      expect(await readFile(join(outside, 'secret.yaml'), 'utf8')).toBe(caller);
    });

    it('keeps the guards of a save: read-only, the working folder, its folders, yaml or yml, a flow', async () => {
      await symlink(outside, join(root, 'demo', 'away'));
      expect((await create(await serve({ editable: false }), 'demo/flows/fresh.yaml')).status).toBe(403);
      const base = await serve();
      expect((await create(base, 'demo/away/fresh.yaml')).status).toBe(403);
      expect((await create(base, 'demo/missing/fresh.yaml')).status).toBe(404);
      expect((await create(base, 'demo/flows/fresh.json')).status).toBe(400);
      expect((await create(base, 'demo/%2E%2E%2Ffresh.yaml')).status).toBe(400);
      expect((await create(base, '.git/fresh.yaml')).status).toBe(400);
      expect((await create(base, 'demo/flows/fresh.yaml', 'kind: endpoint\nid: x\n')).status).toBe(422);
      expect((await create(base, 'demo/flows/fresh.yaml', `${fresh}#${'x'.repeat(MAX_FILE_BYTES)}\n`)).status).toBe(413);
      expect((await create(base, 'demo/flows/fresh.yaml', fresh, { ...page, 'if-none-match': '"v1"' })).status).toBe(428);
      expect((await create(base, 'demo/flows/fresh.yaml', fresh, { ...page, 'if-none-match': '*', 'if-match': etagOf(caller) })).status).toBe(400);
      expect(await readdir(outside)).toEqual(['secret.yaml']);
      expect(await readdir(join(root, 'demo', 'flows'))).not.toContain('fresh.yaml');
    });

    it('accepts a request from elsewhere never', async () => {
      const answer = await create(await serve(), 'demo/flows/fresh.yaml', fresh, { 'sec-fetch-site': 'cross-site', origin: 'http://evil.example', 'if-none-match': '*' });
      expect(answer.status).toBe(403);
    });
  });

  it('says when no working folder is set', async () => {
    const base = await serve({ workspace: undefined });
    expect((await call(`${base}/files/flows`)).body).toEqual({ configured: false, editable: false, items: [], truncated: false });
    expect((await call(`${base}/files/flows/demo/flows/caller.yaml`)).status).toBe(404);
  });

  it('passes every other path on', async () => {
    expect((await call(`${await serve()}/files/endpoints`)).body).toEqual({ text: 'next' });
  });
});
