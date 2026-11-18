import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import { readFunctions, type FunctionsState } from './function-model';
import { previewDraft, type PreviewDraftStore } from './preview-draft';

/** The key of a mapping's saved Functions tab state in the browser. */
export const functionsKey = (mappingId: string) => `mapping:${mappingId}:functions-preview`;

/** The name of the Functions tab's draft among the working copy's preview drafts. */
export const FUNCTIONS_PREVIEW = 'functions';

/** The follow-up issue of user-defined functions, a preview feature. */
export const FUNCTIONS_ISSUE = 169;

export type FunctionsStore = PreviewDraftStore<FunctionsState>;

/**
 * The functions of the open mapping: its user-defined functions, Java areas, imports and archives, a draft of the edit
 * session (`previewDraft`) whose saved state the browser keeps under the mapping's id. They never reach the working
 * copy or the `ApiClient`.
 */
export function functionsPreview(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): FunctionsStore {
  return previewDraft(workingCopy, browserState, { name: FUNCTIONS_PREVIEW, key: functionsKey(mappingId), read: readFunctions });
}
