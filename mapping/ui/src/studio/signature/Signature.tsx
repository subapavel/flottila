import { Button, Modal, ModalBody, ModalFooter, ModalHeader } from '@patternfly/react-core';
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CodeIcon,
  CopyIcon,
  CubeIcon,
  CutIcon,
  PasteIcon,
  PlusIcon,
  ProjectDiagramIcon,
  RedoIcon,
  SearchIcon,
  TimesIcon,
  TrashIcon,
} from '@patternfly/react-icons';
import { useMutation } from '@tanstack/react-query';
import { useId, useRef, useState, type ComponentType } from 'react';
import { useStore } from 'zustand';
import { describeError, type ApiClient } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import { PreviewBadge } from '../mock/PreviewBadge';
import {
  OCCURRENCES,
  PARAMETERS_ISSUE,
  SEVERAL_MESSAGES_ISSUE,
  signatureActions,
  signatureMock,
  type Occurrence,
  type PreviewParameter,
  type Side,
  type SignatureMockStore,
} from '../mock/signature-mock';
import { CopyTextButton } from '../shared/CopyTextButton';
import { refreshMutation, useLoadStructure } from '../shared/structure-query';
import { ROOT_KINDS } from './message-choice';
import { MessageChoiceModal, type ChoiceFailure } from './MessageChoiceModal';
import { messageName, messageNamespace, rootKind, type MessageChoice, type RootKind } from '../shared/structure-names';
import './signature-tab.css';

/** The row id of a side's message in the mapping file, the first row of its table. */
const PRIMARY = 'message';

const cap = (side: Side) => (side === 'source' ? 'Source' : 'Target');

/** A tool button of a section's toolbar (the prototype's ToolbarTools Tool). */
function Tool({ label, icon: Icon, disabled, onClick }: { label: string; icon: ComponentType; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" className="def-tool" aria-label={label} title={label} disabled={disabled} onClick={onClick}>
      <Icon />
    </button>
  );
}

/** A select of a table cell (the prototype's FieldSelect). */
function FieldSelect<T extends string>({
  label,
  value,
  options,
  disabled,
  onChange,
}: {
  label: string;
  value: T;
  options: readonly (readonly [T, string])[];
  disabled?: boolean;
  onChange: (value: T) => void;
}) {
  return (
    <select aria-label={label} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value as T)}>
      {options.map(([option, text]) => (
        <option key={option} value={option}>
          {text}
        </option>
      ))}
    </select>
  );
}

/** A row of a message table: the side's message in the mapping file, or a preview message after it. */
interface MessageRow {
  id: string;
  preview: boolean;
  choice?: MessageChoice;
  occurrence: Occurrence;
}

/** What the open message choice is for: a side's row, and the root kind its Type select asked for. */
interface Choosing {
  side: Side;
  row: string;
  kind?: RootKind;
}

export interface SignatureProps {
  /** The mapping's id, its workspace path; its folder is the package the bundles resolve against. */
  mappingId: string;
  api: ApiClient;
  store: WorkingCopyStore;
  browserState: BrowserStateStore;
}

/**
 * The Signature tab (the prototype's Signature): the source and target message tables, editable in edit mode. The
 * first row of a side is its message in the mapping file (GLOSSARY: Signature); choosing it loads the structure,
 * sets the side on the working copy and refreshes it (ADR-0027). Further messages and the parameters are preview
 * features (ADR-0028), a draft of the edit session that Save keeps in the browser per mapping, never in the file.
 */
