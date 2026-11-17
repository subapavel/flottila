import { describe, expect, test } from 'vitest';
import type { TraceEntry } from '../../../api/client';
import { displayEntry, queueSize } from './display-queue';

describe('the display-queue view model', () => {
  test('a queue keeps its contexts in order, and tells SUPPRESS, "" and a value apart', () => {
    const entry: TraceEntry = { kind: 'queue', contexts: [['1a', ''], [null, '3c'], []], capped: false, slotCount: 4, contextCount: 3 };

    expect(displayEntry(entry)).toEqual({
      kind: 'queue',
      contexts: [
        [{ kind: 'value', text: '1a' }, { kind: 'empty' }],
        [{ kind: 'suppress' }, { kind: 'value', text: '3c' }],
        [],
      ],
      capped: undefined,
    });
  });

  test('a capped queue says how many of its values and contexts it shows, with its totals', () => {
    const entry: TraceEntry = { kind: 'queue', contexts: [['a', 'b'], ['c']], capped: true, slotCount: 20, contextCount: 7 };

    expect(displayEntry(entry)).toMatchObject({ capped: 'showing the first 3 of 20 values, in 2 of 7 contexts' });
  });

  test("a queue's size is its run's totals, after the prototype's .dq__meta; a capped queue counts what the run had", () => {
    expect(queueSize({ kind: 'queue', contexts: [['a', 'b'], ['c']], capped: true, slotCount: 20, contextCount: 7 })).toBe('7 contexts, 20 slots');
    expect(queueSize({ kind: 'queue', contexts: [['a']], capped: false, slotCount: 1, contextCount: 1 })).toBe('1 context, 1 slot');
    expect(queueSize({ kind: 'queue', contexts: [], capped: false, slotCount: 0, contextCount: 0 })).toBe('0 contexts, 0 slots');
  });

  test('an atomic value, occurrences and no entry have no queue size', () => {
    expect(queueSize({ kind: 'atomic', type: 'xs:string', value: 'PO' })).toBeUndefined();
    expect(queueSize({ kind: 'occurrences', min: 0, capped: false, parents: [] })).toBeUndefined();
    expect(queueSize(undefined)).toBeUndefined();
  });

  test('an atomic value shows its type and its value; "" is the empty value', () => {
    expect(displayEntry({ kind: 'atomic', type: 'xs:string', value: 'PO' })).toEqual({
      kind: 'atomic',
      type: 'xs:string',
      value: { kind: 'value', text: 'PO' },
    });
    expect(displayEntry({ kind: 'atomic', type: 'xs:string', value: '' })).toMatchObject({ value: { kind: 'empty' } });
  });

  test('occurrences show each parent slot with its slots, and mark the parent slot that created fewer than min', () => {
    const entry: TraceEntry = {
      kind: 'occurrences',
      min: 1,
      max: 2,
      capped: false,
      parents: [
        {
          parentSlot: 1,
          created: 2,
          slots: [
            { slot: 1, status: 'created' },
            { slot: 2, status: 'suppressed' },
            { slot: 3, status: 'created' },
            { slot: 4, status: 'truncated' },
          ],
        },
        { parentSlot: 2, created: 0, slots: [{ slot: 5, status: 'suppressed' }] },
        { parentSlot: 3, created: 1, slots: [{ slot: 6, status: 'created' }] },
      ],
    };

    expect(displayEntry(entry)).toEqual({
      kind: 'occurrences',
      min: 1,
      max: 2,
      rows: [
        {
          kind: 'parent',
          parentSlot: 1,
          created: 2,
          minOccurs: false,
          slots: [
            { slot: 1, status: 'created' },
            { slot: 2, status: 'suppressed' },
            { slot: 3, status: 'created' },
            { slot: 4, status: 'truncated' },
          ],
        },
        { kind: 'parent', parentSlot: 2, created: 0, minOccurs: true, slots: [{ slot: 5, status: 'suppressed' }] },
        { kind: 'parent', parentSlot: 3, created: 1, minOccurs: false, slots: [{ slot: 6, status: 'created' }] },
      ],
      capped: undefined,
    });
  });

  test('a field without max is unbounded, and a field with min 0 never marks a parent slot', () => {
    const entry: TraceEntry = { kind: 'occurrences', min: 0, capped: false, parents: [{ parentSlot: 1, created: 0, slots: [] }] };

    expect(displayEntry(entry)).toMatchObject({ max: undefined, rows: [{ kind: 'parent', minOccurs: false }] });
  });

  test('a capped occurrences entry keeps its last parent slot, shows the ones left out before it, and its totals', () => {
    const entry: TraceEntry = {
      kind: 'occurrences',
      min: 1,
      max: 1,
      capped: true,
      parents: [
        { parentSlot: 1, created: 1, slots: [{ slot: 1, status: 'created' }] },
        { parentSlot: 2, created: 1, slots: [{ slot: 2, status: 'created' }] },
        // the slot budget was spent: the last parent keeps its count without its slot records
        { parentSlot: 9, created: 1, slots: [] },
      ],
    };

    expect(displayEntry(entry)).toMatchObject({
      rows: [
        { kind: 'parent', parentSlot: 1 },
        { kind: 'parent', parentSlot: 2 },
        { kind: 'left-out', from: 3, to: 8 },
        { kind: 'parent', parentSlot: 9, created: 1, slots: [] },
      ],
      capped: 'showing 3 of 9 parent slots, the last one kept, with 2 slot records',
    });
  });
});
