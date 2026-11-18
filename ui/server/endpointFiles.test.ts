import { mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { endpointFiles, etagOf, listEndpoints, type EndpointFilesOptions } from './endpointFiles';

const origins = ['http://localhost:5173'];
const page = { 'sec-fetch-site': 'same-origin', origin: 'http://localhost:5173' };

const httpIn = `# the door
schemaVersion: 1
kind: endpoint
id: http-in
scheme: http
direction: in
config:
  host: 0.0.0.0   # every interface
  port: 8080
`;

let root: string;
let outside: string;

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'rail8-workspace-'));
  outside = await mkdtemp(join(tmpdir(), 'rail8-outside-'));
  await mkdir(join(root, 'demo', 'endpoints'), { recursive: true });
  await mkdir(join(root, 'demo', 'flows'), { recursive: true });
  await mkdir(join(root, '.git'), { recursive: true });
  await mkdir(join(root, 'node_modules'), { recursive: true });
  await writeFile(join(root, 'demo', 'endpoints', 'http-in.yaml'), httpIn);
  await writeFile(join(root, 'demo', 'endpoints', 'http-in.test.yaml'), httpIn);
  await writeFile(join(root, 'demo', 'endpoints', 'broken.yaml'), 'kind: endpoint\nid: broken\n');
  await writeFile(join(root, 'demo', 'flows', 'orders.yaml'), 'schemaVersion: 1\nkind: flow\nid: orders\n');
  await writeFile(join(root, 'demo', 'notes.yaml'), 'kind: endpoint\nid: [unclosed\n');
  await writeFile(join(root, 'other.yaml'), '- not: an endpoint\n');
  await writeFile(join(root, '.git', 'hidden.yaml'), httpIn);
  await writeFile(join(root, 'node_modules', 'dep.yaml'), httpIn);
  await writeFile(join(outside, 'secret.yaml'), httpIn);
  await symlink(join(outside, 'secret.yaml'), join(root, 'demo', 'endpoints', 'linked.yaml'));
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
  await rm(outside, { recursive: true, force: true });
});

describe('listEndpoints', () => {
  it('finds endpoint files by their kind, and names one it cannot read', async () => {
    expect(await listEndpoints(root)).toEqual({
      items: [
        { path: 'demo/endpoints/broken.yaml', problem: 'an endpoint file names its id and scheme' },
        { path: 'demo/endpoints/http-in.yaml', id: 'http-in', scheme: 'http', direction: 'in' },
        { path: 'demo/notes.yaml', problem: expect.stringContaining('the file is not YAML') },
      ],
      truncated: false,
    });
  });
});

