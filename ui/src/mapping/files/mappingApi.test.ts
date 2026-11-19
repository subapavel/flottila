import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer, request, type IncomingHttpHeaders, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { mappingApi, type MappingApiOptions } from './mappingApi';

/** One call the fake node was given: the operation, method, headers and the call's JSON, with its base64 decoded. */
interface NodeCall {
  op: string;
  method: string;
  headers: IncomingHttpHeaders;
  request?: string;
  files?: Record<string, string>;
  escaping?: string[];
  reads?: string;
  closed: Promise<void>;
}

/** What the fake node answers a call with. */
interface NodeAnswer {
  status: number;
  json?: unknown;
  text?: string;
  type?: string;
}

type Handler = (call: NodeCall) => NodeAnswer | Promise<NodeAnswer>;

const STUDIO = 'urn:rail8:mapping:problem:';

/** A model as the node answers it in canonical form. */
const canonical = (n: number) => `{"formatVersion": 1, "n": ${n}}\n`;
const tagOf = (text: string) => `"sha256:${createHash('sha256').update(text).digest('hex')}"`;
const decode = (text: string) => Buffer.from(text, 'base64').toString('utf8');

const filesNeeded = (...references: string[]): NodeAnswer => ({
  status: 409,
  type: 'application/problem+json',
  json: { type: `${STUDIO}files-needed`, title: 'Files needed', status: 409, detail: 'send them', references },
});

const studioProblem = (status: number, slug: string, members: Record<string, unknown> = {}): NodeAnswer => ({
  status,
  type: 'application/problem+json',
  json: { type: `${STUDIO}${slug}`, title: slug, status, detail: `the node says ${slug}`, ...members },
});

let temp: string;
let root: string;
let node: Server;
let front: Server;
let nodeUrl: string;
let frontPort: number;
let calls: NodeCall[];
let handler: Handler;
/** The `i`th call the fake node was given. */
function nodeCall(i: number): NodeCall {
  const found = calls[i];
  if (found === undefined) throw new Error(`the node was given ${calls.length} calls, not ${i + 1}`);
  return found;
}

/** How many requests the development server has read to their end. */
let ended: number;

const listen = (server: Server) =>
  new Promise<number>((done) => server.listen(0, '127.0.0.1', () => done((server.address() as AddressInfo).port)));

async function startFront(options: Partial<MappingApiOptions> = {}) {
  const middleware = mappingApi({ workspace: root, editable: true, origins: ['http://localhost:5173'], node: { target: nodeUrl, token: 'secret' }, ...options });
  front = createServer((req, res) => {
    req.on('end', () => ended++);
    middleware(req, res, () => res.writeHead(418).end('next'));
  });
  frontPort = await listen(front);
}

beforeEach(async () => {
  temp = await realpath(await mkdtemp(join(tmpdir(), 'rail8-mapping-api-')));
  root = join(temp, 'workspace');
  await mkdir(root);
  calls = [];
  ended = 0;
  handler = () => ({ status: 500, text: 'no handler' });
  node = createServer((req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      const op = (req.url ?? '').replace('/admin/modules/mapping/', '');
      const body = chunks.length === 0 ? {} : (JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>);
      const closed = new Promise<void>((done) => res.on('close', () => done()));
      const call: NodeCall = {
        op,
        method: req.method ?? '',
        headers: req.headers,
        closed,
        ...(typeof body.request === 'string' ? { request: decode(body.request) } : {}),
        ...(body.files === undefined ? {} : { files: Object.fromEntries(Object.entries(body.files as Record<string, string>).map(([k, v]) => [k, decode(v)])) }),
        ...(body.escaping === undefined ? {} : { escaping: body.escaping as string[] }),
        ...(body.reads === undefined ? {} : { reads: body.reads as string }),
      };
      calls.push(call);
      void Promise.resolve(handler(call)).then((answer) => {
        if (res.destroyed) return;
        const text = answer.text ?? JSON.stringify(answer.json);
        res.writeHead(answer.status, { 'content-type': answer.type ?? 'application/json' }).end(text);
      });
    });
  });
  nodeUrl = `http://127.0.0.1:${await listen(node)}`;
  await startFront();
});

afterEach(async () => {
  await new Promise((done) => front.close(done));
  node.closeAllConnections();
  await new Promise((done) => node.close(done));
  await rm(temp, { recursive: true, force: true });
});

interface Reply {
  status: number;
  headers: IncomingHttpHeaders;
  text: string;
  json: Record<string, unknown> & { type?: string; detail?: string };
}

const SAME_ORIGIN = { 'sec-fetch-site': 'same-origin', 'content-type': 'application/json' };

/** Calls the development server; a request with a body comes from its own page unless `headers` says otherwise. */
function call(method: string, path: string, body?: string | Buffer, headers: Record<string, string> = body === undefined ? {} : SAME_ORIGIN): Promise<Reply> {
  return new Promise((done, fail) => {
    const req = request({ host: '127.0.0.1', port: frontPort, method, path, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = {};
        try {
          json = JSON.parse(text) as Record<string, unknown>;
        } catch {
          // not every answer is JSON
        }
        done({ status: res.statusCode ?? 0, headers: res.headers, text, json });
      });
    });
    req.on('error', fail);
    req.end(body);
  });
}

async function put(path: string, text: string) {
  await mkdir(join(root, path, '..'), { recursive: true });
  await writeFile(join(root, path), text);
}

const read = (path: string) => readFile(join(root, path), 'utf8');

function expectProblem(reply: Reply, status: number, type: string) {
  expect(reply.status, reply.text).toBe(status);
  expect(reply.headers['content-type']).toBe('application/problem+json');
  expect(reply.json).toMatchObject({ type, status, title: expect.any(String), detail: expect.any(String) });
}

