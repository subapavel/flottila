import { describe, expect, it, vi } from 'vitest';
import { FlowsError, httpFlowsApi } from './api';

const answer = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

describe('httpFlowsApi', () => {
  it('reads a flow file with its ETag, its path encoded part by part', async () => {
    const send = vi.fn(async () => answer(200, { path: 'demo/flows/a b.yaml', text: 'x', editable: false, context: {} }, { etag: '"e1"' }));
    const stored = await httpFlowsApi(send).read('demo/flows/a b.yaml');
    expect(send).toHaveBeenCalledWith('/files/flows/demo/flows/a%20b.yaml', expect.anything());
    expect(stored.etag).toBe('"e1"');
  });

  it('saves a text on condition that the file is still at its ETag', async () => {
    const send = vi.fn(async () => answer(200, { path: 'demo/flows/a.yaml', text: 'y', editable: true, context: {} }, { etag: '"e2"' }));
    const stored = await httpFlowsApi(send).save('demo/flows/a.yaml', 'y', '"e1"');
    expect(send).toHaveBeenCalledWith('/files/flows/demo/flows/a.yaml', {
      method: 'PUT',
      headers: { accept: 'application/json', 'content-type': 'application/json', 'if-match': '"e1"' },
      body: JSON.stringify({ text: 'y' }),
    });
    expect(stored).toEqual({ file: { path: 'demo/flows/a.yaml', text: 'y', editable: true, context: {} }, etag: '"e2"' });
    const changed = httpFlowsApi(async () => answer(412, { error: 'the file changed since it was read; nothing was saved' }));
    await expect(changed.save('demo/flows/a.yaml', 'y', '"e1"')).rejects.toEqual(new FlowsError(412, 'the file changed since it was read; nothing was saved'));
  });

  it('rejects with the error text the server gives', async () => {
    const api = httpFlowsApi(async () => answer(404, { error: 'demo/x.yaml is not a flow file' }));
    await expect(api.read('demo/x.yaml')).rejects.toEqual(new FlowsError(404, 'demo/x.yaml is not a flow file'));
  });

  it("gives the node's step types, or why they could not be read", async () => {
    expect(await httpFlowsApi(async () => answer(200, { items: [{ type: 'jq', configSchema: null }] })).steps()).toEqual([{ type: 'jq', configSchema: null }]);
    expect(await httpFlowsApi(async () => answer(401, { error: 'no token' })).steps()).toEqual({ missing: 'no token' });
    expect(await httpFlowsApi(async () => Promise.reject(new Error('refused'))).steps()).toEqual({ missing: 'the server does not answer: Error: refused' });
  });
});
