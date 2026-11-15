import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import type { BoxPosition, FieldPath, ParamValue } from '../../generated/mapping-model';
import { previewDraft, type PreviewDraftStore } from './preview-draft';

/** The follow-up issues of the Definition tab's preview features (spec #152, #162). */
export const VARIABLES_ISSUE = 163;
export const GROUPED_ISSUE = 164;
export const LOOKUPS_ISSUE = 165;
export const VALUE_MAPPING_ISSUE = 166;

/** A parameter value of a preview node: text, a switch or a list of field names, or a catalogue function's table. */
export type PreviewParam = string | boolean | string[] | ParamValue;

/** What a preview node is (the prototype's node kinds): a variable read, a source field, a function, another target field. */
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

/** The id prefix of a preview node; no authoring id has a `:` (ADR-0010). */
export const PREVIEW_PREFIX = 'preview:';
export const isPreviewId = (id: string) => id.startsWith(PREVIEW_PREFIX);
export const newPreviewId = () => `${PREVIEW_PREFIX}${crypto.randomUUID()}`;

/**
 * A variable's path is its parent's and the step `$<name>` (the prototype's reserved `$`); a JSON property may start with
 * `$` too, so a path is a variable's only when the previews list it, never by its spelling.
 */
const parentOf = (path: FieldPath) => path.slice(0, path.lastIndexOf('/'));

/** A variable's name: its last step without the `$`. */
export const variableName = (path: FieldPath) => path.slice(path.lastIndexOf('/') + 2);

/** Whether `target` is within a variable's scope: the following siblings in its block and their descendants. */
const inScope = (variable: FieldPath, target: FieldPath) => target.startsWith(`${parentOf(variable)}/`);

/**
 * The variables a target field or variable may read (the prototype's availableVariables): those in scope; a variable
 * reads only those before it, as a parent's variables come first among its children in the tree.
 */
export function availableVariables(variables: readonly FieldPath[], target: FieldPath): FieldPath[] {
  const at = variables.indexOf(target);
  return (at < 0 ? variables : variables.slice(0, at)).filter((v) => inScope(v, target));
}

/** Where a variable may be read (the prototype's variableTargets): the variables after it and the target fields in its scope. */
export function variableTargets(variables: readonly FieldPath[], fields: readonly FieldPath[], variable: FieldPath): FieldPath[] {
  return [...variables.slice(variables.indexOf(variable) + 1), ...fields].filter((target) => inScope(variable, target));
}

const EMPTY: PreviewCanvas = { nodes: [], wires: [] };
export const canvasOf = (data: DefinitionPreviewData, key: FieldPath): PreviewCanvas => data.canvases[key] ?? EMPTY;

function change(data: DefinitionPreviewData, key: FieldPath, edit: (canvas: PreviewCanvas) => PreviewCanvas): DefinitionPreviewData {
  return { ...data, canvases: { ...data.canvases, [key]: edit(canvasOf(data, key)) } };
}

/** Adds variable `$<name>` under `parent`, first among its variables; a name it has already changes nothing. */
export function addVariable(data: DefinitionPreviewData, parent: FieldPath, name: string): DefinitionPreviewData {
  const path = `${parent}/$${name}`;
  if (data.variables.includes(path)) return data;
  const at = data.variables.findIndex((v) => parentOf(v) === parent);
  const variables = [...data.variables];
  variables.splice(at < 0 ? variables.length : at, 0, path);
  return { ...data, variables };
}

export const addNode = (data: DefinitionPreviewData, key: FieldPath, node: PreviewNode) => change(data, key, (c) => ({ ...c, nodes: [...c.nodes, node] }));

/** Moves preview nodes, and a variable's own target under `target`. */
export function moveNodes(data: DefinitionPreviewData, key: FieldPath, positions: Readonly<Record<string, BoxPosition>>, target?: BoxPosition): DefinitionPreviewData {
  return change(data, key, (c) => ({ ...c, nodes: c.nodes.map((n) => (positions[n.id] ? { ...n, position: positions[n.id]! } : n)), ...(target && { target }) }));
}

/** Removes preview nodes and every wire into or out of them, or of `ids` that name model boxes. */
export function removeNodes(data: DefinitionPreviewData, key: FieldPath, ids: readonly string[]): DefinitionPreviewData {
  return change(data, key, (c) => ({ ...c, nodes: c.nodes.filter((n) => !ids.includes(n.id)), wires: c.wires.filter((w) => !ids.includes(w.source) && !ids.includes(w.target)) }));
}

/** Removes every preview wire into or out of node `id`, a preview node or a model box. */
export const disconnectNode = (data: DefinitionPreviewData, key: FieldPath, id: string) =>
  change(data, key, (c) => ({ ...c, wires: c.wires.filter((w) => w.source !== id && w.target !== id) }));

export const addWire = (data: DefinitionPreviewData, key: FieldPath, wire: PreviewWire) => change(data, key, (c) => ({ ...c, wires: [...c.wires, wire] }));

export const removeWire = (data: DefinitionPreviewData, key: FieldPath, id: string) => change(data, key, (c) => ({ ...c, wires: c.wires.filter((w) => w.id !== id) }));

export function setParams(data: DefinitionPreviewData, key: FieldPath, id: string, params: Record<string, PreviewParam>): DefinitionPreviewData {
  return change(data, key, (c) => ({ ...c, nodes: c.nodes.map((n) => (n.id === id ? { ...n, params } : n)) }));
}

/** The key of a mapping's Definition previews in the browser state (ADR-0028: mock state per mapping id). */
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
 * The Definition tab's previews of the open mapping (ADR-0028): variables (#163), grouped targets (#164), lookups and
 * value mapping (#165, #166), and every node and wire of theirs, a draft of the edit session whose saved state the
 * browser keeps under the mapping's id. Nothing here reaches the working copy or the `ApiClient`.
 */
export function definitionPreview(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): DefinitionPreviewStore {
  return previewDraft(workingCopy, browserState, {
    name: DEFINITION_PREVIEW,
    key: definitionPreviewKey(mappingId),
    read: (kept): DefinitionPreviewData => (isData(kept) ? kept : { variables: [], canvases: {} }),
  });
}
