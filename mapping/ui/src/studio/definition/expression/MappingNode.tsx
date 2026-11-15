import { CogIcon, ExclamationTriangleIcon } from '@patternfly/react-icons';
import { Handle, Position, useUpdateNodeInternals, type Node, type NodeProps } from '@xyflow/react';
import type { CSSProperties } from 'react';
import { useEffect, useRef } from 'react';
import type { FieldPath } from '../../../generated/mapping-model';
import { localPath, stepName } from '../../../model/field-path';
import { properAncestors } from '../../../model/set-context';
import { SOURCE_FIELD_DRAG } from '../../store/drag';
import { PreviewBadge } from '../../mock/PreviewBadge';
import { FieldTypeIcon } from '../../shared/FieldTypeIcon';
import { NODE_WIDTH, nodeHeight, type ExpressionNode, type NodeState, type Port } from './expression-graph';

const ROLES = { source: 'Source field', function: 'Node function', target: 'Target field' } as const;

/** What a node is, in words (the prototype's `nodeRole`): a mapping variable says so. */
export const nodeRole = (node: ExpressionNode) => node.role ?? ROLES[node.kind];

/** The data of a canvas node: the graph node, its state, its field's type and the opener of its parameters. */
export type MappingNodeData = {
  node: ExpressionNode;
  state: NodeState;
  /** The type icon of a field node: `structure` or a value type's local name. */
  fieldType?: string;
  /** Opens the parameter dialog of a standard function box with parameters. */
  editParameters?: () => void;
  /** The parameters a function card shows below its name, as text. */
  settings?: string;
  /** The deviation note of a standard function box (GLOSSARY: Fidelity), shown in its hint only; absent for any other node. */
  note?: string;
  /** A source-field box that reads a field the source structure lacks (GLOSSARY: Orphaned reference). */
  orphaned?: boolean;
  /** Points an orphaned box at a source field dropped on it (GLOSSARY: Remap); absent for any other box and in view mode. */
  remap?: (field: FieldPath) => void;
};

/** What an orphaned box's mark says. */
const ORPHANED_HINT = 'Orphaned reference: the source structure has no such field. Drop a source field here, or choose Remap in the node menu.';

export type MappingFlowNode = Node<MappingNodeData, 'mapping'>;

function portHint(node: ExpressionNode, port: Port | undefined, input: boolean, index: number): string[] {
  const direction = input ? 'Input' : 'Output';
  if (node.kind !== 'function') return [direction, `/${localPath(node.path!)}`];
  return [`${direction} ${index + 1}: ${port ? port.label : 'out'}`, node.name];
}

function PortHandle({ node, state, port, index, count, input }: { node: ExpressionNode; state: NodeState; port?: Port; index: number; count: number; input: boolean }) {
  const hint = portHint(node, port, input, index);
  const missing = input && state.missing.includes(port!.id);
  if (missing) hint.push('Missing connection');
  return (
    <Handle
      id={port ? port.id : 'out'}
      type={input ? 'target' : 'source'}
      position={input ? Position.Left : Position.Right}
      tabIndex={0}
      role="group"
      aria-label={hint.join(' · ')}
      className={missing ? 'missing-input' : undefined}
      data-mapping-hint={[...hint, state.description].join('\n')}
      style={{ top: `calc(var(--node-height) * ${(index + 0.5) / count} - 1px)` }}
    />
  );
}

/**
 * A box or the target on the canvas (the prototype's MappingNode): its symbol with the state mark, its name, a changed
 * context or the missing inputs below it, a gear for a function with parameters, and its ports; a deviation note is in its
 * hint only. A source row dropped on
 * a box that takes a remap remaps it; any other drop, such as a catalogue function's, goes on to the canvas.
 */
export function MappingNode({ id, data, selected }: NodeProps<MappingFlowNode>) {
  const { node, state, fieldType, editParameters, settings, note, orphaned, remap } = data;
  const refresh = useUpdateNodeInternals();
  const ports = JSON.stringify(node.inputs.map((p) => p.id));
  const previousPorts = useRef(ports);
  useEffect(() => {
    // React Flow measures a new node on mount; only rescan when its ports change
    if (previousPorts.current !== ports) refresh(id);
    previousPorts.current = ports;
  }, [id, ports, refresh]);
  const isFunction = node.kind === 'function';
  // a variable has no context (the prototype's mapping variable)
  const isSource = node.kind === 'source' && node.role === undefined;
  const defaultContext = isSource ? (properAncestors(node.path!)[0] ?? '') : '';
  const context = node.context ?? defaultContext;
  const changedContext = isSource && context !== defaultContext;
  const contextLabel = isSource ? `Context · ${context ? stepName(context) : 'root'}` : '';
  const openInputs = isFunction ? node.inputs.filter((p) => state.missing.includes(p.id)) : [];
  const portNote = openInputs.length ? `Missing: ${openInputs.map((p) => p.label).join(', ')}` : '';
  const path = node.path ? `/${localPath(node.path)}` : '';
  const details = [node.name, nodeRole(node) + (fieldType ? ` · ${fieldType}` : ''), path, contextLabel, settings, note, orphaned && ORPHANED_HINT, state.description]
    .filter(Boolean)
    .join('\n');
  return (
    <div
      className={`map-node ${node.kind}${selected ? ' is-selected' : ''}${orphaned ? ' is-orphaned' : ''}`}
      data-state={state.kind}
      data-mapping-hint={details}
      style={{ '--node-height': `${nodeHeight(node)}px`, height: 'var(--node-height)', width: NODE_WIDTH } as CSSProperties}
      onDrop={
        remap &&
        ((e) => {
          const field = e.dataTransfer.getData(SOURCE_FIELD_DRAG);
          if (field !== '') remap(field);
        })
      }
    >
      {node.inputs.map((port, i) => (
        <PortHandle key={port.id} node={node} state={state} port={port} index={i} count={node.inputs.length} input />
      ))}
      <div className="node-content">
        <span className={`node-symbol ${node.kind}`}>
          {isFunction ? 'ƒ' : <FieldTypeIcon type={fieldType ?? ''} />}
          <span className="mapping-state-mark" aria-hidden="true">
            {{ complete: '✓', incomplete: '!', detached: '–' }[state.kind]}
          </span>
        </span>
        <div className="node-caption">
          <strong className={changedContext ? 'changed-context' : undefined}>{node.name}</strong>
          {orphaned && (
            <span className="orphaned-mark" role="img" aria-label="Orphaned reference" title={ORPHANED_HINT}>
              <ExclamationTriangleIcon />
            </span>
          )}
          {node.preview && <PreviewBadge {...node.preview} />}
          {portNote ? (
            <div className="node-meta port-note">{portNote}</div>
          ) : (
            <>
              {changedContext && <div className="node-meta">{contextLabel}</div>}
              {settings && <div className="node-meta">{settings}</div>}
            </>
          )}
        </div>
        {editParameters && (
          <button
            type="button"
            className="node-settings nodrag nopan"
            aria-label={`Parameters for ${node.name}`}
            data-mapping-hint={`Function parameters\n${details}`}
            onDoubleClick={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              editParameters();
            }}
          >
            <CogIcon />
          </button>
        )}
      </div>
      {node.output && <PortHandle node={node} state={state} index={0} count={1} input={false} />}
    </div>
  );
}
