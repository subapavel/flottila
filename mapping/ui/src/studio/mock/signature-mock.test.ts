import { describe, expect, test, vi } from 'vitest';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore, selectUnsaved } from '../store/working-copy';
import type { MappingModel } from '../../generated/mapping-model';
import { moveRow, signatureActions, signatureMock, signatureMockKey, type PreviewMessage, type PreviewParameter } from './signature-mock';

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const MODEL: MappingModel = { formatVersion: 1 };
const workingCopy = () =>
  createWorkingCopyStore(
    { model: MODEL, etag: '"1"' },
    { newId: () => 'id' as never, save: vi.fn(async (model: MappingModel) => ({ model, etag: '"2"' })), load: async () => ({ model: MODEL, etag: '"3"' }), compile: async () => [] },
  );

const message = (id: string): PreviewMessage => ({ id, occurrence: '1' });
const parameter = (id: string, name: string): PreviewParameter => ({ id, name, type: 'string', direction: 'Import', description: '' });
const KEY = signatureMockKey('mappings/order');

describe('the prototype moveRow', () => {
  test('swaps a row with its neighbour', () => {
    expect(moveRow(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moveRow(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
  });

  test('gives back the same rows at an end or for a row it does not have', () => {
    const rows = ['a', 'b'];
    expect(moveRow(rows, 0, -1)).toBe(rows);
    expect(moveRow(rows, 1, 1)).toBe(rows);
    expect(moveRow(rows, -1, 1)).toBe(rows);
    expect(moveRow(rows, 2, -1)).toBe(rows);
  });
});

describe('the preview signature of a mapping (ADR-0028)', () => {
  test('starts empty; a change is a draft of the edit session, which Save keeps in the browser state under the mapping id', async () => {
    const browserState = memoryBrowserState();
    const store = workingCopy();
    const mock = signatureMock(store, browserState, 'mappings/order');
    expect(mock.getState().value).toMatchObject({ source: [], target: [], parameters: [] });

    signatureActions(mock).insertMessage('source', 0, message('m1'));
    signatureActions(mock).addParameter(parameter('p1', 'date'));
    expect(selectUnsaved(store.getState())).toBe(true);
    expect(browserState.values.has(KEY)).toBe(false);

    await store.getState().save();

    expect(browserState.values.get(KEY)).toEqual({ source: [message('m1')], target: [], parameters: [parameter('p1', 'date')] });
    expect(signatureMock(workingCopy(), browserState, 'mappings/order').getState().value).toMatchObject({ source: [message('m1')], parameters: [parameter('p1', 'date')] });
    expect(signatureMock(workingCopy(), browserState, 'mappings/other').getState().value.source).toEqual([]);
  });

  test('Discard drops the draft: the messages and parameters go back to the saved ones', () => {
    const browserState = memoryBrowserState({ [KEY]: { source: [message('kept')], target: [], parameters: [] } });
    const store = workingCopy();
    const mock = signatureMock(store, browserState, 'mappings/order');
    signatureActions(mock).insertMessage('source', 1, message('m2'));

    store.getState().discard();

    expect(mock.getState().value.source).toEqual([message('kept')]);
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('anything else kept under the key starts empty', () => {
    for (const kept of ['text', null, { source: [], target: [] }, { source: 1, target: [], parameters: [] }, { source: [], target: {}, parameters: [] }]) {
      const mock = signatureMock(workingCopy(), memoryBrowserState({ [KEY]: kept }), 'mappings/order');

      expect(mock.getState().value).toMatchObject({ source: [], target: [], parameters: [] });
    }
  });

  test('inserts, moves, patches and removes the messages of one side', () => {
    const mock = signatureMock(workingCopy(), memoryBrowserState(), 'mappings/order');
    const { insertMessage, moveMessage, patchMessage, removeMessage } = signatureActions(mock);
    insertMessage('target', 0, message('a'));
    insertMessage('target', 1, message('c'));
    insertMessage('target', 1, message('b'));
    expect(mock.getState().value.target.map((m) => m.id)).toEqual(['a', 'b', 'c']);

    moveMessage('target', 'c', -1);
    patchMessage('target', 'b', { occurrence: '0..unbounded' });
    removeMessage('target', 'a');

    expect(mock.getState().value.target).toEqual([message('c'), { id: 'b', occurrence: '0..unbounded' }]);
    expect(mock.getState().value.source).toEqual([]);
  });

  test('adds, patches, orders by name and removes the parameters', () => {
    const mock = signatureMock(workingCopy(), memoryBrowserState(), 'mappings/order');
    const { addParameter, patchParameter, sortParameters, removeParameter } = signatureActions(mock);
    addParameter(parameter('p1', 'zone'));
    addParameter(parameter('p2', 'date'));
    addParameter(parameter('p3', 'mode'));

    patchParameter('p1', { type: 'integer' });
    sortParameters();
    removeParameter('p3');

    expect(mock.getState().value.parameters).toEqual([parameter('p2', 'date'), { ...parameter('p1', 'zone'), type: 'integer' }]);
  });
});
