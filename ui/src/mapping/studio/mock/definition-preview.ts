import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import type { BoxPosition, FieldPath, ParamValue } from '../../generated/mapping-model';
import { previewDraft, type PreviewDraftStore } from './preview-draft';

/** The follow-up issue of the mapping variables, a preview feature. */
export const VARIABLES_ISSUE = 163;

/** A parameter value of a preview node: text, a switch or a list of field names, or a catalogue function's table. */
export type PreviewParam = string | boolean | string[] | ParamValue;

/** What a preview node is: a variable read, a source field, a function, another target field. */
export type PreviewNodeKind = 'variable' | 'source' | 'function' | 'target';

/**
 * A node of a canvas that the model does not hold: a variable read (`ref` the variable path), a source field read in a
 * variable's expression (`ref` the field), a function (`ref` a catalogue name, or a preview function's key), or another
 * target field of a grouped expression (`ref` the field).
 */
export interface PreviewNode {
  id: string;
  kind: PreviewNodeKind;
  ref: string;
  /** Where the node shows; absent until it is moved, when the canvas places it below the others. */
  position?: BoxPosition;
  params?: Record<string, PreviewParam>;
}

/** A wire with a preview node at one end or both: from `source`'s output into input `slot` of `target`. */
export interface PreviewWire {
  id: string;
  source: string;
  target: string;
  slot: number;
}

/** The preview nodes and wires of one canvas, and where a variable's own target shows. */
export interface PreviewCanvas {
  nodes: PreviewNode[];
  wires: PreviewWire[];
  target?: BoxPosition;
}

export interface DefinitionPreviewData {
  /** The variables of the target tree in tree order: `<parent>/$<name>`, the newest of a parent first. */
  variables: FieldPath[];
  /** The preview nodes and wires by the target field (or variable) whose canvas shows them. */
  canvases: Record<FieldPath, PreviewCanvas>;
}

/**
 * A variable's path is its parent's and the step `$<name>`; a JSON property may start with
 * `$` too, so a path is a variable's only when the previews list it, never by its spelling.
 */
const parentOf = (path: FieldPath) => path.slice(0, path.lastIndexOf('/'));

/** A variable's name: its last step without the `$`. */
export const variableName = (path: FieldPath) => path.slice(path.lastIndexOf('/') + 2);

/** Adds variable `$<name>` under `parent`, first among its variables; a name it has already changes nothing. */
export function addVariable(data: DefinitionPreviewData, parent: FieldPath, name: string): DefinitionPreviewData {
  const path = `${parent}/$${name}`;
  if (data.variables.includes(path)) return data;
  const at = data.variables.findIndex((v) => parentOf(v) === parent);
  const variables = [...data.variables];
  variables.splice(at < 0 ? variables.length : at, 0, path);
  return { ...data, variables };
}

/** The key of a mapping's Definition previews in the browser state. */
export const definitionPreviewKey = (mappingId: string) => `mapping:${mappingId}:preview:definition`;

/** The name of the Definition previews' draft among the working copy's preview drafts. */
export const DEFINITION_PREVIEW = 'definition';

const isData = (value: unknown): value is DefinitionPreviewData => {
  const data = value as Partial<DefinitionPreviewData> | null;
  return (
    typeof data === 'object' &&
    data !== null &&
    Array.isArray(data.variables) &&
    data.variables.every((v) => typeof v === 'string') &&
    typeof data.canvases === 'object' &&
    data.canvases !== null &&
    Object.values(data.canvases).every((c) => Array.isArray(c?.nodes) && Array.isArray(c.wires))
  );
};

export type DefinitionPreviewStore = PreviewDraftStore<DefinitionPreviewData>;

/**
 * The Definition tab's previews of the open mapping: the variables, and the nodes and wires of the canvases, a draft of
 * the edit session whose saved state the browser keeps under the mapping's id. Nothing here reaches the working copy or
 * the `ApiClient`.
 */
export function definitionPreview(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): DefinitionPreviewStore {
  return previewDraft(workingCopy, browserState, {
    name: DEFINITION_PREVIEW,
    key: definitionPreviewKey(mappingId),
    read: (kept): DefinitionPreviewData => (isData(kept) ? kept : { variables: [], canvases: {} }),
  });
}
