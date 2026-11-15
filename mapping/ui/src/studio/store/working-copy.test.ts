import { describe, expect, test, vi } from 'vitest';
import { ApiProblem, type Diagnostic, type StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectUnsaved, UNDO_DEPTH, watchUnsaved, type WorkingCopyPorts } from './working-copy';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};
const baseline: MappingModel = { formatVersion: 1, source: structure, target: structure };
const BASELINE_TAG = '"sha256:base"';
const opened: StoredMapping = { model: baseline, etag: BASELINE_TAG };

/** The ids the id port hands out, in order. */
function ids(...values: string[]) {
  return () => {
    const next = values.shift();
    if (next === undefined) throw new Error('the test ran out of ids');
    return next;
  };
}

/** Ids `prefix1`, `prefix2`, … without end. */
function counted(prefix: string) {
  let n = 0;
  return () => `${prefix}${++n}`;
}

/** A port that answers each call when the test resolves or rejects it. */
function deferred<A extends unknown[]>() {
  const calls: { args: A; resolve: (m: StoredMapping) => void; reject: (e: Error) => void }[] = [];
  const port = (...args: A) =>
    new Promise<StoredMapping>((resolve, reject) => {
      calls.push({ args, resolve, reject });
    });
  return { port, calls };
}

/** A compile port that never answers: these tests do not look at diagnostics (`compile.test.ts` does). */
const unanswered = () => new Promise<Diagnostic[]>(() => {});

function store(ports: Partial<WorkingCopyPorts> = {}, initial: StoredMapping = opened) {
  return createWorkingCopyStore(initial, {
    newId: ids(),
    save: deferred<[MappingModel, string]>().port,
    load: deferred<[]>().port,
    compile: unanswered,
    ...ports,
  });
}

/** The canonical model the service writes for the one link: the same content, keys sorted, no context. */
const canonicalLinked: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: { boxes: { b1: { field: ORDER_ID, kind: 'source' } }, id: 'm1', result: 'b1' },
  },
};

const mappingChanged = () =>
  new ApiProblem({
    type: 'urn:flottila:mapping:problem:mapping-changed',
    title: 'Mapping changed',
    status: 409,
    detail: 'the mapping changed on disk',
  });

describe('the working-copy store', () => {
  test('starts with the working copy equal to the baseline, so nothing is unsaved and nothing can be undone', () => {
    const s = store();

    expect(s.getState().workingCopy).toEqual(baseline);
    expect(s.getState().baseline).toEqual(baseline);
    expect(s.getState().etag).toBe(BASELINE_TAG);
    expect(selectUnsaved(s.getState())).toBe(false);
    expect(s.getState().past).toEqual([]);
    expect(s.getState().future).toEqual([]);
    expect(s.getState().conflict).toBe(false);
  });

  test('a link runs the link command on the working copy with fresh ids, and the working copy becomes unsaved', () => {
    const s = store({ newId: ids('b1', 'm1') });

    s.getState().link(ORDER_ID, ORDER_ID);

    expect(s.getState().workingCopy.targetFieldMappings).toEqual({
      [ORDER_ID]: { id: 'm1', result: 'b1', boxes: { b1: { kind: 'source', field: ORDER_ID } } },
    });
    expect(s.getState().baseline).toEqual(baseline);
    expect(s.getState().refusal).toBeNull();
    expect(selectUnsaved(s.getState())).toBe(true);
  });

  test('a refused link leaves the working copy as it was, records the refusal and is no undo step', () => {
    // the second link reuses b1 inside the same target field mapping: a duplicate id
    const s = store({ newId: ids('b1', 'm1', 'b1', 'm2', 'b2', 'm3') });
    s.getState().link(ORDER_ID, ORDER_ID);
    const linked = s.getState().workingCopy;

    s.getState().link(NOTE, ORDER_ID);

    expect(s.getState().workingCopy).toBe(linked);
    expect(s.getState().refusal).toEqual({ code: 'duplicate-authoring-id', id: 'b1' });
    expect(s.getState().past).toHaveLength(1);

    s.getState().link(NOTE, NOTE);

    expect(s.getState().refusal).toBeNull();
    expect(Object.keys(s.getState().workingCopy.targetFieldMappings ?? {})).toEqual([ORDER_ID, NOTE]);
  });

  test.each(['source', 'target'] as const)(
    'a link with a refused %s field is refused with that refusal and is no undo step (ADR-0027)',
    (side) => {
      const refusal = { code: 'ambiguous-field-path', side: 'both' as const, file: 'order.xsd', line: 14, column: 65 };
      const s = store({ newId: ids('b1', 'm1') });

      s.getState().link(NOTE, ORDER_ID, { [side]: refusal });

      expect(s.getState().workingCopy).toBe(baseline);
      expect(s.getState().refusal).toEqual({
        code: 'refused-field',
        side,
        field: side === 'source' ? NOTE : ORDER_ID,
        refusal,
      });
      expect(s.getState().past).toEqual([]);
    },
  );
});

