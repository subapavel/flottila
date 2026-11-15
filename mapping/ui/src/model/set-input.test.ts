import { describe, expect, test } from 'vitest';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { setInput } from './set-input';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** OrderId: a1 and c1 into concat f1, the result; u1 reads f1. Note: n1. */
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        a1: { kind: 'source', field: ORDER_ID },
        c1: { kind: 'function', fn: 'constant', params: { value: '-' } },
        f1: { kind: 'function', fn: 'concat', inputs: ['a1', 'c1'] },
        u1: { kind: 'function', fn: 'toUpperCase', inputs: ['f1'] },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

const orderId = (result: { kind: string; model?: MappingModel }): TargetFieldMapping | undefined =>
  result.kind === 'applied' ? result.model?.targetFieldMappings?.[ORDER_ID] : undefined;

describe('the set input slot command (the inspector’s slot picker)', () => {
  test('a box wires the slot to it', () => {
    expect(orderId(setInput(model, { targetField: ORDER_ID, target: 'f1', slot: 1, source: 'a1' }))?.boxes.f1).toEqual({
      kind: 'function',
      fn: 'concat',
      inputs: ['a1', 'a1'],
    });
  });

  test('null leaves the slot unconnected', () => {
    expect(orderId(setInput(model, { targetField: ORDER_ID, target: 'f1', slot: 0, source: null }))?.boxes.f1).toEqual({
      kind: 'function',
      fn: 'concat',
      inputs: [null, 'c1'],
    });
  });

  test.each([
    ['a box that reads the target, which would close a cycle', 'u1', 'box-cycle', 'f1'],
    ["another target field mapping's box", 'n1', 'cross-target-field-reference', 'n1'],
  ])('refuses %s, as a wire drawn on the canvas is', (_, source, code, id) => {
    expect(setInput(model, { targetField: ORDER_ID, target: 'f1', slot: 0, source })).toEqual({ kind: 'refused', code, id });
  });

  test("refuses null for another target field mapping's box, as unwiring on the canvas is", () => {
    expect(setInput(model, { targetField: ORDER_ID, target: 'n1', slot: 0, source: null })).toEqual({
      kind: 'refused',
      code: 'cross-target-field-reference',
      id: 'n1',
    });
  });
});
