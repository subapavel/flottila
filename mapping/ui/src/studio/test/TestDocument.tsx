import { AlignLeftIcon, AngleDoubleDownIcon, AngleDoubleUpIcon, AngleDownIcon, AngleRightIcon, CodeIcon, DownloadIcon, RouteIcon } from '@patternfly/react-icons';
import { useMemo, useState } from 'react';
import { FieldTypeIcon } from '../shared/FieldTypeIcon';
import { Tool } from '../shared/Tool';
import { ViewSwitch } from '../shared/ViewSwitch';
import { downloadFile } from '../shared/download';
import { PreviewBadge } from '../mock/PreviewBadge';
import { PARAMETERS_ISSUE } from '../mock/signature-mock';
import { zipInstance } from '../mock/test-instances';
import { CopySurface } from '../shared/CopyTextButton';
import { documentRows, prettyText, type DocumentFormat } from '../shared/documents';

/** A pane's view of its document. */
export const DOCUMENT_MODES = ['Tree', 'Text'] as const;
export type DocumentMode = (typeof DOCUMENT_MODES)[number];

const SECTIONS = ['Document', 'Parameters'] as const;
type Section = (typeof SECTIONS)[number];

/** A row of a pane's parameter tables (a preview, #167): its id among the values, its label and its default. */
export interface ParameterRow {
  id: string;
  label: string;
  initial: string;
}

export interface TestDocumentProps {
  side: 'Input' | 'Output';
  format: DocumentFormat;
  /** The document; `null` for a result before a run, or after a failed one. */
  text: string | null;
  /** Whether the last run failed: the result says so instead of asking for a run. */
  failed?: boolean;
  mode: DocumentMode;
  onMode: (mode: DocumentMode) => void;
  /** Changes the source document; the result has none. */
  edit?: (text: string) => void;
  readOnly: boolean;
  /** Whether a stale trace stands behind the result: it shows a mark. */
  stale?: boolean;
  /** The parameter tables of the Parameters section, each with its rows (a preview, #167). */
  parameters: { title: string; rows: ParameterRow[] }[];
  values: Readonly<Record<string, string>>;
  onParameter?: (id: string, value: string) => void;
  /** Whether the parameter values are read-only; as the document, unless told. */
  valuesReadOnly?: boolean;
}

/**
 * A pane of the Test tab (the prototype's TestDocument): the source document, which the author types, or the result of
 * the last run, each as a tree of fields and values or as text, and a Parameters section, a preview.
 */