describe('editing the function graph', () => {
  test('inserting a function box into a field without a mapping creates it with the box as result, as one undo step', () => {
    const s = store({ newId: ids('c1', 't1') });

    s.getState().insertBox(ORDER_ID, 'constant');

    expect(s.getState().workingCopy.targetFieldMappings).toEqual({
      [ORDER_ID]: { id: 't1', result: 'c1', boxes: { c1: { kind: 'function', fn: 'constant' } } },
    });
    expect(s.getState().past).toEqual([baseline]);
  });

  test('wire, unwire, set result and delete box each run their command as one undo step', () => {
    const s = store({ newId: ids('c1', 't1', 'f1', 'unused') });
    s.getState().insertBox(ORDER_ID, 'constant');
    s.getState().insertBox(ORDER_ID, 'toUpperCase');
    const mapping = () => s.getState().workingCopy.targetFieldMappings?.[ORDER_ID];

    s.getState().wire(ORDER_ID, 'c1', 'f1', 0);
    expect(mapping()?.boxes.f1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: ['c1'] });
    s.getState().setResult(ORDER_ID, 'f1');
    expect(mapping()?.result).toBe('f1');
    s.getState().unwire(ORDER_ID, 'f1', 0);
    expect(mapping()?.boxes.f1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: [null] });
    s.getState().deleteBoxes(['f1']);
    expect(mapping()).toEqual({ id: 't1', result: null, boxes: { c1: { kind: 'function', fn: 'constant' } } });

    expect(s.getState().past).toHaveLength(6);
  });

  test('a refused wire leaves the working copy as it was and records why; an edit that changes nothing is no undo step', () => {
    const s = store({ newId: ids('c1', 't1', 'f1', 'unused') });
    s.getState().insertBox(ORDER_ID, 'constant');
    s.getState().insertBox(ORDER_ID, 'toUpperCase');
    s.getState().wire(ORDER_ID, 'c1', 'f1', 0);
    const wired = s.getState().workingCopy;

    s.getState().wire(ORDER_ID, 'f1', 'f1', 0);
    expect(s.getState().workingCopy).toBe(wired);
    expect(s.getState().refusal).toEqual({ code: 'box-cycle', id: 'f1' });

    s.getState().wire(ORDER_ID, 'c1', 'f1', 0);
    expect(s.getState().workingCopy).toBe(wired);
    expect(s.getState().past).toHaveLength(3);
    expect(s.getState().refusal).toBeNull();
  });

  test('deleting the selected box clears the selection; deleting another box keeps it', () => {
    const s = store({ newId: ids('c1', 't1', 'f1', 'unused') });
    s.getState().insertBox(ORDER_ID, 'constant');
    s.getState().insertBox(ORDER_ID, 'toUpperCase');
    s.getState().select('c1');

    s.getState().deleteBoxes(['f1']);
    expect(s.getState().selected).toEqual(['c1']);

    s.getState().deleteBoxes(['c1']);
    expect(s.getState().selected).toEqual([]);
    expect(s.getState().workingCopy).toEqual(baseline);
  });

  test('a refused delete keeps the selection', () => {
    const s = store();
    s.getState().select('zz');

    s.getState().deleteBoxes(['zz']);

    expect(s.getState().refusal).toEqual({ code: 'unknown-box-reference', id: 'zz' });
    expect(s.getState().selected).toEqual(['zz']);
  });
});

