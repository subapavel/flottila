import {
  Alert,
  Button,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  InputGroup,
  InputGroupItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  Radio,
  Switch,
  TextInput,
} from '@patternfly/react-core';
import { PlusCircleIcon, TrashIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useState, type FormEvent } from 'react';
import type { CatalogueParam } from '../../../generated/function-catalogue';
import type { ParamValue, Params } from '../../../generated/mapping-model';
import { Tool } from '../../shared/Tool';
import { DateFormatHelp } from './DateParameterControls';

/** The label of each catalogue parameter that has one, by `<function>.<parameter>`; another one is named from its name. */
const LABELS: Record<string, string> = {
  'counter.initial': 'Initial Value',
  'counter.increment': 'Increment',
  'substring.start': 'Start Position',
  'substring.length': 'Character Count',
  'fixValues.defaultValue': 'Default Value',
  'fixValues.missing': 'Behaviour if lookup fails',
  'fixValues.table': 'Content of Value Mapping Table',
  'formatNum.pattern': 'Number Format',
  'constant.value': 'Value',
  'currentDate.targetFormat': 'Target Date Format',
  'dateTrans.sourceFormat': 'Input Date Format',
  'dateTrans.targetFormat': 'Output Date Format',
  'dateBefore.format1': 'Format of First Date',
  'dateBefore.format2': 'Format of Second Date',
  'dateAfter.format1': 'Format of First Date',
  'dateAfter.format2': 'Format of Second Date',
  'compareDates.format1': 'Format of First Date',
  'compareDates.format2': 'Format of Second Date',
  'copyValue.position': 'Index',
  'replaceValue.replacement': 'Value to Produce',
  'ifWithoutElse.keepSuppressValues': 'Keep SUPPRESS Values',
  'splitByValue.mode': 'Event After Which Context Should Change',
  'index.initial': 'Initial Value',
  'index.increment': 'Increment',
  'index.reset': 'Reset Index',
  'sort.comparison': 'Defines a Rule for Comparing Values',
  'sort.order': 'Defines sort order',
  'sortByKey.comparison': 'Defines a Rule for Comparing Values',
  'sortByKey.order': 'Defines sort order',
  'mapWithDefault.default': 'Default Value to Be Mapped',
};

/** The words for the values a choice offers. */
const VALUE_LABELS: Record<string, string> = {
  eachValue: 'Each Value',
  emptyValue: 'Empty Value',
  valueChange: 'Value Change',
  eachContext: 'Reset Index to Initial Value with Each New Context',
  never: 'Do Not Reset Index to Initial Value',
  key: 'Use Key',
  default: 'Use Default Value',
  error: 'Throw Exception',
  lexicographic: 'case sensitive',
  caseInsensitive: 'case insensitive',
  numeric: 'float number',
};

/** The parameters edited as integers. */
const INTEGERS = new Set(['counter.initial', 'counter.increment', 'substring.start', 'substring.length', 'copyValue.position', 'index.initial', 'index.increment']);

/** The choices offered in a select rather than as radio buttons. */
const SELECTS = new Set(['splitByValue.mode']);

/** A parameter's label: its entry in `LABELS`, else its name in words (`defaultValue` is "Default value"). */
export const paramLabel = (fn: string, name: string) =>
  LABELS[`${fn}.${name}`] ?? name.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase()).replace(/ (\w)/g, (_, c: string) => ` ${c.toLowerCase()}`);

type Row = { key: string; value: string };
type Draft = Record<string, string | Row[]>;

const toDraft = (params: readonly CatalogueParam[], values: Params | undefined): Draft =>
  Object.fromEntries(
    params.map((p) => {
      const value = values?.[p.name] ?? p.default;
      return [p.name, typeof value === 'string' ? value : Object.entries(value).map(([key, v]) => ({ key, value: v }))];
    }),
  );

const toParams = (draft: Draft): Record<string, ParamValue> =>
  Object.fromEntries(Object.entries(draft).map(([name, v]) => [name, typeof v === 'string' ? v : Object.fromEntries(v.map((r) => [r.key, r.value]))]));

function FixedValues({ value, change, label, disabled }: { value: Row[]; change: (rows: Row[]) => void; label: string; disabled: boolean }) {
  const cell = (index: number, column: keyof Row, next: string) => change(value.map((row, i) => (i === index ? { ...row, [column]: next } : row)));
  return (
    <FormGroup label={label} role="group" fieldId="param-table">
      <Button variant="link" isInline icon={<PlusCircleIcon />} isDisabled={disabled} onClick={() => change([...value, { key: '', value: '' }])}>
        Add pair
      </Button>
      <Table aria-label={label} variant="compact" role="table">
        <Thead>
          <Tr>
            <Th>key</Th>
            <Th>value</Th>
            <Th screenReaderText="Row actions" />
          </Tr>
        </Thead>
        <Tbody>
          {value.map((row, i) => (
            <Tr key={i}>
              {(['key', 'value'] as const).map((k) => (
                <Td key={k} dataLabel={k}>
                  <TextInput aria-label={`${k} ${i + 1}`} value={row[k]} isDisabled={disabled} onChange={(_, next) => cell(i, k, next)} />
                </Td>
              ))}
              <Td isActionCell>
                <Tool label={`Remove pair ${i + 1}`} icon={TrashIcon} disabled={disabled} onClick={() => change(value.filter((_, j) => j !== i))} />
              </Td>
            </Tr>
          ))}
        </Tbody>
      </Table>
    </FormGroup>
  );
}

