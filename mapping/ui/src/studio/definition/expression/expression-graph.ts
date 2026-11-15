import type { FunctionCatalogue } from '../../../api/client';
import type { AuthoringId, Box, FieldPath, Params, TargetFieldMapping, Udfs } from '../../../generated/mapping-model';
import { stepName } from '../../../model/field-path';
import type { WireEnd } from '../../../model/rewire';

/** The node id of the target field itself; no authoring id has a `:` (ADR-0010). */
export const TARGET = ':target';

/** A box's width and the height of one row of up to three ports (the prototype's `nodeWidth`, `nodeHeight`). */
export const NODE_WIDTH = 164;
const ROW_HEIGHT = 48;

/** An input port of a node: its handle id (`in0`, `in1` …), its label and the input slot it stands for. */
export interface Port {
  id: string;
  label: string;
  slot: number;
}

/** A node of the canvas: a box, or the target field. */
export interface ExpressionNode {
  id: string;
  kind: 'source' | 'function' | 'target';
  /** What the node shows: a field's name, or a function's catalogue name (a UDF's, an unresolved function's). */
  name: string;
  /** The source field a source-field box reads, or the target field. */
  path?: FieldPath;
  /** A source-field box's context, when the model sets one. */
  context?: FieldPath;
  /** The catalogue function of a standard function box; a UDF or unresolved box has none. */
  fn?: string;
  /** A standard function box's parameters as the model holds them. */
  params?: Params;
  inputs: Port[];
  /** Whether the node has an output port: every box has one, the target none. */
  output: boolean;
  /** A preview node (ADR-0028), which the model does not hold: its feature and follow-up issue. */
  preview?: { feature: string; issue: number };
  /** What the node is in words, when its kind does not say it: a mapping variable. */
  role?: string;
}

/** A wire: a box's output into an input slot of another box, or into the target (no slot). */
export interface ExpressionEdge {
  id: string;
  source: AuthoringId;
  target: string;
  slot?: number;
  /** A preview wire, which the model does not hold. */
  preview?: boolean;
}

export interface ExpressionGraph {
  nodes: ExpressionNode[];
  edges: ExpressionEdge[];
}

/** The handle id of input slot `slot`. */
export const portId = (slot: number) => `in${slot}`;

/** The wire end of a node and a handle id: the target, or the slot the handle stands for. */
export function wireEnd(node: string, handle: string | null | undefined): WireEnd {
  return node === TARGET ? 'target' : { box: node, slot: Number((handle ?? portId(0)).slice(2)) };
}

/** A node's height: one row of 48 px per three ports on its busier side (the prototype's `nodeHeight`). */
export const nodeHeight = (node: ExpressionNode) => ROW_HEIGHT * Math.max(1, Math.ceil(node.inputs.length / 3));

/** The input names of a box: the catalogue's or the UDF declaration's, else numbered from 1; at least as many as the model has. */
function inputNames(box: Exclude<Box, { kind: 'source' }>, catalogue: FunctionCatalogue, udfs: Udfs | undefined): string[] {
  const declared =
    box.kind === 'function' ? catalogue[box.fn]?.inputs?.map((input) => input.name) : box.kind === 'udf' ? udfs?.[box.udf]?.inputs : undefined;
  const names = [...(declared ?? [])];
  for (let slot = names.length; slot < (box.inputs?.length ?? 0); slot++) names.push(String(slot + 1));
  return names;
}

function boxNode(id: AuthoringId, box: Box, catalogue: FunctionCatalogue, udfs: Udfs | undefined): ExpressionNode {
  if (box.kind === 'source') return { id, kind: 'source', name: stepName(box.field), path: box.field, context: box.context, inputs: [], output: true };
  const inputs = inputNames(box, catalogue, udfs).map((label, slot) => ({ id: portId(slot), label, slot }));
  const name = box.kind === 'function' ? box.fn : box.kind === 'udf' ? box.udf : box.name;
  return { id, kind: 'function', name, ...(box.kind === 'function' && { fn: box.fn, params: box.params }), inputs, output: true };
}

/**
 * The canvas of one target field mapping (the prototype's graph): a node per box in sorted authoring id order, then the
 * target; a wire per connected input slot, then the `result` into the target. A target field without a mapping shows its
 * target alone.
 */
