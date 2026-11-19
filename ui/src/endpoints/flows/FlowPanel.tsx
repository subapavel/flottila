import {
  Alert,
  Button,
  Content,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  Stack,
  StackItem,
  Tab,
  TabContentBody,
  Tabs,
  TabTitleText,
  TextInput,
  Title,
} from '@patternfly/react-core';
import { ArrowDownIcon, ArrowUpIcon, PlusCircleIcon, TrashIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useState, type ReactNode } from 'react';
import { FieldControl } from '../FieldControl';
import { RawOptions } from '../RawOptions';
import { placeOf, type Op } from './edit';
import { addEntry, addRow, moveRow, removeRow, renameEntry, setField, setKeys } from './ops';
import { FLOW_PARTS, STEP_KEYS, stepTabFields } from './steps';
import type { FlowNode, FlowProblem, Path, StepField, StepForm, ValueField } from './types';
import { pointer } from './view';

/** What the panel may change while the flow is edited. */
export interface PanelEdit {
  /**
   * Told the operations a change makes, or how to build them (a refusal of either is shown), and the key of the field it
   * changes: changes of one field in a row are undone at once. Answers whether the change was made.
   */
  change: (ops: Op[] | (() => Op[]), key?: string | null) => boolean;
  /** Told whether the field of `key` holds text that cannot become a value, which cannot be saved. */
  invalid: (key: string, bad: boolean) => void;
  /** Moves the selected step up (-1) or down (1) in its list. */
  move: (by: -1 | 1) => void;
  /** Deletes the selected step. */
  remove: () => void;
  /** Whether the selected step can move up, and down, in its list. */
  up: boolean;
  down: boolean;
  /** The other lists of steps the selected step can be moved to the end of, with their names. */
  lists: { path: Path; label: string }[];
  /** Moves the selected step to the end of the list at `list`. */
  moveTo: (list: Path) => void;
  /** Renames the process `from` to `to`, and every call of it. */
  renameProcess: (from: string, to: string) => void;
  /** Deletes the process `name`. */
  deleteProcess: (name: string) => void;
  /** Asks for the name and the first step of a new process. */
  addProcess: () => void;
  /** Changes each time the text is set from outside the form (undo, save, reload): controls holding text of their own start again. */
  generation: number;
}

const shown = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value));

const samePath = (a: Path, b: Path) => a.length === b.length && a.every((s, i) => b[i] === s);

