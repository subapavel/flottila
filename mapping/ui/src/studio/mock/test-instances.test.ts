import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { describe, expect, test, vi } from 'vitest';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore, selectUnsaved } from '../store/working-copy';
import type { MappingModel } from '../../generated/mapping-model';
import {
  createInstance,
  currentInstance,
  instanceActions,
  instancesOf,
  readInstance,
  removeInstance,
  saveInstance,
  selectInstance,
  testInput,
  testInstances,
  testInstancesKey,
  zipInstance,
  type TestInstance,
} from './test-instances';

const MODEL: MappingModel = { formatVersion: 1 };
const KEY = testInstancesKey('m/a');

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const workingCopy = () =>
  createWorkingCopyStore(
    { model: MODEL, etag: '"1"' },
    { newId: () => 'id' as never, save: vi.fn(async (model: MappingModel) => ({ model, etag: '"2"' })), load: async () => ({ model: MODEL, etag: '"3"' }), compile: async () => [] },
  );

const instance = (id: string, name: string, lastUsed: number, format: 'XML' | 'JSON' = 'XML'): TestInstance => ({ id, name, format, text: `<${name}/>`, values: {}, lastUsed });

describe('the test instance library (the prototype useTestInstances, #170)', () => {
  test('the instances of a format; the current one is the last used, the later one on a tie', () => {
    const list = [instance('a', 'A', 2), instance('b', 'B', 1), instance('j', 'J', 9, 'JSON'), instance('c', 'C', 2)];

    expect(instancesOf(list, 'XML').map((i) => i.id)).toEqual(['a', 'b', 'c']);
    expect(currentInstance(list, 'XML')?.id).toBe('c');
    expect(currentInstance(list, 'JSON')?.id).toBe('j');
    expect(currentInstance([], 'XML')).toBeUndefined();
  });

  test('create adds an instance of the resource as the current one; a name the format has already is refused', () => {
    const list = [instance('a', 'A', 4), instance('j', 'B', 1, 'JSON')];

    const created = createInstance(list, 'XML', 'B', { text: '<x/>', values: { 'header:Profiling': 'on' } }, 'n');

    expect(created.at(-1)).toEqual({ id: 'n', name: 'B', format: 'XML', text: '<x/>', values: { 'header:Profiling': 'on' }, lastUsed: 5 });
    expect(currentInstance(created, 'XML')?.id).toBe('n');
    expect(() => createInstance(created, 'XML', 'B', { text: '', values: {} }, 'm')).toThrow('This name already exists.');
  });

  test('select makes an instance the current one; an id of another format changes nothing', () => {
    const list = [instance('a', 'A', 1), instance('b', 'B', 2), instance('j', 'J', 3, 'JSON')];

    expect(currentInstance(selectInstance(list, 'XML', 'a'), 'XML')?.id).toBe('a');
    expect(selectInstance(list, 'XML', 'j').find((i) => i.id === 'j')?.lastUsed).toBe(3);
  });

  test('save writes the resource into the current instance with a new revision, unless it is the same', () => {
    const list = [instance('a', 'A', 1), instance('b', 'B', 2)];

    const saved = saveInstance(list, 'XML', { text: '<changed/>', values: { x: '1' } });

    expect(saved.find((i) => i.id === 'b')).toMatchObject({ text: '<changed/>', values: { x: '1' }, revision: 1 });
    expect(saved.find((i) => i.id === 'a')).toBe(list[0]);
    expect(saveInstance(saved, 'XML', { text: '<changed/>', values: { x: '1' } })).toEqual(saved);
    expect(saveInstance([], 'XML', { text: '', values: {} })).toEqual([]);
  });

  test('remove deletes the current instance; the one used before it becomes current', () => {
    const list = [instance('a', 'A', 1), instance('b', 'B', 2), instance('j', 'J', 3, 'JSON')];

    const removed = removeInstance(list, 'XML');

    expect(removed.map((i) => i.id)).toEqual(['a', 'j']);
    expect(currentInstance(removed, 'XML')?.id).toBe('a');
  });

  test('is a draft of the edit session: Save keeps it in the browser under the mapping id, Discard reverts it', async () => {
    const browserState = memoryBrowserState({ [KEY]: [instance('a', 'A', 1)] });
    const store = workingCopy();
    const library = testInstances(store, browserState, 'm/a');
    const actions = instanceActions(library, 'XML');

    actions.create('B', { text: '<b/>', values: {} });
    expect(selectUnsaved(store.getState())).toBe(true);
    store.getState().discard();
    expect(library.getState().value).toEqual([instance('a', 'A', 1)]);

    actions.create('B', { text: '<b/>', values: {} });
    await store.getState().save();
    expect((browserState.values.get(KEY) as TestInstance[]).map((i) => i.name)).toEqual(['A', 'B']);
    expect(testInstances(store, browserState, 'm/a')).toBe(library);
  });

  test('anything but a list of instances read back from the browser is an empty library', () => {
    for (const kept of ['text', null, [{ id: 1 }], [{ ...instance('a', 'A', 1), values: null }]]) {
      expect(testInstances(workingCopy(), memoryBrowserState({ [KEY]: kept }), 'm/a').getState().value).toEqual([]);
    }
  });

  test('testInput counts each document loaded, one per working copy', () => {
    const store = workingCopy();
    const input = testInput(store);

    input.getState().load('<a/>');
    input.getState().load('<a/>');

    expect(input.getState()).toMatchObject({ text: '<a/>', loads: 2 });
    expect(testInput(store)).toBe(input);
    expect(testInput(workingCopy()).getState().loads).toBe(0);
  });
});

describe('test instance files (the prototype readInstance and downloadInstance)', () => {
  test('zipInstance stores the document as instance.<format> in a zip, which readInstance reads back', async () => {
    const zipped = zipInstance('<Order/>', 'XML');

    expect(strFromU8(unzipSync(zipped)['instance.xml']!)).toBe('<Order/>');
    expect(await readInstance(new File([zipped], 'schema.zip'), 'XML')).toBe('<Order/>');
  });

  test('a plain file is read as text; a zip without instance.<format> is refused; a byte order mark is dropped', async () => {
    expect(await readInstance(new File(['{"a":1}'], 'in.json'), 'JSON')).toBe('{"a":1}');
    await expect(readInstance(new File([zipSync({ 'other.xml': strToU8('<x/>') })], 'x.zip'), 'XML')).rejects.toThrow('The archive needs a instance.xml file under 16 MB.');
    expect(await readInstance(new File([zipSync({ 'instance.json': strToU8('﻿{}') })], 'x.zip'), 'JSON')).toBe('{}');
  });
});
