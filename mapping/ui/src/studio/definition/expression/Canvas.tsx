import { Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { CopyIcon, CubeIcon, LayerGroupIcon, ListIcon, PasteIcon, TrashIcon } from '@patternfly/react-icons';
import {
  Background,
  MarkerType,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useNodesInitialized,
  useReactFlow,
  type Connection,
  type Edge,
  type NodeChange,
  type EdgeChange,
  type FinalConnectionState,
  type OnConnectStartParams,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent, type MouseEvent as ReactMouseEvent, type RefObject } from 'react';
import { useStore } from 'zustand';
import type { FunctionCatalogue as Catalogue } from '../../../api/client';
import type { BrowserStateStore } from '../../browser-state/browser-state';
import { selectTrace, selectTraceStale, type WorkingCopyStore } from '../../store/working-copy';
import type { AuthoringId, BoxPosition, FieldPath, MappingModel } from '../../../generated/mapping-model';
import type { CommandResult } from '../../../model/command';
import { localPath, stepName } from '../../../model/field-path';
import { rewire, type WireEnd } from '../../../model/rewire';
import { properAncestors } from '../../../model/set-context';
import { setResult, wire } from '../../../model/wire';
import {
  addNode,
  addWire,
  availableVariables,
  canvasOf,
  definitionPreview,
  disconnectNode,
  isPreviewId,
  moveNodes,
  newPreviewId,
  PREVIEW_PREFIX,
  removeNodes,
  removeWire,
  setParams,
  variableName,
  VARIABLES_ISSUE,
  type DefinitionPreviewData,
  type PreviewNode,
} from '../../mock/definition-preview';
import { PreviewBadge } from '../../mock/PreviewBadge';
import { previewFunction } from '../../mock/preview-functions';
import { testInstances } from '../../mock/test-instances';
import { testPreview } from '../../mock/test-preview';
import { QueueDialog } from './QueueDialog';
import type { Side } from '../definition-model';
import { formatOf } from '../../shared/documents';
import { FieldTypeIcon } from '../../shared/FieldTypeIcon';
import type { TestSession } from '../../shared/test-session';
import { SideBySide } from '../../shared/SplitLayout';
import { action, ActionMenu, deleteAction, isContextMenuKey, useActionMenu, type MenuItem } from './ActionMenu';
import { expressionGraph, inspectExpression, NODE_WIDTH, portId, TARGET, upstream, wireEnd, type ExpressionEdge, type ExpressionNode } from './expression-graph';
import { arrange, layoutOf, shownPositions, type Positions } from './expression-layout';
import './expression.css';
import { deviationNote } from './fidelity';
import { FunctionCatalogue, functionDragType } from './FunctionCatalogue';
import { FunctionParameters } from './FunctionParameters';
import { MappingHint } from './MappingHint';
import { MappingNode, nodeRole, type MappingFlowNode } from './MappingNode';
import { previewPositions, VARIABLE_ROLE, VARIABLES, withPreview } from './preview-graph';
import { PreviewParameters } from './PreviewParameters';
import { RemapDialog } from './RemapDialog';

const types = { mapping: MappingNode };
const fixedView = { minZoom: 1, maxZoom: 1, padding: 0 };
const EDGE_COLOR = '#567187';

/** The wire end a wire ends at. */
const endOf = (edge: ExpressionEdge): WireEnd => (edge.target === TARGET ? 'target' : { box: edge.target, slot: edge.slot! });

/** The input handle a wire ends at. */
const handleOf = (edge: ExpressionEdge) => portId(edge.slot ?? 0);

/** The positions of the model's nodes only, for a model command. */
const modelOnly = (positions: Positions): Positions => Object.fromEntries(Object.entries(positions).filter(([id]) => !isPreviewId(id)));

/** A wire from `source` into a handle, or `moving` moved there: the rewire command, else the wire or set result command. */
function connection(model: MappingModel, targetField: FieldPath, source: string, target: string, handle: string | null | undefined, moving?: ExpressionEdge): CommandResult {
  const to = wireEnd(target, handle);
  if (moving) return rewire(model, { targetField, source, from: endOf(moving), to });
  return to === 'target' ? setResult(model, { targetField, box: source }) : wire(model, { targetField, source, target: to.box, slot: to.slot });
}

/** The prototype's ViewFit: the first view fits the nodes at zoom 1, an added node off screen is centred, a resize keeps the centre. */
function ViewFit({ nodeIds, host }: { nodeIds: string; host: RefObject<HTMLElement | null> }) {
  const flow = useReactFlow();
  const initialized = useNodesInitialized();
  const lastIds = useRef<string[] | null>(null);
  useEffect(() => {
    if (!initialized) return;
    const ids = JSON.parse(nodeIds) as string[];
    const before = lastIds.current;
    lastIds.current = ids;
    // the first view is the canvas's own fitView; a queued fitView call would run at the next node change instead
    if (!before) return;
    const added = ids.findLast((id) => !before.includes(id));
    if (!added) return;
    const bounds = flow.getNodesBounds([added]);
    const rect = host.current!.getBoundingClientRect();
    const pos = flow.flowToScreenPosition({ x: bounds.x, y: bounds.y });
    if (pos.x < rect.left || pos.x + bounds.width > rect.right || pos.y < rect.top || pos.y + bounds.height > rect.bottom) {
      void flow.setCenter(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, { zoom: 1 });
    }
  }, [initialized, nodeIds, host, flow]);
  useEffect(() => {
    let previous: { width: number; height: number } | null = null;
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry!.contentRect;
      if (width && height && previous) {
        const view = flow.getViewport();
        void flow.setViewport({ x: view.x + (width - previous.width) / 2, y: view.y + (height - previous.height) / 2, zoom: 1 });
      }
      previous = width && height ? { width, height } : null;
    });
    observer.observe(host.current!);
    return () => observer.disconnect();
  }, [host, flow]);
  return null;
}

