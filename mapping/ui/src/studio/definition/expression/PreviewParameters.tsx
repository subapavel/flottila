import { Button, Modal, ModalBody, ModalHeader, Switch } from '@patternfly/react-core';
import { ArrowDownIcon, ArrowUpIcon, TrashIcon } from '@patternfly/react-icons';
import { useState, type FormEvent } from 'react';
import type { PreviewParam } from '../../mock/definition-preview';
import { initialParams, RFC_MODULE, RFC_NAMESPACE, type PreviewFunction, type PreviewFunctionParameter } from '../../mock/preview-functions';
import { moveRow } from '../../mock/signature-mock';
import { Tool } from '../../shared/Tool';

type Values = Record<string, PreviewParam>;

/** The prototype's choices of Behaviour if lookup fails. */
const STRATEGIES = [
  ['2', 'Use Key'],
  ['1', 'Use Default Value'],
  ['0', 'Throw Exception'],
] as const;

const TITLES: Record<PreviewFunction['key'], string> = { valuemap: 'Value Mapping Properties', tableLookup: 'JDBC Lookup', rfclookup: 'RFC Lookup' };

/** A field of the Value Mapping Properties (the prototype's Field): a text, an agency with its default offered, or the strategy. */
function Field({ param, value, change }: { param: PreviewFunctionParameter; value: string; change: (value: string) => void }) {
  const { key, label, type } = param;
  if (type === 'vmstrategy') {
    return (
      <fieldset className="fn-radio">
        <legend>{label}</legend>
        {STRATEGIES.map(([id, name]) => (
          <label key={id}>
            <input type="radio" name={key} value={id} checked={value === id} onChange={() => change(id)} />
            {name}
          </label>
        ))}
      </fieldset>
    );
  }
  const agency = type === 'vmsagency' ? 'SenderParty' : type === 'vmtagency' ? 'ReceiverParty' : null;
  return (
    <label>
      {label}
      <input value={value} list={agency ? `agency-${key}` : undefined} onChange={(e) => change(e.target.value)} />
      {agency && (
        <datalist id={`agency-${key}`}>
          <option value={agency} />
        </datalist>
      )}
    </label>
  );
}

/** A list of a lookup's fields (the prototype's FieldList): reorder or remove the selected field. */
function FieldList({ title, fields, change }: { title: string; fields: string[]; change: (fields: string[]) => void }) {
  const [selected, setSelected] = useState('');
  const index = fields.indexOf(selected);
  return (
    <fieldset>
      <legend>{title}</legend>
      <select aria-label={title} size={4} value={selected} onChange={(e) => setSelected(e.target.value)}>
        {fields.map((name) => (
          <option key={name}>{name}</option>
        ))}
      </select>
      <div className="fn-port-tools">
        <Tool label={`Move ${title} up`} icon={ArrowUpIcon} disabled={index <= 0} onClick={() => change([...moveRow(fields, index, -1)])} />
        <Tool label={`Move ${title} down`} icon={ArrowDownIcon} disabled={index < 0 || index >= fields.length - 1} onClick={() => change([...moveRow(fields, index, 1)])} />
        <Tool label={`Remove selected field from ${title}`} icon={TrashIcon} disabled={index < 0} onClick={() => change(fields.filter((n) => n !== selected))} />
      </div>
    </fieldset>
  );
}

