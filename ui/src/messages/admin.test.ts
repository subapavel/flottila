import { describe, expect, it } from 'vitest';
import { AdminError, httpAdminApi, PAYLOAD_SHOWN } from './admin';

/** A fetch that records every call and answers each with the next of `answers`. */
function fakeFetch(...answers: Response[]) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const send = (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    const next = answers.shift();
    return next === undefined ? Promise.reject(new Error('no answer left')) : Promise.resolve(next);
  };
  return { send: send as typeof fetch, calls };
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('httpAdminApi', () => {
  it('searches by business key as name:value, with the cursor of the next page', async () => {
    const { send, calls } = fakeFetch(json(200, { items: ['m-1'], next: 'c1' }), json(200, { items: ['m-2'], next: null }));
    const api = httpAdminApi(send);
    expect(await api.findByKey('order', 'A:1 & 2')).toEqual({ items: ['m-1'], next: 'c1' });
    expect(await api.findByKey('order', 'A:1 & 2', 'c1')).toEqual({ items: ['m-2'], next: null });
    expect(calls.map((c) => c.url)).toEqual(['/admin/messages?key=order%3AA%3A1+%26+2', '/admin/messages?key=order%3AA%3A1+%26+2&after=c1']);
  });

  it('searches by flow and step, with the cursor of the next page', async () => {
    const { send, calls } = fakeFetch(json(200, { items: ['m-1'], next: 'c1' }), json(200, { items: [], next: null }));
    const api = httpAdminApi(send);
    expect(await api.findByStep('demo/order-jq', 'call/pick')).toEqual({ items: ['m-1'], next: 'c1' });
    expect(await api.findByStep('demo/order-jq', 'call/pick', 'c1')).toEqual({ items: [], next: null });
    expect(calls.map((c) => c.url)).toEqual(['/admin/messages?flow=demo%2Forder-jq&step=call%2Fpick', '/admin/messages?flow=demo%2Forder-jq&step=call%2Fpick&after=c1']);
  });

  it('lists the ids of the deployed flows', async () => {
    const { send, calls } = fakeFetch(json(200, { items: [{ id: 'demo/invoice', state: 'RUNNING' }, { id: 'demo/order-jq', state: 'FAILED' }] }));
    expect(await httpAdminApi(send).flows()).toEqual(['demo/invoice', 'demo/order-jq']);
    expect(calls[0]?.url).toBe('/admin/flows');
  });

  it("reads a message's timeline, and null for a message the node did not record", async () => {
    const timeline = { id: 'a/b', flow: 'demo/orders', steps: [] };
    const { send, calls } = fakeFetch(json(200, timeline), json(404, { error: 'no message x recorded' }));
    const api = httpAdminApi(send);
    expect(await api.timeline('a/b')).toEqual(timeline);
    expect(await api.timeline('x')).toBeNull();
    expect(calls[0]?.url).toBe('/admin/messages/a%2Fb');
  });

  it("rejects with the node's error text and status", async () => {
    const { send } = fakeFetch(json(400, { error: 'bad request: key must be name:value' }), new Response('down', { status: 503, statusText: 'Service Unavailable' }));
    const api = httpAdminApi(send);
    await expect(api.findByKey('', '')).rejects.toEqual(new AdminError(400, 'bad request: key must be name:value'));
    const refused = await api.timeline('m-1').catch((e: unknown) => e);
    expect(refused).toBeInstanceOf(AdminError);
    expect(refused).toMatchObject({ status: 503, message: '503 Service Unavailable' });
  });

  it('reads the payload after a step of an attempt as text', async () => {
    const { send, calls } = fakeFetch(new Response('{"total":3}', { headers: { 'content-type': 'application/json' } }));
    expect(await httpAdminApi(send).payload('m-1', 2, 3)).toEqual({ contentType: 'application/json', text: '{"total":3}', complete: true });
    expect(calls[0]?.url).toBe('/admin/messages/m-1/steps/2/body?attempt=3');
  });

  it('reads no more of a payload than it shows', async () => {
    const big = new Uint8Array(PAYLOAD_SHOWN + 10).fill(0x61);
    const { send } = fakeFetch(new Response(big));
    const payload = await httpAdminApi(send).payload('m-1', 1, 1);
    expect(payload.complete).toBe(false);
    expect(payload.text).toHaveLength(PAYLOAD_SHOWN);
    expect(payload.contentType).toBe('application/octet-stream');
  });

  it('restarts a failed message with a POST and returns its new attempt', async () => {
    const { send, calls } = fakeFetch(json(202, { id: 'm 1', attempt: 2 }));
    expect(await httpAdminApi(send).restart('m 1')).toEqual({ id: 'm 1', attempt: 2 });
    expect(calls[0]?.url).toBe('/admin/dlq/m%201/retry');
    expect(calls[0]?.init?.method).toBe('POST');
  });
});
