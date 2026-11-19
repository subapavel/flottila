import {
  Alert,
  Button,
  Content,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  HelperText,
  HelperTextItem,
  Stack,
  StackItem,
  Tab,
  TabContentBody,
  Tabs,
  TabTitleText,
  Title,
} from '@patternfly/react-core';
import { ArrowDownIcon, ArrowUpIcon, TrashIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useState, type ReactNode } from 'react';
import { FieldControl } from '../FieldControl';
import { RawOptions } from '../RawOptions';
import type { Op } from './edit';
import { setField, setKeys } from './ops';
import { STEP_KEYS, stepTabFields } from './steps';
import type { FlowNode, FlowProblem, Path, StepField, StepForm, ValueField } from './types';
import { pointer } from './view';

/** What the panel may change while the flow is edited. */
export interface PanelEdit {
  /** Told the operations a change of a field makes, and the field's key: changes of one field in a row are undone at once. */
  change: (ops: Op[], key: string) => void;
  /** Told whether the field of `key` holds text that cannot become a value, which cannot be saved. */
  invalid: (key: string, bad: boolean) => void;
  /** Moves the selected step up (-1) or down (1) in its list. */
  move: (by: -1 | 1) => void;
  /** Deletes the selected step. */
  remove: () => void;
  /** Whether the selected step can move up, and down, in its list. */
  up: boolean;
  down: boolean;
  /** Changes each time the text is set from outside the form (undo, save, reload): controls holding text of their own start again. */
  generation: number;
}

const shown = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value));

const samePath = (a: Path, b: Path) => a.length === b.length && a.every((s, i) => b[i] === s);

/** The place of `path` in words, as the problems list shows it: `steps[1].name`. */
export const placeOf = (path: Path) =>
  path.length === 0 ? 'the file' : path.reduce<string>((out, s) => (typeof s === 'number' ? `${out}[${s}]` : out === '' ? s : `${out}.${s}`), '');

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

/** A list field as a table: one row per item, one column per field of an item. */
function ListTable({ field, value, at, problems }: { field: Extract<StepField, { type: 'list' }>; value: unknown; at: Path; problems: FlowProblem[] }) {
  const items = Array.isArray(value) ? value : [];
  return (
    <FormGroup label={field.name} fieldId={`${placeOf(at)}`}>
      <Content component="small">{field.help}</Content>
      <Table aria-label={field.name} variant="compact">
        <Thead>
          <Tr>
            {field.item.map((f) => (
              <Th key={f.name}>{f.name}</Th>
            ))}
          </Tr>
        </Thead>
        <Tbody>
          {items.map((item, i) => (
            <Tr key={i}>
              {field.item.map((f) => (
                <Td key={f.name} dataLabel={f.name}>
                  {shown(typeof item === 'object' && item !== null ? (item as Record<string, unknown>)[f.name] : undefined)}
                </Td>
              ))}
            </Tr>
          ))}
        </Tbody>
      </Table>
      <Problems problems={problems.filter((p) => at.every((s, i) => p.path[i] === s))} />
    </FormGroup>
  );
}

/** A map field as a table: one row per entry, its key and its value. */
function MapTable({ field, value, at, problems }: { field: Extract<StepField, { type: 'map' }>; value: unknown; at: Path; problems: FlowProblem[] }) {
  const entries = typeof value === 'object' && value !== null && !Array.isArray(value) ? Object.entries(value) : [];
  return (
    <FormGroup label={field.name} fieldId={`${placeOf(at)}`}>
      <Content component="small">{field.help}</Content>
      <Table aria-label={field.name} variant="compact">
        <Thead>
          <Tr>
            <Th>Value</Th>
            <Th>{field.value.name}</Th>
          </Tr>
        </Thead>
        <Tbody>
          {entries.map(([key, v]) => (
            <Tr key={key}>
              <Td dataLabel="Value">{key}</Td>
              <Td dataLabel={field.value.name}>{shown(v)}</Td>
            </Tr>
          ))}
        </Tbody>
      </Table>
      <Problems problems={problems.filter((p) => at.every((s, i) => p.path[i] === s))} />
    </FormGroup>
  );
}

/**
 * The fields of a form, each with its value from `values` and its problem. With `edit` the fields that hold one value are
 * editable; lists and maps stay tables.
 */
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
            </FormGroup>
          );
        }
        if (f.type === 'list') return <ListTable key={f.name} field={f} value={value} at={path} problems={problems} />;
        if (f.type === 'map') return <MapTable key={f.name} field={f} value={value} at={path} problems={problems} />;
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

/** The settings of the flow itself: every key of the file but its steps and processes. */
function FlowSettings({ value }: { value: Record<string, unknown> }) {
  return (
    <DescriptionList isCompact>
      {Object.entries(value)
        .filter(([k]) => k !== 'steps' && k !== 'processes' && k !== 'kind')
        .map(([k, v]) => (
          <DescriptionListGroup key={k}>
            <DescriptionListTerm>{k}</DescriptionListTerm>
            <DescriptionListDescription>
              <code>{shown(v)}</code>
            </DescriptionListDescription>
          </DescriptionListGroup>
        ))}
    </DescriptionList>
  );
}

export interface FlowPanelProps {
  /** The flow's value as the file holds it. */
  flow: Record<string, unknown>;
  /** The node selected, or null for the flow's own settings. */
  node: FlowNode | null;
  /** The flow a `to` of the selected node calls, when it calls one. */
  calls?: FlowNode;
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
 * component's form, a link to a flow called, or, when nothing is selected, the flow's own settings. While the flow is
 * edited, a step that is not locked can be moved and deleted, and its fields that hold one value changed; the rest is
 * read-only.
 */
export function FlowPanel({ flow, node, calls, problems, endpointForm, openEndpoint, openFlow, edit }: FlowPanelProps) {
  if (node === null) {
    return (
      <Stack hasGutter>
        <StackItem>
          <Title headingLevel="h2">Flow {shown(flow.id)}</Title>
        </StackItem>
        <StackItem>
          <FlowSettings value={flow} />
        </StackItem>
        <StackItem>
          <Problems problems={problems.filter((p) => p.path.length === 0 || (p.path[0] !== 'steps' && p.path[0] !== 'processes'))} />
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
          </Flex>
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
    </Stack>
  );
}