describe('undo and redo', () => {
  test('undo steps back through each edit to the baseline, and redo steps forward again', () => {
    const s = store({ newId: ids('b1', 'm1', 'b2', 'm2') });
    s.getState().link(ORDER_ID, ORDER_ID);
    const first = s.getState().workingCopy;
    s.getState().link(NOTE, NOTE);
    const second = s.getState().workingCopy;

    s.getState().undo();
    expect(s.getState().workingCopy).toBe(first);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(baseline);
    expect(selectUnsaved(s.getState())).toBe(false);

    s.getState().redo();
    expect(s.getState().workingCopy).toBe(first);
    s.getState().redo();
    expect(s.getState().workingCopy).toBe(second);
    expect(s.getState().future).toEqual([]);
  });

  test('undo with nothing to undo and redo with nothing to redo change nothing', () => {
    const s = store({ newId: ids('b1', 'm1') });

    s.getState().undo();
    expect(s.getState().workingCopy).toBe(baseline);
    expect(s.getState().future).toEqual([]);

    s.getState().link(ORDER_ID, ORDER_ID);
    const linked = s.getState().workingCopy;
    s.getState().redo();
    expect(s.getState().workingCopy).toBe(linked);
    expect(s.getState().past).toEqual([baseline]);
  });

  test(`keeps the last ${UNDO_DEPTH} steps of a longer history, at least 100`, () => {
    expect(UNDO_DEPTH).toBeGreaterThanOrEqual(100);
    const s = store({ newId: counted('x') });
    const states = [s.getState().workingCopy];
    for (let i = 0; i < UNDO_DEPTH + 5; i++) {
      s.getState().link(ORDER_ID, ORDER_ID);
      states.push(s.getState().workingCopy);
    }
    expect(s.getState().past).toHaveLength(UNDO_DEPTH);

    for (let i = 0; i < UNDO_DEPTH; i++) s.getState().undo();
    expect(s.getState().workingCopy).toBe(states[5]);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(states[5]);

    for (let i = 0; i < UNDO_DEPTH; i++) s.getState().redo();
    expect(s.getState().workingCopy).toBe(states[UNDO_DEPTH + 5]);
  });

  test('a new edit after an undo clears redo', () => {
    const s = store({ newId: ids('b1', 'm1', 'b2', 'm2') });
    s.getState().link(ORDER_ID, ORDER_ID);
    s.getState().undo();
    expect(s.getState().future).toHaveLength(1);

    s.getState().link(NOTE, NOTE);

    expect(s.getState().future).toEqual([]);
    s.getState().redo();
    expect(Object.keys(s.getState().workingCopy.targetFieldMappings ?? {})).toEqual([NOTE]);
  });

  test('selection is no undo step: undo reverts the last edit and keeps the selection', () => {
    const s = store({ newId: ids('b1', 'm1') });
    s.getState().link(ORDER_ID, ORDER_ID);

    s.getState().select('b1');
    expect(s.getState().selected).toEqual(['b1']);
    expect(s.getState().past).toHaveLength(1);

    s.getState().undo();
    expect(s.getState().workingCopy).toBe(baseline);
    expect(s.getState().selected).toEqual(['b1']);
    s.getState().select(null);
    expect(s.getState().selected).toEqual([]);
  });
});

