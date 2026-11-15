import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { insertBox } from './insert-box';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;
const QUANTITY = `${ORDER}/Quantity`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

const unmapped: MappingModel = { formatVersion: 1, source: structure, target: structure };

/** Note is mapped from Note by box n1; Quantity has an unfinished target field mapping (F-2). */
const partlyMapped: MappingModel = {
  ...unmapped,
  targetFieldMappings: {
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
    [QUANTITY]: { id: 'tq', result: null, boxes: {} },
  },
};

const constant = { kind: 'function', fn: 'constant' } as const;

describe('the insert box command', () => {
  test('the first box into a field without a mapping creates {id, result, boxes} with the box as result', () => {
    const result = insertBox(unmapped, { targetField: ORDER_ID, box: constant, boxId: 'c1', mappingId: 't1' });

    expect(result).toEqual({
      kind: 'applied',
      model: { ...unmapped, targetFieldMappings: { [ORDER_ID]: { id: 't1', result: 'c1', boxes: { c1: constant } } } },
    });
    expect(unmapped.targetFieldMappings).toBeUndefined();
  });

  test('a box into a mapping that has a result is added beside it, and the result stays', () => {
    const result = insertBox(partlyMapped, { targetField: NOTE, box: constant, boxId: 'c1', mappingId: 'unused' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[NOTE]).toEqual({
      id: 'tn',
      result: 'n1',
      boxes: { n1: { kind: 'source', field: NOTE }, c1: constant },
    });
  });

  test('a box into a mapping whose result is null becomes its result, and the mapping keeps its id', () => {
    const result = insertBox(partlyMapped, { targetField: QUANTITY, box: constant, boxId: 'c1', mappingId: 'unused' });

    expect(result.kind === 'applied' && result.model.targetFieldMappings?.[QUANTITY]).toEqual({ id: 'tq', result: 'c1', boxes: { c1: constant } });
  });

  describe('refuses a refused target field (ADR-0027), before it looks at ids, and leaves the model as it was', () => {
    const ambiguous = { code: 'ambiguous-field-path', side: 'both' as const, file: 'order.xsd', line: 14, column: 56 };

    test.each([
      ['a field without a mapping', unmapped, ORDER_ID],
      ['a field whose mapping has a result', partlyMapped, NOTE],
    ])('%s', (_, model, targetField) => {
      const before = structuredClone(model);

      const result = insertBox(model, { targetField, box: constant, boxId: 'n1', mappingId: 'n1', targetRefusal: ambiguous });

      expect(result).toEqual({ kind: 'refused', code: 'refused-field', side: 'target', field: targetField, refusal: ambiguous });
      expect(model).toEqual(before);
    });
  });

  describe('keeps ids fresh and unique: it refuses', () => {
    test.each([
      ['a box id that is a target field mapping id', { boxId: 'tq', mappingId: 't1', targetField: ORDER_ID }, 'duplicate-authoring-id', 'tq'],
      ['a box id of the same target field mapping', { boxId: 'n1', mappingId: 'x', targetField: NOTE }, 'duplicate-authoring-id', 'n1'],
      ['a mapping id that is a box id', { boxId: 'c1', mappingId: 'n1', targetField: ORDER_ID }, 'duplicate-authoring-id', 'n1'],
      ['a mapping id that is a target field mapping id', { boxId: 'c1', mappingId: 'tn', targetField: ORDER_ID }, 'duplicate-authoring-id', 'tn'],
      ['one id for the new box and the new mapping', { boxId: 'c1', mappingId: 'c1', targetField: ORDER_ID }, 'duplicate-authoring-id', 'c1'],
      ['a box id of another target field mapping', { boxId: 'n1', mappingId: 'x', targetField: QUANTITY }, 'cross-target-field-reference', 'n1'],
    ])('%s', (_, ids, code, id) => {
      expect(insertBox(partlyMapped, { box: constant, ...ids })).toEqual({ kind: 'refused', code, id });
    });
  });
});