describe('the list of mappings', () => {
  test('lists the mapping files by id, with what each names, and asks the node nothing', async () => {
    await put('summaries/elements.mapping.json', JSON.stringify({
      formatVersion: 1,
      description: 'Bestellungen → Rechnungen',
      source: { bundle: ['../structures/order.xsd'], root: { element: 'Q{urn:rail8:test:order}Order' }, fingerprint: 'x' },
      target: { bundle: ['../structures/invoice.xsd'], root: { element: 'Invoice' }, fingerprint: 'x' },
    }));
    await put('summaries/deep/wsdl-json.mapping.json', JSON.stringify({
      formatVersion: 1,
      source: { bundle: ['../../s/order.wsdl'], root: { wsdl: { interface: 'P', operation: 'SendOrder', direction: 'fault', fault: 'Rejected' } } },
      target: { bundle: ['../../s/json/a-note.schema.json', '../../s/json/z-defs.schema.json'], root: { json: '$' } },
    }));
    await put('summaries/wrong-types.mapping.json', JSON.stringify({ description: 7, source: { bundle: 'a.xsd', root: { element: 'A' } }, target: { bundle: ['b.xsd'], root: { element: 3 } } }));
    await put('summaries/extra.mapping.json', JSON.stringify({ formatVersion: 1, extra: 1, description: 'still shown' }));
    await put('summaries/no-json.mapping.json', 'not a JSON document');
    await put('summaries/large.mapping.json', `{"description": "${'x'.repeat(1024 * 1024)}"}`);
    await put('top.mapping.json', '{}');
    await put('summaries/b-c.mapping.json', '{}');
    for (const never of ['.hidden/x', 'bad name', 'd/test', 'node_modules/n']) await put(`summaries/${never}.mapping.json`, '{}');
    await put('summaries/other.json', '{}');

    const reply = await call('GET', '/api/mappings');

    expect(reply.status, reply.text).toBe(200);
    expect(reply.headers['content-type']).toBe('application/json');
    expect(reply.headers['rail8-truncated']).toBeUndefined();
    expect(reply.json).toEqual([
      { id: 'summaries/b-c', name: 'b-c', package: 'summaries' },
      { id: 'summaries/deep/wsdl-json', name: 'wsdl-json', package: 'summaries/deep', source: 'SendOrder', target: 'a-note.schema.json' },
      { id: 'summaries/elements', name: 'elements', package: 'summaries', description: 'Bestellungen → Rechnungen', source: 'Order', target: 'Invoice' },
      { id: 'summaries/extra', name: 'extra', package: 'summaries', description: 'still shown' },
      { id: 'summaries/large', name: 'large', package: 'summaries' },
      { id: 'summaries/no-json', name: 'no-json', package: 'summaries' },
      { id: 'summaries/wrong-types', name: 'wrong-types', package: 'summaries' },
      { id: 'top', name: 'top', package: '' },
    ]);
    expect(calls).toEqual([]);
  });

  test('works while the node is down', async () => {
    await new Promise((done) => front.close(done));
    await startFront({ node: { target: 'http://127.0.0.1:1' } });
    await put('a/m.mapping.json', '{}');

    const reply = await call('GET', '/api/mappings');

    expect(reply.status).toBe(200);
    expect(reply.json).toEqual([{ id: 'a/m', name: 'm', package: 'a' }]);
  });

  test('says Rail8-Truncated: true past 1,000 mapping files', async () => {
    await mkdir(join(root, 'many'));
    for (let i = 0; i <= 1000; i++) await writeFile(join(root, 'many', `m${String(i).padStart(4, '0')}.mapping.json`), '{}');

    const reply = await call('GET', '/api/mappings');

    expect(reply.status).toBe(200);
    expect(reply.headers['rail8-truncated']).toBe('true');
    expect(reply.json).toHaveLength(1000);
  });

  test('is 404 not-found when no working folder is set', async () => {
    await new Promise((done) => front.close(done));
    await startFront({ workspace: undefined });

    const reply = await call('GET', '/api/mappings');

    expectProblem(reply, 404, `${STUDIO}not-found`);
    expect(reply.json.detail).toContain('RAIL8_WORKSPACE');
  });
});

