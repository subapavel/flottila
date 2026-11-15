import { useEffect, useState } from 'react';
import { createStore } from 'zustand/vanilla';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import { readFunctions, type FunctionsState } from './function-model';

/** The key of a mapping's saved Functions tab state in the browser (ADR-0028: mock state per mapping id). */
export const functionsKey = (mappingId: string) => `mapping:${mappingId}:functions-preview`;

/** The name of the Functions tab's draft among the working copy's preview drafts. */
export const FUNCTIONS_PREVIEW = 'functions';

export interface FunctionsStoreState {
  /** The functions as this edit session has them: the saved ones until an edit. */
  functions: FunctionsState;
  /** The functions as last saved, as the browser keeps them. */
  saved: FunctionsState;
  /** Applies `change` to the draft, and tells the working copy whether the draft now differs from the saved functions. */
  edit: (change: (current: FunctionsState) => FunctionsState) => void;
}

/**
 * The Functions tab's mock store of one mapping (ADR-0028, ADR-0030, #169): its user-defined functions, Java areas,
 * imports and archives. The saved state is read from `BrowserStateStore` under the mapping's id; an edit is a draft of
 * the edit session, one of the working copy's preview drafts. Nothing here is written to the mapping model or sent to
 * the `ApiClient`.
 */
export function createFunctionsStore(browserState: BrowserStateStore, mappingId: string, workingCopy: WorkingCopyStore) {
  const saved = readFunctions(browserState.read(functionsKey(mappingId)));
  return createStore<FunctionsStoreState>()((set, get) => ({
    functions: saved,
    saved,
    edit: (change) => {
      const functions = change(get().functions);
      set({ functions });
      workingCopy.getState().setPreviewDraft(FUNCTIONS_PREVIEW, JSON.stringify(functions) !== JSON.stringify(get().saved));
    },
  }));
}

export type FunctionsStore = ReturnType<typeof createFunctionsStore>;

/**
 * Makes the draft follow the edit session: a successful save keeps it in the browser as the saved functions (when it
 * changed since the last save), a discard or reload goes back to the saved functions. Returns the unsubscribe.
 */
export function followEditSession(functions: FunctionsStore, workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): () => void {
  return workingCopy.subscribe((state, previous) => {
    const { functions: draft, saved } = functions.getState();
    if (state.commits !== previous.commits && draft !== saved) {
      functions.setState({ saved: draft });
      browserState.write(functionsKey(mappingId), draft);
    }
    if (state.reverts !== previous.reverts) functions.setState({ functions: saved });
  });
}

/** The functions store of the open mapping, for as long as the studio is open, following its edit session. */
export function useFunctionsPreview(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): FunctionsStore {
  const [functions] = useState(() => createFunctionsStore(browserState, mappingId, workingCopy));
  useEffect(() => followEditSession(functions, workingCopy, browserState, mappingId), [functions, workingCopy, browserState, mappingId]);
  return functions;
}