export function TestDocument({
  side,
  format,
  text,
  failed = false,
  mode,
  onMode,
  edit,
  readOnly,
  stale = false,
  parameters,
  values,
  onParameter,
  valuesReadOnly = readOnly,
}: TestDocumentProps) {
  const input = side === 'Input';
  const title = input ? 'Source' : 'Result';
  const [section, setSection] = useState<Section>('Document');
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const [selection, select] = useState<string | null>(null);
  const [fullPath, setFullPath] = useState(false);
  const [formatted, setFormatted] = useState<{ original: string; text: string } | null>(null);
  const [error, setError] = useState('');
  const available = text !== null;
  const displayed = !input && formatted?.original === text ? formatted.text : (text ?? '');
  const treeMode = mode === 'Tree';
  const rows = useMemo(() => {
    try {
      return available ? documentRows(displayed, format, input ? 'Source' : 'Result') : [];
    } catch {
      return null;
    }
  }, [available, displayed, format, input]);
  const visible = useMemo(() => {
    const shown = [];
    let hiddenBelow = Infinity;
    for (const row of rows ?? []) {
      if (row.level > hiddenBelow) continue;
      hiddenBelow = closed.has(row.id) ? row.level : Infinity;
      shown.push(row);
    }
    return shown;
  }, [rows, closed]);
  const selectedPath = rows?.find((r) => r.id === selection)?.path ?? rows?.[0]?.path ?? '';

  const switchMode = (next: DocumentMode) => {
    setError('');
    onMode(next);
  };
  const toggleExpand = () => setClosed(closed.size > 0 ? new Set() : new Set((rows ?? []).filter((r) => r.branch).map((r) => r.id)));
  const toggleRow = (id: string) =>
    setClosed((old) => {
      const next = new Set(old);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const prettyPrint = () => {
    try {
      const value = prettyText(displayed, format);
      if (edit) edit(value);
      else setFormatted({ original: text!, text: value });
      setError('');
    } catch (e) {
      setError(`Cannot process this ${format} document. ${(e as Error).message}`);
    }
  };
  // the prototype's downloadInstance: the document as instance.<format> in schema.zip (#170)
  const download = () => downloadFile('schema.zip', new Blob([zipInstance(displayed, format)], { type: 'application/zip' }));
  const empty = (
    <div className="result-empty">
      <CodeIcon />
      <p>{failed ? 'The simulation failed.' : `Run the simulation to display the ${format} result.`}</p>
    </div>
  );

  return (
    <section id={input ? 'test-input-pane' : 'test-result-pane'} className="panel xml-card test-document" aria-label={title}>
      <header>
        <h3>
          <CodeIcon />
          {title}
        </h3>
        {stale && (
          <span className="stale-mark" title="The working copy changed since this run.">
            stale
          </span>
        )}
        <code>{format}</code>
      </header>
      {section === 'Document' ? (
        <>
          <div className="doc-tools">
            <ViewSwitch label={`${side} document view`} value={mode} onChange={switchMode} modes={DOCUMENT_MODES} />
            <div className="tree-tool-buttons" role="toolbar" aria-label={`${side} document tools`}>
              {treeMode ? (
                <>
                  <Tool
                    label={closed.size > 0 ? 'Expand Tree' : 'Collapse Tree'}
                    icon={closed.size > 0 ? AngleDoubleDownIcon : AngleDoubleUpIcon}
                    disabled={!rows?.length}
                    onClick={toggleExpand}
                  />
                  <Tool label="Show Full Path" icon={RouteIcon} pressed={fullPath} onClick={() => setFullPath((v) => !v)} />
                </>
              ) : (
                <Tool label="Pretty Print" icon={AlignLeftIcon} disabled={!available || readOnly} onClick={prettyPrint} />
              )}
              <Tool label="Save Document Source" icon={DownloadIcon} disabled={!available} onClick={download} />
            </div>
          </div>
          {error && (
            <p className="doc-error" role="alert">
              {error}
            </p>
          )}
          {treeMode ? (
            !available ? (
              empty
            ) : rows === null ? (
              <p className="empty-message" role="status">
                The {format} cannot be displayed as a tree. Open Text to correct it.
              </p>
            ) : (
              <div className="instance-scroll">
                <table className="instance-tree" aria-label={`${side === 'Input' ? 'Input' : 'Result'} structure and values`}>
                  <thead>
                    <tr>
                      <th>Structure</th>
                      <th>Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visible.map((row) => (
                      <tr key={row.id} className={selection === row.id ? 'selected-instance' : ''} onClick={() => select(row.id)}>
                        <td style={{ paddingLeft: 8 + row.level * 13 }}>
                          {row.branch ? (
                            <button
                              type="button"
                              className="caret"
                              aria-expanded={!closed.has(row.id)}
                              aria-label={`${closed.has(row.id) ? 'Expand' : 'Collapse'} ${row.name}`}
                              onClick={() => toggleRow(row.id)}
                            >
                              {closed.has(row.id) ? <AngleRightIcon /> : <AngleDownIcon />}
                            </button>
                          ) : (
                            <span className="instance-spacer" />
                          )}
                          <button type="button" className="instance-name" onClick={() => select(row.id)}>
                            <FieldTypeIcon type={format === 'XML' && row.branch ? 'structure' : row.kind} />
                            {row.name}
                          </button>
                        </td>
                        <td>{row.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
          ) : (
            <CopySurface className="document-content" label={`${input ? 'input' : 'result'} ${format}`} text={displayed}>
              {input ? (
                <textarea aria-label={`Input ${format}`} spellCheck={false} readOnly={readOnly} value={displayed} onChange={(e) => edit!(e.target.value)} />
              ) : available ? (
                <pre className="result-xml">{displayed}</pre>
              ) : (
                empty
              )}
            </CopySurface>
          )}
          {treeMode && fullPath && (
            <CopySurface className="path-display" label={`${side.toLowerCase()} path`} text={selectedPath}>
              <code>{selectedPath}</code>
            </CopySurface>
          )}
        </>
      ) : (
        <div className="test-parameters">
          <p className="parameters-preview">
            Parameters of the test run <PreviewBadge feature="Mapping parameters and message headers" issue={PARAMETERS_ISSUE} />
          </p>
          {parameters.map(({ title: group, rows: entries }) => (
            <section key={group} className="parameter-group">
              <h4>{group}</h4>
              <div className="params-scroll">
                <table className="instance-tree" aria-label={group}>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map(({ id, label, initial }) => (
                      <tr key={id}>
                        <td>{label}</td>
                        <td>{onParameter && <input aria-label={label} readOnly={valuesReadOnly} value={values[id] ?? initial} onChange={(e) => onParameter(id, e.target.value)} />}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {entries.length === 0 && <p className="table-empty">No {input ? 'import' : 'export'} parameters defined.</p>}
              </div>
            </section>
          ))}
        </div>
      )}
      <div className="doc-tabs" role="group" aria-label={`${side} panel`}>
        {SECTIONS.map((tab) => (
          <button key={tab} type="button" aria-pressed={section === tab} onClick={() => setSection(tab)}>
            {tab}
          </button>
        ))}
      </div>
    </section>
  );
}