describe('opening a mapping', () => {
  test("answers the node's canonical form of the stored file, with the ETag of exactly those bytes", async () => {
    await put('mappings/order.mapping.json', '{"formatVersion":1}');
    handler = () => ({ status: 200, text: canonical(1) });

    const reply = await call('GET', '/api/mappings/mappings/order');

    expect(reply.status, reply.text).toBe(200);
    expect(reply.headers['content-type']).toBe('application/json');
    expect(reply.text).toBe(canonical(1));
    expect(reply.headers.etag).toBe(tagOf(canonical(1)));
    expect(calls).toHaveLength(1);
    expect(nodeCall(0)).toMatchObject({ op: 'canonical', method: 'POST', request: '{"formatVersion":1}', reads: 'stored' });
    expect(nodeCall(0).files).toBeUndefined();
  });

  test('an encoded slash names the same id', async () => {
    await put('mappings/order.mapping.json', '{}');
    handler = () => ({ status: 200, text: canonical(1) });

    const reply = await call('GET', '/api/mappings/mappings%2Forder');

    expect(reply.status).toBe(200);
    expect(nodeCall(0).request).toBe('{}');
  });

  test("passes on the node's refusal of the stored file", async () => {
    await put('mappings/order.mapping.json', 'not a JSON document');
    handler = () => studioProblem(422, 'model-refused', { diagnostics: [] });

    const reply = await call('GET', '/api/mappings/mappings/order');

    expectProblem(reply, 422, `${STUDIO}model-refused`);
    expect(reply.json).toMatchObject({ detail: 'the node says model-refused', diagnostics: [] });
  });

  test('is 404 for an unknown id and 400 for no id, before the node is asked', async () => {
    expectProblem(await call('GET', '/api/mappings/mappings/none'), 404, `${STUDIO}not-found`);
    for (const id of ['bad%20name', 'a/test', 'a/..%2F..%2Fb', '.hidden', 'a%ZZ']) {
      expectProblem(await call('GET', `/api/mappings/${id}`), 400, `${STUDIO}invalid-workspace-path`);
    }
    expect(calls).toEqual([]);
  });

  test('is 400 for a link that leads out of the working folder', async () => {
    await writeFile(join(temp, 'outside.mapping.json'), '{}');
    await mkdir(join(root, 'links'));
    await symlink(join(temp, 'outside.mapping.json'), join(root, 'links/out.mapping.json'));

    expectProblem(await call('GET', '/api/mappings/links/out'), 400, `${STUDIO}invalid-workspace-path`);
    expect(calls).toEqual([]);
  });

  test('is 413 too-large for a stored file over 12 MiB', async () => {
    await put('mappings/huge.mapping.json', ' '.repeat(12 * 1024 * 1024 + 1));

    expectProblem(await call('GET', '/api/mappings/mappings/huge'), 413, `${STUDIO}too-large`);
    expect(calls).toEqual([]);
  });
});

describe('saving a mapping', () => {
  beforeEach(async () => {
    await put('mappings/order.mapping.json', canonical(1));
  });

  /** A node whose canonical form of a stored file is the file itself, and of a sent model the model with `n` 2. */
  const canonicalNode: Handler = (c) => (c.reads === 'stored' ? { status: 200, text: c.request } : { status: 200, text: canonical(2) });

  test("without If-Match writes the node's canonical form of the body over whatever is stored", async () => {
    await put('mappings/order.mapping.json', 'not a JSON document');
    handler = canonicalNode;

    const reply = await call('PUT', '/api/mappings/mappings/order', '{"sent": true}');

    expect(reply.status, reply.text).toBe(200);
    expect(reply.text).toBe(canonical(2));
    expect(reply.headers.etag).toBe(tagOf(canonical(2)));
    expect(await read('mappings/order.mapping.json')).toBe(canonical(2));
    expect(calls.map((c) => [c.op, c.reads, c.request])).toEqual([['canonical', 'sent', '{"sent": true}']]);
  });

  test('with the stored ETag writes and answers the new ETag', async () => {
    handler = canonicalNode;

    const reply = await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, 'if-match': tagOf(canonical(1)) });

    expect(reply.status, reply.text).toBe(200);
    expect(reply.headers.etag).toBe(tagOf(canonical(2)));
    expect(await read('mappings/order.mapping.json')).toBe(canonical(2));
    expect(calls.map((c) => c.reads)).toEqual(['stored', 'sent']);
  });

  test('with another ETag is 409 mapping-changed before the body is parsed, and writes nothing', async () => {
    handler = canonicalNode;
    const stored = tagOf(canonical(1));
    for (const tag of [tagOf('other'), `W/${stored}`, stored.replaceAll('"', '')]) {
      calls = [];
      const reply = await call('PUT', '/api/mappings/mappings/order', '{', { ...SAME_ORIGIN, 'if-match': tag });

      expectProblem(reply, 409, `${STUDIO}mapping-changed`);
      expect(calls.map((c) => c.reads), tag).toEqual(['stored']);
      expect(await read('mappings/order.mapping.json')).toBe(canonical(1));
    }
  });

  test('with any ETag over a stored file the node refuses is 409 mapping-changed', async () => {
    await put('mappings/order.mapping.json', 'not a JSON document');
    handler = (c) => (c.reads === 'stored' ? studioProblem(422, 'model-refused', { diagnostics: [] }) : { status: 200, text: canonical(2) });

    const reply = await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, 'if-match': tagOf('not a JSON document') });

    expectProblem(reply, 409, `${STUDIO}mapping-changed`);
    expect(await read('mappings/order.mapping.json')).toBe('not a JSON document');
  });

  test("passes on the node's refusal of the body and writes nothing", async () => {
    for (const refusal of [studioProblem(400, 'malformed-json'), studioProblem(422, 'model-refused', { diagnostics: [] })]) {
      handler = () => refusal;

      const reply = await call('PUT', '/api/mappings/mappings/order', '{,}');

      expectProblem(reply, refusal.status, (refusal.json as { type: string }).type);
      expect(await read('mappings/order.mapping.json')).toBe(canonical(1));
    }
  });

  test('of an unknown id is 404 before the body is read', async () => {
    const reply = await call('PUT', '/api/mappings/mappings/none', '{');

    expectProblem(reply, 404, `${STUDIO}not-found`);
    expect(calls).toEqual([]);
  });

  test('is 415 for a body that is not application/json, and takes one with parameters', async () => {
    handler = canonicalNode;
    expectProblem(await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, 'content-type': 'text/plain' }), 415, `${STUDIO}unsupported-media-type`);
    expectProblem(await call('PUT', '/api/mappings/mappings/order', '{}', { 'sec-fetch-site': 'same-origin' }), 415, `${STUDIO}unsupported-media-type`);
    expect(calls).toEqual([]);

    const reply = await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, 'content-type': 'Application/JSON; charset=utf-8' });
    expect(reply.status).toBe(200);
  });

  test('is 403 read-only when the server shows the working folder read-only', async () => {
    await new Promise((done) => front.close(done));
    await startFront({ editable: false });

    expectProblem(await call('PUT', '/api/mappings/mappings/order', '{}'), 403, `${STUDIO}read-only`);
    expectProblem(await call('POST', '/api/mappings', '{"id": "mappings/new"}'), 403, `${STUDIO}read-only`);
    expect(calls).toEqual([]);
    expect(await read('mappings/order.mapping.json')).toBe(canonical(1));
  });

  test('is 403 foreign-origin from a page that is not this server’s', async () => {
    const json = { 'content-type': 'application/json' };
    expectProblem(await call('PUT', '/api/mappings/mappings/order', '{}', json), 403, `${STUDIO}foreign-origin`);
    expectProblem(await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, origin: 'http://evil.example' }), 403, `${STUDIO}foreign-origin`);
    expectProblem(await call('POST', '/api/mappings/mappings/order/compile', '{}', { ...json, 'sec-fetch-site': 'cross-site' }), 403, `${STUDIO}foreign-origin`);
    expect(calls).toEqual([]);
  });

  test('saves one at a time: a second save waits until the first has written', async () => {
    let release!: () => void;
    const held = new Promise<void>((done) => (release = done));
    handler = async (c) => {
      if (c.request === '{"first": true}') await held;
      return { status: 200, text: c.request };
    };

    const first = call('PUT', '/api/mappings/mappings/order', '{"first": true}');
    await expect.poll(() => calls.length).toBe(1);
    const second = call('PUT', '/api/mappings/mappings/order', '{"second": true}', { ...SAME_ORIGIN, 'if-match': tagOf('{"first": true}') });
    // the second has its body; a list, which reads the folder, gives it the time to reach the node if nothing held it
    await expect.poll(() => ended).toBe(2);
    expect((await call('GET', '/api/mappings')).status).toBe(200);
    expect(calls).toHaveLength(1);
    release();

    expect((await first).status).toBe(200);
    expect((await second).status).toBe(200);
    expect(calls.map((c) => c.request)).toEqual(['{"first": true}', '{"first": true}', '{"second": true}']);
    expect(await read('mappings/order.mapping.json')).toBe('{"second": true}');
  });

  test('a node call past its time limit is 502 node-unavailable and frees the next save', async () => {
    const limits: AbortController[] = [];
    await new Promise((done) => front.close(done));
    await startFront({
      deadline: () => {
        const limit = new AbortController();
        limits.push(limit);
        return limit.signal;
      },
    });
    handler = (c) => (c.request === '{"stuck": true}' ? new Promise<NodeAnswer>(() => undefined) : { status: 200, text: c.request });

    const stuck = call('PUT', '/api/mappings/mappings/order', '{"stuck": true}');
    await expect.poll(() => calls.length).toBe(1);
    const next = call('PUT', '/api/mappings/mappings/order', '{"next": true}');
    limits[0]?.abort();

    const timedOut = await stuck;
    expectProblem(timedOut, 502, `${STUDIO}node-unavailable`);
    expect(timedOut.json.detail).toContain('30 s');
    expect((await next).status).toBe(200);
    expect(await read('mappings/order.mapping.json')).toBe('{"next": true}');
  });

  test('passes on 503 busy unchanged and writes nothing', async () => {
    const busy = { type: 'urn:rail8:admin:problem:busy', title: 'Busy', status: 503, detail: '4 calls of this plugin run already; try again later' };
    handler = () => ({ status: 503, type: 'application/problem+json', json: busy });

    const reply = await call('PUT', '/api/mappings/mappings/order', '{}');

    expect(reply.status).toBe(503);
    expect(reply.headers['content-type']).toBe('application/problem+json');
    expect(reply.json).toEqual(busy);
    expect(await read('mappings/order.mapping.json')).toBe(canonical(1));
  });
});