export interface CanvasProps {
  mappingId: string;
  store: WorkingCopyStore;
  /** The open mapping's test session, which the Display Queue runs when there is no trace yet (#177). */
  session: TestSession;
  /** The target field, or a preview variable (#163), whose expression shows. */
  targetField: FieldPath;
  catalogue: Catalogue;
  browserState: BrowserStateStore;
  /** The type icon of each field by side: `structure` or a value type's local name. */
  fieldTypes: Record<Side, ReadonlyMap<FieldPath, string>>;
  /** Selects a field in its pane (the node menu's Find Field). */
  findField: (side: Side, path: FieldPath) => void;
  /** The orphaned source-field boxes (GLOSSARY: Orphaned reference), found against the loaded source structure. */
  orphaned: ReadonlySet<AuthoringId>;
  /** The source fields an orphaned box can be remapped to, in tree order. */
  sourceFields: readonly FieldPath[];
  /** Points orphaned box `box` of this target field mapping at source field `field` (GLOSSARY: Remap): the dialog's or a drop's. */
  remap: (box: AuthoringId, field: FieldPath) => void;
}

function Drawing({ mappingId, store, session, targetField, catalogue, browserState, fieldTypes, findField, orphaned, sourceFields, remap }: CanvasProps) {
  const host = useRef<HTMLDivElement>(null);
  const edgeDrag = useRef<string | null>(null);
  const inputDrag = useRef<ExpressionEdge | undefined>(undefined);
  const flow = useReactFlow();
  const model = useStore(store, (s) => s.workingCopy);
  const editing = useStore(store, (s) => s.editing);
  const canPaste = useStore(store, (s) => s.clipboard !== null);
  const trace = useStore(store, selectTrace);
  const stale = useStore(store, selectTraceStale);
  const traceLevel = useStore(testPreview(store, browserState, mappingId), (s) => s.traceLevel);
  const previewStore = definitionPreview(store, browserState, mappingId);
  const preview = useStore(previewStore, (s) => s.value);
  const editPreview = (change: (data: DefinitionPreviewData) => DefinitionPreviewData) => previewStore.getState().edit(change);
  // a variable's expression is all preview: the model holds no mapping for it
  const variable = preview.variables.includes(targetField);
  const canvasData = canvasOf(preview, targetField);
  const previewNodes = useMemo(() => new Map<string, PreviewNode>(canvasData.nodes.map((n) => [n.id, n])), [canvasData]);
  const mapping = variable ? undefined : model.targetFieldMappings?.[targetField];
  const modelGraph = useMemo(() => {
    const g = expressionGraph(targetField, mapping, catalogue, model.udfs);
    if (!variable) return g;
    return { ...g, nodes: g.nodes.map((n) => (n.id === TARGET ? { ...n, name: variableName(targetField), role: VARIABLE_ROLE, preview: VARIABLES } : n)) };
  }, [targetField, mapping, catalogue, model.udfs, variable]);
  const graph = useMemo(() => withPreview(modelGraph, canvasData, catalogue), [modelGraph, canvasData, catalogue]);
  const layout = useMemo(() => (variable ? canvasData.target && { target: canvasData.target, boxes: {} } : mapping?.layout), [variable, canvasData.target, mapping?.layout]);
  const shown = useMemo(() => previewPositions(graph, shownPositions(modelGraph, layout), canvasData), [graph, modelGraph, layout, canvasData]);
  const { states, floating } = useMemo(() => inspectExpression(graph), [graph]);
  const edgeById = useMemo(() => new Map(graph.edges.map((e) => [e.id, e])), [graph]);
  const [moving, setMoving] = useState<Positions>({});
  const [measured, setMeasured] = useState<Record<string, { width: number; height: number }>>({});
  // the selection and the open dialog are the store's UI slice (ADR-0028)
  const selectedIds = useStore(store, (s) => s.selected);
  const selected = useMemo(() => new Set(selectedIds), [selectedIds]);
  const dialog = useStore(store, (s) => s.canvasDialog);
  const [paletteOpen, setPaletteOpen] = useState(true);
  const { menu, showMenu, closeMenu } = useActionMenu();
  const actions = store.getState();
  const selectedNode = graph.nodes.find((n) => selected.has(n.id));
  /** The node of the open dialog of `kind`; none once an undo or a delete removed it, which closes the dialog. */
  const dialogNode = (kind: 'parameters' | 'remap' | 'queue') => (dialog?.kind === kind ? graph.nodes.find((n) => n.id === dialog.node) : undefined);
  const openDialog = (kind: 'parameters' | 'remap' | 'queue', node: string) => actions.openCanvasDialog({ kind, node });
  const closeDialog = () => actions.openCanvasDialog(null);
  const queueNode = dialogNode('queue');
  const remappingNode = dialogNode('remap');
  /** Selects and unselects as React Flow's changes say, in their order, over the selection as it is now. */
  const changeSelection = (changes: readonly { id: string; selected: boolean }[]) => {
    const next = new Set(store.getState().selected);
    for (const c of changes) {
      if (c.selected) next.add(c.id);
      else next.delete(c.id);
    }
    actions.selectBoxes([...next]);
  };

  /** Whether a wire is a preview wire (ADR-0028): on a variable's canvas, from or into a preview node, or one moved. */
  const previewWire = (source: string, target: string, moving?: ExpressionEdge) => variable || isPreviewId(source) || isPreviewId(target) || moving?.preview === true;
  const slotOf = (target: string, handle: string | null | undefined) => (target === TARGET ? 0 : Number((handle ?? portId(0)).slice(2)));
  const canConnect = (source: string, target: string, handle: string | null | undefined, moving?: ExpressionEdge) => {
    if (previewWire(source, target, moving)) {
      // a model wire stays in the model; a preview wire takes a free input of another node
      if (moving && !moving.preview) return false;
      const slot = slotOf(target, handle);
      return source !== target && !graph.edges.some((e) => e !== moving && e.target === target && (e.slot ?? 0) === slot);
    }
    const result = connection(model, targetField, source, target, handle, moving);
    return result.kind === 'applied' && result.model !== model;
  };
  const connect = (source: string, target: string, handle: string | null | undefined, moving?: ExpressionEdge) => {
    if (previewWire(source, target, moving)) {
      const slot = slotOf(target, handle);
      const added = { id: `${PREVIEW_PREFIX}${source}->${target}#${slot}`, source, target, slot };
      editPreview((d) => addWire(moving ? removeWire(d, targetField, moving.id) : d, targetField, added));
      return;
    }
    const to = wireEnd(target, handle);
    if (moving) actions.rewire(targetField, source, endOf(moving), to);
    else if (to === 'target') actions.setResult(targetField, source);
    else actions.wire(targetField, source, to.box, to.slot);
  };
  const disconnect = (edge: ExpressionEdge) => {
    if (edge.preview) editPreview((d) => removeWire(d, targetField, edge.id));
    else if (edge.target === TARGET) actions.setResult(targetField, null);
    else actions.unwire(targetField, edge.target, edge.slot!);
  };
  /** Deletes nodes: preview nodes from the preview, boxes from the model. */
  const remove = (ids: readonly string[]) => {
    const previews = ids.filter(isPreviewId);
    const boxes = ids.filter((id) => !isPreviewId(id));
    if (previews.length) editPreview((d) => removeNodes(d, targetField, previews));
    if (boxes.length) actions.deleteBoxes(boxes);
  };
  /** Keeps the positions of moved or laid out nodes: a preview node's and a variable's own target in the preview, the others in the model. */
  const place = (positions: Positions, moved: readonly string[]) => {
    const previews = Object.fromEntries(moved.filter(isPreviewId).map((id) => [id, positions[id]!]));
    const ownTarget = variable && moved.includes(TARGET) ? positions[TARGET] : undefined;
    if (Object.keys(previews).length || ownTarget) editPreview((d) => moveNodes(d, targetField, previews, ownTarget));
    if (!variable && moved.some((id) => !isPreviewId(id))) actions.setPositions(targetField, layoutOf(modelOnly(positions)));
  };
  const togglePalette = () => setPaletteOpen(!paletteOpen);
  /** A spot below every node, for a function added by a click. */
  const freeSpot = (): BoxPosition => {
    const all = Object.values(shown);
    return { x: Math.min(...all.map((p) => p.x)), y: Math.max(...all.map((p) => p.y)) + 72 };
  };
  const addPreviewNode = (node: Omit<PreviewNode, 'id'>) => editPreview((d) => addNode(d, targetField, { id: newPreviewId(), ...node }));
  /** Adds a catalogue function: a box, or on a variable's canvas a preview node. */
  const addFunction = (fn: string, at: BoxPosition = freeSpot()) => {
    if (variable) addPreviewNode({ kind: 'function', ref: fn, position: at });
    else actions.insertBox(targetField, fn, at, layoutOf(modelOnly(shown)));
  };
  const addPreview = (key: string) => addPreviewNode({ kind: 'function', ref: key, position: freeSpot() });
  const layOut = () => place(arrange(graph), graph.nodes.map((n) => n.id));
  const deleteFloatingObjects = () => remove(floating);
  const copyBranch = (id: string) => {
    // the model's branch: a box copies no preview node that feeds it
    const branch = [...upstream(modelGraph, id)];
    actions.copy(targetField, branch, Object.fromEntries(branch.map((box) => [box, shown[box]!])));
  };
  const paste = () => actions.paste(targetField, layoutOf(modelOnly(shown)));
  const scope = availableVariables(preview.variables, targetField);
  /** The preview function a node stands for: Value mapping or a lookup (#166, #165). */
  const previewFn = (id: string) => {
    const own = previewNodes.get(id);
    return own?.kind === 'function' ? previewFunction(own.ref) : undefined;
  };
  const hasParameters = (n: ExpressionNode) => previewFn(n.id) !== undefined || Boolean(n.fn && catalogue[n.fn]?.params?.length);

  const connectionActions = (from: ExpressionNode): MenuItem[] =>
    graph.nodes.flatMap((destination) =>
      destination.inputs
        .filter((port) => canConnect(from.id, destination.id, port.id))
        .map((port) => action(`${destination.name} · ${port.label}`, () => connect(from.id, destination.id, port.id), { key: `${destination.id} ${port.id}` })),
    );
  const nodeMenu = (event: ReactMouseEvent | KeyboardEvent, id: string, element: Element) => {
    const n = graph.nodes.find((item) => item.id === id)!;
    const own = previewNodes.get(id);
    actions.select(id);
    const items: MenuItem[] = [action('Display Queue', () => openDialog('queue', id))];
    if (hasParameters(n)) items.push(action('Properties', () => openDialog('parameters', id)));
    const destinations = editing && n.output ? connectionActions(n) : [];
    if (destinations.length) items.push(action('Connect to', null, { children: destinations }));
    const wired = graph.edges.some((e) => e.source === id || e.target === id);
    const disconnectAll = () => {
      if (own || variable) editPreview((d) => disconnectNode(d, targetField, id));
      else if (n.kind === 'target') actions.setResult(targetField, null);
      else actions.disconnectBox(targetField, id);
    };
    items.push(action('Disconnect', disconnectAll, { disabled: !editing || !wired }), deleteAction('Delete', () => remove([id]), { disabled: !editing || n.kind === 'target' }));
    // another target field of a grouped expression (#164) leaves the canvas
    if (own?.kind === 'target') items.push(action('Ungroup', () => remove([id]), { disabled: !editing }));
    // a variable is found in the target tree
    if (n.kind !== 'function') items.push(action('Find Field', () => findField(n.kind === 'source' && !n.role ? 'source' : 'target', n.path!)));
    // a preview node is no box to copy, nor has a context
    if (own) return showMenu(event, `Node actions for ${n.name}`, items, element);
    if (n.kind !== 'target') items.push(action('Copy', () => copyBranch(id)));
    if (n.kind === 'source') {
      if (orphaned.has(id)) items.push(action('Remap', () => openDialog('remap', id), { disabled: !editing }));
      const choices = properAncestors(n.path!);
      const context = n.context ?? choices[0];
      items.push(
        action('Context', null, {
          disabled: !editing || choices.length === 0,
          children: choices.map((c) => action(stepName(c), () => actions.setContext(targetField, id, c), { key: c, checked: c === context })),
        }),
      );
    }
    showMenu(event, `Node actions for ${n.name}`, items, element);
  };
  const canvasMenu = (event: ReactMouseEvent | MouseEvent | KeyboardEvent) =>
    showMenu(
      event,
      'Canvas actions',
      [
        deleteAction('Delete Floating Objects', deleteFloatingObjects, { disabled: !editing || floating.length === 0 }),
        action('Lay Out Mapping', layOut, { disabled: !editing }),
        action('Paste', paste, { disabled: !editing || !canPaste || variable }),
      ],
      host.current,
    );
  const edgeMenu = (event: ReactMouseEvent | KeyboardEvent, edge: ExpressionEdge, element: Element) =>
    showMenu(event, 'Connection actions', [action('Disconnect', () => disconnect(edge), { disabled: !editing })], element);

  const onCanvasKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!isContextMenuKey(event)) return;
    const target = event.target as Element;
    const element = target.closest<HTMLElement>('.react-flow__node');
    if (element) return nodeMenu(event, element.dataset.id!, element);
    const edgeElement = target.closest<HTMLElement>('.react-flow__edge');
    if (edgeElement) return edgeMenu(event, edgeById.get(edgeElement.dataset.id!)!, edgeElement);
    canvasMenu(event);
  };
  const startPortDrag = (_: unknown, port: OnConnectStartParams) => {
    if (edgeDrag.current || port.handleType !== 'target') return;
    inputDrag.current = graph.edges.find((e) => e.target === port.nodeId && handleOf(e) === port.handleId);
  };
  const endPortDrag = (_: unknown, state: FinalConnectionState) => {
    const edge = inputDrag.current;
    const to = state.toHandle;
    inputDrag.current = undefined;
    // React Flow connects an output to an input; an occupied input dragged onto another input moves the wire's end there
    if (edge && to?.type === 'target' && (edge.target !== to.nodeId || handleOf(edge) !== to.id) && canConnect(edge.source, to.nodeId, to.id, edge)) {
      connect(edge.source, to.nodeId, to.id, edge);
    }
  };
  const onNodesChange = (changes: NodeChange<MappingFlowNode>[]) => {
    const sizes = changes.flatMap((c) => (c.type === 'dimensions' && c.dimensions ? [c] : []));
    if (sizes.length) setMeasured((previous) => ({ ...previous, ...Object.fromEntries(sizes.map((c) => [c.id, c.dimensions!])) }));
    const selections = changes.flatMap((c) => (c.type === 'select' ? [c] : []));
    if (selections.length) changeSelection(selections);
    const moves = changes.flatMap((c) => (c.type === 'position' && c.position ? [c] : []));
    const live = moves.filter((c) => c.dragging);
    if (live.length) setMoving((previous) => ({ ...previous, ...Object.fromEntries(live.map((c) => [c.id, c.position!])) }));
    const ended = moves.filter((c) => !c.dragging);
    if (ended.length) {
      setMoving({});
      place({ ...shown, ...Object.fromEntries(ended.map((c) => [c.id, c.position!])) }, ended.map((c) => c.id));
    }
  };
  const onEdgesChange = (changes: EdgeChange[]) => {
    const selections = changes.flatMap((c) => (c.type === 'select' ? [c] : []));
    if (selections.length) changeSelection(selections);
  };
  const onDelete = ({ nodes: gone, edges: cut }: { nodes: MappingFlowNode[]; edges: Edge[] }) => {
    if (gone.length) remove(gone.map((n) => n.id));
    else for (const e of cut) disconnect(edgeById.get(e.id)!);
  };
  const drop = (event: DragEvent) => {
    event.preventDefault();
    const fn = event.dataTransfer.getData(functionDragType);
    if (!editing || !fn) return;
    const at = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
    addFunction(fn, { x: at.x - NODE_WIDTH / 2, y: at.y - 24 });
  };

  const nodes: MappingFlowNode[] = graph.nodes.map((n) => {
    const state = states.get(n.id)!;
    const settings = Object.entries(n.params ?? {})
      .flatMap(([name, value]) => (typeof value === 'string' ? [`${name} ${value}`] : []))
      .join(' · ');
    // a function box the catalogue lacks (a hand-written model) has no fidelity
    const fidelity = n.fn === undefined ? undefined : catalogue[n.fn]?.fidelity;
    const note = fidelity && deviationNote(fidelity);
    return {
      id: n.id,
      type: 'mapping',
      position: moving[n.id] ?? shown[n.id]!,
      data: {
        node: n,
        state,
        fieldType: n.role ? 'variable' : n.path === undefined ? undefined : fieldTypes[n.kind === 'source' ? 'source' : 'target'].get(n.path),
        ...(hasParameters(n) && { editParameters: () => openDialog('parameters', n.id) }),
        ...(settings && { settings }),
        ...(note && { note }),
        ...(orphaned.has(n.id) && { orphaned: true }),
        // drag-to-remap: a source row dropped on an orphaned box, in edit mode, remaps it as the Remap dialog does
        ...(editing && orphaned.has(n.id) && { remap: (field: FieldPath) => remap(n.id, field) }),
      },
      measured: measured[n.id],
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      selected: selected.has(n.id),
      deletable: n.kind !== 'target',
      // React Flow describes a node by its keyboard help, so a deviation note ends the name
      ariaLabel: `${nodeRole(n)} ${n.name}. ${state.description}${note ? ` ${note}` : ''}`,
    };
  });
  const edges: Edge[] = graph.edges.map((e) => {
    const on = selected.has(e.id);
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: 'out',
      targetHandle: handleOf(e),
      type: 'smoothstep',
      selected: on,
      markerEnd: { type: MarkerType.ArrowClosed, width: 13, height: 13, color: EDGE_COLOR },
      style: { stroke: on ? '#0066cc' : EDGE_COLOR, strokeWidth: on ? 3 : 2 },
    };
  });
  const editingBox = dialogNode('parameters');
  const editingPreview = editingBox && previewFn(editingBox.id);

  return (
    <section className="canvas-panel panel" aria-label="Field mapping canvas">
      <SideBySide
        className="expression-workspace"
        label="Resize functions panel"
        leftId="mapping-functions"
        browserState={browserState}
        widthKey="definition.functions.width"
        initialShare={0.1}
        showDivider={paletteOpen}
      >
        <aside id="mapping-functions" className="mapping-functions" hidden={!paletteOpen}>
          <h3>Functions</h3>
          {paletteOpen && <FunctionCatalogue catalogue={catalogue} disabled={!editing} add={(fn) => addFunction(fn)} addPreview={addPreview} />}
        </aside>
        <div className="expression-drawing">
          <div className="function-rail" role="toolbar" aria-label="Mapping tools" aria-orientation="vertical">
            <button
              type="button"
              aria-label={paletteOpen ? 'Hide functions' : 'Show functions'}
              title={paletteOpen ? 'Hide functions' : 'Show functions'}
              aria-controls="mapping-functions"
              aria-expanded={paletteOpen}
              onClick={togglePalette}
            >
              <LayerGroupIcon />
            </button>
            <button type="button" disabled={!editing} aria-label="Add node" title="Add node" onClick={() => actions.openCanvasDialog({ kind: 'add' })}>
              <CubeIcon />
            </button>
            <button
              type="button"
              disabled={!selectedNode}
              aria-label="Display Queue"
              title="Display Queue: the queue of the selected node in the last test run"
              onClick={() => openDialog('queue', selectedNode!.id)}
            >
              <ListIcon />
            </button>
            <button
              type="button"
              disabled={!editing || floating.length === 0}
              aria-label="Delete Floating Objects"
              title="Delete Floating Objects: remove all objects with no path to the target"
              onClick={deleteFloatingObjects}
            >
              <TrashIcon />
            </button>
            <button type="button" aria-label="Layout mapping" title="Lay Out Mapping" disabled={!editing} onClick={layOut}>
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M8 5h3v14H8m3-7h5" />
                <g fill="currentColor" stroke="none">
                  <rect x="1" y="2" width="7" height="6" rx="1.2" />
                  <rect x="1" y="16" width="7" height="6" rx="1.2" />
                  <rect x="16" y="9" width="7" height="6" rx="1.2" />
                </g>
              </svg>
            </button>
            <button
              type="button"
              disabled={!selectedNode || selectedNode.kind === 'target' || isPreviewId(selectedNode.id)}
              aria-label="Copy"
              title="Copy"
              onClick={() => copyBranch(selectedNode!.id)}
            >
              <CopyIcon />
            </button>
            <button type="button" disabled={!editing || !canPaste || variable} aria-label="Paste" title="Paste" onClick={paste}>
              <PasteIcon />
            </button>
          </div>
          <div className="flow-host" ref={host} role="group" aria-label="Mapping canvas" tabIndex={0} onKeyDown={onCanvasKey} onDragOver={(e) => e.preventDefault()} onDrop={drop}>
            <ReactFlow<MappingFlowNode>
              nodes={nodes}
              edges={edges}
              nodeTypes={types}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onDelete={onDelete}
              onConnect={(c: Connection) => connect(c.source, c.target, c.targetHandle, inputDrag.current)}
              onConnectStart={startPortDrag}
              onConnectEnd={endPortDrag}
              nodesDraggable={editing}
              nodesConnectable={editing}
              edgesReconnectable={editing}
              reconnectRadius={10}
              connectionRadius={15}
              onReconnectStart={(_, edge) => {
                edgeDrag.current = edge.id;
              }}
              onReconnectEnd={() => {
                edgeDrag.current = null;
              }}
              onReconnect={(edge, c) => connect(c.source, c.target, c.targetHandle, edgeById.get(edge.id))}
              onEdgeContextMenu={(event, edge) => edgeMenu(event, edgeById.get(edge.id)!, event.currentTarget as Element)}
              isValidConnection={(c) => canConnect(c.source, c.target, c.targetHandle, edgeById.get(edgeDrag.current ?? '') ?? inputDrag.current)}
              onNodeContextMenu={(event, n) => nodeMenu(event, n.id, (event.target as Element).closest('.react-flow__node')!)}
              onNodeDoubleClick={(_, n) => {
                if (n.data.editParameters) openDialog('parameters', n.id);
              }}
              onPaneContextMenu={canvasMenu}
              minZoom={1}
              maxZoom={1}
              zoomOnScroll={false}
              zoomOnPinch={false}
              zoomOnDoubleClick={false}
              zoomActivationKeyCode={null}
              panOnScroll
              onlyRenderVisibleElements={false}
              nodesFocusable
              edgesFocusable
              deleteKeyCode={editing ? ['Delete', 'Backspace'] : null}
              proOptions={{ hideAttribution: true }}
              fitView
              fitViewOptions={fixedView}
            >
              <Background gap={18} size={1} color="#bfd0d9" />
              <ViewFit nodeIds={JSON.stringify(nodes.map((n) => n.id))} host={host} />
            </ReactFlow>
            <MappingHint host={host} />
          </div>
        </div>
      </SideBySide>
      <footer className="canvas-footer">
        <span>
          {nodes.length} nodes <i>·</i> {edges.length} connections
        </span>
        <span className="mapping-legend" aria-label="Mapping status key">
          <span>✓ Complete</span>
          <span>! Incomplete</span>
          <span>– No target path</span>
        </span>
      </footer>
      {editingBox && editingPreview && (
        <PreviewParameters
          fn={editingPreview}
          values={previewNodes.get(editingBox.id)!.params}
          readOnly={!editing}
          close={closeDialog}
          save={(params) => {
            editPreview((d) => setParams(d, targetField, editingBox.id, params));
            closeDialog();
          }}
        />
      )}
      {editingBox && !editingPreview && (
        <FunctionParameters
          fn={editingBox.fn!}
          params={catalogue[editingBox.fn!]!.params!}
          values={editingBox.params}
          readOnly={!editing}
          close={closeDialog}
          save={(params) => {
            if (isPreviewId(editingBox.id)) editPreview((d) => setParams(d, targetField, editingBox.id, params));
            else actions.setParams(targetField, editingBox.id, params, catalogue);
            closeDialog();
          }}
        />
      )}
      {dialog?.kind === 'add' && (
        <Modal isOpen variant="small" aria-label="Add node" onClose={closeDialog}>
          <ModalHeader title="Add node" />
          <ModalBody>
            <FunctionCatalogue
              catalogue={catalogue}
              add={(fn) => {
                addFunction(fn);
                closeDialog();
              }}
              addPreview={(key) => {
                addPreview(key);
                closeDialog();
              }}
            />
            <section className="palette-variables" aria-label="Variables">
              <h3>
                Variables <PreviewBadge feature="Variables" issue={VARIABLES_ISSUE} />
              </h3>
              {scope.length === 0 ? (
                <p className="muted">
                  No variables are available for this target. Choose Add Variable on a parent in the target tree, or open a target that follows an existing variable in the
                  same branch.
                </p>
              ) : (
                <ul>
                  {scope.map((v) => (
                    <li key={v}>
                      <button
                        type="button"
                        onClick={() => {
                          addPreviewNode({ kind: 'variable', ref: v });
                          closeDialog();
                        }}
                      >
                        <FieldTypeIcon type="variable" />
                        <b>{variableName(v)}</b>
                        <small>/{localPath(v)}</small>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </ModalBody>
        </Modal>
      )}
      {remappingNode && (
        <RemapDialog
          field={remappingNode.path!}
          choices={sourceFields}
          close={closeDialog}
          remap={(field) => {
            remap(remappingNode.id, field);
            closeDialog();
          }}
        />
      )}
      {/* an undo or a delete that removes the node closes its queue */}
      {queueNode && (
        <QueueDialog
          graph={graph}
          node={queueNode}
          trace={trace}
          stale={stale}
          mappingId={mapping?.id}
          browserState={browserState}
          test={{ session, library: testInstances(store, browserState, mappingId), format: formatOf(model.source!), level: traceLevel, editing }}
          onClose={closeDialog}
        />
      )}
      <ActionMenu menu={menu} onClose={closeMenu} />
    </section>
  );
}

/** The mapping expression canvas of one target field mapping (the prototype's Canvas), with its functions drilldown and tools. */
export function Canvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <Drawing {...props} />
    </ReactFlowProvider>
  );
}
