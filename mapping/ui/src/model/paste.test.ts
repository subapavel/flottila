import { describe, expect, test } from 'vitest';
import type { Box, MappingModel } from '../generated/mapping-model';
import { paste } from './paste';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

const a1: Box = { kind: 'source', field: ORDER_ID, context: ORDER };
const c1: Box = { kind: 'function', fn: 'constant', params: { value: '-' } };
const f1: Box = { kind: 'function', fn: 'concat', inputs: ['a1', 'c1'] };
const u1: Box = { kind: 'function', fn: 'toUpperCase', inputs: ['f1'] };

/** OrderId: a1 and c1 into concat f1, which is the result, and f1 into toUpperCase u1; Note has box n1. */
const graph: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: { id: 't1', result: 'f1', boxes: { a1, c1, f1, u1 } },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

/** Fresh ids `p` + the copied id. */
const primed = (copied: string) => `p${copied}`;

describe('the paste command', () => {
  test('adds each copied box under a fresh id, keeps references inside the fragment and nulls those leaving it', () => {
    // the field has a mapping: its mapping id is not used, so a taken one is not refused
    const result = paste(graph, { targetField: ORDER_ID, boxes: { c1, f1, u1 }, freshId: primed, mappingId: 'tn' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[ORDER_ID]).toEqual({
      id: 't1',
      result: 'f1',
      boxes: {
        a1,
        c1,
        f1,
        u1,
        pc1: c1,
        pf1: { kind: 'function', fn: 'concat', inputs: [null, 'pc1'] },
        pu1: { kind: 'function', fn: 'toUpperCase', inputs: ['pf1'] },
      },
    });
    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[NOTE]).toEqual(graph.targetFieldMappings![NOTE]);
  });

  test('keeps an empty slot empty, and a source-field box with its field and context', () => {
    const gap: Box = { kind: 'function', fn: 'concat', inputs: [null, 'a1'] };

    const result = paste(graph, { targetField: ORDER_ID, boxes: { a1, gap }, freshId: primed, mappingId: 'unused' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[ORDER_ID]?.boxes).toMatchObject({
      pa1: a1,
      pgap: { kind: 'function', fn: 'concat', inputs: [null, 'pa1'] },
    });
  });

  test('into a target field without a mapping creates {id, result: null, boxes}', () => {
    const emptied: MappingModel = { ...graph, targetFieldMappings: { [NOTE]: graph.targetFieldMappings![NOTE]! } };

    const result = paste(emptied, { targetField: ORDER_ID, boxes: { c1, f1 }, freshId: primed, mappingId: 't2' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[ORDER_ID]).toEqual({
      id: 't2',
      result: null,
      boxes: { pc1: c1, pf1: { kind: 'function', fn: 'concat', inputs: [null, 'pc1'] } },
    });
    const unmapped: MappingModel = { formatVersion: 1, source: structure, target: structure };
    expect(paste(unmapped, { targetField: NOTE, boxes: { c1 }, freshId: primed, mappingId: 't2' })).toEqual({
      kind: 'applied',
      model: { ...unmapped, targetFieldMappings: { [NOTE]: { id: 't2', result: null, boxes: { pc1: c1 } } } },
    });
  });

  test('an empty fragment leaves the model as it was, and creates no mapping', () => {
    expect(paste(graph, { targetField: `${ORDER}/Quantity`, boxes: {}, freshId: primed, mappingId: 't2' })).toEqual({ kind: 'applied', model: graph });
  });

  describe('never reuses an id: it refuses as duplicate-authoring-id', () => {
    test.each([
      ['a fresh id of a box of the same mapping', { a1: 'u1' }, 'u1'],
      ['a fresh id of a box of another mapping', { a1: 'n1' }, 'n1'],
      ['a fresh id of a target field mapping', { a1: 'tn' }, 'tn'],
      ['a fresh id drawn twice', { a1: 'x', c1: 'x' }, 'x'],
      ['a fresh id that is a copied id the model no longer has', { gone: 'gone' }, 'gone'],
    ])('%s', (_, ids: Record<string, string>, id) => {
      const boxes = Object.fromEntries(Object.keys(ids).map((copied) => [copied, c1]));

      expect(paste(graph, { targetField: ORDER_ID, boxes, freshId: (copied) => ids[copied]!, mappingId: 'unused' })).toEqual({
        kind: 'refused',
        code: 'duplicate-authoring-id',
        id,
      });
    });

    test('a new mapping id that is used, or that is a fresh box id', () => {
      const into = { targetField: `${ORDER}/Quantity`, boxes: { c1 }, freshId: primed };

      expect(paste(graph, { ...into, mappingId: 'tn' })).toEqual({ kind: 'refused', code: 'duplicate-authoring-id', id: 'tn' });
      expect(paste(graph, { ...into, mappingId: 'pc1' })).toEqual({ kind: 'refused', code: 'duplicate-authoring-id', id: 'pc1' });
    });
  });
});