describe('creating a mapping', () => {
  const CREATE = JSON.stringify({ id: 'mappings/new', source: { bundle: ['../structures/order.xsd'], root: { element: 'Order' } } });

  beforeEach(async () => {
    await put('structures/order.xsd', '<xs:schema/>');
    await mkdir(join(root, 'mappings'), { recursive: true });
  });

  test("sends the files the node needs, writes the new file and answers 201 with its ETag and Location", async () => {
    handler = (c) => (c.files === undefined ? filesNeeded('../structures/order.xsd') : { status: 201, text: canonical(3) });

    const reply = await call('POST', '/api/mappings', CREATE);

    expect(reply.status, reply.text).toBe(201);
    expect(reply.text).toBe(canonical(3));
    expect(reply.headers.etag).toBe(tagOf(canonical(3)));
    expect(reply.headers.location).toBe('/api/mappings/mappings/new');
    expect(await read('mappings/new.mapping.json')).toBe(canonical(3));
    expect(calls.map((c) => [c.op, c.request, c.files, c.escaping])).toEqual([
      ['create', CREATE, undefined, undefined],
      ['create', CREATE, { '../structures/order.xsd': '<xs:schema/>' }, []],
    ]);
  });

  test('never writes over a file: 409 mapping-exists', async () => {
    await put('mappings/new.mapping.json', 'mine');
    handler = () => ({ status: 201, text: canonical(3) });

    expectProblem(await call('POST', '/api/mappings', '{"id": "mappings/new"}'), 409, `${STUDIO}mapping-exists`);
    expect(await read('mappings/new.mapping.json')).toBe('mine');
  });

  test('is 404 for a folder that does not exist and 400 for no id, and writes nothing', async () => {
    handler = () => ({ status: 201, text: canonical(3) });

    expectProblem(await call('POST', '/api/mappings', '{"id": "none/new"}'), 404, `${STUDIO}not-found`);
    expectProblem(await call('POST', '/api/mappings', '{"id": "mappings/compile"}'), 400, `${STUDIO}invalid-workspace-path`);
  });

  test('checks the id and its folder before it asks the node', async () => {
    handler = () => studioProblem(422, 'refused');

    expectProblem(await call('POST', '/api/mappings', '{"id": "none/new", "description": 1}'), 404, `${STUDIO}not-found`);
    expectProblem(await call('POST', '/api/mappings', '{"id": "mappings/test", "description": 1}'), 400, `${STUDIO}invalid-workspace-path`);
    expect(calls).toEqual([]);
  });

  test("passes on the node's refusal of the request", async () => {
    handler = () => studioProblem(400, 'invalid-request');

    expectProblem(await call('POST', '/api/mappings', '{"description": "no id"}'), 400, `${STUDIO}invalid-request`);
    expect(calls).toHaveLength(1);
  });
});