export function Signature({ mappingId, api, store, browserState }: SignatureProps) {
  const editing = useStore(store, (s) => s.editing);
  const mock = signatureMock(store, browserState, mappingId);
  const [clipboard, setClipboard] = useState<MessageChoice | null>(null);
  const [choosing, setChoosing] = useState<Choosing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [log, setLog] = useState('');
  const [showLog, setShowLog] = useState(false);
  const pkg = mappingId.slice(0, Math.max(mappingId.lastIndexOf('/'), 0));
  const loadStructure = useLoadStructure(mappingId, api);
  // the answer is taken here, not in mutate's callbacks, so that it still applies once another tab is open
  const refresh = useMutation({ ...refreshMutation(api, mappingId, store), onSuccess: ({ sent, answer }) => void store.getState().applyRefresh(sent, answer) });

  /** Loads the structure, sets the side and refreshes the working copy; a refused or failed load is the dialog's. */
  const choosePrimary = async (side: Side, choice: MessageChoice): Promise<ChoiceFailure | null> => {
    const bundle = [...choice.bundle] as [string, ...string[]];
    try {
      const loaded = await loadStructure({ bundle, root: choice.root });
      if (loaded.kind === 'refused') return { title: 'The structure could not be loaded', diagnostics: loaded.diagnostics };
      store.getState().setStructure(side, { bundle, root: choice.root, fingerprint: loaded.fingerprint });
    } catch (error) {
      const { title, diagnostics } = describeError(error);
      return { title, diagnostics };
    }
    setChoosing(null);
    refresh.mutate(undefined, { onError: (error) => setNotice(`The structures could not be refreshed: ${describeError(error).title}`) });
    return null;
  };

  const apply = async (choice: MessageChoice): Promise<ChoiceFailure | null> => {
    const { side, row } = choosing!;
    if (row === PRIMARY) return choosePrimary(side, choice);
    signatureActions(mock).patchMessage(side, row, { choice });
    setChoosing(null);
    return null;
  };

  const chosenRow = choosing && (choosing.row === PRIMARY ? store.getState().workingCopy[choosing.side] : mock.getState().value[choosing.side].find((m) => m.id === choosing.row)?.choice);

  return (
    <fieldset className="signature mode-fields" aria-label="Mapping signature" disabled={!editing}>
      {notice !== null && (
        <div className="feedback" role="status">
          {notice}
          <button type="button" aria-label="Dismiss signature message" onClick={() => setNotice(null)}>
            <TimesIcon />
          </button>
        </div>
      )}
      <div className="signature-pair">
        {(['source', 'target'] as const).map((side) => (
          <Messages
            key={side}
            side={side}
            pkg={pkg}
            store={store}
            mock={mock}
            clipboard={clipboard}
            setClipboard={setClipboard}
            choose={(row, kind) => setChoosing({ side, row, kind })}
          />
        ))}
      </div>
      <Parameters
        store={store}
        mock={mock}
        editing={editing}
        onLog={(text) => {
          setLog(text);
          setShowLog(true);
        }}
      />
      {log !== '' && <ProcessingLog log={log} />}
      {showLog && <LogDialog log={log} onClose={() => setShowLog(false)} />}
      {choosing && (
        <MessageChoiceModal
          title={`Choose ${choosing.side} message`}
          description={
            choosing.row === PRIMARY
              ? `The ${choosing.side} structure of the mapping, loaded when you apply.`
              : 'A further message is a preview: it is kept in this browser only, never in the mapping file.'
          }
          initial={chosenRow ?? undefined}
          kind={choosing.kind}
          onApply={apply}
          onClose={() => setChoosing(null)}
        />
      )}
    </fieldset>
  );
}

interface MessagesProps {
  side: Side;
  pkg: string;
  store: WorkingCopyStore;
  mock: SignatureMockStore;
  clipboard: MessageChoice | null;
  setClipboard: (choice: MessageChoice) => void;
  /** Opens the message choice for a row, with the root kind its Type select asked for. */
  choose: (row: string, kind?: RootKind) => void;
}