/** The problems at `path`, as a list. */
function Problems({ problems }: { problems: FlowProblem[] }) {
  if (problems.length === 0) return null;
  return (
    <HelperText>
      {problems.map((p, i) => (
        <HelperTextItem key={i} variant={p.warning === true ? 'warning' : 'error'}>
          {placeOf(p.path)}: {p.message}
        </HelperTextItem>
      ))}
    </HelperText>
  );
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** The problems at `at` or inside it. */
const inside = (problems: FlowProblem[], at: Path) => problems.filter((p) => at.every((s, i) => p.path[i] === s));

/**
 * One value of a row of a table: a select for a choice or true or false, else a text input, which takes empty text as
 * a value when its field is required. Read-only it shows the value.
 */
function Cell({ field, value, label, problem, onChange }: { field: StepField; value: unknown; label: string; problem: boolean; onChange?: (value: unknown) => void }) {
  const validated = problem ? 'error' : 'default';
  if (onChange === undefined || field.type === 'list' || field.type === 'map' || field.type === 'raw') return <>{shown(value)}</>;
  if (field.type === 'choice' || field.type === 'boolean') {
    const values = field.type === 'boolean' ? ['true', 'false'] : (field.choices ?? []);
    return (
      <FormSelect aria-label={label} value={shown(value)} validated={validated} onChange={(_, chosen) => onChange(chosen === '' ? undefined : field.type === 'boolean' ? chosen === 'true' : chosen)}>
        <FormSelectOption value="" label="Not set" />
        {value !== undefined && value !== null && !values.includes(shown(value)) && <FormSelectOption value={shown(value)} label={shown(value)} />}
        {values.map((v) => (
          <FormSelectOption key={v} value={v} label={v} />
        ))}
      </FormSelect>
    );
  }
  return <TextInput aria-label={label} value={shown(value)} validated={validated} onChange={(_, text) => onChange(text === '' && !field.required ? undefined : text)} />;
}

/** The new row of a list field: each required field of an item, empty. */
const newRow = (item: readonly StepField[]) => Object.fromEntries(item.filter((f) => f.required).map((f) => [f.name, '']));

/**
 * A list field as a table: one row per item, one column per field of an item. While the flow is edited a row's values
 * change in place, and a row is added at the end, moved up or down, and removed, but the last one.
 */
function ListTable({ field, values, at, problems, edit }: { field: Extract<StepField, { type: 'list' }>; values: Record<string, unknown>; at: Path; problems: FlowProblem[]; edit?: PanelEdit }) {
  const items = Array.isArray(values[field.name]) ? (values[field.name] as unknown[]) : [];
  const step = at.slice(0, -1);
  return (
    <FormGroup label={field.name} fieldId={`${placeOf(at)}`}>
      <Content component="small">{field.help}</Content>
      <Table aria-label={field.name} variant="compact">
        <Thead>
          <Tr>
            {field.item.map((f) => (
              <Th key={f.name}>{f.name}</Th>
            ))}
            {edit !== undefined && <Th screenReaderText="Row actions" />}
          </Tr>
        </Thead>
        <Tbody>
          {items.map((item, i) => {
            const row = isRecord(item) ? item : {};
            return (
              <Tr key={i}>
                {field.item.map((f) => (
                  <Td key={f.name} dataLabel={f.name}>
                    <Cell
                      field={f}
                      value={row[f.name]}
                      label={`${f.name} of row ${i + 1}`}
                      problem={inside(problems, [...at, i, f.name]).length > 0}
                      {...(edit === undefined || !isRecord(item) ? {} : { onChange: (next: unknown) => edit.change(setField([...at, i], row, f.name, next, f.required), pointer([...at, i, f.name])) })}
                    />
                  </Td>
                ))}
                {edit !== undefined && (
                  <Td isActionCell>
                    <Button variant="plain" size="sm" aria-label={`Move row ${i + 1} up`} icon={<ArrowUpIcon />} isDisabled={i === 0} onClick={() => edit.change(moveRow(at, i, i - 1))} />
                    <Button variant="plain" size="sm" aria-label={`Move row ${i + 1} down`} icon={<ArrowDownIcon />} isDisabled={i === items.length - 1} onClick={() => edit.change(moveRow(at, i, i + 1))} />
                    <Button variant="plain" size="sm" aria-label={`Remove row ${i + 1}`} icon={<TrashIcon />} isDisabled={items.length === 1} onClick={() => edit.change(removeRow(at, i))} />
                  </Td>
                )}
              </Tr>
            );
          })}
        </Tbody>
      </Table>
      {edit !== undefined && (
        <Button variant="link" isInline icon={<PlusCircleIcon />} onClick={() => edit.change(addRow(step, values, field.name, newRow(field.item)))}>
          Add a row
        </Button>
      )}
      <Problems problems={inside(problems, at)} />
    </FormGroup>
  );
}

/**
 * The key of an entry of a map field, while the flow is edited: renamed in place when the field loses the focus or Enter
 * is pressed, and held as typed, unsaveable, while it is empty, taken or does not match the field's pattern.
 */
function EntryKey({ field, name, taken, at, edit }: { field: Extract<StepField, { type: 'map' }>; name: string; taken: string[]; at: Path; edit: PanelEdit }) {
  const [text, setText] = useState(name);
  const key = `${pointer([...at, name])}#key`;
  const wrong =
    text === ''
      ? 'is empty'
      : text !== name && taken.includes(text)
        ? 'is taken'
        : field.key.pattern !== undefined && !new RegExp(`^(?:${field.key.pattern})$`).test(text)
          ? `must match ${field.key.pattern}`
          : null;
  const commit = () => {
    if (wrong !== null) return;
    edit.invalid(key, false);
    edit.change(renameEntry(at, name, text));
  };
  return (
    <>
      <TextInput
        aria-label={`Value ${name}`}
        value={text}
        validated={wrong === null ? 'default' : 'error'}
        onChange={(_, next) => {
          setText(next);
          edit.invalid(key, next !== name);
        }}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit();
        }}
      />
      {wrong !== null && (
        <HelperText>
          <HelperTextItem variant="error">{wrong}</HelperTextItem>
        </HelperText>
      )}
    </>
  );
}

