import { createServer, request, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { adminProxy, forward } from './adminProxy';

const origins = ['http://localhost:5173'];

describe('forward', () => {
  it('refuses a request without Sec-Fetch-Site', () => {
    expect(forward({}, { origins })).toHaveProperty('refused');
  });

  it('refuses a request another site sent', () => {
    expect(forward({ 'sec-fetch-site': 'cross-site' }, { origins })).toHaveProperty('refused');
    expect(forward({ 'sec-fetch-site': 'same-site' }, { origins })).toHaveProperty('refused');
    expect(forward({ 'sec-fetch-site': 'none' }, { origins })).toHaveProperty('refused');
  });

  it('refuses an Origin outside the fixed list, even one that matches the Host header', () => {
    const rebound = { 'sec-fetch-site': 'same-origin', origin: 'http://evil.example:5173', host: 'evil.example:5173' };
    expect(forward(rebound, { origins })).toHaveProperty('refused');
  });

  it('passes only Accept and Content-Type on, never what the browser adds', () => {
    const headers: IncomingHttpHeaders = {
      'sec-fetch-site': 'same-origin',
      'sec-fetch-mode': 'cors',
      'sec-fetch-dest': 'empty',
      origin: 'http://localhost:5173',
      referer: 'http://localhost:5173/',
      cookie: 'session=1',
      authorization: 'Bearer from-the-browser',
      accept: 'application/json',
      'content-type': 'application/json',
    };
    expect(forward(headers, { origins })).toEqual({ headers: { accept: 'application/json', 'content-type': 'application/json' } });
  });

  it('sets the configured token and drops the one the browser sent', () => {
    const headers = { 'sec-fetch-site': 'same-origin', authorization: 'Bearer from-the-browser' };
    expect(forward(headers, { origins, token: 'node-token' })).toEqual({ headers: { authorization: 'Bearer node-token' } });
  });
});

describe('adminProxy', () => {
  const servers: Server[] = [];

  afterEach(async () => {
    await Promise.all(servers.splice(0).map((s) => new Promise((done) => s.close(done))));
  });

  async function listen(server: Server): Promise<string> {
    servers.push(server);
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  }

  /** A node that records what it was sent and answers 202 with a JSON body. */
  async function node(): Promise<{ url: string; seen: { method?: string; url?: string; headers?: IncomingHttpHeaders }[] }> {
    const seen: { method?: string; url?: string; headers?: IncomingHttpHeaders }[] = [];
    const url = await listen(
      createServer((req, res) => {
        seen.push({ method: req.method, url: req.url, headers: req.headers });
        res.writeHead(202, { 'content-type': 'application/json', 'set-cookie': 'node=1' }).end('{"id":"m-1","attempt":2}');
      }),
    );
    return { url, seen };
  }

  async function proxy(target: string, token?: string): Promise<string> {
    const middleware = adminProxy({ target, origins, token });
    return listen(createServer((req, res) => middleware(req, res, () => res.writeHead(404).end('next'))));
  }

  function send(base: string, method: string, path: string, headers: Record<string, string>) {
    return new Promise<{ status: number; headers: IncomingHttpHeaders; body: string }>((done, fail) => {
      const req = request(base + path, { method, headers }, (res) => {
        let body = '';
        res.setEncoding('utf8').on('data', (chunk: string) => (body += chunk));
        res.on('end', () => done({ status: res.statusCode ?? 0, headers: res.headers, body }));
      });
      req.on('error', fail).end();
    });
  }

  const browser = { 'sec-fetch-site': 'same-origin', origin: 'http://localhost:5173', cookie: 'session=1' };

  it('sends an admin request on to the node with its path and query, and gives back its answer', async () => {
    const target = await node();
    const base = await proxy(target.url, 'node-token');
    const answer = await send(base, 'POST', '/admin/dlq/m-1/retry?x=1', browser);
    expect(answer.status).toBe(202);
    expect(answer.body).toBe('{"id":"m-1","attempt":2}');
    expect(answer.headers['content-type']).toBe('application/json');
    expect(answer.headers['set-cookie']).toBeUndefined();
    expect(target.seen).toHaveLength(1);
    expect(target.seen[0]?.method).toBe('POST');
    expect(target.seen[0]?.url).toBe('/admin/dlq/m-1/retry?x=1');
    expect(target.seen[0]?.headers?.authorization).toBe('Bearer node-token');
    expect(target.seen[0]?.headers?.origin).toBeUndefined();
    expect(target.seen[0]?.headers?.cookie).toBeUndefined();
    expect(target.seen[0]?.headers?.['sec-fetch-site']).toBeUndefined();
  });

  it('answers 403 itself to a request from another site, and the node never sees it', async () => {
    const target = await node();
    const base = await proxy(target.url);
    const answer = await send(base, 'GET', '/admin/messages/m-1', { 'sec-fetch-site': 'cross-site', origin: 'http://evil.example' });
    expect(answer.status).toBe(403);
    expect(JSON.parse(answer.body)).toHaveProperty('error');
    expect(target.seen).toHaveLength(0);
  });

  it('answers 405 to a method other than GET and POST', async () => {
    const target = await node();
    const base = await proxy(target.url);
    expect((await send(base, 'DELETE', '/admin/packages/demo', browser)).status).toBe(405);
    expect(target.seen).toHaveLength(0);
  });

  it('answers 502 when the node does not answer', async () => {
    const base = await proxy('http://127.0.0.1:1');
    const answer = await send(base, 'GET', '/admin/messages/m-1', browser);
    expect(answer.status).toBe(502);
    expect(JSON.parse(answer.body).error).toContain('http://127.0.0.1:1');
  });

  it('leaves every other path to the next handler', async () => {
    const target = await node();
    const base = await proxy(target.url);
    expect((await send(base, 'GET', '/administration', browser)).body).toBe('next');
    expect((await send(base, 'GET', '/src/main.tsx', browser)).body).toBe('next');
    expect(target.seen).toHaveLength(0);
  });
});
