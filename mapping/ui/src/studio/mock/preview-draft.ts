import { createStore, type StoreApi } from 'zustand/vanilla';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';

/** How a preview feature keeps its state: its name among the preview drafts, its browser key and its reader. */
export interface PreviewDraftOptions<T> {
  /** The feature's name among the working copy's preview drafts. */
  name: string;
  /** The key of its saved state in the browser state, per mapping id (ADR-0028). */
  key: string;
  /** The saved state from what the browser keeps under the key; anything it does not accept reads as the empty state. */
  read: (kept: unknown) => T;
}

export interface PreviewDraftState<T> {
  /** The state as this edit session has it: the saved one until an edit. */
  value: T;
  /** The state as last saved, as the browser keeps it. */
  saved: T;
  /** Applies `change` to the draft, and tells the working copy whether the draft now differs from the saved state. */
  edit: (change: (current: T) => T) => void;
}

export type PreviewDraftStore<T> = StoreApi<PreviewDraftState<T>>;

/**
 * The state of one preview feature of one mapping (ADR-0028), a draft of the edit session: an edit makes the studio
 * unsaved, a successful save keeps the draft in the browser as the saved state (when it changed since), a discard or a
 * reload goes back to the saved state, and a refused save or a save conflict keeps the draft. Nothing here is written to
 * the mapping model or sent to the `ApiClient`.
 */
export function createPreviewDraft<T>(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, { name, key, read }: PreviewDraftOptions<T>): PreviewDraftStore<T> {
  const saved = read(browserState.read(key));
  const draft = createStore<PreviewDraftState<T>>()((set, get) => ({
    value: saved,
    saved,
    edit: (change) => {
      const value = change(get().value);
      set({ value });
      workingCopy.getState().setPreviewDraft(name, JSON.stringify(value) !== JSON.stringify(get().saved));
    },
  }));
  workingCopy.subscribe((state, previous) => {
    const { value, saved: last } = draft.getState();
    if (state.commits !== previous.commits && value !== last) {
      draft.setState({ saved: value });
      browserState.write(key, value);
    }
    if (state.reverts !== previous.reverts) draft.setState({ value: last });
  });
  return draft;
}

const kept = new WeakMap<WorkingCopyStore, Map<string, unknown>>();

/**
 * What `make` makes for the open mapping's store under `key`, made on first use: one per working copy store and key, so
 * that it lives as long as the studio's store, not as long as the tab that shows it.
 */
export function perWorkingCopy<T>(workingCopy: WorkingCopyStore, key: string, make: () => T): T {
  let byKey = kept.get(workingCopy);
  if (!byKey) {
    byKey = new Map();
    kept.set(workingCopy, byKey);
  }
  if (!byKey.has(key)) byKey.set(key, make());
  return byKey.get(key) as T;
}

/** The draft of a preview feature for the open mapping's store, one per working copy store and key (`perWorkingCopy`). */
export function previewDraft<T>(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, options: PreviewDraftOptions<T>): PreviewDraftStore<T> {
  return perWorkingCopy(workingCopy, options.key, () => createPreviewDraft(workingCopy, browserState, options));
}