/**
 * A map field as a table: one row per entry, its key and its value. While the flow is edited an entry's value changes
 * in place, its key is renamed in place, and an entry is added at the end and removed, but the last one.
 */
function MapTable({ field, values, at, problems, edit }: { field: Extract<StepField, { type: 'map' }>; values: Record<string, unknown>; at: Path; problems: FlowProblem[]; edit?: PanelEdit }) {
  const value = values[field.name];
  const entries = isRecord(value) ? Object.entries(value) : [];
  const keys = entries.map(([k]) => k);
  return (
    <FormGroup label={field.name} fieldId={`${placeOf(at)}`}>
      <Content component="small">{field.help}</Content>
      <Table aria-label={field.name} variant="compact">
        <Thead>
          <Tr>
            <Th>Value</Th>
            <Th>{field.value.name}</Th>
            {edit !== undefined && <Th screenReaderText="Row actions" />}
          </Tr>
        </Thead>
        <Tbody>
          {entries.map(([key, v], i) => (
            <Tr key={edit === undefined ? key : `${i}-${key}-${edit.generation}`}>
              <Td dataLabel="Value">{edit === undefined ? key : <EntryKey field={field} name={key} taken={keys} at={at} edit={edit} />}</Td>
              <Td dataLabel={field.value.name}>
                <Cell
                  field={field.value}
                  value={v}
                  label={`${field.value.name} of ${key}`}
                  problem={inside(problems, [...at, key]).length > 0}
                  {...(edit === undefined ? {} : { onChange: (next: unknown) => edit.change(setField(at, value as Record<string, unknown>, key, next, true), pointer([...at, key])) })}
                />
              </Td>
              {edit !== undefined && (
                <Td isActionCell>
                  <Button variant="plain" size="sm" aria-label={`Remove ${key}`} icon={<TrashIcon />} isDisabled={entries.length === 1} onClick={() => edit.change(removeRow(at, key))} />
                </Td>
              )}
            </Tr>
          ))}
        </Tbody>
      </Table>
      {edit !== undefined && (
        <Button variant="link" isInline icon={<PlusCircleIcon />} onClick={() => edit.change(addEntry(at.slice(0, -1), values, field.name, ''))}>
          Add a row
        </Button>
      )}
      <Problems problems={inside(problems, at)} />
    </FormGroup>
  );
}

/** The fields of a form, each with its value from `values` and its problem, and editable with `edit`; lists and maps are tables. */
function Fields({ fields, values, at, problems, edit }: { fields: StepField[]; values: Record<string, unknown>; at: Path; problems: FlowProblem[]; edit?: PanelEdit }) {
  return (
    <Form isHorizontal={false} onSubmit={(e) => e.preventDefault()}>
      {fields.map((f) => {
        const path = [...at, ...f.name.split('.')];
        const value = f.name.split('.').reduce<unknown>((v, k) => (typeof v === 'object' && v !== null ? (v as Record<string, unknown>)[k] : undefined), values);
        if ((f.type === 'list' || f.type === 'map') && (value === undefined || value === null)) {
          return (
            <FormGroup key={f.name} label={f.name} fieldId={placeOf(path)}>
              <Content component="small">Not set. {f.help}</Content>
              {edit !== undefined && (
                <Button
                  variant="link"
                  isInline
                  icon={<PlusCircleIcon />}
                  onClick={() => edit.change(f.type === 'list' ? addRow(at, values, f.name, newRow(f.item)) : addEntry(at, values, f.name, ''))}
                >
                  Add a row
                </Button>
              )}
            </FormGroup>
          );
        }
        const own = edit === undefined ? {} : { edit };
        if (f.type === 'list') return <ListTable key={f.name} field={f} values={values} at={path} problems={problems} {...own} />;
        if (f.type === 'map') return <MapTable key={f.name} field={f} values={values} at={path} problems={problems} {...own} />;
        const problem = problems.find((p) => samePath(p.path, path));
        const key = pointer(path);
        return (
          <FieldControl
            key={`${edit?.generation ?? 0}-${f.name}`}
            field={f as ValueField}
            value={value}
            readOnly={edit === undefined}
            onChange={(next) => edit?.change(setField(at, values, f.name, next, f.required), key)}
            onInvalid={(bad) => edit?.invalid(key, bad)}
            {...(problem === undefined ? {} : { problem: problem.message })}
          />
        );
      })}
    </Form>
  );
}

