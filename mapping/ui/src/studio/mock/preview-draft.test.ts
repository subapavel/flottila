import { expect, test, vi } from 'vitest';
import { ApiProblem } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore, selectUnsaved } from '../store/working-copy';
import type { MappingModel } from '../../generated/mapping-model';
import { createPreviewDraft, previewDraft, type PreviewDraftOptions } from './preview-draft';

const MODEL: MappingModel = { formatVersion: 1 };
const KEY = 'mapping:m/a:preview:test';

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

/** A list of names, read back as an array of strings or else empty. */
const names: PreviewDraftOptions<string[]> = {
  name: 'names',
  key: KEY,
  read: (kept) => (Array.isArray(kept) && kept.every((n) => typeof n === 'string') ? kept : []),
};

test('a draft starts from what the browser keeps under its key, read through its reader', () => {
  const browserState = memoryBrowserState();
  expect(createPreviewDraft(workingCopy(), browserState, names).getState().value).toEqual([]);
  browserState.write(KEY, ['a']);
  expect(createPreviewDraft(workingCopy(), browserState, names).getState().value).toEqual(['a']);
  browserState.write(KEY, [1]);
  expect(createPreviewDraft(workingCopy(), browserState, names).getState().value).toEqual([]);
});

test('an edit is a draft: the working copy is unsaved, the browser keeps nothing; an edit back to the saved value is none', () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  const draft = createPreviewDraft(store, browserState, names);

  draft.getState().edit((n) => [...n, 'a']);
  expect(store.getState().previewDrafts).toEqual(['names']);
  expect(selectUnsaved(store.getState())).toBe(true);
  expect(browserState.values.size).toBe(0);

  draft.getState().edit(() => []);
  expect(store.getState().previewDrafts).toEqual([]);
  expect(selectUnsaved(store.getState())).toBe(false);
});

test('a successful save keeps the draft in the browser as the saved value', async () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  const draft = createPreviewDraft(store, browserState, names);
  draft.getState().edit(() => ['a']);

  await store.getState().save();

  expect(browserState.values.get(KEY)).toEqual(['a']);
  expect(draft.getState().saved).toBe(draft.getState().value);
  expect(selectUnsaved(store.getState())).toBe(false);
});

test('a save without an edit since the last save writes nothing', async () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  createPreviewDraft(store, browserState, names);

  await store.getState().save();

  expect(browserState.values.size).toBe(0);
});

test('a refused save and a save conflict keep the draft and write nothing', async () => {
  const refused = new ApiProblem({ type: 'urn:flottila:mapping:problem:internal', title: 'Internal error', status: 500, detail: 'disk full' });
  const conflict = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
  const browserState = memoryBrowserState();
  const store = workingCopy(vi.fn().mockRejectedValueOnce(refused).mockRejectedValueOnce(conflict));
  const draft = createPreviewDraft(store, browserState, names);
  draft.getState().edit(() => ['a']);

  await expect(store.getState().save()).rejects.toBe(refused);
  await store.getState().save();

  expect(store.getState().conflict).toBe(true);
  expect(draft.getState().value).toEqual(['a']);
  expect(store.getState().previewDrafts).toEqual(['names']);
  expect(browserState.values.size).toBe(0);
});

test.each([
  ['discard', (store: ReturnType<typeof workingCopy>) => Promise.resolve(store.getState().discard())],
  ['reload', (store: ReturnType<typeof workingCopy>) => store.getState().reload()],
])('%s goes back to the saved value', async (_, drop) => {
  const browserState = memoryBrowserState();
  browserState.write(KEY, ['saved']);
  const store = workingCopy();
  const draft = createPreviewDraft(store, browserState, names);
  draft.getState().edit(() => ['changed']);

  await drop(store);

  expect(draft.getState().value).toEqual(['saved']);
  expect(selectUnsaved(store.getState())).toBe(false);
  expect(browserState.values.get(KEY)).toEqual(['saved']);
});

test('an edit that throws changes nothing', () => {
  const store = workingCopy();
  const draft = createPreviewDraft(store, memoryBrowserState(), names);

  expect(() =>
    draft.getState().edit(() => {
      throw new Error('refused');
    }),
  ).toThrow('refused');

  expect(draft.getState().value).toEqual([]);
  expect(store.getState().previewDrafts).toEqual([]);
});

test('previewDraft gives one draft per working copy and key, so that a draft outlives the tab that shows it', () => {
  const browserState = memoryBrowserState();
  const store = workingCopy();
  const draft = previewDraft(store, browserState, names);
  draft.getState().edit(() => ['a']);

  expect(previewDraft(store, browserState, names)).toBe(draft);
  expect(previewDraft(store, browserState, { ...names, key: 'other' })).not.toBe(draft);
  expect(previewDraft(workingCopy(), browserState, names).getState().value).toEqual([]);
});
