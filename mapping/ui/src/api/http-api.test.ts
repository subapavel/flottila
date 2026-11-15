import { describe, expect, test, vi } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { ApiProblem, isSaveConflict, type CreateMappingRequest, type MappingSummary } from './client';
import { httpApiClient } from './http-api';

const model: MappingModel = {
  formatVersion: 1,
  source: { bundle: ['order.xsd'], fingerprint: 'sha256:00', root: { element: 'Q{urn:a}Order' } },
  target: { bundle: ['order.xsd'], fingerprint: 'sha256:00', root: { element: 'Q{urn:a}Order' } },
};

interface Call {
  url: string;
  method: string;
  contentType: string | null;
  ifMatch?: string;
  body: unknown;
}

/** A fetch port that records each request and answers with the given response. */
function fakeFetch(answer: () => Response) {
  const calls: Call[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    const ifMatch = headers.get('if-match');
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      contentType: headers.get('content-type'),
      ...(ifMatch === null ? {} : { ifMatch }),
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
    });
    return answer();
  };
  return { fetch, calls };
}

const json = (body: unknown, status = 200, type = 'application/json', etag?: string) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': type, ...(etag ? { etag } : {}) } });

const TAG = '"sha256:aa"';
const stored = (body: unknown, etag = TAG) => json(body, 200, 'application/json', etag);

