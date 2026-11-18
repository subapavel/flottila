import { describe, expect, test, vi } from 'vitest';
import { EndpointsError, httpEndpointsApi } from './api';

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

const file = { path: 'demo/endpoints/http in.yaml', editable: true, id: 'http-in', scheme: 'http', direction: 'in', description: null, config: {}, environments: [] };

describe('httpEndpointsApi', () => {
  test('reads a file by its path, each segment encoded, with its ETag', async () => {
    const send = vi.fn(async () => json(200, file, { etag: '"sha256-1"' }));
    expect(await httpEndpointsApi(send).read('demo/endpoints/http in.yaml')).toEqual({ file, etag: '"sha256-1"' });
    expect(send).toHaveBeenCalledWith('/files/endpoints/demo/endpoints/http%20in.yaml', { headers: { accept: 'application/json' } });
  });

  test('saves the options with If-Match', async () => {
    const send = vi.fn(async () => json(200, file, { etag: '"sha256-2"' }));
    expect((await httpEndpointsApi(send).save('demo/a.yaml', { port: 1 }, '"sha256-1"')).etag).toBe('"sha256-2"');
    expect(send).toHaveBeenCalledWith('/files/endpoints/demo/a.yaml', {
      method: 'PUT',
      headers: { accept: 'application/json', 'content-type': 'application/json', 'if-match': '"sha256-1"' },
      body: '{"config":{"port":1}}',
    });
  });

  test("lists the node's components from the admin API", async () => {
    const items = [{ scheme: 'http', settings: { format: 1, described: false, tabs: [], fields: [], rules: [] } }];
    const send = vi.fn(async () => json(200, { items }));
    expect(await httpEndpointsApi(send).components()).toEqual(items);
    expect(send).toHaveBeenCalledWith('/admin/components', { headers: { accept: 'application/json' } });
  });

  test("rejects with the server's status and error, or status 0 when it does not answer", async () => {
    await expect(httpEndpointsApi(async () => json(412, { error: 'the file changed' })).read('a.yaml')).rejects.toEqual(new EndpointsError(412, 'the file changed'));
    await expect(httpEndpointsApi(async () => new Response('down', { status: 502, statusText: 'Bad Gateway' })).list()).rejects.toMatchObject({ status: 502, message: '502 Bad Gateway' });
    await expect(httpEndpointsApi(async () => Promise.reject(new TypeError('failed'))).list()).rejects.toMatchObject({ status: 0 });
  });
});
