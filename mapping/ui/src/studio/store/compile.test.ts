import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { ApiProblem, type Diagnostic, type StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { COMPILE_DELAY_MS, createWorkingCopyStore } from './working-copy';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};
const baseline: MappingModel = { formatVersion: 1, source: structure, target: structure };

/** A diagnostic at an authoring id; the tests tell sets apart by code and id. */
const at = (code: string, authoringId: string, severity: Diagnostic['severity'] = 'error'): Diagnostic => ({
  severity,
  code,
  message: `${code} at ${authoringId}`,
  location: { kind: 'authoring-id', authoringId },
});

/** A compile port that answers each call when the test resolves or rejects it. */
function deferredCompile() {
  const calls: {
    sent: MappingModel;
    signal: AbortSignal;
    resolve: (diagnostics: Diagnostic[]) => void;
    reject: (error: unknown) => void;
  }[] = [];
  const compile = (sent: MappingModel, signal: AbortSignal) =>
    new Promise<Diagnostic[]>((resolve, reject) => {
      calls.push({ sent, signal, resolve, reject });
    });
  return { compile, calls };
}

/** A save port that rejects with `error`. */
const refusingSave = (error: unknown) => () => Promise.reject(error);

/** Hands out fresh ids: the link command needs two per link. */
function freshIds() {
  let next = 0;
  return () => `x${++next}`;
}

function storeWith(
  port: ReturnType<typeof deferredCompile>,
  save: (m: MappingModel, etag: string) => Promise<StoredMapping> = async (model) => ({ model, etag: '"e2"' }),
) {
  return createWorkingCopyStore(
    { model: baseline, etag: '"e1"' },
    { newId: freshIds(), save, load: () => new Promise<StoredMapping>(() => {}), compile: port.compile },
  );
}

