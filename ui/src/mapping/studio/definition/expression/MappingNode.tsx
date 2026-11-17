import { Button, Content, Flex, FlexItem, Icon } from '@patternfly/react-core';
import { CheckCircleIcon, CogIcon, ExclamationCircleIcon, ExclamationTriangleIcon, MinusCircleIcon } from '@patternfly/react-icons';
import { Handle, Position, useUpdateNodeInternals, type Node, type NodeProps } from '@xyflow/react';
import { useEffect, useRef, type CSSProperties } from 'react';
import type { FieldPath } from '../../../generated/mapping-model';
import { localPath, stepName } from '../../../model/field-path';
import { properAncestors } from '../../../model/set-context';
import { PreviewBadge } from '../../mock/PreviewBadge';
import { FieldTypeIcon } from '../../shared/FieldTypeIcon';
import { SOURCE_FIELD_DRAG } from '../../store/drag';
import { NODE_WIDTH, nodeHeight, type ExpressionNode, type NodeState, type Port } from './expression-graph';

const ROLES = { source: 'Source field', function: 'Node function', target: 'Target field' } as const;

/** What a node is, in words: a mapping variable says so. */
export const nodeRole = (node: ExpressionNode) => node.role ?? ROLES[node.kind];

/** A node state's mark and its colour: complete, incomplete, or with no path to the target. */
export const STATE_MARKS = {
  complete: { icon: CheckCircleIcon, status: 'success', label: 'Complete' },
  incomplete: { icon: ExclamationCircleIcon, status: 'warning', label: 'Incomplete' },
  detached: { icon: MinusCircleIcon, status: 'custom', label: 'No target path' },
} as const;

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
  /** The deviation note of a standard function box, shown in its hint only; absent for any other node. */
  note?: string;
  /** A source-field box that reads a field the source structure lacks. */
  orphaned?: boolean;
  /** Points an orphaned box at a source field dropped on it; absent for any other box and in view mode. */
  remap?: (field: FieldPath) => void;
};

/** What an orphaned box's mark says. */
const ORPHANED_HINT = 'Orphaned reference: the source structure has no such field. Drop a source field here, or choose Remap in the node menu.';

export type MappingFlowNode = Node<MappingNodeData, 'mapping'>;

/** The colour of each kind of node's symbol: its background and its text. */
const SYMBOL_COLOURS = {
  source: ['var(--pf-t--global--color--nonstatus--teal--default)', 'var(--pf-t--global--text--color--nonstatus--on-teal--default)'],
  function: ['var(--pf-t--global--color--nonstatus--purple--default)', 'var(--pf-t--global--text--color--nonstatus--on-purple--default)'],
  target: ['var(--pf-t--global--color--nonstatus--blue--default)', 'var(--pf-t--global--text--color--nonstatus--on-blue--default)'],
} as const;

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
      data-missing={missing || undefined}
      data-mapping-hint={[...hint, state.description].join('\n')}
      style={{
        top: `calc(var(--node-height) * ${(index + 0.5) / count} - 1px)`,
        width: 'var(--pf-t--global--spacer--sm)',
        height: 'var(--pf-t--global--spacer--sm)',
        ...(missing && { background: 'var(--pf-t--global--color--status--warning--default)', borderColor: 'var(--pf-t--global--color--status--warning--default)' }),
      }}
    />
  );
}

/**
 * A box or the target on the canvas: its symbol with the state mark, its name, a changed context or the missing
 * inputs below it, a gear for a function with parameters, and its ports; a deviation note is in its hint only. A
 * source row dropped on a box that takes a remap remaps it; any other drop, such as a catalogue function's, goes on
 * to the canvas.
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
  // a variable has no context
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
  const mark = STATE_MARKS[state.kind];
  const border = selected
    ? 'var(--pf-t--global--border--color--clicked)'
    : orphaned
      ? 'var(--pf-t--global--border--color--status--warning--default)'
      : 'var(--pf-t--global--border--color--default)';
  const card: CSSProperties = {
    '--node-height': `${nodeHeight(node)}px`,
    height: 'var(--node-height)',
    width: NODE_WIDTH,
    boxSizing: 'border-box',
    display: 'flex',
    alignItems: 'center',
    padding: '0 var(--pf-t--global--spacer--sm)',
    background: 'var(--pf-t--global--background--color--primary--default)',
    border: `${selected ? 'var(--pf-t--global--border--width--strong)' : 'var(--pf-t--global--border--width--regular)'} ${orphaned && !selected ? 'dashed' : 'solid'} ${border}`,
    borderRadius: 'var(--pf-t--global--border--radius--small)',
    boxShadow: selected ? 'var(--pf-t--global--box-shadow--md)' : 'var(--pf-t--global--box-shadow--sm)',
    color: 'var(--pf-t--global--text--color--regular)',
    fontSize: 'var(--pf-t--global--font--size--body--sm)',
  } as CSSProperties;
  return (
    <div
      data-state={state.kind}
      data-orphaned={orphaned || undefined}
      data-mapping-hint={details}
      style={card}
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
      <Flex flexWrap={{ default: 'nowrap' }} alignItems={{ default: 'alignItemsCenter' }} gap={{ default: 'gapSm' }} style={{ minWidth: 0, flex: 1 }}>
        <FlexItem
          style={{
            position: 'relative',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 'var(--pf-t--global--spacer--lg)',
            height: 'var(--pf-t--global--spacer--lg)',
            borderRadius: 'var(--pf-t--global--border--radius--small)',
            background: SYMBOL_COLOURS[node.kind][0],
            color: SYMBOL_COLOURS[node.kind][1],
            fontStyle: isFunction ? 'italic' : undefined,
          }}
        >
          {isFunction ? 'ƒ' : <FieldTypeIcon type={fieldType ?? ''} />}
          <Icon
            size="sm"
            status={mark.status}
            style={{
              position: 'absolute',
              right: 'calc(-1 * var(--pf-t--global--spacer--xs))',
              bottom: 'calc(-1 * var(--pf-t--global--spacer--xs))',
              borderRadius: 'var(--pf-t--global--border--radius--pill)',
              background: 'var(--pf-t--global--background--color--primary--default)',
            }}
          >
            <mark.icon aria-hidden />
          </Icon>
        </FlexItem>
        <FlexItem flex={{ default: 'flex_1' }} style={{ minWidth: 0 }}>
          <Flex gap={{ default: 'gapXs' }} flexWrap={{ default: 'nowrap' }} alignItems={{ default: 'alignItemsCenter' }}>
            <strong
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                ...(changedContext && { color: 'var(--pf-t--global--text--color--link--default)' }),
              }}
            >
              {node.name}
            </strong>
            {orphaned && (
              <Icon size="sm" status="warning" role="img" aria-label="Orphaned reference" title={ORPHANED_HINT}>
                <ExclamationTriangleIcon />
              </Icon>
            )}
            {node.preview && <PreviewBadge {...node.preview} />}
          </Flex>
          {(portNote || changedContext || settings) && (
            <Content component="small" style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {portNote || [changedContext && contextLabel, settings].filter(Boolean).join(' · ')}
            </Content>
          )}
        </FlexItem>
        {editParameters && (
          <FlexItem>
            <Button
              variant="plain"
              size="sm"
              className="nodrag nopan"
              aria-label={`Parameters for ${node.name}`}
              data-mapping-hint={`Function parameters\n${details}`}
              icon={<CogIcon />}
              onDoubleClick={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation();
                editParameters();
              }}
            />
          </FlexItem>
        )}
      </Flex>
      {node.output && <PortHandle node={node} state={state} index={0} count={1} input={false} />}
    </div>
  );
}