describe('the bundle files a call sends', () => {
  /** Compiles `id`, the node asking for `references`, and answers the files and escaping references of its second call. */
  async function sent(id: string, ...references: string[]) {
    handler = (c) => (c.files === undefined ? filesNeeded(...references) : { status: 200, json: { diagnostics: [] } });
    const reply = await call('POST', `/api/mappings/${id}/compile`, '{"formatVersion": 1}');
    expect(reply.status, reply.text).toBe(200);
    expect(calls).toHaveLength(2);
    return { files: nodeCall(1).files, escaping: nodeCall(1).escaping };
  }

  test("reads each reference from the mapping's folder, in order, and leaves out what the engine reports itself", async () => {
    await put('structures/a.xsd', 'A');
    await put('structures/b.xsd', 'B');
    await mkdir(join(root, 'structures/folder'));
    await mkdir(join(root, 'mappings'));

    expect(await sent('mappings/m', '../structures/b.xsd', '../structures/missing.xsd', 'pkg@1.0:/x.xsd', '../structures/folder', '../structures/a.xsd')).toEqual({
      files: { '../structures/b.xsd': 'B', '../structures/a.xsd': 'A' },
      escaping: [],
    });
  });

  test('stops at the first reference that escapes, and sends it as escaping', async () => {
    await put('structures/a.xsd', 'A');
    await writeFile(join(temp, 'secret.xsd'), 'secret');
    await mkdir(join(root, 'mappings'));
    await symlink(join(temp, 'secret.xsd'), join(root, 'structures/linked.xsd'));
    await symlink(join(temp, 'gone.xsd'), join(root, 'structures/dangling.xsd'));

    for (const escaping of ['../../secret.xsd', '/etc/hosts', '../structures/linked.xsd', '../structures/dangling.xsd', '../structures/dangling.xsd/below.xsd']) {
      calls = [];
      expect(await sent('mappings/m', '../structures/a.xsd', escaping, '../structures/a2.xsd'), escaping).toEqual({ files: { '../structures/a.xsd': 'A' }, escaping: [escaping] });
    }
  });

  test('refuses a path that leaves the working folder, even through a link outside that leads back in', async () => {
    await put('structures/a.xsd', 'A');
    await mkdir(join(root, 'mappings'));
    await symlink(join(root, 'structures'), join(temp, 'back'));

    expect(await sent('mappings/m', '../../back/a.xsd')).toEqual({ files: {}, escaping: ['../../back/a.xsd'] });
  });

  test("keeps a mapping's files inside its package: it cannot read another package's", async () => {
    await put('one/package.yaml', 'name: one\n');
    await put('one/shared.xsd', 'ONE');
    await put('one/mappings/m.mapping.json', '{}');
    await put('two/package.yaml', 'name: two\n');
    await put('two/private.xsd', 'TWO');
    await put('loose.xsd', 'LOOSE');

    expect(await sent('one/mappings/m', '../shared.xsd')).toEqual({ files: { '../shared.xsd': 'ONE' }, escaping: [] });
    calls = [];
    expect(await sent('one/mappings/m', '../../two/private.xsd')).toEqual({ files: {}, escaping: ['../../two/private.xsd'] });
    calls = [];
    expect(await sent('one/mappings/m', '../../loose.xsd')).toEqual({ files: {}, escaping: ['../../loose.xsd'] });
  });

  test('is 502 node-unavailable when the node asks for files a second time', async () => {
    await mkdir(join(root, 'mappings'));
    handler = () => filesNeeded('../x.xsd');

    expectProblem(await call('POST', '/api/mappings/mappings/m/compile', '{}'), 502, `${STUDIO}node-unavailable`);
    expect(calls).toHaveLength(2);
  });

  test('is 413 too-large, before the call that would carry them, when the body and the files are over 12 MiB', async () => {
    await mkdir(join(root, 'mappings'));
    await put('structures/big.xsd', 'x'.repeat(6 * 1024 * 1024));
    await put('structures/big2.xsd', 'x'.repeat(6 * 1024 * 1024));
    handler = () => filesNeeded('../structures/big.xsd', '../structures/big2.xsd');

    expectProblem(await call('POST', '/api/mappings/mappings/m/compile', '{}'), 413, `${STUDIO}too-large`);
    expect(calls).toHaveLength(1);
  });

  test('is 413 too-large, with no call to the node, for a body over 12 MiB', async () => {
    await mkdir(join(root, 'mappings'));

    expectProblem(await call('POST', '/api/mappings/mappings/m/test', ' '.repeat(12 * 1024 * 1024 + 1)), 413, `${STUDIO}too-large`);
    expect(calls).toEqual([]);
  });

  test("of a compile, refresh or test run is 404 for a mapping's folder that does not exist, before the body is read", async () => {
    for (const op of ['compile', 'refresh', 'test']) {
      expectProblem(await call('POST', `/api/mappings/none/m/${op}`, '{'), 404, `${STUDIO}not-found`);
    }
    expect(calls).toEqual([]);
  });

  test('passes each of compile, refresh and test to its operation', async () => {
    await mkdir(join(root, 'mappings'));
    handler = () => ({ status: 200, json: { answered: true } });

    for (const op of ['compile', 'refresh', 'test']) {
      const reply = await call('POST', `/api/mappings/mappings/m/${op}`, `{"op": "${op}"}`);
      expect(reply.status).toBe(200);
      expect(reply.json).toEqual({ answered: true });
    }
    expect(calls.map((c) => [c.op, c.request])).toEqual([
      ['compile', '{"op": "compile"}'],
      ['refresh', '{"op": "refresh"}'],
      ['test', '{"op": "test"}'],
    ]);
  });

  test("of a structure are read from its base, which must be a folder of the working folder", async () => {
    await put('structures/a.xsd', 'A');
    handler = (c) => (c.files === undefined ? filesNeeded('a.xsd') : { status: 200, json: { kind: 'loaded' } });

    const loaded = await call('POST', '/api/structures', '{"base": "structures", "bundle": ["a.xsd"], "root": {"element": "A"}}');
    expect(loaded.status, loaded.text).toBe(200);
    expect(nodeCall(1)).toMatchObject({ op: 'structure', files: { 'a.xsd': 'A' } });

    calls = [];
    handler = () => studioProblem(400, 'invalid-request');
    expectProblem(await call('POST', '/api/structures', '{"base": "none", "bundle": ["a.xsd"], "root": {}}'), 404, `${STUDIO}not-found`);
    expectProblem(await call('POST', '/api/structures', '{"base": "../x", "bundle": ["a.xsd"], "root": {}}'), 400, `${STUDIO}invalid-workspace-path`);
    expect(calls).toEqual([]);
  });
});

