import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { sameModel } from './same-model';

const structure = {
  bundle: ['order.xsd'] as [string],
  fingerprint: 'sha256:00',
  root: { element: 'Q{urn:a}Order' },
};
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    '/Q{urn:a}Order/Id': { id: 't1', result: 'a1', boxes: { a1: { kind: 'source', field: '/Q{urn:a}Order/Id' } } },
  },
};

describe('two mapping models are the same', () => {
  test('when they are equal values in other objects, whatever the key order', () => {
    const reordered: MappingModel = {
      targetFieldMappings: {
        '/Q{urn:a}Order/Id': { boxes: { a1: { field: '/Q{urn:a}Order/Id', kind: 'source' } }, result: 'a1', id: 't1' },
      },
      target: { root: { element: 'Q{urn:a}Order' }, fingerprint: 'sha256:00', bundle: ['order.xsd'] },
      source: structure,
      formatVersion: 1,
    };

    expect(sameModel(model, reordered)).toBe(true);
  });

  test('when one leaves out a member the other holds as undefined', () => {
    expect(sameModel({ ...model, udfs: undefined }, model)).toBe(true);
    expect(sameModel(model, { ...model, udfs: undefined })).toBe(true);
  });
});

describe('two mapping models differ', () => {
  test('when a nested string differs', () => {
    expect(sameModel(model, { ...model, target: { ...structure, fingerprint: 'sha256:01' } })).toBe(false);
  });

  test('when a result is null on one side only', () => {
    const unfinished = structuredClone(model);
    unfinished.targetFieldMappings!['/Q{urn:a}Order/Id']!.result = null;

    expect(sameModel(model, unfinished)).toBe(false);
    expect(sameModel(unfinished, model)).toBe(false);
  });

  test('when one holds a member the other lacks', () => {
    const unmapped = { ...model };
    delete unmapped.targetFieldMappings;

    expect(sameModel(model, unmapped)).toBe(false);
    expect(sameModel(unmapped, model)).toBe(false);
  });

  test('when an array has another length or another item', () => {
    const two: MappingModel = { ...model, source: { ...structure, bundle: ['order.xsd', 'types.xsd'] } };
    const other: MappingModel = { ...model, source: { ...structure, bundle: ['types.xsd'] } };

    expect(sameModel(model, two)).toBe(false);
    expect(sameModel(model, other)).toBe(false);
  });

  test('when an array stands where an object stood', () => {
    const odd = { ...model, udfs: [] } as unknown as MappingModel; // a shape the schema forbids, as a witness

    expect(sameModel({ ...model, udfs: {} }, odd)).toBe(false);
  });
});
