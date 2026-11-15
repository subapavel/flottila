import { describe, expect, test } from 'vitest';
import type { Diagnostic, StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore } from './working-copy';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** OrderId: a1 and c1 into concat f1, the result; Note: n1. */
const graph: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: {
        a1: { kind: 'source', field: ORDER_ID },
        c1: { kind: 'function', fn: 'constant', params: { value: '-' } },
        f1: { kind: 'function', fn: 'concat', inputs: ['a1', 'c1'] },
      },
    },
    [NOTE]: { id: 'tn', result: 'n1', boxes: { n1: { kind: 'source', field: NOTE } } },
  },
};

/** The ids the id port hands out, in order. */
function ids(...values: string[]) {
  return () => {
    const next = values.shift();
    if (next === undefined) throw new Error('the test ran out of ids');
    return next;
  };
}

function store(newId: () => string = ids()) {
  const opened: StoredMapping = { model: graph, etag: '"sha256:base"' };
  return createWorkingCopyStore(opened, {
    newId,
    save: () => new Promise(() => {}),
    load: () => new Promise(() => {}),
    compile: () => new Promise<Diagnostic[]>(() => {}),
  });
}

const boxesOf = (s: ReturnType<typeof store>, field: string) => s.getState().workingCopy.targetFieldMappings?.[field]?.boxes;

describe('selecting several boxes', () => {
  test('selectBoxes selects them together, in order; select keeps one', () => {
    const s = store();

    s.getState().selectBoxes(['c1', 'f1']);
    expect(s.getState()).toMatchObject({ selected: ['c1', 'f1'] });

    s.getState().select('a1');
    expect(s.getState()).toMatchObject({ selected: ['a1'] });

    s.getState().selectBoxes([]);
    expect(s.getState()).toMatchObject({ selected: [] });

    s.getState().select(null);
    expect(s.getState().selected).toEqual([]);
  });

  test('deleting a selected box unselects it and keeps the others in order', () => {
    const s = store();
    s.getState().selectBoxes(['a1', 'c1', 'f1']);

    s.getState().deleteBoxes(['c1']);
    expect(s.getState()).toMatchObject({ selected: ['a1', 'f1'] });

    s.getState().deleteBoxes(['f1']);
    expect(s.getState()).toMatchObject({ selected: ['a1'] });
  });

  test('deleting several selected boxes removes them all as one undo step and unselects them; undo brings them back', () => {
    const s = store();
    s.getState().selectBoxes(['n1', 'a1', 'c1', 'f1']);

    s.getState().deleteBoxes(['c1', 'f1']);

    expect(Object.keys(boxesOf(s, ORDER_ID)!)).toEqual(['a1']);
    expect(s.getState().past).toEqual([graph]);
    expect(s.getState()).toMatchObject({ selected: ['n1', 'a1'] });

    s.getState().undo();
    expect(s.getState().workingCopy).toBe(graph);
  });

  test('a delete of several boxes with one unknown id deletes none of them and keeps the selection', () => {
    const s = store();
    s.getState().selectBoxes(['c1', 'f1']);

    s.getState().deleteBoxes(['c1', 'zz', 'f1']);

    expect(s.getState().workingCopy).toBe(graph);
    expect(s.getState().refusal).toEqual({ code: 'unknown-box-reference', id: 'zz' });
    expect(s.getState()).toMatchObject({ selected: ['c1', 'f1'] });
  });

  test('Reload unselects every box and keeps the clipboard', async () => {
    const s = createWorkingCopyStore(
      { model: graph, etag: '"a"' },
      { newId: ids('t9', 'p1'), save: () => new Promise(() => {}), load: async () => ({ model: graph, etag: '"b"' }), compile: () => new Promise(() => {}) },
    );
    s.getState().selectBoxes(['c1', 'f1']);
    s.getState().copy(ORDER_ID);

    await s.getState().reload();

    expect(s.getState()).toMatchObject({ selected: [] });
    expect(Object.keys(s.getState().clipboard?.boxes ?? {})).toEqual(['c1', 'f1']);
  });
});

describe('copy and paste', () => {
  test('copy takes the selected boxes of the target field mapping; paste adds them with fresh ids as one undo step', () => {
    const s = store(ids('t9', 'pc', 'pf'));
    // n1 and the mapping id t1 are no boxes of OrderId's mapping: copy leaves them out
    s.getState().selectBoxes(['n1', 't1', 'c1', 'f1']);

    s.getState().copy(ORDER_ID);
    s.getState().paste(ORDER_ID);

    expect(boxesOf(s, ORDER_ID)).toMatchObject({
      pc: { kind: 'function', fn: 'constant', params: { value: '-' } },
      pf: { kind: 'function', fn: 'concat', inputs: [null, 'pc'] },
    });
    expect(Object.keys(boxesOf(s, ORDER_ID)!)).toHaveLength(5);
    expect(s.getState().past).toEqual([graph]);
    // the pasted boxes become the selection, so that they can be moved at once
    expect(s.getState()).toMatchObject({ selected: ['pc', 'pf'] });
  });

  test('the clipboard keeps the boxes as copied: a later edit does not change what is pasted, and it pastes again', () => {
    const s = store(ids('t8', 'p1', 't9', 'p2'));
    s.getState().select('c1');
    s.getState().copy(ORDER_ID);
    s.getState().deleteBoxes(['c1']);

    s.getState().paste(ORDER_ID);
    s.getState().paste(ORDER_ID);

    expect(boxesOf(s, ORDER_ID)).toMatchObject({ p1: graph.targetFieldMappings![ORDER_ID]!.boxes.c1, p2: graph.targetFieldMappings![ORDER_ID]!.boxes.c1 });
  });

  test('copy with no box of that mapping selected keeps the clipboard as it was', () => {
    const s = store(ids('t9', 'p1'));
    s.getState().select('c1');
    s.getState().copy(ORDER_ID);

    s.getState().select('n1');
    s.getState().copy(ORDER_ID);
    s.getState().copy(`${ORDER}/Quantity`);
    s.getState().paste(ORDER_ID);

    expect(boxesOf(s, ORDER_ID)?.p1).toEqual(graph.targetFieldMappings![ORDER_ID]!.boxes.c1);
  });

  test('paste with an empty clipboard, or into another target field than the copy came from, does nothing', () => {
    const s = store();

    s.getState().paste(ORDER_ID);
    s.getState().select('c1');
    s.getState().copy(ORDER_ID);
    s.getState().paste(NOTE);

    expect(s.getState().workingCopy).toBe(graph);
    expect(s.getState().past).toEqual([]);
  });

  test('a refused paste leaves the working copy and the selection, and records why', () => {
    const s = store(ids('t9', 'n1'));
    s.getState().select('c1');
    s.getState().copy(ORDER_ID);

    s.getState().paste(ORDER_ID);

    expect(s.getState().workingCopy).toBe(graph);
    expect(s.getState().refusal).toEqual({ code: 'duplicate-authoring-id', id: 'n1' });
    expect(s.getState()).toMatchObject({ selected: ['c1'] });
  });
});