describe('the node', () => {
  test('is called with the token, and with none of the browser’s headers', async () => {
    await put('mappings/order.mapping.json', '{}');
    handler = () => ({ status: 200, text: canonical(1) });

    await call('GET', '/api/mappings/mappings/order', undefined, { origin: 'http://localhost:5173', cookie: 'a=b', authorization: 'Bearer browser' });

    expect(nodeCall(0).headers.authorization).toBe('Bearer secret');
    expect(nodeCall(0).headers.origin).toBeUndefined();
    expect(nodeCall(0).headers.cookie).toBeUndefined();
    expect(nodeCall(0).headers['sec-fetch-site']).toBeUndefined();
  });

  test("answers the catalogue as the node gives it, with no working folder", async () => {
    await new Promise((done) => front.close(done));
    await startFront({ workspace: undefined });
    handler = () => ({ status: 200, json: { 'fn:concat': {} } });

    const reply = await call('GET', '/api/catalogue');

    expect(reply.status).toBe(200);
    expect(reply.json).toEqual({ 'fn:concat': {} });
    expect(nodeCall(0)).toMatchObject({ op: 'catalogue', method: 'GET' });
  });

  test('is 502 node-unavailable, with its status, when it is down, refuses the token, has no route or answers what it must not', async () => {
    await put('mappings/order.mapping.json', '{}');
    const answers: [NodeAnswer, string][] = [
      [{ status: 401, json: { error: 'missing or wrong bearer token' } }, '401'],
      [{ status: 404, json: { error: 'no such route' } }, '404'],
      [{ status: 200, type: 'text/plain', text: 'hello' }, '200'],
      [{ status: 200, text: '{' }, '200'],
      [{ status: 500, type: 'application/problem+json', json: { type: 'urn:rail8:admin:problem:invalid-reply', title: 'Invalid reply', status: 500, detail: 'x' } }, '500'],
      [{ status: 400, type: 'application/problem+json', json: { type: `${STUDIO}files-wanted`, title: 'x', status: 401, detail: 'x' } }, '400'],
    ];
    for (const [answer, status] of answers) {
      handler = () => answer;
      const reply = await call('GET', '/api/mappings/mappings/order');
      expectProblem(reply, 502, `${STUDIO}node-unavailable`);
      expect(reply.json.detail).toContain(status);
    }

    await new Promise((done) => front.close(done));
    await startFront({ node: { target: 'http://127.0.0.1:1' } });
    const down = await call('GET', '/api/mappings/mappings/order');
    expectProblem(down, 502, `${STUDIO}node-unavailable`);
    expect(down.json.detail).toContain('is it running?');
  });

  test("is no longer called once the browser's request closes", async () => {
    await mkdir(join(root, 'mappings'));
    handler = () => new Promise<NodeAnswer>(() => undefined);

    const req = request({ host: '127.0.0.1', port: frontPort, method: 'POST', path: '/api/mappings/mappings/m/compile', headers: SAME_ORIGIN });
    req.on('error', () => undefined);
    req.end('{}');
    await expect.poll(() => calls.length).toBe(1);
    req.destroy();

    await nodeCall(0).closed;
  });
});

describe('the routes', () => {
  test('pass every other path to the rest of the server, and answer an unknown path or method under /api', async () => {
    expect((await call('GET', '/apis')).status).toBe(418);
    expect((await call('GET', '/src/mapping/api/client.ts')).status).toBe(418);
    expect((await call('GET', '/api/other')).status).toBe(404);
    expect((await call('DELETE', '/api/mappings/mappings/order', undefined, { 'sec-fetch-site': 'same-origin' })).status).toBe(405);
    expect((await call('GET', '/api/structures')).status).toBe(405);
  });
});