export function expressionGraph(targetField: FieldPath, mapping: TargetFieldMapping | undefined, catalogue: FunctionCatalogue, udfs?: Udfs): ExpressionGraph {
  const ids = Object.keys(mapping?.boxes ?? {}).sort();
  const nodes = ids.map((id) => boxNode(id, mapping!.boxes[id]!, catalogue, udfs));
  nodes.push({ id: TARGET, kind: 'target', name: stepName(targetField), path: targetField, inputs: [{ id: portId(0), label: stepName(targetField), slot: 0 }], output: false });
  const edges: ExpressionEdge[] = [];
  for (const id of ids) {
    const box = mapping!.boxes[id]!;
    if (box.kind === 'source') continue;
    (box.inputs ?? []).forEach((source, slot) => {
      if (source !== null && Object.hasOwn(mapping!.boxes, source)) edges.push({ id: `${source}->${id}#${slot}`, source, target: id, slot });
    });
  }
  const result = mapping?.result ?? null;
  if (result !== null && Object.hasOwn(mapping!.boxes, result)) edges.push({ id: `${result}->${TARGET}`, source: result, target: TARGET });
  return { nodes, edges };
}

/** A node's status mark (the prototype's legend): complete, incomplete or detached (no path to the target). */
export interface NodeState {
  kind: 'complete' | 'incomplete' | 'detached';
  description: string;
  /** The handle ids of the input ports without a wire. */
  missing: string[];
}

/**
 * Each node's state and the floating boxes (the prototype's `inspectExpression`). Walking from each target: a node with an
 * unconnected input is incomplete, and so is one that reads an incomplete node; the others it reaches are complete. A box
 * it does not reach is detached and floating. A cycle of a hand-written model reads as incomplete.
 */
export function inspectExpression({ nodes, edges }: ExpressionGraph): { states: Map<string, NodeState>; floating: AuthoringId[] } {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const inputs = new Map(nodes.map((n) => [n.id, new Map<string, string>()]));
  const wired = new Set<string>();
  for (const e of edges) {
    inputs.get(e.target)!.set(portId(e.slot ?? 0), e.source);
    wired.add(e.source).add(e.target);
  }
  const states = new Map<string, NodeState>();
  const visiting = new Set<string>();
  const visit = (id: string): NodeState => {
    const known = states.get(id);
    if (known) return known;
    if (visiting.has(id)) return { kind: 'incomplete', description: '', missing: [] };
    visiting.add(id);
    const node = byId.get(id)!;
    const wires = inputs.get(id)!;
    const missing = node.inputs.filter((p) => !wires.has(p.id));
    const upstream = [...wires.values()].filter((source) => visit(source).kind !== 'complete');
    const reason = missing.length
      ? `Connect ${missing.map((p) => p.label).join(', ')}.`
      : upstream.length
        ? `Complete the input from ${byId.get(upstream[0]!)!.name}.`
        : '';
    const state: NodeState = {
      kind: reason ? 'incomplete' : 'complete',
      description: reason ? `Incomplete: ${reason}` : 'Complete: all inputs on this path are connected.',
      missing: missing.map((p) => p.id),
    };
    visiting.delete(id);
    states.set(id, state);
    return state;
  };
  // the target, and the other target fields a grouped expression shows (a preview, #164)
  for (const n of nodes) if (n.kind === 'target') visit(n.id);
  const floating: AuthoringId[] = [];
  for (const n of nodes) {
    if (states.has(n.id)) continue;
    states.set(n.id, {
      kind: 'detached',
      description: wired.has(n.id) ? 'Detached: this branch has no path to the target.' : 'Detached: this object has no connections.',
      missing: [],
    });
    floating.push(n.id);
  }
  return { states, floating };
}

/** A box and every box it reads, directly or through others (the prototype's `upstreamIds`). */
export function upstream({ edges }: ExpressionGraph, root: AuthoringId): Set<AuthoringId> {
  const seen = new Set<AuthoringId>();
  const pending = [root];
  while (pending.length > 0) {
    const id = pending.pop()!;
    if (seen.has(id)) continue;
    seen.add(id);
    for (const e of edges) if (e.target === id) pending.push(e.source);
  }
  return seen;
}
