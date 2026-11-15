import { describe, expect, test } from 'vitest';
import type { MappingModel, StructureRef } from '../generated/mapping-model';
import { setStructure } from './set-structure';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;

const order: StructureRef = {
  bundle: ['../structures/skeleton/order.xsd'],
  fingerprint: 'sha256:order',
  root: { element: ORDER },
};
const invoice: StructureRef = {
  bundle: ['../structures/invoice/invoice.xsd', '../structures/invoice/common.xsd'],
  fingerprint: 'sha256:invoice',
  root: { element: 'Q{urn:example:invoice}Invoice' },
};

const sideless: MappingModel = { formatVersion: 1, description: 'new' };
const mapped: MappingModel = {
  formatVersion: 1,
  source: order,
  target: order,
  targetFieldMappings: { [ORDER_ID]: { id: 't1', result: 's1', boxes: { s1: { kind: 'source', field: ORDER_ID } } } },
};

describe('the set structure command (GLOSSARY: Signature)', () => {
  test('sets the source of a mapping that has neither side, and leaves the target absent', () => {
    const result = setStructure(sideless, { side: 'source', structure: order });

    expect(result).toEqual({ kind: 'applied', model: { formatVersion: 1, description: 'new', source: order } });
    expect(sideless).toEqual({ formatVersion: 1, description: 'new' });
  });

  test('replaces one side and keeps the other side and the target field mappings', () => {
    const result = setStructure(mapped, { side: 'target', structure: invoice });

    expect(result.kind === 'applied' && result.model).toEqual({ ...mapped, target: invoice });
  });

  test('a structure equal to the side it replaces gives back the same model', () => {
    const result = setStructure(mapped, { side: 'source', structure: structuredClone(order) });

    expect(result.kind === 'applied' && result.model).toBe(mapped);
  });

  test('another fingerprint, bundle or root is a change', () => {
    for (const structure of [
      { ...order, fingerprint: 'sha256:other' },
      { ...order, bundle: ['../structures/skeleton/order2.xsd'] as [string] },
      { ...order, root: { json: '$' as const } },
    ]) {
      const result = setStructure(mapped, { side: 'source', structure });

      expect(result.kind === 'applied' && result.model.source).toEqual(structure);
    }
  });
});
