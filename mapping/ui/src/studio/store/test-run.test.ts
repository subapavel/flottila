import { describe, expect, test } from 'vitest';
import type { Diagnostic, StoredMapping, TestResult } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectTrace, selectTraceStale } from './working-copy';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};
const baseline: MappingModel = { formatVersion: 1, source: structure, target: structure };

const warning = (code: string): Diagnostic => ({ severity: 'warning', code, message: code, location: { kind: 'authoring-id', authoringId: 'b1' } });

const output: TestResult = {
  kind: 'output',
  output: '<Order/>',
  diagnostics: [warning('unreachable-box')],
  trace: { b1: { kind: 'queue', contexts: [['PO 4711']], capped: false, slotCount: 1, contextCount: 1 } },
};

function freshIds() {
  let next = 0;
  return () => `x${++next}`;
}

/** A store whose save writes `written` (the canonical model) and whose compile never answers. */
function store(written?: (sent: MappingModel) => MappingModel) {
  return createWorkingCopyStore(
    { model: baseline, etag: '"e1"' },
    {
      newId: freshIds(),
      save: async (model): Promise<StoredMapping> => ({ model: written ? written(model) : model, etag: '"e2"' }),
      load: () => new Promise<StoredMapping>(() => {}),
      compile: () => new Promise<Diagnostic[]>(() => {}),
    },
  );
}

describe('the last test run in the store', () => {
  test('there is no trace before a run, and an edit then marks nothing stale', () => {
    const s = store();
    s.getState().link(ORDER_ID, ORDER_ID);

    expect(s.getState().lastRun).toBeNull();
    expect(selectTrace(s.getState())).toBeUndefined();
    expect(selectTraceStale(s.getState())).toBe(false);
  });

  test('a run of the working copy keeps its result and trace, not stale, and its diagnostics become the latest', () => {
    const s = store();
    s.getState().recordRun(s.getState().workingCopy, output);

    expect(s.getState().lastRun).toEqual(output);
    expect(selectTrace(s.getState())).toEqual(output.trace);
    expect(selectTraceStale(s.getState())).toBe(false);
    expect(s.getState().diagnostics).toEqual([warning('unreachable-box')]);
  });

  test('an edit after a run marks the trace stale and keeps it; a new run clears the mark', () => {
    const s = store();
    s.getState().recordRun(s.getState().workingCopy, output);

    s.getState().link(ORDER_ID, ORDER_ID);

    expect(selectTraceStale(s.getState())).toBe(true);
    expect(selectTrace(s.getState())).toEqual(output.trace);

    s.getState().recordRun(s.getState().workingCopy, output);

    expect(selectTraceStale(s.getState())).toBe(false);
  });

  test('a save that writes the run model in canonical form leaves the trace fresh', async () => {
    const s = store((sent) => structuredClone(sent));
    s.getState().link(ORDER_ID, ORDER_ID);
    s.getState().recordRun(s.getState().workingCopy, output);

    await s.getState().save();

    expect(selectTraceStale(s.getState())).toBe(false);
  });

  test('a run answered after an edit is stale at once and leaves the diagnostics of the newer working copy', () => {
    const s = store();
    const sent = s.getState().workingCopy;
    s.getState().link(ORDER_ID, ORDER_ID);

    s.getState().recordRun(sent, output);

    expect(selectTraceStale(s.getState())).toBe(true);
    expect(s.getState().diagnostics).toEqual([]);
  });

  test('a run refused by compile has no trace, and its diagnostics fill the latest set', () => {
    const s = store();
    s.getState().link(NOTE, NOTE);
    const refused: TestResult = {
      kind: 'refused',
      diagnostics: [{ severity: 'error', code: 'unconnected-target-field', message: 'not connected', location: { kind: 'authoring-id', authoringId: 'x2' } }],
    };

    s.getState().recordRun(s.getState().workingCopy, refused);

    expect(selectTrace(s.getState())).toBeUndefined();
    expect(s.getState().diagnostics).toEqual(refused.diagnostics);

    // no trace, so nothing to mark stale after an edit
    s.getState().link(ORDER_ID, ORDER_ID);
    expect(selectTraceStale(s.getState())).toBe(false);
  });

  test('a runtime error keeps the trace up to the error', () => {
    const s = store();
    const error: TestResult = {
      kind: 'error',
      error: { code: 'po:MINOCCURS', message: 'too few', authoringId: 'm1' },
      diagnostics: [],
      trace: { m1: { kind: 'occurrences', min: 1, capped: false, parents: [{ parentSlot: 1, created: 0, slots: [] }] } },
    };

    s.getState().recordRun(s.getState().workingCopy, error);

    expect(selectTrace(s.getState())).toEqual(error.trace);
  });
});
