import { graphlib, layout as dagreLayout } from '@dagrejs/dagre';
import type { BoxPosition, Layout } from '../../../generated/mapping-model';
import { NODE_WIDTH, nodeHeight, TARGET, type ExpressionGraph } from './expression-graph';

/** The top-left corner of each node, by node id, the target's under `TARGET`. */
export type Positions = Record<string, BoxPosition>;

/** The gap between two nodes of a column, and between two columns beside the node width (the prototype's layout). */
const GAP = 24;
const COLUMN = NODE_WIDTH + 64;

/**
 * Lay Out Mapping (ADR-0029): dagre from left to right into the target, the nodes and wires added in the graph's order
 * (sorted authoring ids, input slots in order), so that the same graph always gets the same layout. Rounded.
 */
export function arrange(graph: ExpressionGraph): Positions {
  const g = new graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: GAP, ranksep: 64 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const node of graph.nodes) g.setNode(node.id, { width: NODE_WIDTH, height: nodeHeight(node) });
  for (const edge of graph.edges) g.setEdge(edge.source, edge.target);
  dagreLayout(g);
  return Object.fromEntries(
    graph.nodes.map((node) => {
      const placed = g.node(node.id);
      return [node.id, { x: Math.round(placed.x! - NODE_WIDTH / 2), y: Math.round(placed.y! - nodeHeight(node) / 2) }];
    }),
  );
}

/** A layout's positions by node id: the target's under `TARGET`. */
export function positionsOf(layout: Layout | undefined): Positions {
  return { ...layout?.boxes, ...(layout?.target && { [TARGET]: layout.target }) };
}

/** Positions as a layout: the target's as `target`, the boxes' as `boxes`. */
export function layoutOf(positions: Positions): Layout {
  const { [TARGET]: target, ...boxes } = positions;
  return { ...(target && { target }), boxes };
}

/**
 * Where each node shows (ADR-0029). A mapping without a layout is laid out with dagre in memory, which never changes the
 * working copy. Otherwise each node the layout places stays there, and a node it does not place goes next to its
 * neighbour: left of the first placed node it feeds, else right of the first placed node that feeds it, else below all
 * placed nodes at their left edge; a spot another node's card covers moves it down below that card.
 */
export function shownPositions(graph: ExpressionGraph, layout: Layout | undefined): Positions {
  const kept = positionsOf(layout);
  const placed: Positions = {};
  for (const node of graph.nodes) if (Object.hasOwn(kept, node.id)) placed[node.id] = kept[node.id]!;
  if (Object.keys(placed).length === 0) return arrange(graph);
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const height = (id: string) => nodeHeight(byId.get(id)!);
  for (const node of graph.nodes.filter((n) => !Object.hasOwn(placed, n.id))) {
    const consumer = graph.edges.find((e) => e.source === node.id && Object.hasOwn(placed, e.target))?.target;
    const producer = graph.edges.find((e) => e.target === node.id && Object.hasOwn(placed, e.source))?.source;
    let spot = consumer
      ? { x: placed[consumer]!.x - COLUMN, y: placed[consumer]!.y }
      : producer
        ? { x: placed[producer]!.x + COLUMN, y: placed[producer]!.y }
        : below(placed, height);
    const h = nodeHeight(node);
    const covering = (at: BoxPosition) =>
      Object.entries(placed).find(([other, p]) => Math.abs(p.x - at.x) < NODE_WIDTH && p.y < at.y + h && at.y < p.y + height(other));
    for (let hit = covering(spot); hit; hit = covering(spot)) spot = { x: spot.x, y: hit[1].y + height(hit[0]) + GAP };
    placed[node.id] = spot;
  }
  return placed;
}

/** Below all placed nodes, at their left edge. */
function below(placed: Positions, height: (id: string) => number): BoxPosition {
  const all = Object.entries(placed);
  return { x: Math.min(...all.map(([, p]) => p.x)), y: Math.max(...all.map(([id, p]) => p.y + height(id))) + GAP };
}