/** The name a step has when it is given none: the process a call calls, else its type. */
const defaultNameOf = (values: Record<string, unknown>) => String(values.type === 'process' && typeof values.process === 'string' ? values.process : values.type);

/** A step's form: its own settings, and the "Step" tab of the platform's keys. A rename, while it is edited, warns. */
function StepTabs({ node, form, problems, edit }: { node: FlowNode; form: StepForm; problems: FlowProblem[]; edit?: PanelEdit }) {
  const [tab, setTab] = useState<string | number>('settings');
  const [named] = useState(node.label);
  const own = Object.fromEntries(Object.entries(form.values).filter(([k]) => !STEP_KEYS.has(k)));
  const described = new Set(form.settings.fields.map((f) => f.name));
  const undescribed = Object.fromEntries(Object.entries(own).filter(([k]) => !described.has(k)));
  return (
    <Tabs activeKey={tab} onSelect={(_, key) => setTab(key)} aria-label="Step settings" isBox={false}>
      <Tab eventKey="settings" title={<TabTitleText>Settings</TabTitleText>}>
        <TabContentBody hasPadding>
          <Stack hasGutter>
            {form.missing !== undefined && (
              <StackItem>
                <Alert variant="info" isInline isPlain title={form.missing} />
              </StackItem>
            )}
            {form.settings.described ? (
              <StackItem>
                <Fields fields={form.settings.fields} values={form.values} at={form.path} problems={problems} {...(edit === undefined ? {} : { edit })} />
              </StackItem>
            ) : null}
            {(!form.settings.described || Object.keys(undescribed).length > 0) && (
              <StackItem>
                {/* the keys no field describes, kept as the file holds them */}
                <RawOptions
                  key={`${node.id}-${edit?.generation ?? 0}`}
                  options={form.settings.described ? undescribed : own}
                  readOnly={edit === undefined}
                  onChange={(next) => edit?.change(setKeys(form.path, form.settings.described ? undescribed : own, next), `${pointer(form.path)}#raw`)}
                  onInvalid={(bad) => edit?.invalid(`${pointer(form.path)}#raw`, bad)}
                />
              </StackItem>
            )}
          </Stack>
        </TabContentBody>
      </Tab>
      <Tab eventKey="step" title={<TabTitleText>Step</TabTitleText>}>
        <TabContentBody hasPadding>
          <Stack hasGutter>
            {edit !== undefined && node.label !== named && (
              <StackItem>
                <Alert variant="warning" isInline isPlain title={`Renamed from ${named}: the message history keeps ${named} on the rows written before.`} />
              </StackItem>
            )}
            <StackItem>
              <Fields
                fields={stepTabFields(String(form.values.type), defaultNameOf(form.values))}
                values={form.values}
                at={form.path}
                problems={problems}
                {...(edit === undefined ? {} : { edit })}
              />
            </StackItem>
          </Stack>
        </TabContentBody>
      </Tab>
    </Tabs>
  );
}

/**
 * The flow's own settings as a form, its keys that no field describes in a raw editor, and the problems of the flow that
 * no field shows. With `edit` they are editable, and a process can be added.
 */
function FlowSettings({ form, problems, edit }: { form: StepForm; problems: FlowProblem[]; edit?: PanelEdit }) {
  const described = new Set(form.settings.fields.map((f) => f.name.split('.')[0]!));
  const undescribed = Object.fromEntries(Object.entries(form.values).filter(([k]) => !described.has(k) && !FLOW_PARTS.has(k)));
  const fields = new Set(form.settings.fields.map((f) => pointer(f.name.split('.'))));
  const rest = problems.filter((p) => p.path.length === 0 || (p.path[0] !== 'steps' && p.path[0] !== 'processes' && !fields.has(pointer(p.path))));
  return (
    <Stack hasGutter>
      <StackItem>
        <Fields fields={form.settings.fields} values={form.values} at={[]} problems={problems} {...(edit === undefined ? {} : { edit })} />
      </StackItem>
      {Object.keys(undescribed).length > 0 && (
        <StackItem>
          {/* the keys no field describes, kept as the file holds them */}
          <RawOptions
            key={`flow-${edit?.generation ?? 0}`}
            options={undescribed}
            readOnly={edit === undefined}
            onChange={(next) => edit?.change(setKeys([], undescribed, next), '#raw')}
            onInvalid={(bad) => edit?.invalid('#raw', bad)}
          />
        </StackItem>
      )}
      <StackItem>
        <Problems problems={rest} />
      </StackItem>
      {edit !== undefined && (
        <StackItem>
          <Button variant="secondary" size="sm" icon={<PlusCircleIcon />} onClick={edit.addProcess}>
            Add a process
          </Button>
        </StackItem>
      )}
    </Stack>
  );
}

