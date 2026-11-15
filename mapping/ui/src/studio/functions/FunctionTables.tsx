import { ArrowDownIcon, ArrowUpIcon, CopyIcon, CutIcon, PasteIcon, PlusIcon, SearchIcon, TrashIcon } from '@patternfly/react-icons';
import { useState, type ReactNode } from 'react';
import { ArchiveCatalog } from './ArchiveCatalog';
import { INITIAL_IMPORTS, JAVA_TYPES, moveRow, nameIssue, variable, type Archive, type FunctionsState, type Udf, type Variable, type VariableKind } from '../mock/function-model';
import { Tool } from './ToolbarTools';

/** Insert, Delete, Move up and Move down for the rows of a table, and any further tools (the prototype's RowTools). */
export function RowTools(props: {
  label: string;
  onAdd: () => void;
  onRemove: () => void;
  onMove: (delta: number) => void;
  canRemove: boolean;
  canUp: boolean;
  canDown: boolean;
  disabled?: boolean;
  children?: ReactNode;
}) {
  const { label, onAdd, onRemove, onMove, canRemove, canUp, canDown, disabled = false, children } = props;
  return (
    <div className="sig-toolbar" role="toolbar" aria-label={label}>
      <Tool label="Insert row" icon={PlusIcon} disabled={disabled} onClick={onAdd} />
      <Tool label="Delete row" icon={TrashIcon} disabled={disabled || !canRemove} onClick={onRemove} />
      <i />
      <Tool label="Move up" icon={ArrowUpIcon} disabled={disabled || !canUp} onClick={() => onMove(-1)} />
      <Tool label="Move down" icon={ArrowDownIcon} disabled={disabled || !canDown} onClick={() => onMove(1)} />
      {children}
    </div>
  );
}

/**
 * A name field that commits on blur or Enter when the name is a Java identifier not in `taken`, and says why not
 * otherwise; Escape drops the draft (the prototype's NameEditor).
 */
export function NameEditor({ value, label, taken, onCommit }: { value: string; label: string; taken: readonly string[]; onCommit: (name: string) => void }) {
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState('');
  function commit() {
    if (draft === null) return;
    const name = draft.trim();
    const issue = nameIssue(name, taken);
    if (issue) {
      setError(issue);
      return;
    }
    onCommit(name);
    setDraft(null);
    setError('');
  }
  return (
    <>
      <input
        aria-label={label}
        aria-invalid={!!error}
        value={draft ?? value}
        onChange={(e) => {
          setError('');
          setDraft(e.target.value);
        }}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur();
          if (e.key === 'Escape') {
            setDraft(null);
            setError('');
          }
        }}
      />
      {error && (
        <small role="alert" className="udf-error">
          {error}
        </small>
      )}
    </>
  );
}

