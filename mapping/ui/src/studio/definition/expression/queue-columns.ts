import type { TraceEntry } from '../../../api/client';
import { displayEntry, queueSize, type ShownValue } from './display-queue';
import type { ExpressionGraph, ExpressionNode } from './expression-graph';

/** A cell of the Display Queue (the prototype's queue cells): a value, or where a context ends, before the next or at the end. */
export type QueueCell = { kind: 'value'; value: ShownValue } | { kind: 'boundary' | 'end' };

/** A column of the Display Queue: an input or the output of a node, and its traced queue, or none when it was not traced. */
export interface QueueColumn {
  label: string;
  cells: QueueCell[] | undefined;
  /** The queue's size, "n contexts, n slots"; none for a value or an untraced column. */
  size?: string | undefined;
  /** What a capped queue cut. */
  capped?: string | undefined;
}

/** A column of a trace entry: a queue's values with each context's end after them, or a value; nothing traced, no cells. */
function column(label: string, entry: TraceEntry | undefined): QueueColumn {
  const shown = entry === undefined ? undefined : displayEntry(entry);
  if (shown?.kind === 'atomic') return { label, cells: [{ kind: 'value', value: shown.value }] };
  if (shown?.kind !== 'queue') return { label, cells: undefined };
  const cells = shown.contexts.flatMap((context, i): QueueCell[] => [
    ...context.map((value) => ({ kind: 'value' as const, value })),
    { kind: i === shown.contexts.length - 1 ? 'end' : 'boundary' },
  ]);
  return { label, cells, size: queueSize(entry), capped: shown.capped };
}

/**
 * The Display Queue of `node` of `graph` (the prototype's previewQueue, over the trace of the last test run by authoring id): a
 * column per input, the queue of the box wired into it, then the node's own output; the target field has its result's.
 */
export function queueColumns(graph: ExpressionGraph, node: ExpressionNode, trace: Readonly<Record<string, TraceEntry>>): { name: string; columns: QueueColumn[] } {
  const inputs = node.inputs.map((port) => {
    const wire = graph.edges.find((e) => e.target === node.id && (e.slot ?? 0) === port.slot);
    return column(`${node.name}:${port.label}`, wire && trace[wire.source]);
  });
  return { name: node.name, columns: node.output ? [...inputs, column(`${node.name}:out`, trace[node.id])] : inputs };
}