/** A process's lane while the flow is edited: its name, which renames it and every call of it, and Delete while no step calls it. */
function LaneEdit({ name, calls, edit }: { name: string; calls: FlowNode[]; edit: PanelEdit }) {
  const [text, setText] = useState(name);
  const unnamed = calls.filter((c) => c.defaultName).length;
  return (
    <Form onSubmit={(e) => e.preventDefault()}>
      <FormGroup label="Name" fieldId={`lane-${name}`}>
        <Flex>
          <FlexItem grow={{ default: 'grow' }}>
            <TextInput id={`lane-${name}`} value={text} onChange={(_, next) => setText(next)} />
          </FlexItem>
          <FlexItem>
            <Button variant="secondary" size="sm" isDisabled={text === name || text === ''} onClick={() => edit.renameProcess(name, text)}>
              Rename
            </Button>
          </FlexItem>
        </Flex>
        {unnamed > 0 && (
          <HelperText>
            <HelperTextItem variant="warning">
              {unnamed === 1 ? 'A call' : `${unnamed} calls`} without a name {unnamed === 1 ? 'is' : 'are'} named after the process: a rename changes the names its
              steps run under, and the message history keeps {name} on the rows written before.
            </HelperTextItem>
          </HelperText>
        )}
      </FormGroup>
      <Flex alignItems={{ default: 'alignItemsCenter' }}>
        <FlexItem>
          <Button variant="secondary" isDanger size="sm" icon={<TrashIcon />} isDisabled={calls.length > 0} onClick={() => edit.deleteProcess(name)}>
            Delete process
          </Button>
        </FlexItem>
        {calls.length > 0 && (
          <FlexItem>
            <Content component="small">
              {calls.length === 1 ? 'A step calls' : `${calls.length} steps call`} it: {calls.map((c) => placeOf(c.path)).join(', ')}.
            </Content>
          </FlexItem>
        )}
      </Flex>
    </Form>
  );
}

export interface FlowPanelProps {
  /** The flow's value as the file holds it. */
  flow: Record<string, unknown>;
  /** The form of the flow's own settings. */
  settings?: StepForm;
  /** The node selected, or null for the flow's own settings. */
  node: FlowNode | null;
  /** The flow a `to` of the selected node calls, when it calls one. */
  calls?: FlowNode;
  /** The steps that call the process of the selected lane, when a lane is selected. */
  laneCalls?: FlowNode[];
  problems: FlowProblem[];
  /** Shows the endpoint file at this path in the component's form, read-only. */
  endpointForm: (path: string) => ReactNode;
  openEndpoint: (path: string) => void;
  openFlow: (path: string) => void;
  /** What may be changed while the flow is edited; without it the panel only shows. */
  edit?: PanelEdit;
}

/**
 * What the drawer beside the canvas shows of the selection: a step's form with its "Step" tab, an endpoint in its
 * component's form, a link to a flow called, a process's lane, or, when nothing is selected or the arrival is, the flow's
 * own settings. While the flow is edited, a step that is not locked can be moved within its list or to another, and
 * deleted, and its fields changed; a process renamed, deleted while no step calls it, and added; and the flow's own
 * settings changed.
 */