/** The browser's contract, written by hand next to the mapping model's schema. */
const contract = JSON.parse(readFileSync(join(import.meta.dirname, '../../../../mapping/contract/openapi.json'), 'utf8')) as {
  paths: Record<string, Record<string, { responses: Record<string, ContractResponse> }>>;
  components: { schemas: Record<string, Schema> };
};

interface ContractResponse {
  headers?: Record<string, { required?: boolean }>;
  content?: Record<string, { schema: Schema }>;
}

type Schema = Record<string, unknown>;

const KEYWORDS = new Set(['$ref', 'additionalProperties', 'const', 'discriminator', 'enum', 'format', 'items', 'oneOf', 'properties', 'required', 'type']);

/**
 * How `value` breaks `schema`: a member its object schema does not name, unless `additionalProperties` gives the
 * schema of the others, a required member missing, a value of another type, a union member its discriminator does not
 * pick. A schema of the mapping model's or the catalogue's own file is only checked to be an object, and a keyword the
 * check does not know fails it.
 */
function violations(value: unknown, schema: Schema, at = '$'): string[] {
  const found = Object.keys(schema).filter((k) => !KEYWORDS.has(k)).map((k) => `${at}: the check does not know ${k}`);
  if (typeof schema.$ref === 'string') {
    if (!schema.$ref.startsWith('#/components/schemas/')) return isObject(value) ? found : [...found, `${at}: no object for ${schema.$ref}`];
    return [...found, ...violations(value, contract.components.schemas[schema.$ref.slice('#/components/schemas/'.length)] ?? {}, at)];
  }
  if (typeof schema.type === 'string' && !typed(value, schema.type)) return [...found, `${at}: ${JSON.stringify(value)} is no ${schema.type}`];
  if ('const' in schema && schema.const !== value) found.push(`${at}: ${JSON.stringify(value)} is not ${JSON.stringify(schema.const)}`);
  if (Array.isArray(schema.enum) && !schema.enum.includes(value)) found.push(`${at}: ${JSON.stringify(value)} is none of ${JSON.stringify(schema.enum)}`);
  if (schema.format === 'uri' && !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(String(value))) found.push(`${at}: ${JSON.stringify(value)} is no absolute uri`);
  if (schema.format === 'int32' && !(Number.isInteger(value) && Math.abs(value as number) < 2 ** 31)) found.push(`${at}: ${JSON.stringify(value)} is no int32`);
  if (Array.isArray(schema.oneOf)) {
    const { propertyName, mapping } = schema.discriminator as { propertyName: string; mapping: Record<string, string> };
    const member = isObject(value) ? mapping[String(value[propertyName])] : undefined;
    return member === undefined ? [...found, `${at}: no member of the union for ${JSON.stringify(value)}`] : [...found, ...violations(value, { $ref: member }, at)];
  }
  if (isObject(value)) {
    const properties = (schema.properties ?? {}) as Record<string, Schema>;
    for (const member of (schema.required ?? []) as string[]) if (!(member in value)) found.push(`${at}: ${member} is missing`);
    for (const [member, inner] of Object.entries(value)) {
      const named = properties[member] ?? (isObject(schema.additionalProperties) ? schema.additionalProperties : undefined);
      if (named === undefined) found.push(`${at}: ${member} is no member of it`);
      else found.push(...violations(inner, named, `${at}.${member}`));
    }
  }
  if (Array.isArray(value) && isObject(schema.items)) value.forEach((item, i) => found.push(...violations(item, schema.items as Schema, `${at}[${i}]`)));
  return found;
}

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

function typed(value: unknown, type: string): boolean {
  if (type === 'integer') return Number.isInteger(value);
  if (type === 'object') return isObject(value);
  if (type === 'array') return Array.isArray(value);
  return typeof value === type;
}

/** How a reply of the operation `method path` breaks the contract: its status, its type, its headers or its JSON. */
function breaks(method: string, path: string, reply: Reply): string[] {
  const response = contract.paths[path]?.[method]?.responses[String(reply.status)];
  if (response === undefined) return [`${method} ${path} has no ${reply.status}: ${reply.text}`];
  const type = reply.status < 400 ? 'application/json' : 'application/problem+json';
  const found: string[] = [];
  if (reply.headers['content-type'] !== type || Object.keys(response.content ?? {}).join() !== type) found.push(`${method} ${path} ${reply.status} is no ${type}`);
  const listed = Object.keys(response.headers ?? {}).map((h) => h.toLowerCase());
  for (const [name, header] of Object.entries(response.headers ?? {})) if (header.required && reply.headers[name.toLowerCase()] === undefined) found.push(`${method} ${path} ${reply.status} has no ${name}`);
  if (reply.headers['rail8-truncated'] !== undefined && !listed.includes('rail8-truncated')) found.push(`${method} ${path} ${reply.status} lists no Rail8-Truncated`);
  found.push(...violations(reply.json, response.content?.[type]?.schema ?? {}).map((v) => `${method} ${path} ${reply.status} ${v}`));
  return found;
}

