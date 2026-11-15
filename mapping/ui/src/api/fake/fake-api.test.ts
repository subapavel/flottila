import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../../generated/mapping-model';
import { ApiProblem, type Diagnostic, type TestResult } from '../client';
import { fakeApiClient } from './fake-api';

// The expectations come from the corpus files and from replies of the real service, observed on 2026-11-02.
const corpus = (path: string) => readFileSync(new URL(`../../../../corpus/${path}`, import.meta.url), 'utf8');
const corpusMapping = JSON.parse(corpus('mappings/order-id-copy.mapping.json')) as MappingModel;
const caseInput = corpus('cases/link/direct-copy/001/input.xml');
const caseOutput = corpus('cases/link/direct-copy/001/expected.xml');
const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';

async function problemOf(call: Promise<unknown>): Promise<ApiProblem> {
  const error: unknown = await call.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(ApiProblem);
  return error as ApiProblem;
}

describe('the fake ApiClient over the corpus', () => {
  test('opens the corpus mapping, and the mapping without its target field mappings', async () => {
    const api = fakeApiClient();

    expect((await api.getMapping('mappings/order-id-copy')).model).toEqual(corpusMapping);
    const unmapped = { ...corpusMapping };
    delete unmapped.targetFieldMappings;
    expect((await api.getMapping('mappings/order-unmapped')).model).toEqual(unmapped);
  });

  test('tags each mapping, and the tag stays until the mapping is written', async () => {
    const api = fakeApiClient();

    const first = await api.getMapping('mappings/order-id-copy');
    const other = await api.getMapping('mappings/order-unmapped');

    expect(first.etag).toMatch(/^"fake:\d+"$/);
    expect(other.etag).not.toBe(first.etag);
    expect((await api.getMapping('mappings/order-id-copy')).etag).toBe(first.etag);
  });

  test('answers an unknown mapping id with a not-found problem', async () => {
    const problem = await problemOf(fakeApiClient().getMapping('mappings/missing'));

    expect(problem.problem.status).toBe(404);
    expect(problem.problem.type).toBe('urn:flottila:mapping:problem:not-found');
  });

  test('save keeps the model, sorts its keys as the canonical writer does, and the next open returns it', async () => {
    const api = fakeApiClient();
    const sent: MappingModel = {
      target: corpusMapping.target,
      source: corpusMapping.source,
      formatVersion: 1,
      targetFieldMappings: { [ORDER_ID]: { result: 'b1', id: 'm1', boxes: { b1: { kind: 'source', field: ORDER_ID } } } },
    };

    const { etag } = await api.getMapping('mappings/order-unmapped');

    const saved = await api.saveMapping('mappings/order-unmapped', sent, etag);

    expect(saved.model).toEqual(sent);
    expect(saved.etag).not.toBe(etag);
    expect(Object.keys(saved.model)).toEqual(['formatVersion', 'source', 'target', 'targetFieldMappings']);
    expect(Object.keys(saved.model.targetFieldMappings![ORDER_ID]!)).toEqual(['boxes', 'id', 'result']);
    expect(await api.getMapping('mappings/order-unmapped')).toEqual(saved);
    expect((await problemOf(api.saveMapping('mappings/missing', sent, etag))).problem.status).toBe(404);
  });

  test('a save with a tag the stored mapping no longer has is 409 mapping-changed and keeps what is stored', async () => {
    const api = fakeApiClient();
    const opened = await api.getMapping('mappings/order-id-copy');
    const unmapped = { ...corpusMapping };
    delete unmapped.targetFieldMappings;
    await api.saveMapping('mappings/order-id-copy', unmapped, opened.etag);

    const problem = await problemOf(api.saveMapping('mappings/order-id-copy', corpusMapping, opened.etag));

    expect(problem.problem.status).toBe(409);
    expect(problem.problem.type).toBe('urn:flottila:mapping:problem:mapping-changed');
    expect((await api.getMapping('mappings/order-id-copy')).model).toEqual(unmapped);
  });

  test('what it returns is a copy: changing it leaves the fake as it was', async () => {
    const api = fakeApiClient();
    const first = await api.getMapping('mappings/order-id-copy');
    first.model.targetFieldMappings![ORDER_ID]!.result = null;

    expect((await api.getMapping('mappings/order-id-copy')).model).toEqual(corpusMapping);
  });

  test('loads the order structure relative to the mapping directory', async () => {
    const response = await fakeApiClient().loadStructure({
      base: 'mappings',
      bundle: corpusMapping.source!.bundle,
      root: corpusMapping.source!.root,
    });

    expect(response.kind).toBe('loaded');
    if (response.kind !== 'loaded') return;
    expect(response.fingerprint).toBe(corpusMapping.source!.fingerprint);
    expect(response.root.step).toBe('Q{urn:flottila:corpus:skeleton}Order');
    expect(response.types[response.root.type!]!.map((f) => f.step)).toEqual([
      '@channel',
      'OrderId',
      'Quantity',
      'Amount',
      'Urgent',
      'DeliveryDate',
      'PickupTime',
      'CreatedAt',
      'Note',
    ]);
  });

  test('loads the same-step structure of the corpus mappings that use a refused field, with its refusals (#116)', async () => {
    const sameStep = JSON.parse(corpus('mappings/same-step-source-box.mapping.json')) as MappingModel;

    const response = await fakeApiClient().loadStructure({ base: 'mappings', bundle: sameStep.source!.bundle, root: sameStep.source!.root });

    expect(response.kind).toBe('loaded');
    if (response.kind !== 'loaded') return;
    expect(response.fingerprint).toBe(sameStep.source!.fingerprint);
    expect(response.types['/Doc']!.map((f) => `${f.step} ${f.refusal?.code ?? '-'}`)).toEqual([
      'Id -',
      'Note ambiguous-field-path',
      'Code -',
      'Note ambiguous-field-path',
      'Head -',
    ]);
  });

  test('resolves a bundle against the workspace root when the base is empty', async () => {
    const response = await fakeApiClient().loadStructure({
      base: '',
      bundle: ['structures/skeleton/order.xsd'],
      root: corpusMapping.source!.root,
    });

    expect(response.kind).toBe('loaded');
  });

  test('refuses a bundle it does not know as unreadable at line 1, column 1 of the file', async () => {
    const response = await fakeApiClient().loadStructure({
      base: 'mappings',
      bundle: ['missing.xsd'],
      root: corpusMapping.source!.root,
    });

    expect(response).toEqual({
      kind: 'refused',
      diagnostics: [
        {
          severity: 'error',
          code: 'schema-unreadable',
          message: 'the fake API has no structure for mappings/missing.xsd',
          location: { kind: 'file-position', file: 'missing.xsd', line: 1, column: 1 },
        },
      ],
    });
  });

  test('a test run of the corpus mapping on the corpus input gives the corpus output', async () => {
    const result = await fakeApiClient().runTest('mappings/order-id-copy', { model: corpusMapping, input: caseInput });

    // since #53 a test run's output carries its trace; the fake records none (ADR-0015)
    expect(result).toEqual({ kind: 'output', output: caseOutput, diagnostics: [], trace: {} });
  });

  test('a link drawn with other ids is the same link: the test run still gives the corpus output', async () => {
    const relinked: MappingModel = {
      ...corpusMapping,
      targetFieldMappings: { [ORDER_ID]: { id: 'x9', result: 'y9', boxes: { y9: { kind: 'source', field: ORDER_ID } } } },
    };

    const result = await fakeApiClient().runTest('mappings/order-unmapped', { model: relinked, input: caseInput });

    expect(result.kind).toBe('output');
  });

  test('a target field mapping whose result is null refuses the test run with unconnected-target-field at its id', async () => {
    const unfinished = structuredClone(corpusMapping);
    unfinished.targetFieldMappings![ORDER_ID]!.result = null;

    const result = await fakeApiClient().runTest('mappings/order-id-copy', { model: unfinished, input: caseInput });

    expect(result).toEqual({
      kind: 'refused',
      diagnostics: [
        {
          severity: 'error',
          code: 'unconnected-target-field',
          message: "the target field mapping's result is not connected",
          location: { kind: 'authoring-id', authoringId: 't1' },
        },
      ],
    });
  });

  test('a working copy or input without a corpus case is an invalid-request problem, never a made-up output', async () => {
    const api = fakeApiClient();
    const unmapped = { ...corpusMapping };
    delete unmapped.targetFieldMappings;

    const noCase = await problemOf(api.runTest('mappings/order-unmapped', { model: unmapped, input: caseInput }));
    const otherInput = await problemOf(api.runTest('mappings/order-id-copy', { model: corpusMapping, input: '<a/>' }));

    for (const problem of [noCase, otherInput]) {
      expect(problem.problem.status).toBe(400);
      expect(problem.problem.type).toBe('urn:flottila:mapping:problem:invalid-request');
    }
  });

  test('a test run of a mapping the scenario names answers its fixture, whatever the working copy and input', async () => {
    const minOccurs: TestResult = {
      kind: 'error',
      error: { code: 'po:MINOCCURS', message: 'too few occurrences', authoringId: 't1' },
      diagnostics: [],
      trace: { t1: { kind: 'occurrences', min: 1, max: 1, capped: false, parents: [{ parentSlot: 1, created: 0, slots: [] }] } },
    };
    const api = fakeApiClient(undefined, { test: { 'mappings/order-id-copy': minOccurs } });

    expect(await api.runTest('mappings/order-id-copy', { model: corpusMapping, input: '<a/>' })).toEqual(minOccurs);
    expect((await api.runTest('mappings/order-id-copy', { model: corpusMapping, input: caseInput })).kind).toBe('error');
    expect((await problemOf(api.runTest('mappings/order-unmapped', { model: corpusMapping, input: '<a/>' }))).problem.status).toBe(400);
  });

  test('lists its mappings sorted by id, each named by the last segment of its id, in the package of its folder', async () => {
    const api = fakeApiClient({ 'b/two': corpusMapping, 'a/deep/one': corpusMapping, 'b/one': corpusMapping });

    // the corpus mapping's roots are the element Q{urn:flottila:corpus:skeleton}Order on both sides
    expect(await api.listMappings()).toStrictEqual([
      { id: 'a/deep/one', name: 'one', package: 'a/deep', source: 'Order', target: 'Order' },
      { id: 'b/one', name: 'one', package: 'b', source: 'Order', target: 'Order' },
      { id: 'b/two', name: 'two', package: 'b', source: 'Order', target: 'Order' },
    ]);
  });

  test('a summary names a WSDL root by its operation and a JSON root by its first file, and leaves out what is not there (ADR-0029)', async () => {
    const described: MappingModel = {
      description: 'Bestellungen → Rechnungen',
      formatVersion: 1,
      source: {
        bundle: ['../wsdl/orders.wsdl'],
        fingerprint: 'sha256:00',
        root: { wsdl: { interface: 'Q{urn:x}OrderPort', operation: 'SendOrder', direction: 'fault', fault: 'Rejected' } },
      },
      target: { bundle: ['schemas/a-note.schema.json', 'z-defs.schema.json'], fingerprint: 'sha256:00', root: { json: '$' } },
    };
    const onlyTarget: MappingModel = {
      formatVersion: 1,
      target: { bundle: ['invoice.xsd'], fingerprint: 'sha256:00', root: { element: 'Invoice' } },
    };
    const api = fakeApiClient({ top: described, 'drafts/bare': { formatVersion: 1 }, 'drafts/half': onlyTarget });

    expect(await api.listMappings()).toStrictEqual([
      { id: 'drafts/bare', name: 'bare', package: 'drafts' },
      { id: 'drafts/half', name: 'half', package: 'drafts', target: 'Invoice' },
      { id: 'top', name: 'top', package: '', description: 'Bestellungen → Rechnungen', source: 'SendOrder', target: 'a-note.schema.json' },
    ]);
  });

  test('creates a mapping from its id alone, without structures; it is listed and opens with its tag', async () => {
    const api = fakeApiClient();

    const created = await api.createMapping({ id: 'drafts/new-one' });

    expect(created.model).toStrictEqual({ formatVersion: 1 });
    expect(created.etag).toMatch(/^"fake:\d+"$/);
    expect(await api.listMappings()).toContainEqual({ id: 'drafts/new-one', name: 'new-one', package: 'drafts' });
    expect(await api.getMapping('drafts/new-one')).toEqual(created);
  });

  test('creates a mapping with its description and the one side it is given, keys sorted as the canonical writer does', async () => {
    const api = fakeApiClient();
    const order = '../structures/skeleton/order.xsd';
    const root = { element: 'Q{urn:flottila:corpus:skeleton}Order' };

    const described = await api.createMapping({ id: 'drafts/described', description: 'Bestellungen zu Rechnungen' });
    const half = await api.createMapping({ id: 'mappings/half', description: 'only the target', target: { bundle: [order], root } });

    expect(described.model).toStrictEqual({ description: 'Bestellungen zu Rechnungen', formatVersion: 1 });
    expect(Object.keys(described.model)).toEqual(['description', 'formatVersion']);
    expect(half.model).toStrictEqual({
      description: 'only the target',
      formatVersion: 1,
      target: { bundle: [order], fingerprint: corpusMapping.target!.fingerprint, root },
    });
    expect(Object.keys(half.model)).toEqual(['description', 'formatVersion', 'target']);
  });

  test('a create with only a source it does not know is 422 model-refused with that one diagnostic', async () => {
    const api = fakeApiClient();

    const problem = await problemOf(api.createMapping({ id: 'mappings/new-one', source: { bundle: ['missing.xsd'], root: { json: '$' } } }));

    expect(problem.problem.status).toBe(422);
    expect('diagnostics' in problem.problem && problem.problem.diagnostics.map((d) => d.code)).toEqual(['schema-unreadable']);
    expect((await api.listMappings()).map((m) => m.id)).not.toContain('mappings/new-one');
  });

  test('save keeps the description and the layout, their keys sorted as the canonical writer does', async () => {
    const api = fakeApiClient();
    const { etag } = await api.getMapping('mappings/order-id-copy');
    const sent: MappingModel = {
      targetFieldMappings: {
        [ORDER_ID]: {
          result: 'a1',
          layout: { target: { y: 48, x: 360 }, boxes: { a1: { y: 48, x: -120 } } },
          id: 't1',
          boxes: { a1: { kind: 'source', field: ORDER_ID } },
        },
      },
      target: corpusMapping.target,
      source: corpusMapping.source,
      formatVersion: 1,
      description: 'Copies the order id',
    };

    const saved = await api.saveMapping('mappings/order-id-copy', sent, etag);

    expect(saved.model).toEqual(sent);
    expect(Object.keys(saved.model)).toEqual(['description', 'formatVersion', 'source', 'target', 'targetFieldMappings']);
    const layout = saved.model.targetFieldMappings![ORDER_ID]!.layout!;
    expect(Object.keys(layout)).toEqual(['boxes', 'target']);
    expect(Object.keys(layout.target!)).toEqual(['x', 'y']);
  });

  test('creates a mapping with both fingerprints and no target field mapping; it is listed and opens with its tag', async () => {
    const api = fakeApiClient();
    const order = '../structures/skeleton/order.xsd';

    const created = await api.createMapping({
      id: 'mappings/new-one',
      source: { bundle: [order], root: { json: '$' } },
      target: { bundle: [order], root: { element: 'Q{urn:flottila:corpus:skeleton}Order' } },
    });

    expect(created.model).toEqual({
      formatVersion: 1,
      source: { bundle: [order], fingerprint: corpusMapping.source!.fingerprint, root: { json: '$' } },
      target: { bundle: [order], fingerprint: corpusMapping.target!.fingerprint, root: { element: 'Q{urn:flottila:corpus:skeleton}Order' } },
    });
    expect(created.etag).toMatch(/^"fake:\d+"$/);
    expect((await api.listMappings()).map((m) => m.id)).toContain('mappings/new-one');
    expect(await api.getMapping('mappings/new-one')).toEqual(created);
  });

  test('a create with an id that exists is 409 mapping-exists and keeps what is stored', async () => {
    const api = fakeApiClient();
    const before = await api.getMapping('mappings/order-id-copy');

    const problem = await problemOf(
      api.createMapping({ id: 'mappings/order-id-copy', source: corpusMapping.source, target: corpusMapping.target }),
    );

    expect(problem.problem.status).toBe(409);
    expect(problem.problem.type).toBe('urn:flottila:mapping:problem:mapping-exists');
    expect(await api.getMapping('mappings/order-id-copy')).toEqual(before);
  });

  test.each(['', 'mappings/../escape', '/mappings/abs', 'mappings//double', 'mappings/test', 'mappings/a b'])(
    'a create with the id %j, not a workspace path, is 400 invalid-workspace-path',
    async (id) => {
      const api = fakeApiClient();
      const before = await api.listMappings();

      const problem = await problemOf(api.createMapping({ id, source: corpusMapping.source, target: corpusMapping.target }));

      expect(problem.problem.status).toBe(400);
      expect(problem.problem.type).toBe('urn:flottila:mapping:problem:invalid-workspace-path');
      expect(await api.listMappings()).toEqual(before);
    },
  );

  test.each(['source', 'target'])('a create with a %s bundle it does not know is 422 model-refused with the diagnostic, and creates nothing', async (side) => {
    const api = fakeApiClient();
    const unknown = { bundle: ['missing.xsd'], root: { json: '$' as const } };

    const problem = await problemOf(
      api.createMapping({
        id: 'mappings/new-one',
        source: side === 'source' ? unknown : corpusMapping.source,
        target: side === 'target' ? unknown : corpusMapping.target,
      }),
    );

    expect(problem.problem.status).toBe(422);
    expect(problem.problem.type).toBe('urn:flottila:mapping:problem:model-refused');
    expect('diagnostics' in problem.problem && problem.problem.diagnostics.map((d) => d.code)).toEqual(['schema-unreadable']);
    expect((await api.listMappings()).map((m) => m.id)).not.toContain('mappings/new-one');
  });

  test('compile answers the scenario fixture of the mapping, and no diagnostics for a mapping it does not name', async () => {
    const unreachable: Diagnostic = {
      severity: 'warning',
      code: 'unreachable-box',
      message: 'the result of the target field mapping t1 does not reach this box, so compile ignores it',
      location: { kind: 'authoring-id', authoringId: 'f9' },
    };
    const api = fakeApiClient(undefined, { compile: { 'mappings/order-id-copy': [unreachable] } });

    expect(await api.compile('mappings/order-id-copy', corpusMapping)).toEqual({ diagnostics: [unreachable] });
    expect(await api.compile('mappings/order-unmapped', corpusMapping)).toEqual({ diagnostics: [] });
    expect(await fakeApiClient().compile('mappings/order-id-copy', corpusMapping)).toEqual({ diagnostics: [] });
  });

  test('compile of a mapping it does not know is a not-found problem', async () => {
    const problem = await problemOf(fakeApiClient().compile('mappings/missing', corpusMapping));

    expect(problem.problem.status).toBe(404);
  });

  describe('refresh', () => {
    const stale = { ...corpusMapping, source: { ...corpusMapping.source!, fingerprint: 'sha256:stale' } };
    const orphaned: Diagnostic = {
      severity: 'error',
      code: 'orphaned-reference',
      message: 'the structure has no field /Q{urn:flottila:corpus:skeleton}Order/Gone',
      location: { kind: 'authoring-id', authoringId: 'a1' },
    };

    test('derives each fingerprint from the structure it loads, and answers the scenario fixture as the diagnostics', async () => {
      const api = fakeApiClient(undefined, { refresh: { 'mappings/order-id-copy': [orphaned] } });

      const answer = await api.refresh('mappings/order-id-copy', stale);

      expect(answer).toEqual({ model: corpusMapping, diagnostics: [orphaned] });
    });

    test('answers no diagnostics for a mapping the scenario does not name, and keeps the stored file as it is', async () => {
      const api = fakeApiClient();

      expect(await api.refresh('mappings/order-id-copy', stale)).toEqual({ model: corpusMapping, diagnostics: [] });
      expect((await api.getMapping('mappings/order-id-copy')).model).toEqual(corpusMapping);
    });

    test('a side whose bundle does not load keeps its fingerprint and gives its load errors as the only diagnostics', async () => {
      const broken = { ...stale, target: { ...stale.target!, bundle: ['missing.xsd'] as [string], fingerprint: 'sha256:kept' } };
      const api = fakeApiClient(undefined, { refresh: { 'mappings/order-id-copy': [orphaned] } });

      const answer = await api.refresh('mappings/order-id-copy', broken);

      expect(answer.model).toEqual({ ...broken, source: corpusMapping.source });
      expect(answer.diagnostics).toEqual([
        {
          severity: 'error',
          code: 'schema-unreadable',
          message: 'the fake API has no structure for mappings/missing.xsd',
          location: { kind: 'file-position', file: 'missing.xsd', line: 1, column: 1 },
        },
      ]);
    });

    test('of a model with one side derives that fingerprint, leaves the other side out and keeps the description', async () => {
      const api = fakeApiClient();
      const half: MappingModel = { formatVersion: 1, description: 'kept', source: stale.source };

      expect(await api.refresh('mappings/order-id-copy', half)).toStrictEqual({
        model: { description: 'kept', formatVersion: 1, source: corpusMapping.source },
        diagnostics: [],
      });
    });

    test('of a model without structures returns it as it is, with no diagnostics', async () => {
      const api = fakeApiClient();

      expect(await api.refresh('mappings/order-id-copy', { formatVersion: 1 })).toStrictEqual({ model: { formatVersion: 1 }, diagnostics: [] });
    });

    test('of a mapping it does not know is a not-found problem', async () => {
      expect((await problemOf(fakeApiClient().refresh('mappings/missing', corpusMapping))).problem.status).toBe(404);
    });
  });

  test('a fake seeded with its own mappings opens those instead of the corpus', async () => {
    const api = fakeApiClient({ 'drafts/one': corpusMapping });

    expect((await api.getMapping('drafts/one')).model).toEqual(corpusMapping);
    expect((await problemOf(api.getMapping('mappings/order-id-copy'))).problem.status).toBe(404);
  });

  test('serves the committed function catalogue of contract/ (ADR-0027)', async () => {
    const committed = readFileSync(new URL('../../../../contract/function-catalogue.json', import.meta.url), 'utf8');

    const catalogue = await fakeApiClient().getCatalogue();

    expect(catalogue).toEqual(JSON.parse(committed));
    // witness: spec 2's functions are in it
    expect(catalogue.useOneAsMany?.family).toBe('context');
  });
});
