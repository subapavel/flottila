import { describe, expect, test } from 'vitest';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { properAncestors, setContext } from './set-context';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ITEM = `${ORDER}/Q{urn:flottila:corpus:skeleton}Item`;
const SKU = `${ITEM}/Q{urn:flottila:corpus:skeleton}Sku`;
const ORDER_ID = `${ORDER}/Q{urn:flottila:corpus:skeleton}OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** OrderId: s1 reads Sku in its parent's context, s2 reads Sku in the Order's; f1 is a function box. Note: n1. */
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        s1: { kind: 'source', field: SKU },
        s2: { kind: 'source', field: SKU, context: ORDER },
        f1: { kind: 'function', fn: 'concat', inputs: ['s1', 's2'] },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

const orderId = (result: { kind: string; model?: MappingModel }): TargetFieldMapping | undefined =>
  result.kind === 'applied' ? result.model?.targetFieldMappings?.[ORDER_ID] : undefined;

describe("a field's proper ancestors", () => {
  test('are its parent first, then up to the root; a step may hold a / inside its braces (ADR-0007)', () => {
    expect(properAncestors(SKU)).toEqual([ITEM, ORDER]);
    expect(properAncestors('/Q{http://a/b}Order/Q{http://a/b}Item/@code')).toEqual(['/Q{http://a/b}Order/Q{http://a/b}Item', '/Q{http://a/b}Order']);
  });

  test('are none for a root, and none for an empty path', () => {
    expect(properAncestors(ORDER)).toEqual([]);
    expect(properAncestors('')).toEqual([]);
  });
});

describe('the set context command', () => {
  test("sets a source-field box's context to an ancestor above its parent", () => {
    const result = setContext(model, { targetField: ORDER_ID, box: 's1', context: ORDER });

    expect(orderId(result)?.boxes.s1).toEqual({ kind: 'source', field: SKU, context: ORDER });
    expect(model.targetFieldMappings?.[ORDER_ID]?.boxes.s1).toEqual({ kind: 'source', field: SKU });
  });

  test('a context equal to the parent is left out', () => {
    const result = setContext(model, { targetField: ORDER_ID, box: 's2', context: ITEM });

    expect(orderId(result)?.boxes.s2).toEqual({ kind: 'source', field: SKU });
  });

  test.each([
    ['the parent on a box that leaves the context out', 's1', ITEM],
    ['the context a box already has', 's2', ORDER],
  ])('%s gives back the same model', (_, box, context) => {
    const result = setContext(model, { targetField: ORDER_ID, box, context });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  describe('refuses', () => {
    test.each([
      ['the field itself, which is no proper ancestor', 's1', SKU, 'context-not-ancestor', 's1'],
      ['a field below the box’s field', 's1', `${SKU}/Deeper`, 'context-not-ancestor', 's1'],
      ['a field on another branch', 's1', ORDER_ID, 'context-not-ancestor', 's1'],
      ['a context on a function box, which reads no field', 'f1', ORDER, 'context-not-ancestor', 'f1'],
      ["another target field mapping's box", 'n1', ORDER, 'cross-target-field-reference', 'n1'],
      ['no box', 'zz', ORDER, 'unknown-box-reference', 'zz'],
    ])('%s', (_, box, context, code, id) => {
      expect(setContext(model, { targetField: ORDER_ID, box, context })).toEqual({ kind: 'refused', code, id });
    });
  });
});
