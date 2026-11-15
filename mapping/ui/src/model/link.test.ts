import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { link } from './link';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;
const QUANTITY = `${ORDER}/Quantity`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** No target field mapping at all: the member is absent, as the canonical writer leaves it out. */
const unmapped: MappingModel = { formatVersion: 1, source: structure, target: structure };

/** Note is mapped from Note by box n1; Quantity has an unfinished target field mapping (F-2). */
const partlyMapped: MappingModel = {
  ...unmapped,
  targetFieldMappings: {
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
    [QUANTITY]: { id: 'tq', result: null, boxes: {} },
  },
};

describe('the link command', () => {
  test('a target field without a mapping gets a target field mapping whose result is the new source-field box', () => {
    const result = link(unmapped, { sourceField: ORDER_ID, targetField: ORDER_ID, boxId: 'a1', mappingId: 't1' });

    expect(result).toEqual({
      kind: 'applied',
      model: {
        ...unmapped,
        targetFieldMappings: {
          [ORDER_ID]: { id: 't1', result: 'a1', boxes: { a1: { kind: 'source', field: ORDER_ID } } },
        },
      },
    });
  });

  test('a new target field mapping leaves the other target field mappings and the given model untouched', () => {
    const before = structuredClone(partlyMapped);

    const result = link(partlyMapped, { sourceField: QUANTITY, targetField: ORDER_ID, boxId: 'a1', mappingId: 't1' });

    expect(result.kind).toBe('applied');
    if (result.kind !== 'applied') return;
    expect(result.model.targetFieldMappings?.[NOTE]).toEqual(before.targetFieldMappings?.[NOTE]);
    expect(result.model.targetFieldMappings?.[QUANTITY]).toEqual(before.targetFieldMappings?.[QUANTITY]);
    expect(result.model.targetFieldMappings?.[ORDER_ID]).toEqual({
      id: 't1',
      result: 'a1',
      boxes: { a1: { kind: 'source', field: QUANTITY } },
    });
    expect(partlyMapped).toEqual(before);
  });

  test('an unfinished target field mapping keeps its id, and the new box becomes its result', () => {
    const result = link(partlyMapped, { sourceField: ORDER_ID, targetField: QUANTITY, boxId: 'q1', mappingId: 'unused' });

    expect(result.kind).toBe('applied');
    if (result.kind !== 'applied') return;
    expect(result.model.targetFieldMappings?.[QUANTITY]).toEqual({
      id: 'tq',
      result: 'q1',
      boxes: { q1: { kind: 'source', field: ORDER_ID } },
    });
  });

  test('a target field mapping with a result gets the new box beside it, and its result stays (an unreachable box)', () => {
    const result = link(partlyMapped, { sourceField: ORDER_ID, targetField: NOTE, boxId: 'n2', mappingId: 'unused' });

    expect(result.kind).toBe('applied');
    if (result.kind !== 'applied') return;
    expect(result.model.targetFieldMappings?.[NOTE]).toEqual({
      id: 'tn',
      result: 'n1',
      boxes: { n1: { kind: 'source', field: NOTE }, n2: { kind: 'source', field: ORDER_ID } },
    });
  });

  test('the mapping id is not checked when the target field already has a mapping', () => {
    // 'n1' is taken, but no new target field mapping is created, so the mapping id is never used
    const result = link(partlyMapped, { sourceField: ORDER_ID, targetField: QUANTITY, boxId: 'q1', mappingId: 'n1' });

    expect(result.kind).toBe('applied');
  });

  describe('refuses a duplicate id', () => {
    test.each([
      ['the box id is a target field mapping id', { boxId: 'tq', mappingId: 't1', targetField: ORDER_ID }, 'tq'],
      ['the box id is a box of the same target field mapping', { boxId: 'n1', mappingId: 'x', targetField: NOTE }, 'n1'],
      ['the new mapping id is a box id', { boxId: 'a1', mappingId: 'n1', targetField: ORDER_ID }, 'n1'],
      ['the new mapping id is a target field mapping id', { boxId: 'a1', mappingId: 'tn', targetField: ORDER_ID }, 'tn'],
      ['the new box and the new mapping share one id', { boxId: 'a1', mappingId: 'a1', targetField: ORDER_ID }, 'a1'],
    ])('when %s', (_, ids, id) => {
      const result = link(partlyMapped, { sourceField: ORDER_ID, ...ids });

      expect(result).toEqual({ kind: 'refused', code: 'duplicate-authoring-id', id });
    });
  });

  describe('refuses a refused field (ADR-0027) with its refusal, and leaves the model as it was', () => {
    const ambiguous = { code: 'ambiguous-field-path', side: 'both' as const, file: 'order.xsd', line: 14, column: 56 };
    const abstract = { code: 'abstract-target-element', side: 'target' as const, file: 'types.xsd', line: 3, column: 40 };
    const ids = { boxId: 'a1', mappingId: 't1' };

    test('a refused source field', () => {
      const before = structuredClone(partlyMapped);

      const result = link(partlyMapped, { sourceField: NOTE, targetField: ORDER_ID, ...ids, sourceRefusal: ambiguous });

      expect(result).toEqual({ kind: 'refused', code: 'refused-field', side: 'source', field: NOTE, refusal: ambiguous });
      expect(partlyMapped).toEqual(before);
    });

    test('a refused target field', () => {
      const result = link(partlyMapped, { sourceField: NOTE, targetField: ORDER_ID, ...ids, targetRefusal: abstract });

      expect(result).toEqual({ kind: 'refused', code: 'refused-field', side: 'target', field: ORDER_ID, refusal: abstract });
    });

    test('both refused: the source field is named', () => {
      const result = link(partlyMapped, {
        sourceField: NOTE,
        targetField: ORDER_ID,
        ...ids,
        sourceRefusal: ambiguous,
        targetRefusal: abstract,
      });

      expect(result).toMatchObject({ kind: 'refused', side: 'source', field: NOTE });
    });
  });

  test('refuses a cross-target-field reference: a box id that names a box of another target field mapping', () => {
    const result = link(partlyMapped, { sourceField: ORDER_ID, targetField: QUANTITY, boxId: 'n1', mappingId: 'x' });

    expect(result).toEqual({ kind: 'refused', code: 'cross-target-field-reference', id: 'n1' });
  });
});
