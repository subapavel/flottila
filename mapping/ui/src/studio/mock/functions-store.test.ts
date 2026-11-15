import { expect, test, vi } from 'vitest';
import { ApiProblem } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore, selectUnsaved } from '../store/working-copy';
import type { MappingModel } from '../../generated/mapping-model';
import { emptyFunctions, newFunction, type FunctionsState } from './function-model';
import { createFunctionsStore, followEditSession, functionsKey } from './functions-store';

const MODEL: MappingModel = { formatVersion: 1 };

function memoryBrowserState(): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

function workingCopy(save = vi.fn(async (model: MappingModel) => ({ model, etag: '"2"' }))) {
  return createWorkingCopyStore(
    { model: MODEL, etag: '"1"' },
    { newId: () => 'id' as never, save, load: async () => ({ model: MODEL, etag: '"3"' }), compile: async () => [] },
  );
}

/** A functions store of mapping `m/a` following `store`'s edit session, over `browserState`. */
function following(browserState: BrowserStateStore, store = workingCopy()) {
  const functions = createFunctionsStore(browserState, 'm/a', store);
  followEditSession(functions, store, browserState, 'm/a');
  return { functions, store };
}

const withFunction = (s: FunctionsState): FunctionsState => ({ ...s, functions: [newFunction('calculate1', 'f1')] });

test('a mapping without saved functions starts empty; one with saved functions starts with them', () => {
  const browserState = memoryBrowserState();
  expect(createFunctionsStore(browserState, 'm/a', workingCopy()).getState().functions).toEqual(emptyFunctions());
  browserState.write(functionsKey('m/a'), withFunction(emptyFunctions()));
  expect(createFunctionsStore(browserState, 'm/a', workingCopy()).getState().functions).toEqual(withFunction(emptyFunctions()));
});

test('an edit is a draft: the working copy is unsaved, the browser keeps nothing; an edit back to the saved functions is none', () => {
  const browserState = memoryBrowserState();
  const { functions, store } = following(browserState);

  functions.getState().edit(withFunction);
  expect(store.getState().previewDrafts).toEqual(['functions']);
  expect(selectUnsaved(store.getState())).toBe(true);
  expect(browserState.values.size).toBe(0);

  functions.getState().edit((s) => ({ ...s, functions: [] }));
  expect(store.getState().previewDrafts).toEqual([]);
  expect(selectUnsaved(store.getState())).toBe(false);
});

test('a successful save keeps the draft in the browser under the mapping id as the saved functions', async () => {
  const browserState = memoryBrowserState();
  const { functions, store } = following(browserState);
  functions.getState().edit(withFunction);

  await store.getState().save();

  expect(browserState.values.get(functionsKey('m/a'))).toEqual(withFunction(emptyFunctions()));
  expect(functions.getState().saved).toBe(functions.getState().functions);
  expect(selectUnsaved(store.getState())).toBe(false);
  expect(createFunctionsStore(browserState, 'm/b', workingCopy()).getState().functions).toEqual(emptyFunctions());
});

test('a save without a function edit since the last save writes nothing', async () => {
  const browserState = memoryBrowserState();
  const { store } = following(browserState);

  await store.getState().save();

  expect(browserState.values.size).toBe(0);
});

test('a refused save and a save conflict keep the draft and write nothing', async () => {
  const refused = new ApiProblem({ type: 'urn:flottila:mapping:problem:internal', title: 'Internal error', status: 500, detail: 'disk full' });
  const conflict = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
  const browserState = memoryBrowserState();
  const { functions, store } = following(browserState, workingCopy(vi.fn().mockRejectedValueOnce(refused).mockRejectedValueOnce(conflict)));
  functions.getState().edit(withFunction);

  await expect(store.getState().save()).rejects.toBe(refused);
  await store.getState().save();

  expect(store.getState().conflict).toBe(true);
  expect(functions.getState().functions).toEqual(withFunction(emptyFunctions()));
  expect(store.getState().previewDrafts).toEqual(['functions']);
  expect(browserState.values.size).toBe(0);
});

test.each([
  ['discard', (store: ReturnType<typeof workingCopy>) => Promise.resolve(store.getState().discard())],
  ['reload', (store: ReturnType<typeof workingCopy>) => store.getState().reload()],
])('%s goes back to the saved functions', async (_, drop) => {
  const browserState = memoryBrowserState();
  const saved = withFunction(emptyFunctions());
  browserState.write(functionsKey('m/a'), saved);
  const { functions, store } = following(browserState);
  functions.getState().edit((s) => ({ ...s, functions: [] }));

  await drop(store);

  expect(functions.getState().functions).toEqual(saved);
  expect(selectUnsaved(store.getState())).toBe(false);
  expect(browserState.values.get(functionsKey('m/a'))).toEqual(saved);
});

test('an edit that throws changes nothing', () => {
  const browserState = memoryBrowserState();
  const { functions, store } = following(browserState);

  expect(() =>
    functions.getState().edit(() => {
      throw new Error('refused');
    }),
  ).toThrow('refused');

  expect(functions.getState().functions).toEqual(emptyFunctions());
  expect(store.getState().previewDrafts).toEqual([]);
});

test('followEditSession returns its unsubscribe', async () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  const functions = createFunctionsStore(browserState, 'm/a', store);
  followEditSession(functions, store, browserState, 'm/a')();
  functions.getState().edit(withFunction);

  await store.getState().save();

  expect(browserState.values.size).toBe(0);
});