describe('the HTTP ApiClient', () => {
  test('uses the global fetch when no fetch port is given', async () => {
    const port = fakeFetch(() => stored(model));
    vi.stubGlobal('fetch', port.fetch);
    try {
      expect(await httpApiClient().getMapping('m/one')).toEqual({ model, etag: TAG });
      expect(port.calls.map((c) => c.url)).toEqual(['/api/mappings/m/one']);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  test('opens a mapping by GET on its id, keeping the id segments as path segments, with its ETag', async () => {
    const port = fakeFetch(() => stored(model));

    const opened = await httpApiClient('http://svc', port.fetch).getMapping('mappings/order-id-copy');

    expect(opened).toEqual({ model, etag: TAG });
    expect(port.calls).toEqual([
      { url: 'http://svc/api/mappings/mappings/order-id-copy', method: 'GET', contentType: null, body: undefined },
    ]);
  });

  test('saves by PUT of the working copy as JSON with If-Match, and returns the canonical response and its new ETag', async () => {
    const canonical = { ...model, target: { ...model.target, fingerprint: 'sha256:01' } };
    const port = fakeFetch(() => stored(canonical, '"sha256:bb"'));

    const saved = await httpApiClient('', port.fetch).saveMapping('m/one', model, TAG);

    expect(saved).toEqual({ model: canonical, etag: '"sha256:bb"' });
    expect(port.calls).toEqual([
      { url: '/api/mappings/m/one', method: 'PUT', contentType: 'application/json', ifMatch: TAG, body: model },
    ]);
  });

  test('a mapping answered without an ETag is an error: the editor could not save it conditionally', async () => {
    const port = fakeFetch(() => json(model));

    await expect(httpApiClient('', port.fetch).getMapping('m/one')).rejects.toThrow('no ETag');
  });

  test('a 409 mapping-changed answer to a save is a save conflict', async () => {
    const changed = {
      type: 'urn:flottila:mapping:problem:mapping-changed',
      title: 'Mapping changed',
      status: 409,
      detail: 'the mapping changed on disk since the If-Match tag was read; nothing was saved',
    };
    const port = fakeFetch(() => json(changed, 409, 'application/problem+json'));

    const error = await httpApiClient('', port.fetch).saveMapping('m/one', model, TAG).catch((e: unknown) => e);

    expect(isSaveConflict(error)).toBe(true);
    expect((error as ApiProblem).problem).toEqual(changed);
  });

  test('lists the workspace mappings by GET on /api/mappings, with their package, description and root names', async () => {
    // a summary leaves out a description or a side that is not there (ADR-0029)
    const list: MappingSummary[] = [
      { id: 'drafts/new', name: 'new', package: 'drafts', description: 'Bestellungen zu Rechnungen' },
      { id: 'mappings/order-id-copy', name: 'order-id-copy', package: 'mappings', source: 'Order', target: 'Order' },
      { id: 'top', name: 'top', package: '', target: 'Invoice' },
    ];
    const port = fakeFetch(() => json(list));

    expect(await httpApiClient('', port.fetch).listMappings()).toEqual(list);
    expect(port.calls).toEqual([{ url: '/api/mappings', method: 'GET', contentType: null, body: undefined }]);
  });

  test('creates a mapping by POST of its id and description alone, and returns the model without structures', async () => {
    const created: MappingModel = { description: 'Bestellungen zu Rechnungen', formatVersion: 1 };
    const port = fakeFetch(() => json(created, 201, 'application/json', TAG));
    const request: CreateMappingRequest = { id: 'drafts/new', description: 'Bestellungen zu Rechnungen' };

    expect(await httpApiClient('', port.fetch).createMapping(request)).toEqual({ model: created, etag: TAG });
    expect(port.calls).toEqual([{ url: '/api/mappings', method: 'POST', contentType: 'application/json', body: request }]);
  });

  test('creates a mapping by POST of the request to /api/mappings, and returns the new model with its ETag', async () => {
    const port = fakeFetch(() => json(model, 201, 'application/json', TAG));
    const request = {
      id: 'mappings/new',
      source: { bundle: ['order.xsd'], root: { element: 'Q{urn:a}Order' } },
      target: { bundle: ['order.json'], root: { json: '$' as const } },
    };

    expect(await httpApiClient('', port.fetch).createMapping(request)).toEqual({ model, etag: TAG });
    expect(port.calls).toEqual([{ url: '/api/mappings', method: 'POST', contentType: 'application/json', body: request }]);
  });

  test('a 409 mapping-exists answer to a create is an ApiProblem with the problem', async () => {
    const exists = {
      type: 'urn:flottila:mapping:problem:mapping-exists',
      title: 'Mapping exists',
      status: 409,
      detail: 'a mapping with this id exists',
    };
    const port = fakeFetch(() => json(exists, 409, 'application/problem+json'));
    const side = { bundle: ['a.xsd'], root: { element: 'a' } };

    const error = await httpApiClient('', port.fetch).createMapping({ id: 'm/one', source: side, target: side }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).problem).toEqual(exists);
  });

  test('loads a structure by POST to /api/structures', async () => {
    const refused = { kind: 'refused', diagnostics: [] };
    const port = fakeFetch(() => json(refused));
    const request = { base: 'mappings', bundle: ['order.xsd'], root: { element: 'Q{urn:a}Order' } };

    expect(await httpApiClient('', port.fetch).loadStructure(request)).toEqual(refused);
    expect(port.calls).toEqual([{ url: '/api/structures', method: 'POST', contentType: 'application/json', body: request }]);
  });

  test('reads the function catalogue by GET on /api/catalogue', async () => {
    const catalogue = {
      constant: { family: 'constant', executionKind: 'single', portable: true, fidelity: { level: 'exact' } },
    };
    const port = fakeFetch(() => json(catalogue));

    expect(await httpApiClient('http://svc', port.fetch).getCatalogue()).toEqual(catalogue);
    expect(port.calls).toEqual([{ url: 'http://svc/api/catalogue', method: 'GET', contentType: null, body: undefined }]);
  });

  test('runs a test by POST of the working copy and the input to the mapping test path', async () => {
    const output = { kind: 'output', output: '<a/>', diagnostics: [], trace: {} };
    const port = fakeFetch(() => json(output));

    expect(await httpApiClient('', port.fetch).runTest('m/one', { model, input: '<a/>' })).toEqual(output);
    expect(port.calls).toEqual([
      { url: '/api/mappings/m/one/test', method: 'POST', contentType: 'application/json', body: { model, input: '<a/>' } },
    ]);
  });

  test('compiles by POST of the working copy to the mapping compile path, passing the abort signal on', async () => {
    const compiled = {
      diagnostics: [
        { severity: 'warning', code: 'unreachable-box', message: 'not reached', location: { kind: 'authoring-id', authoringId: 'f9' } },
      ],
    };
    const port = fakeFetch(() => json(compiled));
    const signals: (AbortSignal | null | undefined)[] = [];
    const fetch: typeof port.fetch = (input, init) => {
      signals.push(init?.signal);
      return port.fetch(input, init);
    };
    const controller = new AbortController();

    expect(await httpApiClient('', fetch).compile('m/one', model, controller.signal)).toEqual(compiled);
    expect(port.calls).toEqual([{ url: '/api/mappings/m/one/compile', method: 'POST', contentType: 'application/json', body: model }]);
    expect(signals).toEqual([controller.signal]);
  });

  test('refreshes by POST of the working copy to the mapping refresh path, and answers the refreshed model and its diagnostics', async () => {
    const refreshed = {
      model: { ...model, source: { ...model.source, fingerprint: 'sha256:11' } },
      diagnostics: [
        { severity: 'error', code: 'orphaned-reference', message: 'gone', location: { kind: 'authoring-id', authoringId: 's1' } },
      ],
    };
    const port = fakeFetch(() => json(refreshed));

    expect(await httpApiClient('', port.fetch).refresh('m/one', model)).toEqual(refreshed);
    expect(port.calls).toEqual([{ url: '/api/mappings/m/one/refresh', method: 'POST', contentType: 'application/json', body: model }]);
  });

  test('a problem+json answer becomes an ApiProblem with its type, status and diagnostics', async () => {
    const refused = {
      type: 'urn:flottila:mapping:problem:model-refused',
      title: 'Model refused',
      status: 422,
      detail: 'the mapping model is refused by validation stage 1 or 2',
      diagnostics: [
        { severity: 'error', code: 'missing-member', message: 'required properties are missing: source, target', location: { kind: 'pointer', pointer: '' } },
      ],
    };
    const port = fakeFetch(() => json(refused, 422, 'application/problem+json'));

    const error = await httpApiClient('', port.fetch).saveMapping('m/one', model, TAG).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).problem).toEqual(refused);
    expect((error as ApiProblem).message).toBe('Model refused: the mapping model is refused by validation stage 1 or 2');
  });

  test('an error answer without problem+json still becomes an ApiProblem with its status', async () => {
    // Vert.x answers a body above 10 MB with a bare 413 in spec 1 (ADR-0024)
    const port = fakeFetch(() => new Response('', { status: 413, statusText: 'Request Entity Too Large' }));

    const error = await httpApiClient('', port.fetch).runTest('m/one', { model, input: '<a/>' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).problem).toEqual({ type: 'about:blank', title: 'Request Entity Too Large', status: 413, detail: '' });
    expect((error as ApiProblem).message).toBe('Request Entity Too Large');
  });
});
