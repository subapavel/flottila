import { Button } from '@patternfly/react-core';
import { CodeIcon, DownloadIcon, UploadIcon } from '@patternfly/react-icons';
import { useQuery } from '@tanstack/react-query';
import { useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import type { ApiClient } from '../../api/client';
import type { WorkingCopyStore } from '../store/working-copy';
import type { FunctionsStore } from '../mock/functions-store';
import { PreviewBadge } from '../mock/PreviewBadge';
import { downloadFile } from '../shared/download';
import {
  AREAS,
  ATTRIBUTES,
  builtIn,
  declaration,
  EXECUTION_KINDS,
  metadata,
  moveRow,
  newFunction,
  unusedName,
  type AreaName,
  type ExecutionKind,
  type JavaArea,
  type Udf,
} from '../mock/function-model';
import { exportSource, importSource } from './function-source';
import { Dependencies, NameEditor, RowTools, Variables } from './FunctionTables';
import { Dialog, Tool } from './ToolbarTools';
import './functions.css';

const JDK = 'JDK 25 (Default)';

export interface FunctionsTabProps {
  /** The mapping's id: its name names the class, its mapping package the Java package. */
  mappingId: string;
  /** The open mapping's store, read for edit mode and the messages only: the functions never enter the model. */
  store: WorkingCopyStore;
  /** The mapping's functions, a draft of the edit session over the ones saved in the browser. */
  functions: FunctionsStore;
  /** Read for the standard functions' names, which a function may not take. */
  api: ApiClient;
}

/**
 * The Functions tab, a preview (ADR-0028, ADR-0030, #169; the prototype's FunctionsTab): the class and package of the
 * mapping's functions, Export and Import Source Text, the list of Java areas and user-defined functions with their
 * editor, and the import instructions and archives. The functions live in the browser only (`mock/functions-store`),
 * never in the mapping file; Save keeps an edit and Discard drops it. They are edited in edit mode once the mapping has
 * both messages; otherwise they are read-only, and only Export Source Text works.
 */
export function FunctionsTab({ mappingId, store, functions, api }: FunctionsTabProps) {
  const state = useStore(functions, (s) => s.functions);
  const edit = useStore(functions, (s) => s.edit);
  const editing = useStore(store, (s) => s.editing);
  const hasMessages = useStore(store, (s) => s.workingCopy.source !== undefined && s.workingCopy.target !== undefined);
  const catalogue = useQuery({ queryKey: ['catalogue'], queryFn: () => api.getCatalogue() });
  const [selected, select] = useState<string>(ATTRIBUTES);
  const [log, setLog] = useState('');
  const [showLog, setShowLog] = useState(false);
  const file = useRef<HTMLInputElement>(null);

  const list = state.functions;
  const index = list.findIndex((fn) => fn.id === selected);
  const isCustom = index >= 0;
  const custom: Udf | undefined = list[index];
  const active: Udf | (JavaArea & { name: AreaName; id?: undefined }) = custom ?? builtIn(state, selected as AreaName);
  const attributes = active.name === ATTRIBUTES;
  const lifecycle = !isCustom && !attributes;
  const ready = editing && hasMessages;
  const meta = metadata(mappingId);

  function change(changes: Partial<Udf>) {
    if (isCustom) edit((s) => ({ ...s, functions: s.functions.map((fn) => (fn.id === active.id ? { ...fn, ...changes } : fn)) }));
    else {
      const name = active.name as AreaName;
      edit((s) => {
        const { body, description, variables } = { ...builtIn(s, name), ...changes };
        return { ...s, javaAreas: { ...s.javaAreas, [name]: { body, description, variables } } };
      });
    }
  }
  function add() {
    const fn = newFunction(unusedName('calculate', list.map((f) => f.name)));
    const at = index + 1;
    edit((s) => ({ ...s, functions: [...s.functions.slice(0, at), fn, ...s.functions.slice(at)] }));
    select(fn.id);
  }
  function remove() {
    edit((s) => ({ ...s, functions: s.functions.filter((fn) => fn.id !== active.id) }));
    select(list[index - 1]?.id ?? list[index + 1]?.id ?? ATTRIBUTES);
  }
  function download() {
    const filename = `${meta.className}.java`;
    downloadFile(filename, new Blob([exportSource(mappingId, state)], { type: 'text/plain;charset=utf-8' }));
    setLog(`Exported ${filename}`);
    setShowLog(true);
  }
  async function readFile(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files![0]!;
    event.target.value = '';
    const text = await chosen.text();
    try {
      edit((s) => importSource(mappingId, s, text));
      setLog(`Imported source text from ${chosen.name}`);
    } catch (error) {
      setLog(`Import failed. ${(error as Error).message}`);
    }
    setShowLog(true);
  }

  const taken = [...AREAS, ...Object.keys(catalogue.data ?? {})];
  return (
    <div className="functions-tab">
      <div className="panel function-meta udf-metadata">
        <div>
          <span>Class Name</span>
          <code>{meta.className}</code>
        </div>
        <div>
          <span>Package Name</span>
          <code>{meta.packageName || '(default package)'}</code>
        </div>
        <PreviewBadge feature="User-defined functions" issue={169} />
        <div className="udf-file-actions" role="toolbar" aria-label="Java source actions">
          <Tool label="Export Source Text" icon={DownloadIcon} onClick={download} />
          <Tool label="Import Source Text" icon={UploadIcon} disabled={!ready} onClick={() => file.current!.click()} />
          <input type="file" disabled={!ready} hidden accept=".java,text/plain" aria-label="Java source file" ref={file} onChange={(e) => void readFile(e)} />
        </div>
        <label>
          JDK Compliance
          <select aria-label="JDK Compliance" defaultValue={JDK} disabled={!ready}>
            <option>{JDK}</option>
          </select>
        </label>
      </div>
      <div className="function-workspace udf-workspace">
        <aside className="function-side">
          <section className="panel">
            <header className="editor-section-head">
              <h2>Functions and Java Areas</h2>
            </header>
            <RowTools
              label="Function actions"
              disabled={!ready}
              onAdd={add}
              canRemove={isCustom}
              canUp={index > 0}
              canDown={isCustom && index < list.length - 1}
              onRemove={remove}
              onMove={(delta) => edit((s) => ({ ...s, functions: [...moveRow(s.functions, index, delta)] }))}
            />
            <div className="udf-name-heading">Name</div>
            <div className="area-list">
              {AREAS.map((name) => (
                <button key={name} aria-pressed={active.name === name} onClick={() => select(name)}>
                  <CodeIcon />
                  {name}
                </button>
              ))}
              {list.map((fn) => (
                <div className={`udf-function-row ${active.id === fn.id ? 'current' : ''}`} key={fn.id}>
                  <button aria-label={`Select function ${fn.name}`} aria-pressed={active.id === fn.id} onClick={() => select(fn.id)}>
                    <CodeIcon />
                    {fn.name}
                  </button>
                  {ready && fn.id === active.id && (
                    <label className="udf-method-name">
                      Method name
                      <NameEditor label="Method name" value={fn.name} taken={[...taken, ...list.filter((f) => f.id !== fn.id).map((f) => f.name)]} onCommit={(name) => change({ name })} />
                    </label>
                  )}
                </div>
              ))}
            </div>
          </section>
          <Dependencies state={state} edit={edit} disabled={!ready} />
        </aside>
        <section className="panel udf-editor" aria-label="Function editor">
          <header className="editor-section-head">
            <h2>
              <CodeIcon />
              {active.name}
            </h2>
            <span>Java</span>
          </header>
          {(isCustom || active.name === 'init') && (
            <fieldset className="udf-properties mode-fields" disabled={!ready} key={`properties-${selected}`}>
              {custom && (
                <label>
                  Title
                  <input value={custom.title} onChange={(e) => change({ title: e.target.value })} />
                </label>
              )}
              <label>
                Description
                <input value={active.description} onChange={(e) => change({ description: e.target.value })} />
              </label>
              {custom && (
                <>
                  <label>
                    Execution Kind
                    <select aria-label="Execution Kind" value={custom.execution} onChange={(e) => change({ execution: e.target.value as ExecutionKind })}>
                      {EXECUTION_KINDS.map((m) => (
                        <option key={m}>{m}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Category
                    <input value={custom.category} onChange={(e) => change({ category: e.target.value })} />
                  </label>
                </>
              )}
            </fieldset>
          )}
          {(isCustom || active.name === 'init') && (
            <Variables disabled={!ready} key={`variables-${selected}`} fn={active} lifecycle={lifecycle} onChange={(variables) => change({ variables })} />
          )}
          <div className="udf-code">
            <div className="udf-declaration">
              {attributes ? (
                <p>Class attributes and shared methods are available to all functions in this mapping.</p>
              ) : (
                <>
                  {lifecycle && <p>{active.name === 'init' ? 'Called before mapping.' : 'Called after mapping.'}</p>}
                  <code>{declaration(active)}</code>
                </>
              )}
            </div>
            <textarea aria-label={`Source code for ${active.name}`} readOnly={!ready} spellCheck={false} value={active.body} onChange={(e) => change({ body: e.target.value })} />
            {!attributes && <code className="udf-closing">{'}'}</code>}
          </div>
        </section>
      </div>
      {log && (
        <div className="panel sig-log">
          <h3>Processing Log</h3>
          <p className="plain-log" role="status">
            {log}
          </p>
        </div>
      )}
      {showLog && (
        <Dialog title="Processing Log" close={() => setShowLog(false)} footer={<Button onClick={() => setShowLog(false)}>Close</Button>}>
          <p className="plain-log">{log}</p>
        </Dialog>
      )}
    </div>
  );
}
