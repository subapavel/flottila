import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import type { MessageChoice } from '../shared/structure-names';
import { previewDraft, type PreviewDraftStore } from './preview-draft';

/** The follow-up issue of several source or target messages (spec #152, preview features). */
export const SEVERAL_MESSAGES_ISSUE = 168;
/** The follow-up issue of mapping parameters (spec #152, preview features). */
export const PARAMETERS_ISSUE = 167;

export type Side = 'source' | 'target';

/** A message's occurrence in a multi-message signature, as the prototype offers them. */
export const OCCURRENCES = ['1', '0..1', '0..unbounded'] as const;
export type Occurrence = (typeof OCCURRENCES)[number];

/** A source or target message after the first: a preview feature (#168), kept in the browser and never in the file. */
export interface PreviewMessage {
  id: string;
  /** The message chosen for the row; none until one is chosen. */
  choice?: MessageChoice;
  occurrence: Occurrence;
}

/** A mapping parameter: a preview feature (#167), kept in the browser and never in the file. */
export interface PreviewParameter {
  id: string;
  name: string;
  type: 'string' | 'integer';
  direction: 'Import' | 'Export';
  description: string;
}

export interface SignatureMockData {
  source: PreviewMessage[];
  target: PreviewMessage[];
  parameters: PreviewParameter[];
}

export type SignatureMockStore = PreviewDraftStore<SignatureMockData>;

/** The key of a mapping's preview signature in the browser state (ADR-0028: mock state per mapping id). */
export const signatureMockKey = (mappingId: string) => `mapping:${mappingId}:preview:signature`;

/** The name of the preview signature's draft among the working copy's preview drafts. */
export const SIGNATURE_PREVIEW = 'signature';

/** The prototype's moveRow: the rows with `from` swapped with its neighbour `delta` away, or the same rows at an end. */
export function moveRow<T>(rows: readonly T[], from: number, delta: number): readonly T[] {
  const to = from + delta;
  if (from < 0 || from >= rows.length || to < 0 || to >= rows.length) return rows;
  const next = rows.slice();
  [next[from], next[to]] = [next[to]!, next[from]!];
  return next;
}

/** Whether a value read back from the browser has the three lists; anything else starts empty. */
function isData(value: unknown): value is SignatureMockData {
  const data = value as Partial<SignatureMockData> | null;
  return typeof data === 'object' && data !== null && Array.isArray(data.source) && Array.isArray(data.target) && Array.isArray(data.parameters);
}

/**
 * The preview signature of the open mapping (ADR-0028): the messages after the first of each side and the parameters, a
 * draft of the edit session (`previewDraft`) whose saved state the browser keeps under the mapping's id. It never
 * reaches the working copy or the `ApiClient`.
 */
export function signatureMock(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): SignatureMockStore {
  return previewDraft(workingCopy, browserState, {
    name: SIGNATURE_PREVIEW,
    key: signatureMockKey(mappingId),
    read: (kept): SignatureMockData => (isData(kept) ? kept : { source: [], target: [], parameters: [] }),
  });
}

/** The edits of the preview signature, each a change of its draft. */
export function signatureActions(mock: SignatureMockStore) {
  const edit = (change: (data: SignatureMockData) => SignatureMockData) => mock.getState().edit(change);
  const messages = (side: Side, change: (rows: PreviewMessage[]) => readonly PreviewMessage[]) => edit((data) => ({ ...data, [side]: change(data[side]) }));
  const parameters = (change: (rows: PreviewParameter[]) => PreviewParameter[]) => edit((data) => ({ ...data, parameters: change(data.parameters) }));
  return {
    /** Inserts a message at `index` of the side's preview messages. */
    insertMessage: (side: Side, index: number, message: PreviewMessage) => messages(side, (rows) => [...rows.slice(0, index), message, ...rows.slice(index)]),
    removeMessage: (side: Side, id: string) => messages(side, (rows) => rows.filter((row) => row.id !== id)),
    /** Swaps a message with its neighbour `delta` places away; at an end it stays. */
    moveMessage: (side: Side, id: string, delta: -1 | 1) =>
      messages(side, (rows) =>
        moveRow(
          rows,
          rows.findIndex((row) => row.id === id),
          delta,
        ),
      ),
    patchMessage: (side: Side, id: string, patch: Partial<Omit<PreviewMessage, 'id'>>) => messages(side, (rows) => rows.map((row) => (row.id === id ? { ...row, ...patch } : row))),
    addParameter: (parameter: PreviewParameter) => parameters((rows) => [...rows, parameter]),
    patchParameter: (id: string, patch: Partial<Omit<PreviewParameter, 'id'>>) => parameters((rows) => rows.map((p) => (p.id === id ? { ...p, ...patch } : p))),
    removeParameter: (id: string) => parameters((rows) => rows.filter((p) => p.id !== id)),
    /** Orders the parameters by name (the prototype's Refresh). */
    sortParameters: () => parameters((rows) => [...rows].sort((a, b) => a.name.localeCompare(b.name))),
  };
}