describe('save', () => {
  test('sends the working copy with the baseline ETag; the stored model and its tag become the baseline', async () => {
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1'), save: save.port });
    s.getState().link(ORDER_ID, ORDER_ID);
    const sent = s.getState().workingCopy;

    const saving = s.getState().save();
    expect(save.calls).toHaveLength(1);
    expect(save.calls[0]!.args[0]).toBe(sent);
    expect(save.calls[0]!.args[1]).toBe(BASELINE_TAG);
    expect(selectUnsaved(s.getState())).toBe(true);

    save.calls[0]!.resolve({ model: canonicalLinked, etag: '"sha256:new"' });
    await saving;

    expect(s.getState().baseline).toBe(canonicalLinked);
    expect(s.getState().etag).toBe('"sha256:new"');
    expect(s.getState().workingCopy).toBe(canonicalLinked);
    expect(selectUnsaved(s.getState())).toBe(false);
  });

  test('does not clear the history: undo after a save steps back to before the edit', async () => {
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1'), save: save.port });
    s.getState().link(ORDER_ID, ORDER_ID);

    const saving = s.getState().save();
    save.calls[0]!.resolve({ model: canonicalLinked, etag: '"sha256:new"' });
    await saving;

    expect(s.getState().past).toEqual([baseline]);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(baseline);
    expect(selectUnsaved(s.getState())).toBe(true);
  });

  test('the canonical response is the baseline even where it differs from what was sent', async () => {
    // the service drops a context equal to the field's parent (ADR-0010 canonical form)
    const withContext: MappingModel = {
      ...baseline,
      targetFieldMappings: {
        [ORDER_ID]: { id: 'm1', result: 'b1', boxes: { b1: { kind: 'source', field: ORDER_ID, context: ORDER } } },
      },
    };
    const save = deferred<[MappingModel, string]>();
    const s = store({ save: save.port }, { model: withContext, etag: BASELINE_TAG });

    const saving = s.getState().save();
    save.calls[0]!.resolve({ model: canonicalLinked, etag: '"sha256:new"' });
    await saving;

    expect(s.getState().baseline).toEqual(canonicalLinked);
    expect(s.getState().baseline).not.toEqual(withContext);
    expect(selectUnsaved(s.getState())).toBe(false);
  });

  test('an edit made while a save is under way stays in the working copy and is unsaved against the new baseline', async () => {
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1', 'b2', 'm2'), save: save.port });
    s.getState().link(ORDER_ID, ORDER_ID);

    const saving = s.getState().save();
    s.getState().link(NOTE, NOTE);
    save.calls[0]!.resolve({ model: canonicalLinked, etag: '"sha256:new"' });
    await saving;

    expect(s.getState().baseline).toBe(canonicalLinked);
    expect(s.getState().workingCopy.targetFieldMappings?.[NOTE]).toEqual({
      id: 'm2',
      result: 'b2',
      boxes: { b2: { kind: 'source', field: NOTE } },
    });
    expect(selectUnsaved(s.getState())).toBe(true);
  });

  test('a refused save keeps the baseline and the working copy, stays unsaved, opens no conflict and passes the error on', async () => {
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1'), save: save.port });
    s.getState().link(ORDER_ID, ORDER_ID);
    const edited = s.getState().workingCopy;

    const saving = s.getState().save();
    save.calls[0]!.reject(new Error('model-refused'));

    await expect(saving).rejects.toThrow('model-refused');
    expect(s.getState().baseline).toBe(baseline);
    expect(s.getState().workingCopy).toBe(edited);
    expect(s.getState().conflict).toBe(false);
    expect(selectUnsaved(s.getState())).toBe(true);
  });

  test('unsaved compares content, not identity: an edit that the response already holds is saved', async () => {
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1'), save: save.port });

    // the save leaves before the edit, and its response happens to hold the edit already
    const saving = s.getState().save();
    s.getState().link(ORDER_ID, ORDER_ID);
    save.calls[0]!.resolve({ model: structuredClone(canonicalLinked), etag: '"sha256:new"' });
    await saving;

    expect(s.getState().workingCopy).not.toBe(s.getState().baseline);
    expect(selectUnsaved(s.getState())).toBe(false);
  });
});