/** The prototype's LookupPicker: the lookup catalogue, the one RFC module it offers, no database table. */
function LookupPicker({ rfc, current, apply, close }: { rfc: boolean; current: string; apply: (ref: string) => void; close: () => void }) {
  const [selected, setSelected] = useState('');
  const [query, setQuery] = useState('');
  const entries = [...new Set([...(rfc ? [RFC_MODULE] : []), ...(current ? [current] : [])])];
  const rows = entries.filter((n) => n.toLowerCase().includes(query.trim().toLowerCase()));
  const title = rfc ? 'Choose Function Module' : 'Choose External Definition';
  return (
    <Modal isOpen variant="medium" aria-label={title} onClose={close}>
      <ModalHeader title={title} />
      <ModalBody>
        <div className="sig-criteria">
          <h3>Search Criteria</h3>
          <dl>
            <dt>Category</dt>
            <dd>{rfc ? 'rfc' : 'dbtab'}</dd>
          </dl>
        </div>
        <h3 className="sig-result-title">Search Result</h3>
        <div className="fn-parameters">
          <label>
            Search
            <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
        </div>
        <table className="editor-table sig-catalog">
          <thead>
            <tr>
              <th>Name</th>
              <th>Namespace</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((n) => (
              <tr key={n} className={selected === n ? 'sig-selected' : ''}>
                <td>
                  <button type="button" className="sig-result-name" aria-pressed={selected === n} onClick={() => setSelected(n)}>
                    {n}
                  </button>
                </td>
                <td>{n === RFC_MODULE ? RFC_NAMESPACE : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="empty-message">No Suitable Data Found</p>}
        <div className="form-actions">
          <Button isDisabled={!rows.includes(selected)} onClick={() => apply(selected)}>
            Apply
          </Button>
          <Button variant="link" onClick={close}>
            Cancel
          </Button>
        </div>
      </ModalBody>
    </Modal>
  );
}

/** The prototype's LookupParameters: the channel, the table or module, its field lists and its switch. */
function LookupParameters({ fn, draft, change, openPicker }: { fn: PreviewFunction; draft: Values; change: (key: string, value: PreviewParam) => void; openPicker: () => void }) {
  const rfc = fn.key === 'rfclookup';
  const flag = rfc ? 'useExc' : 'split';
  const flagSwitch = (
    <Switch label={rfc ? 'Use Exception' : 'Insert Context Change After Each Result Value'} isChecked={draft[flag] as boolean} onChange={(_, checked) => change(flag, checked)} />
  );
  const ref = draft.ref as string;
  return (
    <>
      <label>
        Communication Channel
        {/* the mapping parameters (#167) have no Adapter category yet: no channel to offer */}
        <select aria-label="Communication Channel" value="" disabled>
          <option value="">No suitable parameter found; define new parameter of type &apos;Channel&apos; first</option>
        </select>
      </label>
      <div className="fn-ref">
        <span>{ref || (rfc ? 'Select an RFC module' : 'Select a database table')}</span>
        <Button type="button" variant="secondary" size="sm" onClick={openPicker}>
          {rfc ? 'Choose RFC module' : 'Choose database table'}
        </Button>
      </div>
      {!rfc && flagSwitch}
      <div className={rfc ? 'fn-pair-columns' : 'fn-jdbc-columns'}>
        <FieldList title={rfc ? 'Import fields' : 'Input fields'} fields={draft.ins as string[]} change={(v) => change('ins', v)} />
        {!rfc && (
          <fieldset>
            <legend>Database table fields</legend>
            <div className="empty-message">No fields available</div>
          </fieldset>
        )}
        <FieldList title={rfc ? 'Export fields' : 'Output fields'} fields={draft.outs as string[]} change={(v) => change('outs', v)} />
      </div>
      {rfc && flagSwitch}
    </>
  );
}

export interface PreviewParametersProps {
  fn: PreviewFunction;
  /** The node's parameters as the preview keeps them: those that differ from the initial values. */
  values: Values | undefined;
  readOnly: boolean;
  /** Saves the parameters that differ from the initial values. */
  save: (params: Values) => void;
  close: () => void;
}

/**
 * The parameter dialog of a preview function (the prototype's FunctionParameters for Value mapping and the lookups):
 * the Value Mapping Properties, or a lookup's channel, table or module and fields. Apply saves them to the preview; in
 * view mode it only shows them.
 */
export function PreviewParameters({ fn, values, readOnly, save, close }: PreviewParametersProps) {
  const initial = initialParams(fn);
  const [draft, setDraft] = useState<Values>(() => ({ ...initial, ...values }));
  const [picking, setPicking] = useState(false);
  const change = (key: string, value: PreviewParam) => setDraft((d) => ({ ...d, [key]: value }));
  const lookup = fn.key !== 'valuemap';
  if (picking) {
    return (
      <LookupPicker
        rfc={fn.key === 'rfclookup'}
        current={draft.ref as string}
        close={() => setPicking(false)}
        apply={(ref) => {
          setDraft((d) => ({ ...d, ref, ins: [], outs: [] }));
          setPicking(false);
        }}
      />
    );
  }
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save(Object.fromEntries(Object.entries(draft).filter(([key, value]) => JSON.stringify(value) !== JSON.stringify(initial[key]))));
  };
  return (
    <Modal isOpen onClose={close} variant={lookup ? 'medium' : 'small'} aria-label={TITLES[fn.key]}>
      <ModalHeader title={TITLES[fn.key]} />
      <ModalBody>
        <form className="fn-parameters" onSubmit={submit}>
          <fieldset className="mode-fields" disabled={readOnly}>
            {lookup ? (
              <LookupParameters fn={fn} draft={draft} change={change} openPicker={() => setPicking(true)} />
            ) : (
              fn.parameters.map((p) => <Field key={p.key} param={p} value={draft[p.key] as string} change={(v) => change(p.key, v)} />)
            )}
          </fieldset>
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
