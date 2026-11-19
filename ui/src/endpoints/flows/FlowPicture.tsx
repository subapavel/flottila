import {
  Alert,
  AlertActionCloseButton,
  AlertGroup,
  Bullseye,
  Button,
  CodeBlock,
  CodeBlockCode,
  Drawer,
  DrawerActions,
  DrawerCloseButton,
  DrawerContent,
  DrawerContentBody,
  DrawerHead,
  DrawerPanelBody,
  DrawerPanelContent,
  ExpandableSection,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  Label,
  List,
  ListItem,
  Menu,
  MenuContent,
  MenuItem,
  MenuList,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  TextInput,
  Title,
} from '@patternfly/react-core';
import { CheckCircleIcon, EditIcon, SaveIcon, SyncAltIcon, UndoIcon } from '@patternfly/react-icons';
import { ReactFlowProvider } from '@xyflow/react';
import { useCallback, useEffect, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import { DiscardDialog } from '../../DiscardDialog';
import type { CloseGuard } from '../EndpointForm';
import { FlowsError, type FlowsApi, type StoredFlow } from './api';
import { edit, EditRefused, follow, placeOf, type Op } from './edit';
import { FlowCanvas } from './FlowCanvas';
import { readFlow } from './flowYaml';
import { FlowPanel, type PanelEdit } from './FlowPanel';
import { addProcess, addStep, callable, callOf, callsOf, deleteProcess, deleteStep, moveStep, moveToList, renameProcess, stepListsOf } from './ops';
import { BUILT_IN } from './steps';
import type { FlowNode, FlowProblem, Path, ServedSteps } from './types';
import { pointer, view } from './view';

/** The most texts undo goes back through. */
const MAX_UNDO = 100;

export interface FlowPictureProps {
  api: FlowsApi;
  /** The flow file's path in the working folder. */
  path: string;
  /** Shows the endpoint file at this path in the component's form, read-only. */
  endpointForm: (path: string) => ReactNode;
  openEndpoint: (path: string) => void;
  openFlow: (path: string) => void;
  /** Creates the endpoint file at this path with this text, and fails when it exists. */
  createEndpoint: (path: string, text: string) => Promise<unknown>;
  /** Given the question to ask before the tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; stored: StoredFlow; steps: ServedSteps };

/**
 * The working copy while the flow is edited: its text, the texts before its changes (the last at the end), and the key
 * of the field the last change changed.
 */
interface Work {
  text: string;
  undo: string[];
  key: string | null;
}

type Discarding = { then: 'return to view mode' } | { then: 'close its tab'; close: () => void };

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * A flow file as a picture: its steps on a canvas, and beside it a drawer with the settings of the node chosen, or the
 * flow's own when none is. A file that cannot be drawn shows its text with its problems.
 *
 * It opens read-only; Edit, when the file may be edited and the flow is drawn, then Undo, Cancel, Check and Save. While
 * it is edited, the button on an edge adds a step there, or a call of another flow; the drawer moves the step chosen
 * within its list or to another, deletes it and changes its fields, renames and deletes the process of the lane chosen,
 * adds a process and changes the flow's own settings; and the canvas, while it has the focus, takes Delete, the up and
 * down arrows and Ctrl+Z (Cmd+Z). A call of a flow whose address no `direct` out endpoint of the package names creates
 * that endpoint file first, as a save of its own that Cancel leaves in place.
 * Each change is a new text from `edit`; undo goes back through the last 100. The selection follows its step through
 * every change, and is kept through an undo while its path is in the view. The problems are found again after each
 * change and never block Save, which a field holding text that cannot become a value does. Save sends the text with the
 * version it read; a file changed on disk since opens a dialog. Cancel, or closing the tab, with unsaved changes asks
 * first, and the page asks before it unloads.
 */
export function FlowPicture({ api, path, endpointForm, openEndpoint, openFlow, createEndpoint, guardClose }: FlowPictureProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [selected, setSelected] = useState<Path | null>(null);
  const [work, setWork] = useState<Work | null>(null);
  // a new generation remounts the controls that hold text of their own, so that text goes with the text it belonged to
  const [generation, setGeneration] = useState(0);
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(new Set());
  // the id of the node whose form the drawer shows, whose controls `invalid` speaks of
  const [formShown, setFormShown] = useState<string | null>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [check, setCheck] = useState<FlowProblem[] | null>(null);
  const [adding, setAdding] = useState<Path | null>(null);
  // the endpoint file a call of a flow is creating, and why that failed
  const [calling, setCalling] = useState<string | null>(null);
  const [callFailure, setCallFailure] = useState<string | null>(null);
  // the new process asked for, and why it was refused
  const [newProcess, setNewProcess] = useState<{ name: string; type: string; refused?: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveFailure, setSaveFailure] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState(0);
  const [discarding, setDiscarding] = useState<Discarding | null>(null);
  const ids = useId();
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    let current = true;
    Promise.all([api.read(path), api.steps()]).then(
      ([stored, steps]) => current && setResult({ kind: 'read', stored, steps }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, path, asked]);

  const stored = result.kind === 'read' ? result.stored : null;
  const text = work?.text ?? stored?.file.text ?? '';
  const drawn = useMemo(() => {
    if (result.kind !== 'read') return null;
    const read = readFlow(text);
    return { view: view(text, { ...result.stored.file.context, steps: result.steps }), value: read.kind === 'flow' ? read.value : {} };
  }, [result, text]);

  const unsaved = work !== null && stored !== null && work.text !== stored.file.text;

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.((close) => setDiscarding({ then: 'close its tab', close }));
    return () => guardClose?.(null);
  }, [guardClose, unsaved]);

  if (result.kind === 'reading') {
    return (
      <PageSection>
        <Bullseye>
          <Spinner aria-label="Reading the flow file" />
        </Bullseye>
      </PageSection>
    );
  }
  if (result.kind === 'failed' || drawn === null) {
    return (
      <PageSection>
        <Alert variant="danger" isInline title="The flow file could not be read">
          {result.kind === 'failed' ? result.message : ''}
        </Alert>
      </PageSection>
    );
  }

  const { view: picture, value } = drawn;
  const file = result.stored.file;
  const errors = picture.problems.filter((p) => p.warning !== true).length;
  const warnings = picture.problems.length - errors;
  // the selection is kept while its path is in the view
  const node: FlowNode | null = selected === null ? null : (picture.nodes.find((n) => n.id === pointer(selected)) ?? null);
  if (selected !== null && node === null) setSelected(null);
  // the form of another node, or of the selected step at its new place, starts from the text: what the controls before
  // it held that could not become a value is gone with them
  if ((node?.id ?? null) !== formShown) {
    setFormShown(node?.id ?? null);
    if (invalid.size > 0) setInvalid(new Set());
  }
  const calls = node === null ? undefined : picture.nodes.find((n) => n.id === `${node.id}#calls`);
  const canEdit = file.editable && picture.readable && picture.readOnly === undefined;

  /**
   * Applies `ops`, or the operations `ops` builds, to the working copy; a change of the field `key` right after another
   * of it is undone with it.
   */
  const change = (build: Op[] | (() => Op[]), key: string | null = null): boolean => {
    if (work === null) return false;
    let next: string;
    let ops: Op[];
    try {
      ops = typeof build === 'function' ? build() : build;
      if (ops.length === 0) return false;
      next = edit(work.text, ...ops);
    } catch (failed) {
      if (!(failed instanceof EditRefused)) throw failed;
      setRefused(failed.message);
      return false;
    }
    setRefused(null);
    setCheck(null);
    if (selected !== null) setSelected(follow(selected, ops));
    if (next !== work.text) {
      const undo = key !== null && work.key === key ? work.undo : [...work.undo, work.text].slice(-MAX_UNDO);
      setWork({ text: next, undo, key });
    }
    return true;
  };

  const startAgain = () => {
    setGeneration((g) => g + 1);
    setInvalid(new Set());
    setRefused(null);
    setCheck(null);
  };

  const undo = () => {
    const last = work?.undo.at(-1);
    if (work === null || last === undefined) return;
    setWork({ text: last, undo: work.undo.slice(0, -1), key: null });
    startAgain();
  };

  // the step chosen, where it stands in its list, and how long that list is
  const step = node !== null && node.kind === 'step' && typeof node.path.at(-1) === 'number' ? node : null;
  const index = step === null ? -1 : (step.path.at(-1) as number);
  const length = step === null ? 0 : picture.nodes.filter((n) => n.kind === 'step' && n.path.length === step.path.length && n.path.slice(0, -1).every((s, i) => step.path[i] === s)).length;
  const move = (by: -1 | 1) => {
    if (step !== null) change(moveStep(step.path, [...step.path.slice(0, -1), index + by]));
  };
  const remove = () => {
    if (step !== null) change(deleteStep(step.path));
  };
  const add = (type: string) => {
    const at = adding;
    setAdding(null);
    if (at !== null && change(addStep(picture, at, type))) setSelected(at);
  };
  // a call of the flow `called` at `adding`: its endpoint file is created first when the package has none, and the
  // context read again so that the picture knows it
  const addCall = async (called: { id: string; address?: string }) => {
    const at = adding;
    if (at === null) return;
    const call = callOf(file.context, path, called);
    if (call.create !== undefined) {
      const created = call.create.path;
      setCalling(created);
      setCallFailure(null);
      try {
        await createEndpoint(created, call.create.text);
      } catch (error) {
        setCallFailure(`${created} was not created: ${messageOf(error)}`);
        setCalling(null);
        return;
      }
      try {
        const fresh = await api.read(path);
        setResult({ ...result, stored: { ...result.stored, file: { ...result.stored.file, context: fresh.file.context } } });
      } catch (error) {
        setCallFailure(`${created} was created, but the flow's endpoints could not be read again: ${messageOf(error)}. Refresh after Save shows them.`);
        setCalling(null);
        return;
      }
      setCalling(null);
    }
    setAdding(null);
    if (change(addStep(picture, at, 'to', { endpoint: call.endpoint }))) setSelected(at);
  };
  const flowsCalled = callable(file.context, path);
  const lane = node !== null && node.kind === 'lane' ? node : null;
  const lists = step === null ? [] : stepListsOf(picture).filter((l) => pointer(l) !== pointer(step.path.slice(0, -1)));
  const createProcess = () => {
    if (newProcess === null) return;
    const name = newProcess.name;
    let ops: Op[];
    try {
      ops = addProcess(picture, value, name, newProcess.type);
    } catch (failed) {
      if (!(failed instanceof EditRefused)) throw failed;
      setNewProcess({ ...newProcess, refused: failed.message });
      return;
    }
    if (change(ops)) {
      setNewProcess(null);
      setSelected(['processes', name, 'steps']);
    }
  };

  // the types a step can be added of: the built-in ones, then those the node serves
  const served = Array.isArray(result.steps) ? result.steps.map((s) => s.type).filter((t) => !Object.hasOwn(BUILT_IN, t)) : [];
  const addable = [...Object.keys(BUILT_IN).map((type) => ({ type, from: 'Built in' })), ...[...served].sort().map((type) => ({ type, from: 'Served by the node' }))];

  const markInvalid = (key: string, bad: boolean) =>
    setInvalid((set) => {
      if (set.has(key) === bad) return set;
      const next = new Set(set);
      if (bad) next.add(key);
      else next.delete(key);
      return next;
    });

  const panelEdit: PanelEdit | undefined =
    work === null
      ? undefined
      : {
          change,
          invalid: markInvalid,
          move,
          remove,
          up: index > 0,
          down: index >= 0 && index < length - 1,
          lists: lists.map((l) => ({ path: l, label: l.length === 1 ? 'the flow’s steps' : `process ${String(l[1])}` })),
          moveTo: (list) => {
            if (step !== null) change(moveToList(picture, step.path, list));
          },
          renameProcess: (from, to) => {
            if (change(() => renameProcess(picture, value, from, to))) setSelected(['processes', to, 'steps']);
          },
          deleteProcess: (name) => change(deleteProcess(value, name)),
          addProcess: () => setNewProcess({ name: '', type: addable[0]?.type ?? 'to' }),
          generation,
        };

  /** The canvas's keys, while it has the focus and the flow is edited: never those typed into a field. */
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (work === null || (event.target as HTMLElement).closest('input, textarea, select, [contenteditable="true"]') !== null) return;
    if ((event.ctrlKey || event.metaKey) && !event.shiftKey && event.key.toLowerCase() === 'z') {
      event.preventDefault();
      undo();
    } else if (step !== null && (event.key === 'Delete' || event.key === 'Backspace')) {
      event.preventDefault();
      remove();
    } else if (step !== null && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      event.preventDefault();
      if (event.key === 'ArrowUp' ? index > 0 : index < length - 1) move(event.key === 'ArrowUp' ? -1 : 1);
    }
  };

  const leaveEditing = () => {
    setDiscarding(null);
    setSaveFailure(null);
    setWork(null);
    startAgain();
  };
  const discard = () => {
    if (discarding?.then === 'close its tab') {
      setDiscarding(null);
      discarding.close();
    } else leaveEditing();
  };

  const save = async () => {
    if (work === null) return;
    setSaving(true);
    setSaveFailure(null);
    try {
      const next = await api.save(path, work.text, result.stored.etag);
      // editing goes on from the text saved, and undo still goes back through it
      setResult({ kind: 'read', stored: next, steps: result.steps });
      setSaved((n) => n + 1);
    } catch (error) {
      if (error instanceof FlowsError && error.status === 412) setConflict(true);
      else setSaveFailure(messageOf(error));
    } finally {
      setSaving(false);
    }
  };

  const reload = () => {
    setConflict(false);
    leaveEditing();
    refresh();
  };

  const header = (
    <Stack hasGutter>
      <StackItem>
        <Flex alignItems={{ default: 'alignItemsCenter' }}>
          <FlexItem>
            <Title headingLevel="h1">{typeof value.id === 'string' ? value.id : path}</Title>
          </FlexItem>
          {!canEdit && (
            <FlexItem>
              <Label variant="outline">Read-only</Label>
            </FlexItem>
          )}
          {errors > 0 && (
            <FlexItem>
              <Label status="danger">{errors === 1 ? '1 problem' : `${errors} problems`}</Label>
            </FlexItem>
          )}
          {warnings > 0 && (
            <FlexItem>
              <Label status="warning">{warnings === 1 ? '1 warning' : `${warnings} warnings`}</Label>
            </FlexItem>
          )}
          <FlexItem align={{ default: 'alignRight' }}>
            {work !== null ? (
              <>
                <Button size="sm" variant="secondary" icon={<UndoIcon />} isDisabled={work.undo.length === 0} onClick={undo}>
                  Undo
                </Button>{' '}
                <Button size="sm" variant="link" onClick={() => (unsaved ? setDiscarding({ then: 'return to view mode' }) : leaveEditing())}>
                  Cancel
                </Button>{' '}
                <Button size="sm" variant="secondary" icon={<CheckCircleIcon />} onClick={() => setCheck(picture.problems)}>
                  Check
                </Button>{' '}
                <Button size="sm" variant="primary" icon={<SaveIcon />} isDisabled={!unsaved || saving || invalid.size > 0} onClick={() => void save()}>
                  Save
                </Button>
              </>
            ) : (
              <>
                <Button variant="secondary" size="sm" icon={<SyncAltIcon />} onClick={refresh}>
                  Refresh
                </Button>
                {canEdit && (
                  <>
                    {' '}
                    <Button size="sm" variant="primary" icon={<EditIcon />} onClick={() => setWork({ text: file.text, undo: [], key: null })}>
                      Edit
                    </Button>
                  </>
                )}
              </>
            )}
          </FlexItem>
        </Flex>
      </StackItem>
      <StackItem>
        <code>{path}</code>
      </StackItem>
      {file.editable && picture.readable && picture.readOnly !== undefined && (
        <StackItem>
          <Alert variant="info" isInline isPlain title={`The editor shows this flow read-only: ${picture.readOnly}.`} />
        </StackItem>
      )}
      {refused !== null && (
        <StackItem>
          <Alert
            variant="danger"
            isInline
            title="The change was not made"
            actionClose={<AlertActionCloseButton aria-label="Dismiss the refusal" onClose={() => setRefused(null)} />}
          >
            {refused}
          </Alert>
        </StackItem>
      )}
      {check !== null && (
        <StackItem>
          <Alert
            variant={check.length > 0 ? 'warning' : 'success'}
            isInline
            role="status"
            title={check.length > 0 ? 'Check found issues' : 'Check passed'}
            actionClose={<AlertActionCloseButton aria-label="Dismiss check" onClose={() => setCheck(null)} />}
          >
            {check.length > 0 && (
              <List aria-label="Issues">
                {check.map((p, i) => (
                  <ListItem key={i}>
                    {placeOf(p.path)}: {p.message}
                  </ListItem>
                ))}
              </List>
            )}
            <p>The node checks the flow again when the package is deployed.</p>
          </Alert>
        </StackItem>
      )}
      {saveFailure !== null && (
        <StackItem>
          <Alert variant="danger" isInline title="The file was not saved">
            {saveFailure}
          </Alert>
        </StackItem>
      )}
      {picture.problems.length > 0 && (
        <StackItem>
          <ExpandableSection toggleTextCollapsed="Show the problems" toggleTextExpanded="Hide the problems" isExpanded={!picture.readable || undefined}>
            <HelperText>
              {picture.problems.map((p, i) => (
                <HelperTextItem key={i} variant={p.warning === true ? 'warning' : 'error'}>
                  {placeOf(p.path)}: {p.message}
                </HelperTextItem>
              ))}
            </HelperText>
          </ExpandableSection>
        </StackItem>
      )}
    </Stack>
  );

  if (!picture.readable) {
    return (
      <PageSection>
        <Stack hasGutter>
          <StackItem>{header}</StackItem>
          <StackItem>
            <Alert variant="warning" isInline title="The flow cannot be drawn">
              The file is shown as it is, with what keeps it from being drawn.
            </Alert>
          </StackItem>
          <StackItem>
            <CodeBlock>
              <CodeBlockCode>{result.stored.file.text}</CodeBlockCode>
            </CodeBlock>
          </StackItem>
        </Stack>
      </PageSection>
    );
  }

  const panel = (
    <DrawerPanelContent isResizable defaultSize="28rem" minSize="20rem">
      <DrawerHead>
        <span />
        {node !== null && (
          <DrawerActions>
            <DrawerCloseButton aria-label="Show the flow's settings" onClick={() => setSelected(null)} />
          </DrawerActions>
        )}
      </DrawerHead>
      <DrawerPanelBody>
        <FlowPanel
          flow={value}
          {...(picture.settings === undefined ? {} : { settings: picture.settings })}
          node={node}
          {...(calls === undefined ? {} : { calls })}
          {...(lane === null ? {} : { laneCalls: callsOf(picture, lane.label) })}
          problems={picture.problems}
          endpointForm={endpointForm}
          openEndpoint={openEndpoint}
          openFlow={openFlow}
          {...(panelEdit === undefined ? {} : { edit: panelEdit })}
        />
      </DrawerPanelBody>
    </DrawerPanelContent>
  );

  return (
    <PageSection padding={{ default: 'noPadding' }}>
      <Stack>
        <StackItem>
          <PageSection>{header}</PageSection>
        </StackItem>
        <StackItem isFilled>
          <Drawer isExpanded isInline position="end">
            <DrawerContent panelContent={panel}>
              <DrawerContentBody>
                {/* the canvas takes its keys while it, or a node on it, has the focus */}
                <div style={{ height: '70vh', minHeight: '24rem' }} data-testid="flow-canvas" tabIndex={-1} onKeyDown={onKey}>
                  <ReactFlowProvider>
                    <FlowCanvas
                      view={picture}
                      selected={selected === null ? null : pointer(selected)}
                      onSelect={(n) => setSelected(n.path)}
                      {...(work === null ? {} : { onInsert: setAdding })}
                    />
                  </ReactFlowProvider>
                </div>
              </DrawerContentBody>
            </DrawerContent>
          </Drawer>
        </StackItem>
      </Stack>
      <Modal
        variant="small"
        isOpen={adding !== null}
        onClose={() => {
          if (calling === null) {
            setAdding(null);
            setCallFailure(null);
          }
        }}
        aria-labelledby={`${ids}-add`}
      >
        <ModalHeader title="Add a step" labelId={`${ids}-add`} description={adding === null ? undefined : `At ${placeOf(adding)}`} />
        <ModalBody>
          <Stack hasGutter>
            <StackItem>
              <Menu isPlain aria-label="Step types" onSelect={(_, type) => add(String(type))}>
                <MenuContent>
                  <MenuList>
                    {addable.map(({ type, from }) => (
                      <MenuItem key={type} itemId={type} description={from} isDisabled={calling !== null}>
                        {type}
                      </MenuItem>
                    ))}
                  </MenuList>
                </MenuContent>
              </Menu>
            </StackItem>
            {flowsCalled.length > 0 && (
              <StackItem>
                <Title headingLevel="h3">Call a flow</Title>
                <Menu
                  isPlain
                  aria-label="Flows to call"
                  onSelect={(_, id) => {
                    const called = flowsCalled.find((f) => f.path === id);
                    if (called !== undefined) void addCall(called);
                  }}
                >
                  <MenuContent>
                    <MenuList>
                      {flowsCalled.map((f) => (
                        <MenuItem key={f.path} itemId={f.path} description={`Serves ${f.address ?? ''}`} isDisabled={calling !== null}>
                          {f.id}
                        </MenuItem>
                      ))}
                    </MenuList>
                  </MenuContent>
                </Menu>
              </StackItem>
            )}
            {calling !== null && (
              <StackItem>
                <Spinner size="md" aria-label={`Creating ${calling}`} /> Creating the endpoint {calling}
              </StackItem>
            )}
            {callFailure !== null && (
              <StackItem>
                <Alert variant="danger" isInline title="The call was not added">
                  {callFailure}
                </Alert>
              </StackItem>
            )}
          </Stack>
        </ModalBody>
      </Modal>
      <Modal variant="small" isOpen={newProcess !== null} onClose={() => setNewProcess(null)} aria-labelledby={`${ids}-process`}>
        <ModalHeader title="Add a process" labelId={`${ids}-process`} description="A process holds at least one step: choose its first." />
        <ModalBody>
          <Form
            id={`${ids}-process-form`}
            onSubmit={(event) => {
              event.preventDefault();
              createProcess();
            }}
          >
            <FormGroup label="Name" isRequired fieldId={`${ids}-process-name`}>
              <TextInput
                id={`${ids}-process-name`}
                isRequired
                value={newProcess?.name ?? ''}
                validated={newProcess?.refused === undefined ? 'default' : 'error'}
                onChange={(_, name) => setNewProcess((p) => (p === null ? p : { name, type: p.type }))}
              />
              {newProcess?.refused !== undefined && (
                <HelperText>
                  <HelperTextItem variant="error">{newProcess.refused}</HelperTextItem>
                </HelperText>
              )}
            </FormGroup>
            <FormGroup label="First step" fieldId={`${ids}-process-type`}>
              <FormSelect id={`${ids}-process-type`} value={newProcess?.type ?? ''} onChange={(_, type) => setNewProcess((p) => (p === null ? p : { ...p, type }))}>
                {addable.map(({ type }) => (
                  <FormSelectOption key={type} value={type} label={type} />
                ))}
              </FormSelect>
            </FormGroup>
          </Form>
        </ModalBody>
        <ModalFooter>
          <Button variant="primary" type="submit" form={`${ids}-process-form`} isDisabled={newProcess?.name === ''}>
            Add
          </Button>
          <Button variant="link" onClick={() => setNewProcess(null)}>
            Cancel
          </Button>
        </ModalFooter>
      </Modal>
      <DiscardDialog isOpen={discarding !== null} name={typeof value.id === 'string' ? value.id : path} then={discarding?.then ?? ''} onDiscard={discard} onKeepEditing={() => setDiscarding(null)} />
      <Modal variant="small" isOpen={conflict} onClose={() => setConflict(false)} aria-labelledby={`${ids}-conflict`}>
        <ModalHeader title="The file changed on disk" labelId={`${ids}-conflict`} />
        <ModalBody>
          <p>
            <strong>{path}</strong> changed since it was opened, and nothing was saved. Reload shows the file as it is now and discards your changes.
          </p>
        </ModalBody>
        <ModalFooter>
          <Button variant="primary" onClick={reload}>
            Reload
          </Button>
          <Button variant="link" onClick={() => setConflict(false)}>
            Keep editing
          </Button>
        </ModalFooter>
      </Modal>
      <AlertGroup isToast isLiveRegion aria-label="Save notifications">
        {saved > 0 && (
          <Alert
            key={saved}
            variant="success"
            title={`Flow ${typeof value.id === 'string' ? value.id : path} saved`}
            timeout={8000}
            actionClose={<AlertActionCloseButton aria-label="Dismiss save notification" onClose={() => setSaved(0)} />}
          />
        )}
      </AlertGroup>
    </PageSection>
  );
}