describe('a save conflict', () => {
  async function conflicted() {
    const save = deferred<[MappingModel, string]>();
    const load = deferred<[]>();
    const s = store({ newId: ids('b1', 'm1'), save: save.port, load: load.port });
    s.getState().link(ORDER_ID, ORDER_ID);
    s.getState().select('b1');
    const saving = s.getState().save();
    save.calls[0]!.reject(mappingChanged());
    await saving;
    return { s, save, load };
  }

  test('a 409 mapping-changed opens the conflict and keeps the working copy, the baseline and the history', async () => {
    const { s } = await conflicted();

    expect(s.getState().conflict).toBe(true);
    expect(s.getState().baseline).toBe(baseline);
    expect(s.getState().etag).toBe(BASELINE_TAG);
    expect(Object.keys(s.getState().workingCopy.targetFieldMappings ?? {})).toEqual([ORDER_ID]);
    expect(s.getState().past).toEqual([baseline]);
  });

  test('Keep editing closes the conflict and keeps the working copy; the next save sends the old tag again', async () => {
    const { s, save } = await conflicted();
    const edited = s.getState().workingCopy;

    s.getState().keepEditing();

    expect(s.getState().conflict).toBe(false);
    expect(s.getState().workingCopy).toBe(edited);
    void s.getState().save();
    expect(save.calls[1]!.args[1]).toBe(BASELINE_TAG);
  });

  test('Reload loads the file from disk as the new baseline and drops the working copy, the history and the selection', async () => {
    const { s, load } = await conflicted();
    s.getState().undo();
    expect(s.getState().future).toHaveLength(1);
    const onDisk: MappingModel = { ...canonicalLinked, targetFieldMappings: {} };

    const reloading = s.getState().reload();
    expect(load.calls).toHaveLength(1);
    load.calls[0]!.resolve({ model: onDisk, etag: '"sha256:disk"' });
    await reloading;

    expect(s.getState().baseline).toBe(onDisk);
    expect(s.getState().workingCopy).toBe(onDisk);
    expect(s.getState().etag).toBe('"sha256:disk"');
    expect(s.getState().past).toEqual([]);
    expect(s.getState().future).toEqual([]);
    expect(s.getState().selected).toEqual([]);
    expect(s.getState().conflict).toBe(false);
    expect(selectUnsaved(s.getState())).toBe(false);
  });

  test('a Reload that fails keeps everything as it was and passes the error on', async () => {
    const { s, load } = await conflicted();
    const edited = s.getState().workingCopy;

    const reloading = s.getState().reload();
    load.calls[0]!.reject(new Error('model-refused'));

    await expect(reloading).rejects.toThrow('model-refused');
    expect(s.getState().workingCopy).toBe(edited);
    expect(s.getState().past).toEqual([baseline]);
    expect(s.getState().conflict).toBe(true);
  });
});

describe('the unsaved flag', () => {
  test('a watcher hears each flip of working copy against baseline, and only then, until it unsubscribes', async () => {
    const onUnsavedChange = vi.fn();
    const save = deferred<[MappingModel, string]>();
    const s = store({ newId: ids('b1', 'm1', 'b2', 'm2', 'b3', 'm3'), save: save.port });
    const unsubscribe = watchUnsaved(s, onUnsavedChange);

    s.getState().select('x');
    expect(onUnsavedChange).not.toHaveBeenCalled();

    s.getState().link(ORDER_ID, ORDER_ID);
    s.getState().link(NOTE, NOTE);
    expect(onUnsavedChange.mock.calls).toEqual([[true]]);

    s.getState().undo();
    s.getState().undo();
    expect(onUnsavedChange.mock.calls).toEqual([[true], [false]]);

    s.getState().redo();
    const saving = s.getState().save();
    save.calls[0]!.resolve({ model: canonicalLinked, etag: '"sha256:new"' });
    await saving;
    expect(onUnsavedChange.mock.calls).toEqual([[true], [false], [true], [false]]);

    unsubscribe();
    s.getState().link(ORDER_ID, `${ORDER}/Quantity`);
    expect(selectUnsaved(s.getState())).toBe(true);
    expect(onUnsavedChange).toHaveBeenCalledTimes(4);
  });
});