/** One side's message table (the prototype's Messages): its message in the mapping file, then the preview messages. */
function Messages({ side, pkg, store, mock, clipboard, setClipboard, choose }: MessagesProps) {
  const primary = useStore(store, (s) => s.workingCopy[side]);
  const previews = useStore(mock, (s) => s.value[side]);
  const [selected, select] = useState('');
  const rows: MessageRow[] = [
    ...(primary ? [{ id: PRIMARY, preview: false, choice: primary, occurrence: '1' as const }] : []),
    ...previews.map((m) => ({ ...m, preview: true })),
  ];
  const row = rows.find((r) => r.id === selected);
  const at = previews.findIndex((m) => m.id === selected);
  const actions = signatureActions(mock);
  const label = cap(side);

  // without the side's message, Insert row chooses it; with it, a preview message goes after the selected row, or last
  const insert = () => {
    if (!primary) {
      choose(PRIMARY);
      return;
    }
    const id = crypto.randomUUID();
    actions.insertMessage(side, at >= 0 ? at + 1 : selected === PRIMARY ? 0 : previews.length, { id, occurrence: '1' });
    select(id);
  };
  const remove = () => {
    const next = previews.filter((m) => m.id !== selected);
    actions.removeMessage(side, selected);
    select(next[Math.min(at, next.length - 1)]?.id ?? '');
  };
  const copy = (cut: boolean) => {
    setClipboard(row!.choice!);
    if (cut) actions.patchMessage(side, selected, { choice: undefined });
  };

  return (
    <section className="panel sig-messages" aria-label={`${label} messages`}>
      <header className="editor-section-head">
        <h2>
          <span className={`role-tile ${side}`}>
            <CubeIcon />
          </span>
          {label} Message(s) <small aria-label="required">*</small>
        </h2>
        <span className="sig-count" aria-label={`${label} messages chosen`}>
          {rows.filter((r) => r.choice).length}
        </span>
      </header>
      <div className="signature-component">
        <span>Package</span>
        <strong>{pkg === '' ? '—' : pkg}</strong>
      </div>
      <div className="sig-toolbar" role="toolbar" aria-label={`${label} message actions`}>
        <Tool label="Insert row" icon={PlusIcon} onClick={insert} />
        <Tool label="Delete row" icon={TrashIcon} disabled={!row?.preview} onClick={remove} />
        <i />
        <Tool label="Move up" icon={ArrowUpIcon} disabled={at < 1} onClick={() => actions.moveMessage(side, selected, -1)} />
        <Tool label="Move down" icon={ArrowDownIcon} disabled={at < 0 || at === previews.length - 1} onClick={() => actions.moveMessage(side, selected, 1)} />
        <i />
        <Tool label="Cut Object" icon={CutIcon} disabled={!row?.preview || !row.choice} onClick={() => copy(true)} />
        <Tool label="Copy Object" icon={CopyIcon} disabled={!row?.choice} onClick={() => copy(false)} />
        <Tool label="Paste Object" icon={PasteIcon} disabled={!clipboard || !row?.preview} onClick={() => actions.patchMessage(side, selected, { choice: clipboard! })} />
      </div>
      <div className="editor-table-scroll">
        <table className="editor-table sig-message-table" aria-label={`${label} signature`}>
          <thead>
            <tr>
              <th aria-label="Select row">#</th>
              <th>Type</th>
              <th>Name</th>
              <th>Namespace / ID</th>
              <th>Occurrence</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id} className={selected === r.id ? 'sig-selected' : ''} onFocus={() => select(r.id)} onClick={() => select(r.id)}>
                <td>
                  <button type="button" aria-label={`Select row ${i + 1}`} aria-pressed={selected === r.id} className="sig-row-num">
                    {i + 1}
                  </button>
                </td>
                <td>
                  <FieldSelect label="Message type" value={r.choice ? rootKind(r.choice.root) : 'element'} options={ROOT_KINDS} onChange={(kind) => choose(r.id, kind)} />
                </td>
                <td>
                  <div className="sig-name-cell">
                    <button
                      type="button"
                      className="sig-lookup"
                      aria-label={`Choose message ${i + 1}`}
                      title={r.choice ? r.choice.bundle.join(', ') : 'Choose message'}
                      onClick={() => choose(r.id)}
                    >
                      <span>{r.choice ? messageName(r.choice) : 'Choose…'}</span>
                      <SearchIcon />
                    </button>
                    {r.preview && <PreviewBadge feature="Several source or target messages" issue={SEVERAL_MESSAGES_ISSUE} />}
                  </div>
                </td>
                <td>
                  <button type="button" className="sig-lookup" aria-label={`Choose namespace or ID ${i + 1}`} onClick={() => choose(r.id)}>
                    <span>{(r.choice && messageNamespace(r.choice)) ?? '—'}</span>
                    <SearchIcon />
                  </button>
                </td>
                <td>
                  <FieldSelect
                    label="Occurrence"
                    value={r.occurrence}
                    options={OCCURRENCES.map((o) => [o, o] as const)}
                    disabled={!r.preview}
                    onChange={(occurrence) => actions.patchMessage(side, r.id, { occurrence })}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!primary && (
        <p className="empty-message">
          No {side} message yet.{' '}
          <Button variant="link" isInline onClick={() => choose(PRIMARY)}>
            Add {side} message
          </Button>
        </p>
      )}
    </section>
  );
}

const newParameter = (id: string, name = ''): PreviewParameter => ({ id, name, type: 'string', direction: 'Import', description: '' });

