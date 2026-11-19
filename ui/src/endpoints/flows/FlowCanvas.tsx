import { Icon } from '@patternfly/react-core';
import { ExclamationCircleIcon, ExclamationTriangleIcon, LockIcon } from '@patternfly/react-icons';
import { Background, Handle, MarkerType, Position, ReactFlow, type Edge, type Node, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { memo, useMemo, type CSSProperties } from 'react';
import type { FlowEdge, FlowNode, FlowProblem, FlowView } from './types';
import { pointer } from './view';

/** React Flow's look in PatternFly's tokens. */
const FLOW_STYLE = {
  '--xy-background-color': 'var(--pf-t--global--background--color--secondary--default)',
  '--xy-edge-stroke': 'var(--pf-t--global--border--color--default)',
  '--xy-edge-stroke-selected': 'var(--pf-t--global--border--color--clicked)',
  '--xy-edge-label-color': 'var(--pf-t--global--text--color--regular)',
  '--xy-edge-label-background-color': 'var(--pf-t--global--background--color--primary--default)',
  '--xy-node-border-radius': 'var(--pf-t--global--border--radius--small)',
  '--xy-node-boxshadow-selected': 'none',
  height: '100%',
} as CSSProperties;

/** What a node of the canvas shows: its node of the view, its problems, and whether it is selected. */
type CanvasData = { node: FlowNode; problems: FlowProblem[]; selected: boolean };
type CanvasNode = Node<CanvasData, 'step' | 'frame'>;

/** The handles every node has, hidden: edges leave at the bottom and arrive at the top, and those drawn beside go sideways. */
function Handles() {
  const hidden = { opacity: 0 } as const;
  return (
    <>
      <Handle type="target" position={Position.Top} isConnectable={false} style={hidden} />
      <Handle type="source" position={Position.Bottom} isConnectable={false} style={hidden} />
      <Handle id="side" type="source" position={Position.Right} isConnectable={false} style={hidden} />
      <Handle id="side" type="target" position={Position.Left} isConnectable={false} style={hidden} />
    </>
  );
}

const box = (selected: boolean, dashed: boolean): CSSProperties => ({
  width: '100%',
  height: '100%',
  boxSizing: 'border-box',
  padding: 'var(--pf-t--global--spacer--xs) var(--pf-t--global--spacer--sm)',
  background: 'var(--pf-t--global--background--color--primary--default)',
  border: `var(--pf-t--global--border--width--${selected ? 'strong' : 'regular'}) ${dashed ? 'dashed' : 'solid'} var(--pf-t--global--border--color--${selected ? 'clicked' : 'default'})`,
  borderRadius: 'var(--pf-t--global--border--radius--small)',
  overflow: 'hidden',
  fontSize: 'var(--pf-t--global--font--size--sm)',
  cursor: 'pointer',
});

const line: CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };
const subtle: CSSProperties = { ...line, color: 'var(--pf-t--global--text--color--subtle)', fontSize: 'var(--pf-t--global--font--size--xs)' };

/** The marks of a node: its worst problem, and a lock. */
function Marks({ node, problems }: { node: FlowNode; problems: FlowProblem[] }) {
  const errors = problems.filter((p) => p.warning !== true);
  return (
    <span style={{ float: 'right' }}>
      {errors.length > 0 ? (
        <Icon status="danger" size="sm" title={errors.map((p) => p.message).join('\n')}>
          <ExclamationCircleIcon />
        </Icon>
      ) : problems.length > 0 ? (
        <Icon status="warning" size="sm" title={problems.map((p) => p.message).join('\n')}>
          <ExclamationTriangleIcon />
        </Icon>
      ) : null}
      {node.locked !== undefined && (
        <Icon size="sm" title={node.locked}>
          <LockIcon />
        </Icon>
      )}
    </span>
  );
}

/** A node of the line: a step, the arrival, a destination, a flow called, or an end. */
const StepNode = memo(function StepNode({ data }: NodeProps<CanvasNode>) {
  const { node, problems, selected } = data;
  const what = node.kind === 'step' ? node.type : node.kind === 'from' ? 'from' : node.kind === 'flow' ? 'flow' : node.kind === 'destination' ? 'to' : undefined;
  return (
    <div style={box(selected, node.kind === 'flow' || node.kind === 'end')} aria-label={`${node.label}${what === undefined ? '' : ` (${what})`}`}>
      <Handles />
      <Marks node={node} problems={problems} />
      <div style={{ ...line, fontWeight: 'var(--pf-t--global--font--weight--body--bold)', ...(node.defaultName ? { color: 'var(--pf-t--global--text--color--subtle)' } : {}) }}>
        <span>{node.label}</span>
        {what !== undefined && <span style={{ ...subtle, fontWeight: 'normal' }}> · {what}</span>}
      </div>
      {node.detail !== undefined && <div style={subtle}>{node.detail}</div>}
      {node.rows?.map((row, i) => (
        <div key={i} style={subtle}>
          {row}
        </div>
      ))}
    </div>
  );
});

