import { describe, expect, test } from 'vitest';
import type { Diagnostic, StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectUnsaved } from './working-copy';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const NOTE = `/${ORDER}/Note`;

/** OrderId: source box a1 into toUpperCase f1, the result, without a layout. */
const model: MappingModel = {
  formatVersion: 1,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: { a1: { kind: 'source', field: ORDER_ID }, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] }, e1: { kind: 'function', fn: 'concat' } },
    },
  },
};

function store(initial: MappingModel = model) {
  const opened: StoredMapping = { model: initial, etag: '"sha256:base"' };
  const never = () => new Promise<never>(() => {});
  let n = 0;
  return createWorkingCopyStore(opened, { newId: () => `id${++n}`, save: never, load: never, compile: () => new Promise<Diagnostic[]>(() => {}) });
}

const orderId = (s: ReturnType<typeof store>) => s.getState().workingCopy.targetFieldMappings?.[ORDER_ID];

describe('the mapping expression edits of the store (#157)', () => {
  test('a function added at a position writes its box, its position and the positions shown, as one undo step', () => {
    const s = store();

    s.getState().insertBox(ORDER_ID, 'concat', { x: 10.4, y: 20.6 }, { target: { x: 400, y: 0 }, boxes: { a1: { x: 0, y: 0 }, f1: { x: 200, y: 0 }, e1: { x: 0, y: 80 } } });

    expect(orderId(s)?.boxes.id1).toEqual({ kind: 'function', fn: 'concat' });
    expect(orderId(s)?.layout).toEqual({ target: { x: 400, y: 0 }, boxes: { a1: { x: 0, y: 0 }, f1: { x: 200, y: 0 }, e1: { x: 0, y: 80 }, id1: { x: 10, y: 21 } } });
    expect(s.getState().past).toHaveLength(1);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(s.getState().baseline);
  });

  test('a function added at a position into a target field without a mapping creates it with that position', () => {
    const s = store();

    s.getState().insertBox(NOTE, 'constant', { x: 5, y: 6 }, { target: { x: 300, y: 0 } });

    expect(s.getState().workingCopy.targetFieldMappings?.[NOTE]).toEqual({
      id: 'id2',
      result: 'id1',
      boxes: { id1: { kind: 'function', fn: 'constant' } },
      layout: { target: { x: 300, y: 0 }, boxes: { id1: { x: 5, y: 6 } } },
    });
  });

  test('a refused insert records why and writes no position', () => {
    const s = store({ formatVersion: 1, targetFieldMappings: { [NOTE]: { id: 'id1', result: null, boxes: {} } } });

    s.getState().insertBox(ORDER_ID, 'concat', { x: 1, y: 1 }, {});

    expect(s.getState().refusal).toEqual({ code: 'duplicate-authoring-id', id: 'id1' });
    expect(s.getState().past).toEqual([]);
  });

  test('setPositions writes a drag as one undo step and makes the mapping unsaved; the same positions again change nothing', () => {
    const s = store();

    s.getState().setPositions(ORDER_ID, { boxes: { a1: { x: 3.2, y: 4.7 } } });
    expect(orderId(s)?.layout).toEqual({ boxes: { a1: { x: 3, y: 5 } } });
    expect(selectUnsaved(s.getState())).toBe(true);

    s.getState().setPositions(ORDER_ID, { boxes: { a1: { x: 3, y: 5 } } });
    expect(s.getState().past).toHaveLength(1);
  });

  test('rewire moves a wire as one undo step, and a refused one records why', () => {
    const s = store();

    s.getState().rewire(ORDER_ID, 'a1', { box: 'f1', slot: 0 }, { box: 'e1', slot: 1 });
    expect(orderId(s)?.boxes.e1).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'a1'] });
    expect(orderId(s)?.boxes.f1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: [null] });
    expect(s.getState().past).toHaveLength(1);

    s.getState().rewire(ORDER_ID, 'f1', 'target', { box: 'f1', slot: 0 });
    expect(s.getState().refusal).toEqual({ code: 'box-cycle', id: 'f1' });
    expect(s.getState().past).toHaveLength(1);
  });

  test('setParams applies the parameter dialog as one undo step, checked against the catalogue', () => {
    const s = store({ formatVersion: 1, targetFieldMappings: { [ORDER_ID]: { id: 't1', result: 's1', boxes: { s1: { kind: 'function', fn: 'substring' } } } } });
    const catalogue = {
      substring: {
        family: 'text',
        executionKind: 'single' as const,
        params: [{ name: 'start', type: 'string' as const, default: '0' }, { name: 'length', type: 'string' as const, default: '0' }] as const,
        portable: true,
        fidelity: { level: 'exact' as const },
      },
    };

    s.getState().setParams(ORDER_ID, 's1', { start: '2', length: '3' }, { substring: { ...catalogue.substring, params: [...catalogue.substring.params] } });

    expect(orderId(s)?.boxes.s1).toEqual({ kind: 'function', fn: 'substring', params: { start: '2', length: '3' } });
    expect(s.getState().past).toHaveLength(1);
  });

  test('copy takes the given boxes with their positions; paste places the copies 24 px off, clear of the boxes shown, and selects them', () => {
    const s = store();

    s.getState().copy(ORDER_ID, ['f1', 'a1'], { f1: { x: 200, y: 0 }, a1: { x: 0, y: 0 } });
    s.getState().paste(ORDER_ID, { target: { x: 400, y: 0 }, boxes: { a1: { x: 0, y: 0 }, f1: { x: 200, y: 0 }, e1: { x: 24, y: 24 } } });

    // the store draws the mapping id (id1) before the copies' ids
    expect(s.getState().selected).toEqual(['id2', 'id3']);
    expect(orderId(s)?.boxes.id2).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: ['id3'] });
    expect(orderId(s)?.layout?.boxes).toEqual({ a1: { x: 0, y: 0 }, f1: { x: 200, y: 0 }, e1: { x: 24, y: 24 }, id2: { x: 248, y: 48 }, id3: { x: 48, y: 48 } });
    expect(s.getState().past).toHaveLength(1);
  });

  test('copied positions pasted with nothing shown move 24 px', () => {
    const s = store();

    s.getState().copy(ORDER_ID, ['a1'], { a1: { x: 10, y: 10 } });
    s.getState().paste(ORDER_ID);

    expect(orderId(s)?.layout).toEqual({ boxes: { id2: { x: 34, y: 34 } } });
  });

  test('copy without positions pastes without writing any', () => {
    const s = store();

    s.getState().copy(ORDER_ID, ['a1']);
    s.getState().paste(ORDER_ID, { boxes: { a1: { x: 0, y: 0 } } });

    expect(orderId(s)?.boxes.id2).toEqual({ kind: 'source', field: ORDER_ID });
    expect(orderId(s)?.layout).toBeUndefined();
  });

  test('disconnectBox removes every wire of a box as one undo step', () => {
    const s = store();

    s.getState().disconnectBox(ORDER_ID, 'f1');

    expect(orderId(s)?.result).toBeNull();
    expect(orderId(s)?.boxes.f1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: [null] });
    expect(s.getState().past).toHaveLength(1);
  });
});