/** The signature variables of a function or lifecycle area; a lifecycle area has box parameters only. */
export function Variables({ fn, onChange, lifecycle, disabled }: { fn: Pick<Udf, 'variables'>; onChange: (variables: Variable[]) => void; lifecycle: boolean; disabled: boolean }) {
  const [selected, select] = useState<string | null>(null);
  const rows = fn.variables;
  const index = rows.findIndex((v) => v.id === selected);
  const patch = (id: string, changes: Partial<Variable>) => onChange(rows.map((v) => (v.id === id ? { ...v, ...changes } : v)));
  function add() {
    const v = variable(lifecycle ? 'Parameter' : 'Argument', rows);
    const at = index < 0 ? rows.length : index + 1;
    onChange([...rows.slice(0, at), v, ...rows.slice(at)]);
    select(v.id);
  }
  const names = rows.map((r) => r.name);
  const headings = ['Type', 'Name', 'Java Type', 'Title', ...(lifecycle ? [] : ['Description'])];
  return (
    <fieldset disabled={disabled} className="udf-vars mode-fields" aria-label="Signature Variables">
      <h3>Signature Variables</h3>
      <RowTools
        label="Variable actions"
        onAdd={add}
        canRemove={index >= 0}
        canUp={index > 0}
        canDown={index >= 0 && index < rows.length - 1}
        onRemove={() => {
          onChange(rows.filter((v) => v.id !== selected));
          select(null);
        }}
        onMove={(delta) => onChange([...moveRow(rows, index, delta)])}
      />
      <div className="editor-table-scroll">
        <table className="editor-table udf-table">
          <thead>
            <tr>
              {headings.map((s) => (
                <th key={s}>{s}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((v) => (
              <tr key={v.id} className={v.id === selected ? 'sig-selected' : ''} onFocus={() => select(v.id)} onClick={() => select(v.id)}>
                <td>
                  {lifecycle ? (
                    'Parameter'
                  ) : (
                    <select
                      aria-label="Variable kind"
                      value={v.kind}
                      onChange={(e) => {
                        const kind = e.target.value as VariableKind;
                        patch(v.id, { kind, javaType: (JAVA_TYPES[kind] as readonly string[]).includes(v.javaType) ? v.javaType : 'String' });
                      }}
                    >
                      {Object.keys(JAVA_TYPES).map((k) => (
                        <option key={k}>{k}</option>
                      ))}
                    </select>
                  )}
                </td>
                <td>
                  <NameEditor label="Variable name" value={v.name} taken={['run', 'out', ...names.filter((n) => n !== v.name)]} onCommit={(name) => patch(v.id, { name })} />
                </td>
                <td>
                  <select aria-label="Java type" value={v.javaType} onChange={(e) => patch(v.id, { javaType: e.target.value })}>
                    {JAVA_TYPES[v.kind].map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </td>
                <td>
                  <input aria-label="Variable title" value={v.title} onChange={(e) => patch(v.id, { title: e.target.value })} />
                </td>
                {!lifecycle && (
                  <td>
                    <input aria-label="Variable description" value={v.description ?? ''} onChange={(e) => patch(v.id, { description: e.target.value })} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </fieldset>
  );
}

const DEPENDENCY_VIEWS = ['Import Instructions', 'Archives Used'] as const;
const EMPTY_ARCHIVE: Archive = { name: '', namespace: '' };

/**
 * The import instructions and archives of the mapping's functions (the prototype's Dependencies): the initial import
 * instructions are fixed, the ones added after them editable; an archive and its namespace are chosen from the
 * catalogue, and an archive row can be cut, copied and pasted.
 */
export function Dependencies({ state, edit, disabled }: { state: FunctionsState; edit: (change: (current: FunctionsState) => FunctionsState) => void; disabled: boolean }) {
  const [view, setView] = useState<(typeof DEPENDENCY_VIEWS)[number]>('Import Instructions');
  const [index, select] = useState(-1);
  const [picker, setPicker] = useState<{ row: number; kind: 'archive' | 'namespace' } | null>(null);
  const [clip, setClip] = useState<Archive | null>(null);
  const archive = view === 'Archives Used';
  const rows: readonly (string | Archive)[] = archive ? state.archives : state.imports;
  const valid = index >= 0 && index < rows.length;
  const editable = valid && (archive || index >= INITIAL_IMPORTS.length);
  const write = (list: readonly (string | Archive)[]) =>
    edit((current) => (archive ? { ...current, archives: list as Archive[] } : { ...current, imports: list as string[] }));
  const replace = (at: number, value: string | Archive) => write(rows.map((r, i) => (i === at ? value : r)));
  function add() {
    const at = editable ? index + 1 : rows.length;
    write([...rows.slice(0, at), archive ? EMPTY_ARCHIVE : '', ...rows.slice(at)]);
    select(at);
    if (!archive) requestAnimationFrame(() => document.querySelector<HTMLElement>(`[aria-label="Import instruction ${at + 1}"]`)?.focus());
  }
  function move(delta: number) {
    write(moveRow(rows, index, delta));
    select(index + delta);
  }
  const current = state.archives[index];
  return (
    <section className="panel udf-dependencies" aria-label="Function dependencies">
      <div className="doc-tabs" role="group" aria-label="Function dependencies view">
        {DEPENDENCY_VIEWS.map((t) => (
          <button
            key={t}
            aria-pressed={view === t}
            onClick={() => {
              setView(t);
              select(-1);
            }}
          >
            {t}
          </button>
        ))}
      </div>
      <RowTools
        label={archive ? 'Archive actions' : 'Import actions'}
        disabled={disabled}
        onAdd={add}
        canRemove={editable}
        canUp={editable && index > (archive ? 0 : INITIAL_IMPORTS.length)}
        canDown={editable && index < rows.length - 1}
        onRemove={() => {
          write(rows.filter((_, i) => i !== index));
          select(-1);
        }}
        onMove={move}
      >
        {archive && (
          <>
            <i />
            <Tool
              label="Cut Object"
              icon={CutIcon}
              disabled={disabled || !current?.name}
              onClick={() => {
                setClip(current!);
                replace(index, EMPTY_ARCHIVE);
              }}
            />
            <Tool label="Copy Object" icon={CopyIcon} disabled={!current?.name} onClick={() => setClip(current!)} />
            <Tool label="Paste Object" icon={PasteIcon} disabled={disabled || !current || !clip} onClick={() => replace(index, { ...clip! })} />
          </>
        )}
      </RowTools>
      <div className="editor-table-scroll">
        <table className="editor-table udf-table" aria-label={view}>
          <thead>
            <tr>
              <th>{archive ? 'Name' : 'import'}</th>
              {archive && <th>Namespace</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className={index === i ? 'sig-selected' : ''} onClick={() => select(i)} onFocus={() => select(i)}>
                {typeof r === 'string' ? (
                  <td>
                    {i < INITIAL_IMPORTS.length ? (
                      <code>{r}</code>
                    ) : (
                      <input readOnly={disabled} aria-label={`Import instruction ${i + 1}`} placeholder="package.Class" value={r} onChange={(e) => replace(i, e.target.value)} />
                    )}
                  </td>
                ) : (
                  (['name', 'namespace'] as const).map((field) => (
                    <td key={field}>
                      <button disabled={disabled} className="sig-lookup" aria-label={`Choose archive ${field} ${i + 1}`} onClick={() => setPicker({ row: i, kind: field === 'name' ? 'archive' : 'namespace' })}>
                        <span>{r[field] || 'Choose…'}</span>
                        <SearchIcon />
                      </button>
                    </td>
                  ))
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {picker && (
        <ArchiveCatalog
          kind={picker.kind}
          close={() => setPicker(null)}
          onApply={(entry) => {
            replace(picker.row, picker.kind === 'namespace' ? { ...state.archives[picker.row]!, namespace: entry.name } : entry);
            setPicker(null);
          }}
        />
      )}
    </section>
  );
}
