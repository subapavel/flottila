import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { deleteTargetFieldMapping } from './delete-target-field-mapping';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const GONE = `${ORDER}/Gone`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** Gone: an orphaned target field mapping (the structure lost the field) with a wired graph. Note: n1. */
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [GONE]: {
      id: 't1',
      result: 'f1',
      boxes: { s1: { kind: 'source', field: NOTE }, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['s1'] } },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

describe('the delete target field mapping command', () => {
  test('removes an orphaned target field mapping with all its boxes and keeps the others', () => {
    const result = deleteTargetFieldMapping(model, { targetField: GONE });

    expect(result.kind === 'applied' && result.model.targetFieldMappings).toEqual({ [NOTE]: model.targetFieldMappings![NOTE] });
    expect(Object.keys(model.targetFieldMappings!)).toEqual([GONE, NOTE]);
  });

  test('removes targetFieldMappings with the last one, as the canonical writer leaves an empty member out', () => {
    const once = deleteTargetFieldMapping(model, { targetField: GONE });
    const twice = once.kind === 'applied' ? deleteTargetFieldMapping(once.model, { targetField: NOTE }) : once;

    expect(twice.kind === 'applied' && twice.model).toEqual({ formatVersion: 1, source: structure, target: structure });
  });

  test('a target field without a mapping gives back the same model', () => {
    const result = deleteTargetFieldMapping(model, { targetField: `${ORDER}/OrderId` });

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('a model without targetFieldMappings gives back the same model', () => {
    const empty: MappingModel = { formatVersion: 1, source: structure, target: structure };

    const result = deleteTargetFieldMapping(empty, { targetField: GONE });

    expect(result.kind === 'applied' && result.model).toBe(empty);
  });
});