/** A frame: a process's lane, or what a split repeats. */
const FrameNode = memo(function FrameNode({ data }: NodeProps<CanvasNode>) {
  const { node, selected } = data;
  return (
    <div
      style={{
        ...box(selected, node.kind === 'group'),
        background: node.kind === 'lane' ? 'var(--pf-t--global--background--color--tertiary--default)' : 'transparent',
        cursor: node.kind === 'lane' ? 'pointer' : 'default',
      }}
    >
      <Handles />
      <div style={{ ...line, fontWeight: 'var(--pf-t--global--font--weight--body--bold)' }}>{node.kind === 'lane' ? `process ${node.label}` : node.label}</div>
    </div>
  );
});

const types = { step: StepNode, frame: FrameNode };

/** The node a click on `node` selects: its path's own, so that a node drawn beside a step selects the step. */
export const selectionOf = (node: FlowNode) => (node.kind === 'lane' ? node.id : pointer(node.path));

/** The problems at `path` or inside it, but those inside another node of the view drawn for a path inside it. */
function problemsOf(view: FlowView, node: FlowNode): FlowProblem[] {
  if (node.kind === 'lane' || node.kind === 'group' || node.kind === 'end') return [];
  const at = node.path;
  return view.problems.filter((p) => at.every((s, i) => p.path[i] === s) && (node.kind !== 'step' || !(p.path.length > at.length + 1 && p.path[at.length] === 'endpoints')));
}

const DASHED: Partial<Record<FlowEdge['kind'], string>> = { process: '6 4', calls: '6 4', ends: '2 4' };

export interface FlowCanvasProps {
  view: FlowView;
  /** The id of the selected node, or null. */
  selected: string | null;
  /** Told the node chosen. */
  onSelect: (node: FlowNode) => void;
}

/** The picture of a flow: its view's nodes where the layout put them, and its edges, labelled. It changes nothing. */
export function FlowCanvas({ view, selected, onSelect }: FlowCanvasProps) {
  const byId = useMemo(() => new Map(view.nodes.map((n) => [n.id, n])), [view]);
  const nodes = useMemo<CanvasNode[]>(
    () =>
      view.nodes.map((n) => {
        const parent = n.parent === undefined ? undefined : byId.get(n.parent);
        return {
          id: n.id,
          type: n.kind === 'lane' || n.kind === 'group' ? 'frame' : 'step',
          position: { x: n.x - (parent?.x ?? 0), y: n.y - (parent?.y ?? 0) },
          width: n.width,
          height: n.height,
          ...(n.parent === undefined ? {} : { parentId: n.parent }),
          draggable: false,
          connectable: false,
          selectable: n.kind !== 'group',
          data: { node: n, problems: problemsOf(view, n), selected: selected !== null && selectionOf(n) === selected && n.kind !== 'group' },
          ariaLabel: n.label,
        };
      }),
    [view, byId, selected],
  );
  const edges = useMemo<Edge[]>(
    () =>
      view.edges.map((e) => {
        const side = e.kind === 'calls' || e.kind === 'ends' || e.kind === 'process';
        const dash = DASHED[e.kind];
        return {
          id: e.id,
          source: e.source,
          target: e.target,
          type: 'smoothstep',
          ...(side ? { sourceHandle: 'side', targetHandle: 'side' } : {}),
          // the flow node beside a call says what it calls; a label would cover the short edge to it
          ...(e.label === undefined || e.kind === 'calls' ? {} : { label: e.label }),
          markerEnd: { type: MarkerType.ArrowClosed },
          ...(dash === undefined ? {} : { style: { strokeDasharray: dash } }),
          selectable: false,
        };
      }),
    [view],
  );

  return (
    <ReactFlow
      style={FLOW_STYLE}
      nodes={nodes}
      edges={edges}
      nodeTypes={types}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable
      onNodeClick={(_, n) => {
        const node = byId.get(n.id);
        if (node !== undefined && node.kind !== 'group') onSelect(node);
      }}
      fitView
      fitViewOptions={{ maxZoom: 1 }}
      minZoom={0.2}
      proOptions={{ hideAttribution: true }}
    >
      <Background color="var(--pf-t--global--border--color--subtle)" />
    </ReactFlow>
  );
}