/** The parameters table (the prototype's Signature parameters): a preview feature, kept in the browser (#167). */
function Parameters({ store, mock, editing, onLog }: { store: WorkingCopyStore; mock: SignatureMockStore; editing: boolean; onLog: (log: string) => void }) {
  const parameters = useStore(mock, (s) => s.value.parameters);
  // an Import parameter is typed in the source's words, an Export one in the target's: JSON has no xsd: prefix
  const json = {
    Import: useStore(store, (s) => s.workingCopy.source !== undefined && rootKind(s.workingCopy.source.root) === 'json'),
    Export: useStore(store, (s) => s.workingCopy.target !== undefined && rootKind(s.workingCopy.target.root) === 'json'),
  };
  const [selected, select] = useState('');
  const [blankId, setBlankId] = useState(() => crypto.randomUUID());
  const table = useRef<HTMLTableElement>(null);
  const actions = signatureActions(mock);
  const parameter = parameters.find((p) => p.id === selected);

  const add = (name = '') => {
    const p = newParameter(blankId, name);
    actions.addParameter(p);
    select(p.id);
    setBlankId(crypto.randomUUID());
    return p;
  };
  const addAndFocus = () => {
    const p = add();
    requestAnimationFrame(() => table.current?.querySelector<HTMLInputElement>(`[data-param="${p.id}"] input`)?.focus());
  };
  // the new row's name is empty, so a change gives it one: the row becomes a parameter, and a new row follows
  const changeName = (p: PreviewParameter, name: string) => {
    if (p.id === blankId) add(name);
    else actions.patchParameter(p.id, { name });
  };
  const rows = editing ? [...parameters, newParameter(blankId)] : parameters;

  return (
    <section className="panel sig-parameters" aria-label="Signature parameters">
      <header className="editor-section-head">
        <h2>
          <CodeIcon /> Parameters <PreviewBadge feature="Mapping parameters" issue={PARAMETERS_ISSUE} />
        </h2>
        <span className="sig-count" aria-label="Parameters">
          {parameters.length}
        </span>
      </header>
      <div className="sig-toolbar" role="toolbar" aria-label="Parameter actions">
        <Tool label="Add parameter" icon={PlusIcon} onClick={addAndFocus} />
        <Tool
          label="Delete parameter"
          icon={TrashIcon}
          disabled={!parameter}
          onClick={() => {
            actions.removeParameter(selected);
            select('');
          }}
        />
        <i />
        <Tool label="Where Used" icon={ProjectDiagramIcon} disabled={!parameter?.name} onClick={() => onLog(`Parameter ${parameter!.name} is not in use`)} />
        <Tool
          label="Refresh"
          icon={RedoIcon}
          onClick={() => {
            actions.sortParameters();
            select('');
          }}
        />
      </div>
      <div className="editor-table-scroll">
        <table ref={table} className="editor-table sig-param-table" aria-label="Mapping parameters">
          <thead>
            <tr>
              {['Name', 'Category', 'Type', 'Parameter', 'Description'].map((h) => (
                <th key={h}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => {
              const blank = p.id === blankId;
              const prefix = json[p.direction] ? '' : 'xsd:';
              return (
                <tr key={p.id} data-param={p.id} className={selected === p.id ? 'sig-selected' : ''} onFocus={() => select(p.id)} onClick={() => select(p.id)}>
                  <td>
                    <input aria-label="Parameter name" placeholder={blank ? 'New parameter…' : 'Name'} value={p.name} onChange={(e) => changeName(p, e.target.value)} />
                  </td>
                  {blank ? (
                    <td colSpan={4} />
                  ) : (
                    <>
                      <td>
                        <FieldSelect label="Parameter category" value="simple" options={[['simple', 'Simple Type']]} onChange={() => undefined} />
                      </td>
                      <td>
                        <FieldSelect
                          label="Parameter type"
                          value={p.type}
                          options={[
                            ['string', `${prefix}string`],
                            ['integer', `${prefix}integer`],
                          ]}
                          onChange={(type) => actions.patchParameter(p.id, { type })}
                        />
                      </td>
                      <td>
                        <FieldSelect
                          label="Parameter direction"
                          value={p.direction}
                          options={[
                            ['Import', 'Import'],
                            ['Export', 'Export'],
                          ]}
                          onChange={(direction) => actions.patchParameter(p.id, { direction })}
                        />
                      </td>
                      <td>
                        <input aria-label="Parameter description" value={p.description} onChange={(e) => actions.patchParameter(p.id, { description: e.target.value })} />
                      </td>
                    </>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** The processing log of Where Used (the prototype's sig-log panel), with a copy button. */
function ProcessingLog({ log }: { log: string }) {
  return (
    <div className="panel sig-log">
      <h3>Processing Log</h3>
      <div className="copy-surface plain-log">
        <p>{log}</p>
        <div className="clipboard-actions">
          <CopyTextButton label="processing log" text={log} />
        </div>
      </div>
    </div>
  );
}

/** The Processing Log dialog Where Used opens (the prototype's log Dialog). */
function LogDialog({ log, onClose }: { log: string; onClose: () => void }) {
  const title = useId();
  return (
    <Modal variant="small" isOpen onClose={onClose} aria-labelledby={title}>
      <ModalHeader title="Processing Log" labelId={title} />
      <ModalBody>
        <p>{log}</p>
      </ModalBody>
      <ModalFooter>
        <Button onClick={onClose}>Close</Button>
      </ModalFooter>
    </Modal>
  );
}
