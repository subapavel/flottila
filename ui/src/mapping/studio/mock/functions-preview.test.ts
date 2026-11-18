import { expect, test, vi } from 'vitest';
import type { MappingModel } from '../../generated/mapping-model';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore, selectUnsaved } from '../store/working-copy';
import { emptyFunctions, newFunction, type FunctionsState } from './function-model';
import { functionsKey, functionsPreview } from './functions-preview';

const MODEL: MappingModel = { formatVersion: 1 };

function memoryBrowserState(): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

function workingCopy() {
  return createWorkingCopyStore(
    { model: MODEL, etag: '"1"' },
    { newId: () => 'id' as never, save: vi.fn(async (model: MappingModel) => ({ model, etag: '"2"' })), load: async () => ({ model: MODEL, etag: '"3"' }), compile: async () => [] },
  );
}

const withFunction = (s: FunctionsState): FunctionsState => ({ ...s, functions: [newFunction('calculate1', 'f1')] });

test('a mapping without saved functions, or with something else kept, starts empty; one with saved functions starts with them', () => {
  const browserState = memoryBrowserState();
  expect(functionsPreview(workingCopy(), browserState, 'm/a').getState().value).toEqual(emptyFunctions());
  browserState.write(functionsKey('m/a'), { functions: 'not a list' });
  expect(functionsPreview(workingCopy(), browserState, 'm/a').getState().value).toEqual(emptyFunctions());
  browserState.write(functionsKey('m/a'), withFunction(emptyFunctions()));
  expect(functionsPreview(workingCopy(), browserState, 'm/a').getState().value).toEqual(withFunction(emptyFunctions()));
});

test('one draft per open mapping store: an edit is the functions preview draft, and a save keeps it under the mapping id', async () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  const functions = functionsPreview(store, browserState, 'm/a');
  expect(functionsPreview(store, browserState, 'm/a')).toBe(functions);

  functions.getState().edit(withFunction);
  expect(store.getState().previewDrafts).toEqual(['functions']);
  expect(selectUnsaved(store.getState())).toBe(true);
  await store.getState().save();

  expect(browserState.values.get(functionsKey('m/a'))).toEqual(withFunction(emptyFunctions()));
  expect(selectUnsaved(store.getState())).toBe(false);
});