/** Lets the answered port's promise settle. */
const settled = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the compile scheduler of the working-copy store', () => {
  test('compiles once, about 500 ms after the last of several edits, the working copy as it then is', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    await vi.advanceTimersByTimeAsync(2 * COMPILE_DELAY_MS);
    expect(port.calls).toHaveLength(0);

    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS - 200);
    store.getState().link(NOTE, NOTE);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS - 1);
    expect(port.calls).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(1);

    expect(COMPILE_DELAY_MS).toBe(500);
    expect(port.calls).toHaveLength(1);
    expect(port.calls[0]!.sent).toBe(store.getState().workingCopy);
    expect(Object.keys(port.calls[0]!.sent.targetFieldMappings ?? {})).toEqual([ORDER_ID, NOTE]);
  });

  test('a newer edit aborts the compile under way, and its late answer is dropped', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);

    store.getState().link(NOTE, NOTE);
    expect(port.calls[0]!.signal.aborted).toBe(true);
    port.calls[0]!.resolve([at('unconnected-target-field', 'old')]);
    await settled();

    expect(store.getState().diagnostics).toEqual([]);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[1]!.resolve([at('unreachable-box', 'new', 'warning')]);
    await settled();
    expect(store.getState().diagnostics).toEqual([at('unreachable-box', 'new', 'warning')]);
  });

  test('the rejection of an aborted compile is no compile failure', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);

    store.getState().link(NOTE, NOTE);
    port.calls[0]!.reject(new DOMException('The operation was aborted.', 'AbortError'));
    await settled();

    expect(store.getState().compileFailure).toBeNull();
  });

  test('the latest set replaces the previous one', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[0]!.resolve([at('orphaned-reference', 'b1'), at('unconnected-target-field', 'm1')]);
    await settled();
    expect(store.getState().diagnostics).toHaveLength(2);

    store.getState().link(NOTE, NOTE);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[1]!.resolve([at('unreachable-box', 'b2', 'warning')]);
    await settled();

    expect(store.getState().diagnostics).toEqual([at('unreachable-box', 'b2', 'warning')]);
  });

  test('a compile that fails in transport is the compile failure, which the next answer clears', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[0]!.reject(new ApiProblem({ type: 'urn:flottila:mapping:problem:not-found', title: 'Not found', status: 404, detail: '' }));
    await settled();
    expect(store.getState().compileFailure).toBe('Not found');

    store.getState().link(NOTE, NOTE);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[1]!.resolve([]);
    await settled();

    expect(store.getState().compileFailure).toBeNull();
  });

  test("a save refused with 422 replaces the set with its diagnostics until the next compile", async () => {
    const refused = new ApiProblem({
      type: 'urn:flottila:mapping:problem:model-refused',
      title: 'Model refused',
      status: 422,
      detail: 'the mapping model is refused by validation stage 1 or 2',
      diagnostics: [at('box-cycle', 'b1')],
    });
    const port = deferredCompile();
    const store = storeWith(port, refusingSave(refused));
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[0]!.resolve([at('unreachable-box', 'b9', 'warning')]);
    await settled();

    await expect(store.getState().save()).rejects.toBe(refused);
    expect(store.getState().diagnostics).toEqual([at('box-cycle', 'b1')]);

    store.getState().link(NOTE, NOTE);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[1]!.resolve([]);
    await settled();
    expect(store.getState().diagnostics).toEqual([]);
  });

  test('a save that fails without diagnostics leaves the set as it is', async () => {
    const notFound = new ApiProblem({ type: 'urn:flottila:mapping:problem:not-found', title: 'Not found', status: 404, detail: '' });
    const port = deferredCompile();
    const store = storeWith(port, refusingSave(notFound));
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[0]!.resolve([at('unreachable-box', 'b9', 'warning')]);
    await settled();

    await expect(store.getState().save()).rejects.toBe(notFound);

    expect(store.getState().diagnostics).toEqual([at('unreachable-box', 'b9', 'warning')]);
  });

  test('undo and redo change the working copy, so each schedules a compile of the copy it leaves', async () => {
    const port = deferredCompile();
    const store = storeWith(port);
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);

    store.getState().undo();
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    store.getState().redo();
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);

    expect(port.calls.map((c) => Object.keys(c.sent.targetFieldMappings ?? {}))).toEqual([[ORDER_ID], [], [ORDER_ID]]);
    expect(port.calls[1]!.sent).toBe(baseline);
  });

  test('a save conflict (409) is no refusal: the set stays and the conflict opens', async () => {
    const changed = new ApiProblem({
      type: 'urn:flottila:mapping:problem:mapping-changed',
      title: 'Mapping changed',
      status: 409,
      detail: 'the mapping changed on disk',
    });
    const port = deferredCompile();
    const store = storeWith(port, refusingSave(changed));
    store.getState().link(ORDER_ID, ORDER_ID);
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    port.calls[0]!.resolve([at('unreachable-box', 'b9', 'warning')]);
    await settled();

    await store.getState().save();

    expect(store.getState().conflict).toBe(true);
    expect(store.getState().diagnostics).toEqual([at('unreachable-box', 'b9', 'warning')]);
  });

  test('scheduleCompile compiles the working copy as it is, and cancelCompile drops one scheduled or under way', async () => {
    const port = deferredCompile();
    const store = storeWith(port);

    store.getState().scheduleCompile();
    store.getState().cancelCompile();
    await vi.advanceTimersByTimeAsync(2 * COMPILE_DELAY_MS);
    expect(port.calls).toHaveLength(0);

    store.getState().scheduleCompile();
    await vi.advanceTimersByTimeAsync(COMPILE_DELAY_MS);
    expect(port.calls.map((c) => c.sent)).toEqual([baseline]);
    store.getState().cancelCompile();
    expect(port.calls[0]!.signal.aborted).toBe(true);
    port.calls[0]!.resolve([at('unreachable-box', 'b9', 'warning')]);
    await settled();

    expect(store.getState().diagnostics).toEqual([]);
  });
});
