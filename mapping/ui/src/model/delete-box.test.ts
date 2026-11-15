import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { deleteBox, deleteBoxes } from './delete-box';
import { insertBox } from './insert-box';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

const unmapped: MappingModel = { formatVersion: 1, source: structure, target: structure };

/** OrderId: a1 feeds both slots of f1 (the result) and u1; Note: n1. */
const model: MappingModel = {
  ...unmapped,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        a1: { kind: 'source', field: ORDER_ID },
        f1: { kind: 'function', fn: 'concat', inputs: ['a1', 'a1'] },
        u1: { kind: 'unresolved', name: 'ns:fn', origin: 'po', inputs: [null, 'a1'] },
        e1: { kind: 'function', fn: 'concat' },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

describe('the delete box command', () => {
  test('removes the box and sets every input that read it to null', () => {
    const result = deleteBox(model, { box: 'a1' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings).toEqual({
      [ORDER_ID]: {
        id: 't1',
        result: 'f1',
        boxes: {
          f1: { kind: 'function', fn: 'concat', inputs: [null, null] },
          u1: { kind: 'unresolved', name: 'ns:fn', origin: 'po', inputs: [null, null] },
          e1: { kind: 'function', fn: 'concat' },
        },
      },
      [NOTE]: model.targetFieldMappings![NOTE],
    });
  });

  test('deleting the result box sets result to null and keeps the mapping while it has boxes', () => {
    const result = deleteBox(model, { box: 'f1' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[ORDER_ID]).toEqual({
      id: 't1',
      result: null,
      boxes: {
        a1: { kind: 'source', field: ORDER_ID },
        u1: { kind: 'unresolved', name: 'ns:fn', origin: 'po', inputs: [null, 'a1'] },
        e1: { kind: 'function', fn: 'concat' },
      },
    });
  });

  test('deleting the last box removes the target field mapping, and the other mappings stay', () => {
    const result = deleteBox(model, { box: 'n1' });

    expect(result.kind === 'applied' && Object.keys(result.model.targetFieldMappings ?? {})).toEqual([ORDER_ID]);
  });

  test('deleting the only mapping\'s last box gives back the model without target field mappings', () => {
    const inserted = insertBox(unmapped, { targetField: NOTE, box: { kind: 'function', fn: 'constant' }, boxId: 'c1', mappingId: 't1' });
    if (inserted.kind !== 'applied') throw new Error('the insert was refused');

    expect(deleteBox(inserted.model, { box: 'c1' })).toEqual({ kind: 'applied', model: unmapped });
  });

  test("drops the box's position from the layout, and the layout boxes once none is left (ADR-0029)", () => {
    const placed: MappingModel = {
      ...unmapped,
      targetFieldMappings: {
        [NOTE]: {
          id: 'tn',
          result: 'n1',
          boxes: { n1: { kind: 'source', field: NOTE }, c1: { kind: 'function', fn: 'constant' } },
          layout: { target: { x: 300, y: 0 }, boxes: { n1: { x: 0, y: 0 }, c1: { x: 0, y: 60 } } },
        },
      },
    };

    const once = deleteBox(placed, { box: 'c1' });
    expect(once.kind === 'applied' && once.model.targetFieldMappings?.[NOTE]?.layout).toEqual({ target: { x: 300, y: 0 }, boxes: { n1: { x: 0, y: 0 } } });
    const twice = once.kind === 'applied' ? deleteBox({ ...once.model, targetFieldMappings: { [NOTE]: { ...once.model.targetFieldMappings![NOTE]!, boxes: { ...once.model.targetFieldMappings![NOTE]!.boxes, c2: { kind: 'function', fn: 'constant' } } } } }, { box: 'n1' }) : once;
    expect(twice.kind === 'applied' && twice.model.targetFieldMappings?.[NOTE]?.layout).toEqual({ target: { x: 300, y: 0 } });
  });

  test('refuses an id that names no box', () => {
    expect(deleteBox(model, { box: 't1' })).toEqual({ kind: 'refused', code: 'unknown-box-reference', id: 't1' });
    expect(deleteBox(unmapped, { box: 'a1' })).toEqual({ kind: 'refused', code: 'unknown-box-reference', id: 'a1' });
  });
});

describe('the delete boxes command', () => {
  test('removes every box at once, across target field mappings, and nulls every input and result that read one', () => {
    const result = deleteBoxes(model, { boxes: ['a1', 'f1', 'n1'] });

    expect(result).toEqual({
      kind: 'applied',
      model: {
        ...unmapped,
        targetFieldMappings: {
          [ORDER_ID]: {
            id: 't1',
            result: null,
            boxes: {
              u1: { kind: 'unresolved', name: 'ns:fn', origin: 'po', inputs: [null, null] },
              e1: { kind: 'function', fn: 'concat' },
            },
          },
        },
      },
    });
  });

  test('deleting every box of every mapping gives back the model without target field mappings', () => {
    expect(deleteBoxes(model, { boxes: ['e1', 'u1', 'f1', 'a1', 'n1'] })).toEqual({ kind: 'applied', model: unmapped });
  });

  test('an id that names no box refuses the whole command, even after ids that do', () => {
    expect(deleteBoxes(model, { boxes: ['a1', 'zz', 'f1'] })).toEqual({ kind: 'refused', code: 'unknown-box-reference', id: 'zz' });
  });

  test('an id named twice refuses the whole command: the second time it names no box', () => {
    expect(deleteBoxes(model, { boxes: ['a1', 'f1', 'a1'] })).toEqual({ kind: 'refused', code: 'unknown-box-reference', id: 'a1' });
  });

  test('no ids leave the very same model, so that the store records no undo step', () => {
    const result = deleteBoxes(model, { boxes: [] });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });
});