interface FieldProps {
  fn: string;
  param: CatalogueParam;
  value: string | Row[];
  change: (value: string | Row[]) => void;
  chooseFormat: () => void;
  disabled: boolean;
}

function Field({ fn, param, value, change, chooseFormat, disabled }: FieldProps) {
  const label = paramLabel(fn, param.name);
  const id = `param-${param.name}`;
  if (param.type === 'table') return <FixedValues label={label} value={value as Row[]} change={change} disabled={disabled} />;
  const text = value as string;
  if (param.values?.length === 2 && param.values.includes('true') && param.values.includes('false')) {
    return <Switch id={id} label={label} isChecked={text === 'true'} isDisabled={disabled} onChange={(_, checked) => change(String(checked))} />;
  }
  if (param.values && SELECTS.has(`${fn}.${param.name}`)) {
    return (
      <FormGroup label={label} fieldId={id}>
        <FormSelect id={id} value={text} isDisabled={disabled} onChange={(_, next) => change(next)}>
          {param.values.map((v) => (
            <FormSelectOption key={v} value={v} label={VALUE_LABELS[v] ?? v} />
          ))}
        </FormSelect>
      </FormGroup>
    );
  }
  if (param.values) {
    return (
      <FormGroup label={label} role="radiogroup" isStack fieldId={id}>
        {param.values.map((v) => (
          <Radio key={v} id={`${id}-${v}`} name={id} label={VALUE_LABELS[v] ?? v} isChecked={text === v} isDisabled={disabled} onChange={() => change(v)} />
        ))}
      </FormGroup>
    );
  }
  if (param.syntax === 'datePattern') {
    return (
      <FormGroup label={label} fieldId={id}>
        <InputGroup>
          <InputGroupItem isFill>
            <TextInput id={id} value={text} isDisabled={disabled} onChange={(_, next) => change(next)} />
          </InputGroupItem>
          <InputGroupItem>
            <Button variant="control" aria-label={`Choose ${label}`} isDisabled={disabled} onClick={chooseFormat}>
              Choose format…
            </Button>
          </InputGroupItem>
        </InputGroup>
      </FormGroup>
    );
  }
  const integer = INTEGERS.has(`${fn}.${param.name}`);
  return (
    <FormGroup label={label} fieldId={id}>
      <TextInput id={id} type={integer ? 'number' : 'text'} step={integer ? 1 : undefined} value={text} isDisabled={disabled} onChange={(_, next) => change(next)} />
    </FormGroup>
  );
}

export interface FunctionParametersProps {
  /** The catalogue name of the box's function. */
  fn: string;
  /** Its catalogue parameters. */
  params: readonly CatalogueParam[];
  /** The box's parameters as the model holds them; a missing one shows the catalogue's default. */
  values: Params | undefined;
  readOnly: boolean;
  save: (params: Record<string, ParamValue>) => void;
  close: () => void;
}

/**
 * The parameter dialog of a standard function box: one field per catalogue parameter, a choice as radio buttons (or a
 * select, or a switch for true and false), a date pattern with "Choose format…", a table as key and value pairs.
 * Apply checks the integers and the table's keys and saves all at once; in view mode it only shows them.
 */
export function FunctionParameters({ fn, params, values, readOnly, save, close }: FunctionParametersProps) {
  const [draft, setDraft] = useState<Draft>(() => toDraft(params, values));
  const [error, setError] = useState('');
  const [helper, setHelper] = useState<string | null>(null);
  const change = (name: string, value: string | Row[]) => {
    setDraft((d) => ({ ...d, [name]: value }));
    setError('');
  };
  if (helper) {
    return (
      <DateFormatHelp
        close={() => setHelper(null)}
        apply={(v) => {
          change(helper, v);
          setHelper(null);
        }}
      />
    );
  }
  const submit = (e: FormEvent) => {
    e.preventDefault();
    for (const p of params) {
      const value = draft[p.name]!;
      if (INTEGERS.has(`${fn}.${p.name}`) && !/^-?\d+$/.test(value as string)) {
        setError(`${paramLabel(fn, p.name)} must be an integer.`);
        return;
      }
      if (typeof value !== 'string' && new Set(value.map((r) => r.key)).size !== value.length) {
        setError('Each key must be unique.');
        return;
      }
    }
    save(toParams(draft));
  };
  const title = fn === 'fixValues' ? 'Fixed-Values Table' : `Function parameters · ${fn}`;
  return (
    <Modal isOpen onClose={close} variant={fn === 'fixValues' ? 'medium' : 'small'} aria-label={title}>
      <ModalHeader title={title} />
      <ModalBody>
        <Form id="function-parameters" onSubmit={submit}>
          {params.map((p) => (
            <Field key={p.name} fn={fn} param={p} value={draft[p.name]!} change={(v) => change(p.name, v)} chooseFormat={() => setHelper(p.name)} disabled={readOnly} />
          ))}
          {error && <Alert variant="danger" isInline isPlain role="alert" title={error} />}
        </Form>
      </ModalBody>
      <ModalFooter>
        {!readOnly && (
          <Button type="submit" form="function-parameters">
            Apply
          </Button>
        )}
        <Button variant="link" onClick={close}>
          {readOnly ? 'Close' : 'Cancel'}
        </Button>
      </ModalFooter>
    </Modal>
  );
}
