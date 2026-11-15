import { describe, expect, test } from 'vitest';
import type { Diagnostic, FunctionCatalogue, StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore } from './working-copy';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const SKU = `${ORDER}/Item/Sku`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** OrderId: a1 (an item's Sku) into toUpperCase f1, the result, and a formatNumber n1 that reads nothing. */
const opened: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        a1: { kind: 'source', field: SKU },
        f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] },
        n1: { kind: 'function', fn: 'formatNumber' },
      },
    },
  },
};

const catalogue: FunctionCatalogue = {
  formatNumber: {
    family: 'arithmetic',
    executionKind: 'single',
    inputs: [{ name: 'value' }],
    params: [{ name: 'pattern', type: 'string', default: '0', syntax: 'numberPattern' }],
    portable: true,
    fidelity: { level: 'exact' },
  },
};

function store() {
  const stored: StoredMapping = { model: opened, etag: '"sha256:base"' };
  const never = () => new Promise<never>(() => {});
  return createWorkingCopyStore(stored, {
    newId: () => 'unused',
    save: never,
    load: never,
    compile: () => new Promise<Diagnostic[]>(() => {}),
  });
}

const boxes = (s: ReturnType<typeof store>) => s.getState().workingCopy.targetFieldMappings![ORDER_ID]!.boxes;

describe("the inspector's edits", () => {
  test('set param, set context and set input slot each run their command as one undo step', () => {
    const s = store();

    s.getState().setParam(ORDER_ID, 'n1', 'pattern', '#,##0', catalogue);
    expect(boxes(s).n1).toEqual({ kind: 'function', fn: 'formatNumber', params: { pattern: '#,##0' } });
    s.getState().setContext(ORDER_ID, 'a1', ORDER);
    expect(boxes(s).a1).toEqual({ kind: 'source', field: SKU, context: ORDER });
    s.getState().setInput(ORDER_ID, 'n1', 0, 'f1');
    expect(boxes(s).n1).toMatchObject({ inputs: ['f1'] });
    s.getState().setInput(ORDER_ID, 'n1', 0, null);
    expect(boxes(s).n1).toMatchObject({ inputs: [null] });

    expect(s.getState().past).toHaveLength(4);
    s.getState().undo();
    expect(boxes(s).n1).toMatchObject({ inputs: ['f1'] });
  });

  test.each([
    ['a param the entry lacks', (s: ReturnType<typeof store>) => s.getState().setParam(ORDER_ID, 'n1', 'scale', '2', catalogue), 'unknown-parameter', 'n1'],
    ['a context that is not an ancestor', (s: ReturnType<typeof store>) => s.getState().setContext(ORDER_ID, 'a1', ORDER_ID), 'context-not-ancestor', 'a1'],
    ['a slot wired into a cycle', (s: ReturnType<typeof store>) => s.getState().setInput(ORDER_ID, 'f1', 0, 'f1'), 'box-cycle', 'f1'],
  ])('a refused edit (%s) leaves the working copy and records why', (_, edit, code, id) => {
    const s = store();

    edit(s);

    expect(s.getState().workingCopy).toBe(opened);
    expect(s.getState().refusal).toEqual({ code, id });
    expect(s.getState().past).toEqual([]);
  });
});
