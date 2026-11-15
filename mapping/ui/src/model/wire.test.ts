import { describe, expect, test } from 'vitest';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { setResult, unwire, wire } from './wire';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;
const QUANTITY = `${ORDER}/Quantity`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** OrderId: a1 and c1 into concat f1, the result; u1 reads f1 and feeds nothing; e1 has no inputs yet. Note: n1. */
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
        e1: { kind: 'function', fn: 'concat' },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

const orderId = (result: { kind: string; model?: MappingModel }): TargetFieldMapping | undefined =>
  result.kind === 'applied' ? result.model?.targetFieldMappings?.[ORDER_ID] : undefined;

describe('the wire command', () => {
  test("wires a box's output into an input slot, padding the slots before it with null", () => {
    const result = wire(model, { targetField: ORDER_ID, source: 'a1', target: 'e1', slot: 1 });

    expect(orderId(result)?.boxes.e1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'a1'] });
    expect(model.targetFieldMappings?.[ORDER_ID]?.boxes.e1).toEqual({ kind: 'function', fn: 'concat' });
  });

  test('replaces the box an input slot read, and one output may feed several inputs', () => {
    const result = wire(model, { targetField: ORDER_ID, source: 'a1', target: 'f1', slot: 1 });

    expect(orderId(result)?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1', 'a1'] });
  });

  test('a wire the slot already has gives back the same model', () => {
    const result = wire(model, { targetField: ORDER_ID, source: 'c1', target: 'f1', slot: 1 });

    expect(result).toEqual({ kind: 'applied', model });
    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('the cycle walk ends in a cycle of a hand-written model and skips an empty slot and a reference to no box', () => {
    const handWritten: MappingModel = {
      ...model,
      targetFieldMappings: {
        [ORDER_ID]: {
          id: 't1',
          result: 'x',
          boxes: {
            x: { kind: 'function', fn: 'concat', inputs: ['y', null] },
            y: { kind: 'function', fn: 'concat', inputs: ['x', 'zz'] },
            e1: { kind: 'function', fn: 'concat' },
          },
        },
      },
    };

    expect(orderId(wire(handWritten, { targetField: ORDER_ID, source: 'x', target: 'e1', slot: 0 }))?.boxes.e1).toEqual({
      kind: 'function',
      fn: 'concat',
      inputs: ['x'],
    });
  });

  describe('refuses', () => {
    test.each([
      ['a wire from a box to itself', { source: 'f1', target: 'f1', slot: 0 }, 'box-cycle', 'f1'],
      ['a wire from a box that reads the target', { source: 'u1', target: 'f1', slot: 0 }, 'box-cycle', 'f1'],
      ['a wire from a box that reads the target through another', { source: 'u1', target: 'c1', slot: 0 }, 'box-cycle', 'c1'],
      ["a wire from another target field mapping's box", { source: 'n1', target: 'e1', slot: 0 }, 'cross-target-field-reference', 'n1'],
      ["a wire into another target field mapping's box", { source: 'a1', target: 'n1', slot: 0 }, 'cross-target-field-reference', 'n1'],
      ['a wire from no box', { source: 'zz', target: 'e1', slot: 0 }, 'unknown-box-reference', 'zz'],
      ['a wire into no box', { source: 'a1', target: 'zz', slot: 0 }, 'unknown-box-reference', 'zz'],
      ['a wire into a source-field box, which has no input slot', { source: 'c1', target: 'a1', slot: 0 }, 'wrong-arity', 'a1'],
    ])('%s', (_, ends, code, id) => {
      expect(wire(model, { targetField: ORDER_ID, ...ends })).toEqual({ kind: 'refused', code, id });
    });

    test('a wire in a target field without a mapping', () => {
      expect(wire(model, { targetField: QUANTITY, source: 'a1', target: 'f1', slot: 0 })).toEqual({
        kind: 'refused',
        code: 'cross-target-field-reference',
        id: 'f1',
      });
    });

    test('a wire in a model without target field mappings', () => {
      const unmapped: MappingModel = { formatVersion: 1, source: structure, target: structure };

      expect(wire(unmapped, { targetField: ORDER_ID, source: 'a1', target: 'f1', slot: 0 })).toEqual({
        kind: 'refused',
        code: 'unknown-box-reference',
        id: 'f1',
      });
    });
  });
});

describe('the unwire command', () => {
  test('sets the input slot to null and keeps the other slots', () => {
    const result = unwire(model, { targetField: ORDER_ID, target: 'f1', slot: 0 });

    expect(orderId(result)?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'c1'] });
  });

  test.each([
    ['a slot that is already null', 'f1', 2],
    ['a box without inputs', 'e1', 0],
    ['a source-field box', 'a1', 0],
  ])('unwiring %s gives back the same model', (_, target, slot) => {
    const result = unwire(model, { targetField: ORDER_ID, target, slot });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test("refuses another target field mapping's box", () => {
    expect(unwire(model, { targetField: ORDER_ID, target: 'n1', slot: 0 })).toEqual({
      kind: 'refused',
      code: 'cross-target-field-reference',
      id: 'n1',
    });
  });
});

describe('the set result command', () => {
  test('connects a box to the sink', () => {
    expect(orderId(setResult(model, { targetField: ORDER_ID, box: 'u1' }))?.result).toBe('u1');
  });

  test('disconnects the sink with null', () => {
    expect(orderId(setResult(model, { targetField: ORDER_ID, box: null }))?.result).toBeNull();
  });

  test('null for a target field without a mapping gives back the same model', () => {
    const result = setResult(model, { targetField: QUANTITY, box: null });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test.each([
    ["another target field mapping's box", 'n1', 'cross-target-field-reference'],
    ['no box', 'zz', 'unknown-box-reference'],
  ])('refuses %s', (_, box, code) => {
    expect(setResult(model, { targetField: ORDER_ID, box })).toEqual({ kind: 'refused', code, id: box });
  });
});
