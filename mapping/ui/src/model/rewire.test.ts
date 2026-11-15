import { describe, expect, test } from 'vitest';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { disconnectBox, rewire } from './rewire';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

/** OrderId: a1 and c1 into concat f1, the result; u1 reads f1 and feeds nothing; e1 has no inputs yet. Note: n1. */
const model: MappingModel = {
  formatVersion: 1,
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

describe('the rewire command: an end of a wire dragged to another input (as the prototype does)', () => {
  test('moves the wire into another input slot: the old slot is left unconnected', () => {
    const mapping = orderId(rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'f1', slot: 1 }, to: { box: 'e1', slot: 0 } }));

    expect(mapping?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1', null] });
    expect(mapping?.boxes.e1).toEqual({ kind: 'function', fn: 'concat', inputs: ['c1'] });
  });

  test('moved onto an occupied input, the wire replaces the one that input had', () => {
    const mapping = orderId(rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'f1', slot: 1 }, to: { box: 'f1', slot: 0 } }));

    expect(mapping?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: ['c1', null] });
  });

  test('moves a wire onto the target, which then reads its box, and the result wire onto an input', () => {
    const toTarget = orderId(rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'f1', slot: 1 }, to: 'target' }));
    expect(toTarget?.result).toBe('c1');
    expect(toTarget?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1', null] });

    const fromTarget = orderId(rewire(model, { targetField: ORDER_ID, source: 'f1', from: 'target', to: { box: 'e1', slot: 1 } }));
    expect(fromTarget?.result).toBeNull();
    expect(fromTarget?.boxes.e1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'f1'] });
  });

  test('the source end moved to another box keeps the input and reads the new box', () => {
    const mapping = orderId(rewire(model, { targetField: ORDER_ID, source: 'a1', from: { box: 'f1', slot: 1 }, to: { box: 'f1', slot: 1 } }));

    expect(mapping?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1', 'a1'] });
  });

  test('a wire dropped where it started gives back the same model', () => {
    const result = rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'f1', slot: 1 }, to: { box: 'f1', slot: 1 } });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('refuses a move that would close a cycle, and the old wire stays', () => {
    expect(rewire(model, { targetField: ORDER_ID, source: 'u1', from: { box: 'e1', slot: 0 }, to: { box: 'f1', slot: 0 } })).toEqual({
      kind: 'refused',
      code: 'box-cycle',
      id: 'f1',
    });
  });

  test("refuses a move onto another target field mapping's box", () => {
    expect(rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'f1', slot: 1 }, to: { box: 'n1', slot: 0 } })).toMatchObject({
      kind: 'refused',
      code: 'cross-target-field-reference',
    });
    expect(rewire(model, { targetField: ORDER_ID, source: 'c1', from: { box: 'n1', slot: 0 }, to: { box: 'e1', slot: 0 } })).toMatchObject({
      kind: 'refused',
      code: 'cross-target-field-reference',
    });
  });
});

describe('the disconnect box command', () => {
  test('leaves every input that read the box, the result, and every input of the box unconnected', () => {
    const mapping = orderId(disconnectBox(model, { targetField: ORDER_ID, box: 'f1' }));

    expect(mapping?.result).toBeNull();
    expect(mapping?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, null] });
    expect(mapping?.boxes.u1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: [null] });
    expect(mapping?.boxes.a1).toEqual(model.targetFieldMappings?.[ORDER_ID]?.boxes.a1);
  });

  test('keeps a result that names another box', () => {
    const mapping = orderId(disconnectBox(model, { targetField: ORDER_ID, box: 'a1' }));

    expect(mapping?.result).toBe('f1');
    expect(mapping?.boxes.f1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'c1'] });
  });

  test('a box without wires gives back the same model', () => {
    const result = disconnectBox(model, { targetField: ORDER_ID, box: 'e1' });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test("refuses another target field mapping's box", () => {
    expect(disconnectBox(model, { targetField: ORDER_ID, box: 'n1' })).toEqual({ kind: 'refused', code: 'cross-target-field-reference', id: 'n1' });
  });
});