describe('the replies', () => {
  const diagnostics = { diagnostics: [] };
  const answers: Record<string, NodeAnswer> = {
    catalogue: { status: 200, json: {} },
    create: { status: 201, text: canonical(3) },
    structure: { status: 200, json: { kind: 'refused', ...diagnostics } },
    compile: { status: 200, json: diagnostics },
    refresh: { status: 200, json: { model: { formatVersion: 1 }, ...diagnostics } },
    test: { status: 200, json: { kind: 'refused', ...diagnostics } },
  };
  const studio: Handler = (c) =>
    c.op === 'canonical' ? { status: 200, text: c.reads === 'stored' ? c.request : canonical(2) } : (answers[c.op] ?? { status: 500, text: `no answer for ${c.op}` });
  const busy: Handler = () => ({ status: 503, type: 'application/problem+json', json: { type: 'urn:rail8:admin:problem:busy', title: 'Busy', status: 503, detail: 'try again later' } });
  const refused: Handler = () => studioProblem(422, 'model-refused', diagnostics);
  const malformed: Handler = () => studioProblem(400, 'malformed-json');

  /** An operation of the contract asked here: its method and path, a request of it and the same with no folder or a bad path. */
  interface Operation {
    method: string;
    template: string;
    path: string;
    body?: string;
    none?: [string, string?];
    bad?: [string, string?];
    refusals: Handler[];
  }
  const OPERATIONS: Operation[] = [
    { method: 'get', template: '/api/catalogue', path: '/api/catalogue', refusals: [] },
    { method: 'get', template: '/api/mappings/{id}', path: '/api/mappings/mappings/order', none: ['/api/mappings/none/order'], bad: ['/api/mappings/mappings/test'], refusals: [refused] },
    ...(['compile', 'refresh', 'test'] as const).map((action) => ({
      method: 'post',
      template: `/api/mappings/{id}/${action}`,
      path: `/api/mappings/mappings/order/${action}`,
      body: '{}',
      none: [`/api/mappings/none/order/${action}`] as [string],
      bad: [`/api/mappings/mappings/test/${action}`] as [string],
      refusals: action === 'refresh' ? [malformed, refused] : [malformed],
    })),
    { method: 'put', template: '/api/mappings/{id}', path: '/api/mappings/mappings/order', body: '{}', none: ['/api/mappings/none/order'], bad: ['/api/mappings/mappings/test'], refusals: [malformed, refused] },
    { method: 'post', template: '/api/mappings', path: '/api/mappings', body: '{"id": "mappings/new"}', none: ['/api/mappings', '{"id": "none/new"}'], bad: ['/api/mappings', '{"id": "mappings/test"}'], refusals: [malformed, refused] },
    { method: 'post', template: '/api/structures', path: '/api/structures', body: '{"base": "mappings"}', none: ['/api/structures', '{"base": "none"}'], bad: ['/api/structures', '{"base": "../up"}'], refusals: [malformed] },
  ];

  test('meet the contract, and reach every status it lists', async () => {
    const reached = new Set<string>();
    const meets = (method: string, path: string, reply: Reply) => {
      expect(breaks(method, path, reply)).toEqual([]);
      reached.add(`${method} ${path} ${reply.status}`);
    };
    const restart = async (options: Partial<MappingApiOptions> = {}) => {
      await new Promise((done) => front.close(done));
      await startFront(options);
    };
    await put('mappings/order.mapping.json', canonical(1));

    for (const { method, template, path, body, none, bad, refusals } of OPERATIONS) {
      const ask = (at: string, sent = body, headers?: Record<string, string>) => call(method.toUpperCase(), at, sent, headers);
      for (const answer of [studio, busy, ...refusals]) {
        handler = answer;
        meets(method, template, await ask(path));
        await put('mappings/order.mapping.json', canonical(1));
        await rm(join(root, 'mappings/new.mapping.json'), { force: true });
      }
      await restart({ node: { target: 'http://127.0.0.1:1' } });
      meets(method, template, await ask(path));
      await restart();
      handler = studio;
      if (none !== undefined && bad !== undefined) {
        meets(method, template, await ask(none[0], none[1] ?? body));
        meets(method, template, await ask(bad[0], bad[1] ?? body));
      }
      if (body === undefined) continue;
      meets(method, template, await ask(path, body, { 'content-type': 'application/json' }));
      meets(method, template, await ask(path, body, { 'sec-fetch-site': 'same-origin', 'content-type': 'text/plain' }));
      meets(method, template, await ask(path, ' '.repeat(12 * 1024 * 1024 + 1)));
    }
    await put('mappings/huge.mapping.json', ' '.repeat(12 * 1024 * 1024 + 1));
    meets('get', '/api/mappings/{id}', await call('GET', '/api/mappings/mappings/huge'));
    handler = studio;
    meets('put', '/api/mappings/{id}', await call('PUT', '/api/mappings/mappings/order', '{}', { ...SAME_ORIGIN, 'if-match': tagOf('other') }));
    await put('mappings/new.mapping.json', 'mine');
    meets('post', '/api/mappings', await call('POST', '/api/mappings', '{"id": "mappings/new"}'));
    meets('get', '/api/mappings', await call('GET', '/api/mappings'));
    await restart({ workspace: undefined, editable: false });
    meets('get', '/api/mappings', await call('GET', '/api/mappings'));
    meets('put', '/api/mappings/{id}', await call('PUT', '/api/mappings/mappings/order', '{}'));
    meets('post', '/api/mappings', await call('POST', '/api/mappings', '{"id": "mappings/new"}'));

    const listed = Object.entries(contract.paths).flatMap(([path, methods]) =>
      Object.entries(methods).flatMap(([method, { responses }]) => Object.keys(responses).map((status) => `${method} ${path} ${status}`)),
    );
    expect(listed.filter((status) => !reached.has(status))).toEqual([]);
    expect(contract.paths['/api/mappings']?.get?.responses['200']?.headers).toHaveProperty('Rail8-Truncated');
  });
});
