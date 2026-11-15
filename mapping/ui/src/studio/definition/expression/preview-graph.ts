import type { FunctionCatalogue } from '../../../api/client';
import type { Params } from '../../../generated/mapping-model';
import { stepName } from '../../../model/field-path';
import { GROUPED_ISSUE, VARIABLES_ISSUE, variableName, type PreviewCanvas, type PreviewNode } from '../../mock/definition-preview';
import { previewFunction } from '../../mock/preview-functions';
import { nodeHeight, portId, TARGET, type ExpressionGraph, type ExpressionNode } from './expression-graph';
import type { Positions } from './expression-layout';

/** The gap between a placed preview node and the node above it (the layout's gap). */
const GAP = 24;

/** The Preview mark of a variable's nodes. */
export const VARIABLES = { feature: 'Variables', issue: VARIABLES_ISSUE };

/** What a node that reads or defines a mapping variable is, in words (the prototype's nodeRole). */
export const VARIABLE_ROLE = 'Mapping variable';

/** A preview node as a canvas node (the prototype's node kinds), with its feature's Preview mark. */
function canvasNode(node: PreviewNode, catalogue: FunctionCatalogue): ExpressionNode {
  const { id, kind, ref } = node;
  const params = node.params as Params | undefined;
  if (kind === 'target') {
    return { id, kind, name: stepName(ref), path: ref, inputs: [{ id: portId(0), label: stepName(ref), slot: 0 }], output: false, preview: { feature: 'Grouped expressions', issue: GROUPED_ISSUE } };
  }
  if (kind === 'variable') return { id, kind: 'source', name: variableName(ref), path: ref, inputs: [], output: true, preview: VARIABLES, role: VARIABLE_ROLE };
  if (kind === 'source') return { id, kind, name: stepName(ref), path: ref, inputs: [], output: true, preview: VARIABLES };
  const stand = previewFunction(ref);
  if (stand) {
    const inputs = stand.inputs.map((label, slot) => ({ id: portId(slot), label, slot }));
    const feature = stand.key === 'valuemap' ? 'Value mapping' : 'Lookups';
    return { id, kind, name: stand.name, inputs, output: stand.output, ...(params && { params }), preview: { feature, issue: stand.issue } };
  }
  // a catalogue function in a variable's expression
  const inputs = (catalogue[ref]?.inputs ?? []).map((input, slot) => ({ id: portId(slot), label: input.name, slot }));
  return { id, kind, name: ref, fn: ref, inputs, output: true, ...(params && { params }), preview: VARIABLES };
}

/**
 * Where each node shows with the preview nodes: the model's nodes where `positions` puts them, a moved preview node where
 * it was put, and the others below every node in the order they were added, a target field under the target and the
 * rest at the left edge.
 */
export function previewPositions(graph: ExpressionGraph, positions: Positions, canvas: PreviewCanvas): Positions {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const all: Positions = { ...positions };
  for (const n of canvas.nodes) if (n.position) all[n.id] = n.position;
  for (const n of canvas.nodes) {
    if (n.position) continue;
    const spots = Object.entries(all);
    const y = Math.max(...spots.map(([id, p]) => p.y + nodeHeight(byId.get(id)!))) + GAP;
    all[n.id] = { x: n.kind === 'target' ? all[TARGET]!.x : Math.min(...spots.map(([, p]) => p.x)), y };
  }
  return all;
}

/**
 * The canvas with its preview nodes and wires (ADR-0028): the model's nodes and wires, then the preview nodes, then each
 * preview wire whose ends both show.
 */
export function withPreview(graph: ExpressionGraph, canvas: PreviewCanvas, catalogue: FunctionCatalogue): ExpressionGraph {
  const nodes = [...graph.nodes, ...canvas.nodes.map((n) => canvasNode(n, catalogue))];
  const ids = new Set(nodes.map((n) => n.id));
  const wires = canvas.wires.filter((w) => ids.has(w.source) && ids.has(w.target)).map((w) => ({ ...w, preview: true }));
  return { nodes, edges: [...graph.edges, ...wires] };
}
