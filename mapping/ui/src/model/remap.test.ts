import { describe, expect, test } from 'vitest';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { describeRefusal } from './command';
import { remap } from './remap';

const NS = 'Q{urn:flottila:corpus:skeleton}';
const ORDER = `/${NS}Order`;
const ITEM = `${ORDER}/${NS}Item`;
const LINE = `${ITEM}/${NS}Line`;
const SKU = `${ITEM}/${NS}Sku`;
const ORDER_ID = `${ORDER}/${NS}OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: `${NS}Order` },
};

/**
 * OrderId: s1 reads the orphaned Item/Code in its parent's context, s2 reads it in the Order's, s3 in the Item's
 * written out; f1 concatenates s1 and s2 and is the result. Note: n1.
 */
const CODE = `${ITEM}/${NS}Code`;
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        s1: { kind: 'source', field: CODE },
        s2: { kind: 'source', field: CODE, context: ORDER },
        s3: { kind: 'source', field: CODE, context: ITEM },
        f1: { kind: 'function', fn: 'concat', inputs: ['s1', 's2'] },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

const orderId = (result: { kind: string; model?: MappingModel }): TargetFieldMapping | undefined =>
  result.kind === 'applied' ? result.model?.targetFieldMappings?.[ORDER_ID] : undefined;

describe('the remap command', () => {
  test('points a source-field box at another field and keeps its id, the wires into other boxes and the result', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's1', field: SKU });

    expect(orderId(result)).toEqual({ ...model.targetFieldMappings![ORDER_ID], boxes: { ...model.targetFieldMappings![ORDER_ID]!.boxes, s1: { kind: 'source', field: SKU } } });
    expect(model.targetFieldMappings![ORDER_ID]!.boxes.s1).toEqual({ kind: 'source', field: CODE });
  });

  test('keeps a context that is still a proper ancestor of the new field', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's2', field: SKU });

    expect(orderId(result)?.boxes.s2).toEqual({ kind: 'source', field: SKU, context: ORDER });
  });

  test('leaves the context out when it is the new field’s parent (ADR-0010)', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's3', field: SKU });

    expect(orderId(result)?.boxes.s3).toEqual({ kind: 'source', field: SKU });
  });

  test('resets a context that is no proper ancestor of the new field to the parent', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's3', field: ORDER_ID });

    expect(orderId(result)?.boxes.s3).toEqual({ kind: 'source', field: ORDER_ID });
  });

  test('a context below the new field is reset too: it is no ancestor of it', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's3', field: ITEM });

    expect(orderId(result)?.boxes.s3).toEqual({ kind: 'source', field: ITEM });
  });

  test('a deeper field under the same context keeps it', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's3', field: `${LINE}/${NS}Sku` });

    expect(orderId(result)?.boxes.s3).toEqual({ kind: 'source', field: `${LINE}/${NS}Sku`, context: ITEM });
  });

  test('the field the box already reads gives back the same model', () => {
    const result = remap(model, { targetField: ORDER_ID, box: 's2', field: CODE });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  describe('refuses', () => {
    test.each([
      ['a function box, which reads no field', 'f1', 'not-source-field-box', 'f1'],
      ["another target field mapping's box", 'n1', 'cross-target-field-reference', 'n1'],
      ['no box', 'zz', 'unknown-box-reference', 'zz'],
    ])('%s', (_, box, code, id) => {
      expect(remap(model, { targetField: ORDER_ID, box, field: SKU })).toEqual({ kind: 'refused', code, id });
    });

    test('a refused source field first, as link does (ADR-0027)', () => {
      const refusal = { code: 'ambiguous-field-path', side: 'both' as const, file: 'order.xsd', line: 4, column: 9 };

      expect(remap(model, { targetField: ORDER_ID, box: 'f1', field: SKU, sourceRefusal: refusal })).toEqual({
        kind: 'refused',
        code: 'refused-field',
        side: 'source',
        field: SKU,
        refusal,
      });
    });

    test('a function box with a message that names the box', () => {
      expect(describeRefusal({ code: 'not-source-field-box', id: 'f1' })).toBe('Box f1 reads no source field, so it cannot be remapped.');
    });
  });
});
