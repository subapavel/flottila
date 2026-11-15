import { describe, expect, test } from 'vitest';
import type { TargetFieldMapping } from '../generated/mapping-model';
import { reachedBoxes } from './reached';

const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';

/** OrderId and a constant into concat, which is the result; toUpperCase reads OrderId but feeds nothing. */
const mapping: TargetFieldMapping = {
  id: 't1',
  result: 'f1',
  boxes: {
    u1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] },
    f1: { kind: 'function', fn: 'concat', inputs: ['a1', 'c1'] },
    c1: { kind: 'function', fn: 'constant', params: { value: '-' } },
    a1: { kind: 'source', field: ORDER_ID },
  },
};

describe('the boxes result reaches (ADR-0013)', () => {
  test('reaches the boxes that feed result, each once, and not an unreachable box', () => {
    expect([...reachedBoxes(mapping)].sort()).toEqual(['a1', 'c1', 'f1']);
  });

  test('the walk ends in a cycle of a hand-written model and skips an empty slot and a reference to no box', () => {
    const cyclic: TargetFieldMapping = {
      id: 't1',
      result: 'x',
      boxes: { x: { kind: 'function', fn: 'concat', inputs: ['y', null] }, y: { kind: 'function', fn: 'concat', inputs: ['x', 'toString'] } },
    };

    expect([...reachedBoxes(cyclic)].sort()).toEqual(['x', 'y']);
  });

  test('a null result reaches no box', () => {
    expect(reachedBoxes({ ...mapping, result: null })).toEqual([]);
    expect(reachedBoxes(undefined)).toEqual([]);
  });

  test('are met depth first from result, each box before its inputs and the inputs in slot order', () => {
    const deep: TargetFieldMapping = {
      id: 't1',
      result: 'f1',
      boxes: {
        f1: { kind: 'function', fn: 'concat', inputs: ['a1', null, 'f2', 'a3'] },
        f2: { kind: 'function', fn: 'concat', inputs: ['a1', 'f3', 'a5'] },
        f3: { kind: 'function', fn: 'concat' },
        a1: { kind: 'source', field: ORDER_ID },
        a3: { kind: 'source', field: ORDER_ID },
        a5: { kind: 'source', field: ORDER_ID },
      },
    };

    expect(reachedBoxes(deep)).toEqual(['f1', 'a1', 'f2', 'f3', 'a5', 'a3']);
  });
});
