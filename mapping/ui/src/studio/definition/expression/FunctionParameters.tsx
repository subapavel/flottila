import { Button, Modal, ModalBody, ModalHeader, Switch } from '@patternfly/react-core';
import { TrashIcon } from '@patternfly/react-icons';
import { useState, type FormEvent } from 'react';
import type { CatalogueParam } from '../../../generated/function-catalogue';
import type { ParamValue, Params } from '../../../generated/mapping-model';
import { Tool } from '../../shared/Tool';
import { DateFormatHelp } from './DateParameterControls';

/** The prototype's label of each catalogue parameter it has, by `<function>.<parameter>`; another one is named from its name. */
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

/** The prototype's words for the values a choice offers. */
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

/** The parameters the prototype edits as integers. */
const INTEGERS = new Set(['counter.initial', 'counter.increment', 'substring.start', 'substring.length', 'copyValue.position', 'index.initial', 'index.increment']);

/** The choices the prototype offers in a select rather than as radio buttons. */
const SELECTS = new Set(['splitByValue.mode']);

/** A parameter's label: the prototype's, else its name in words (`defaultValue` is "Default value"). */
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

function FixedValues({ value, change, label }: { value: Row[]; change: (rows: Row[]) => void; label: string }) {
  const cell = (index: number, column: keyof Row, next: string) => change(value.map((row, i) => (i === index ? { ...row, [column]: next } : row)));
  return (
    <fieldset>
      <legend>{label}</legend>
      <Button type="button" variant="link" size="sm" onClick={() => change([...value, { key: '', value: '' }])}>
        Add pair
      </Button>
      <table className="editor-table">
        <thead>
          <tr>
            <th>key</th>
            <th>value</th>
            <th aria-label="Row actions" />
          </tr>
        </thead>
        <tbody>
          {value.map((row, i) => (
            <tr key={i}>
              {(['key', 'value'] as const).map((k) => (
                <td key={k}>
                  <input aria-label={`${k} ${i + 1}`} value={row[k]} onChange={(e) => cell(i, k, e.target.value)} />
                </td>
              ))}
              <td>
                <Tool label={`Remove pair ${i + 1}`} icon={TrashIcon} onClick={() => change(value.filter((_, j) => j !== i))} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </fieldset>
  );
}

interface FieldProps {
  fn: string;
  param: CatalogueParam;
  value: string | Row[];
  change: (value: string | Row[]) => void;
  chooseFormat: () => void;
}

function Field({ fn, param, value, change, chooseFormat }: FieldProps) {
  const label = paramLabel(fn, param.name);
  if (param.type === 'table') return <FixedValues label={label} value={value as Row[]} change={change} />;
  const text = value as string;
  if (param.values?.length === 2 && param.values.includes('true') && param.values.includes('false')) {
    return <Switch id={`param-${param.name}`} label={label} isChecked={text === 'true'} onChange={(_, checked) => change(String(checked))} />;
  }
  if (param.values && SELECTS.has(`${fn}.${param.name}`)) {
    return (
      <label>
        {label}
        <select aria-label={label} value={text} onChange={(e) => change(e.target.value)}>
          {param.values.map((v) => (
            <option key={v} value={v}>
              {VALUE_LABELS[v] ?? v}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (param.values) {
    return (
      <fieldset className="fn-radio">
        <legend>{label}</legend>
        {param.values.map((v) => (
          <label key={v}>
            <input type="radio" name={param.name} value={v} checked={text === v} onChange={() => change(v)} />
            {VALUE_LABELS[v] ?? v}
          </label>
        ))}
      </fieldset>
    );
  }
  if (param.syntax === 'datePattern') {
    return (
      <div className="fn-date-row">
        <label>
          {label}
          <input value={text} onChange={(e) => change(e.target.value)} />
        </label>
        <Button type="button" variant="secondary" aria-label={`Choose ${label}`} onClick={chooseFormat}>
          Choose format…
        </Button>
      </div>
    );
  }
  const integer = INTEGERS.has(`${fn}.${param.name}`);
  return (
    <label>
      {label}
      <input type={integer ? 'number' : 'text'} step={integer ? 1 : undefined} value={text} onChange={(e) => change(e.target.value)} />
    </label>
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
 * The prototype's parameter dialog of a standard function box: one field per catalogue parameter, a choice as radio
 * buttons (or a select, or a switch for true and false), a date pattern with "Choose format…", a table as key and value
 * pairs. Apply checks the integers and the table's keys and saves all at once; in view mode it only shows them.
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
        <form className="fn-parameters" onSubmit={submit}>
          <fieldset className="mode-fields" disabled={readOnly}>
            {params.map((p) => (
              <Field key={p.name} fn={fn} param={p} value={draft[p.name]!} change={(v) => change(p.name, v)} chooseFormat={() => setHelper(p.name)} />
            ))}
          </fieldset>
          {error && <p role="alert">{error}</p>}
          <div className="form-actions">
            {!readOnly && <Button type="submit">Apply</Button>}
            <Button type="button" variant="link" onClick={close}>
              {readOnly ? 'Close' : 'Cancel'}
            </Button>
          </div>
        </form>
      </ModalBody>
    </Modal>
  );
}
