import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { setPositions } from './set-positions';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

/** OrderId: a1 into toUpperCase f1, the result, with no layout. Note: n1, at a kept position. */
const model: MappingModel = {
  formatVersion: 1,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: { a1: { kind: 'source', field: ORDER_ID }, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] } },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } }, layout: { target: { x: 300, y: 0 }, boxes: { n1: { x: 0, y: 0 } } } },
  },
};

const layoutOf = (result: ReturnType<typeof setPositions>, field: string) =>
  result.kind === 'applied' ? result.model.targetFieldMappings?.[field]?.layout : undefined;

describe('the set positions command (ADR-0029)', () => {
  test('writes the positions of boxes and target into a mapping without layout, rounded to integers', () => {
    const result = setPositions(model, { targetField: ORDER_ID, layout: { target: { x: 400.4, y: 79.5 }, boxes: { a1: { x: -0.6, y: 10.2 }, f1: { x: 200, y: 10 } } } });

    expect(layoutOf(result, ORDER_ID)).toEqual({ target: { x: 400, y: 80 }, boxes: { a1: { x: -1, y: 10 }, f1: { x: 200, y: 10 } } });
    expect(model.targetFieldMappings?.[ORDER_ID]?.layout).toBeUndefined();
  });

  test('keeps the positions it is not given and replaces those it is given', () => {
    const result = setPositions(model, { targetField: NOTE, layout: { boxes: { n1: { x: 12, y: 34 } } } });

    expect(layoutOf(result, NOTE)).toEqual({ target: { x: 300, y: 0 }, boxes: { n1: { x: 12, y: 34 } } });
  });

  test("the target's position alone leaves the boxes' positions as they were", () => {
    const result = setPositions(model, { targetField: NOTE, layout: { target: { x: 320, y: 8 } } });

    expect(layoutOf(result, NOTE)).toEqual({ target: { x: 320, y: 8 }, boxes: { n1: { x: 0, y: 0 } } });
  });

  test('positions equal to the kept ones after rounding give back the same model', () => {
    const result = setPositions(model, { targetField: NOTE, layout: { target: { x: 300.2, y: -0.3 }, boxes: { n1: { x: 0, y: 0.4 } } } });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('refuses a position for a box the mapping does not have, and writes none', () => {
    expect(setPositions(model, { targetField: ORDER_ID, layout: { boxes: { a1: { x: 1, y: 1 }, n1: { x: 0, y: 0 } } } })).toEqual({
      kind: 'refused',
      code: 'cross-target-field-reference',
      id: 'n1',
    });
    expect(setPositions(model, { targetField: ORDER_ID, layout: { boxes: { zz: { x: 0, y: 0 } } } })).toEqual({ kind: 'refused', code: 'unknown-box-reference', id: 'zz' });
  });

  test('a target field without a mapping has no layout to hold a position: the model stays as it was', () => {
    const result = setPositions(model, { targetField: `${ORDER}/Quantity`, layout: { target: { x: 1, y: 2 } } });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });
});