describe('endpointFiles', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
  });

  async function serve(options: Partial<EndpointFilesOptions> = {}): Promise<string> {
    const middleware = endpointFiles({ workspace: root, editable: true, origins, ...options });
    const server = createServer((req, res) => middleware(req, res, () => res.writeHead(404).end('next')));
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  }

  interface Answer {
    status: number;
    etag?: string;
    body: Record<string, unknown>;
  }

  function call(url: string, method = 'GET', headers: Record<string, string> = page, sent?: string): Promise<Answer> {
    return new Promise((done, failed) => {
      const req = request(url, { method, headers }, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          done({ status: res.statusCode ?? 0, etag: res.headers.etag, body: text.startsWith('{') ? JSON.parse(text) : { text } });
        });
      });
      req.on('error', failed);
      req.end(sent);
    });
  }

  const save = (url: string, config: unknown, etag: string | undefined) =>
    call(url, 'PUT', { ...page, 'content-type': 'application/json', ...(etag === undefined ? {} : { 'if-match': etag }) }, JSON.stringify({ config }));

  it('lists the working folder and says whether it may be edited', async () => {
    const base = await serve({ editable: false });
    const answer = await call(`${base}/files/endpoints`);
    expect(answer.status).toBe(200);
    expect(answer.body).toMatchObject({ configured: true, editable: false, truncated: false });
    expect((answer.body.items as unknown[]).length).toBe(3);
  });

  it('says when no working folder is set', async () => {
    const base = await serve({ workspace: undefined });
    expect((await call(`${base}/files/endpoints`)).body).toEqual({ configured: false, editable: false, items: [], truncated: false });
    expect((await call(`${base}/files/endpoints/demo/endpoints/http-in.yaml`)).status).toBe(404);
  });

  it('reads one endpoint file with its ETag', async () => {
    const base = await serve();
    const answer = await call(`${base}/files/endpoints/demo/endpoints/http-in.yaml`);
    expect(answer.status).toBe(200);
    expect(answer.etag).toBe(etagOf(httpIn));
    expect(answer.body).toEqual({
      path: 'demo/endpoints/http-in.yaml',
      editable: true,
      id: 'http-in',
      scheme: 'http',
      direction: 'in',
      description: null,
      config: { host: '0.0.0.0', port: 8080 },
      environments: [],
    });
  });

  it('saves only the edited part, through a temporary file renamed over it', async () => {
    const base = await serve();
    const url = `${base}/files/endpoints/demo/endpoints/http-in.yaml`;
    const answer = await save(url, { host: '0.0.0.0', port: 8081, path: '/in' }, etagOf(httpIn));
    expect(answer.status).toBe(200);
    const text = await readFile(join(root, 'demo', 'endpoints', 'http-in.yaml'), 'utf8');
    expect(text).toBe(httpIn.replace('  port: 8080\n', '  port: 8081\n  path: /in\n'));
    expect(answer.etag).toBe(etagOf(text));
    expect(answer.body).toMatchObject({ config: { host: '0.0.0.0', port: 8081, path: '/in' } });
    expect((await readdir(join(root, 'demo', 'endpoints'))).filter((n) => n.endsWith('.tmp'))).toEqual([]);
  });

  it('refuses a save without If-Match, or one made over a file that changed since', async () => {
    const base = await serve();
    const url = `${base}/files/endpoints/demo/endpoints/http-in.yaml`;
    expect((await save(url, { port: 1 }, undefined)).status).toBe(428);
    expect((await save(url, { port: 1 }, '"sha256-other"')).status).toBe(412);
    expect(await readFile(join(root, 'demo', 'endpoints', 'http-in.yaml'), 'utf8')).toBe(httpIn);
  });

  it('refuses a save when the folder is read-only, or the edit cannot be made line by line', async () => {
    const readOnly = await serve({ editable: false });
    expect((await save(`${readOnly}/files/endpoints/demo/endpoints/http-in.yaml`, { port: 1 }, etagOf(httpIn))).status).toBe(403);
    const flow = 'kind: endpoint\nid: f\nscheme: http\ndirection: in\nconfig: {port: 1}\n';
    await writeFile(join(root, 'demo', 'endpoints', 'flow.yaml'), flow);
    const base = await serve();
    const refused = await save(`${base}/files/endpoints/demo/endpoints/flow.yaml`, { port: 2 }, etagOf(flow));
    expect(refused.status).toBe(422);
    expect(refused.body.error).toContain('text editor');
    expect(await readFile(join(root, 'demo', 'endpoints', 'flow.yaml'), 'utf8')).toBe(flow);
  });

  it('refuses a body that is not {"config": {...}}', async () => {
    const base = await serve();
    const url = `${base}/files/endpoints/demo/endpoints/http-in.yaml`;
    expect((await save(url, [1], etagOf(httpIn))).status).toBe(400);
    expect((await call(url, 'PUT', { ...page, 'if-match': etagOf(httpIn) }, 'not json')).status).toBe(400);
  });

  it('never leaves the working folder', async () => {
    const base = await serve();
    expect((await call(`${base}/files/endpoints/demo/endpoints/linked.yaml`)).status).toBe(403);
    expect((await call(`${base}/files/endpoints/demo/..%2F..%2Fetc/passwd.yaml`)).status).toBe(400);
    expect((await call(`${base}/files/endpoints/demo/flows/orders.yaml`)).status).toBe(404);
    expect((await call(`${base}/files/endpoints/demo/flows/missing.yaml`)).status).toBe(404);
  });

  it("answers only the UI's own pages", async () => {
    const base = await serve();
    expect((await call(`${base}/files/endpoints`, 'GET', {})).status).toBe(403);
    expect((await call(`${base}/files/endpoints`, 'GET', { 'sec-fetch-site': 'cross-site' })).status).toBe(403);
    expect((await call(`${base}/files/endpoints`, 'GET', { ...page, origin: 'http://evil.example' })).status).toBe(403);
  });

  it('takes only GET and PUT, and leaves every other path to the next middleware', async () => {
    const base = await serve();
    expect((await call(`${base}/files/endpoints`, 'DELETE')).status).toBe(405);
    expect((await call(`${base}/files/endpoints`, 'PUT')).status).toBe(405);
    expect((await call(`${base}/files/other`)).body).toEqual({ text: 'next' });
  });
});