export function FlowPanel({ flow, settings, node, calls, laneCalls, problems, endpointForm, openEndpoint, openFlow, edit }: FlowPanelProps) {
  const own = edit === undefined ? {} : { edit };
  if (node === null) {
    return (
      <Stack hasGutter>
        <StackItem>
          <Title headingLevel="h2">Flow {shown(flow.id)}</Title>
        </StackItem>
        <StackItem>
          {settings !== undefined && <FlowSettings key={edit?.generation ?? 0} form={settings} problems={problems} {...own} />}
        </StackItem>
      </Stack>
    );
  }
  const at = node.path;
  const mine = problems.filter((p) => at.every((s, i) => p.path[i] === s));
  const opens = node.opens;
  const flowLink = calls?.opens !== undefined && 'flow' in calls.opens ? calls.opens.flow : opens !== undefined && 'flow' in opens ? opens.flow : undefined;
  const editable = edit !== undefined && node.kind === 'step' && node.locked === undefined ? edit : undefined;
  return (
    <Stack hasGutter>
      <StackItem>
        <Title headingLevel="h2">{node.kind === 'lane' ? `Process ${node.label}` : node.label}</Title>
        <Content component="small">
          {node.type ?? node.kind}
          {node.runs > 0 && ` · runs as ${node.runsAs.join(', ')}${node.runs > node.runsAs.length ? ` and ${node.runs - node.runsAs.length} more` : ''}`}
        </Content>
      </StackItem>
      {editable !== undefined && (
        <StackItem>
          <Flex>
            <FlexItem>
              <Button variant="secondary" size="sm" icon={<ArrowUpIcon />} isDisabled={!editable.up} onClick={() => editable.move(-1)}>
                Move up
              </Button>
            </FlexItem>
            <FlexItem>
              <Button variant="secondary" size="sm" icon={<ArrowDownIcon />} isDisabled={!editable.down} onClick={() => editable.move(1)}>
                Move down
              </Button>
            </FlexItem>
            <FlexItem>
              <Button variant="secondary" isDanger size="sm" icon={<TrashIcon />} onClick={editable.remove}>
                Delete
              </Button>
            </FlexItem>
            {editable.lists.length > 0 && (
              <FlexItem>
                <FormSelect
                  aria-label="Move to the end of"
                  value=""
                  onChange={(_, chosen) => {
                    const list = editable.lists.find((l) => pointer(l.path) === chosen);
                    if (list !== undefined) editable.moveTo(list.path);
                  }}
                >
                  <FormSelectOption value="" label="Move to…" isDisabled />
                  {editable.lists.map((l) => (
                    <FormSelectOption key={pointer(l.path)} value={pointer(l.path)} label={l.label} />
                  ))}
                </FormSelect>
              </FlexItem>
            )}
          </Flex>
        </StackItem>
      )}
      {edit !== undefined && node.kind === 'lane' && (
        <StackItem>
          <LaneEdit key={`${node.id}-${edit.generation}`} name={node.label} calls={laneCalls ?? []} edit={edit} />
        </StackItem>
      )}
      {node.locked !== undefined && (
        <StackItem>
          <Alert variant="info" isInline isPlain title={node.locked} />
        </StackItem>
      )}
      {node.kind === 'lane' && node.runs === 0 && (
        <StackItem>
          <Content component="p">No step of this flow calls the process.</Content>
        </StackItem>
      )}
      {node.form === undefined && mine.length > 0 && (
        <StackItem>
          <Problems problems={mine} />
        </StackItem>
      )}
      {node.form !== undefined && (
        <StackItem>
          <StepTabs key={node.id} node={node} form={node.form} problems={mine} {...(editable === undefined ? {} : { edit: editable })} />
        </StackItem>
      )}
      {node.form !== undefined && mine.some((p) => p.path.length <= at.length) && (
        <StackItem>
          <Problems problems={mine.filter((p) => p.path.length <= at.length)} />
        </StackItem>
      )}
      {flowLink !== undefined && (
        <StackItem>
          <Button variant="secondary" onClick={() => openFlow(flowLink)}>
            Open flow {calls?.label ?? node.label}
          </Button>
        </StackItem>
      )}
      {opens !== undefined && 'endpoint' in opens && (
        <StackItem>
          <Stack hasGutter>
            <StackItem>
              <Button variant="secondary" onClick={() => openEndpoint(opens.endpoint)}>
                Open endpoint
              </Button>
            </StackItem>
            <StackItem>{endpointForm(opens.endpoint)}</StackItem>
          </Stack>
        </StackItem>
      )}
      {node.kind === 'from' && settings !== undefined && (
        <StackItem>
          <Title headingLevel="h3">Flow {shown(flow.id)}</Title>
          <FlowSettings key={edit?.generation ?? 0} form={settings} problems={problems} {...own} />
        </StackItem>
      )}
    </Stack>
  );
}
