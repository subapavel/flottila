import { Button, Modal, ModalBody, ModalFooter, ModalHeader, Toolbar, ToolbarContent, ToolbarItem } from '@patternfly/react-core';
import { PlayIcon } from '@patternfly/react-icons';
import { useState } from 'react';
import { useStore } from 'zustand';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { selectTraceStale, type WorkingCopyStore } from '../store/working-copy';
import { PaneSizeTools } from '../shared/PaneSizeTools';
import { readPanelPreference, savePanelPreference } from '../shared/panel-preferences';
import { SideBySide, useSplitLayout, VerticalSplitLayout } from '../shared/SplitLayout';
import { PreviewBadge } from '../mock/PreviewBadge';
import { signatureMock, type PreviewParameter } from '../mock/signature-mock';
import { testInput, testInstances } from '../mock/test-instances';
import { HEADER_DEFAULTS, testPreview, TRACE_LEVEL_ISSUE, TRACE_LEVELS, type TraceLevel } from '../mock/test-preview';
import { CopySurface } from '../shared/CopyTextButton';
import { formatOf } from '../shared/documents';
import { InstanceTools } from '../mock/InstanceTools';
import { DOCUMENT_MODES, TestDocument, type DocumentMode, type ParameterRow } from './TestDocument';
import './test.css';
import { logLines, logText, type LogLine, type TestSession } from '../shared/test-session';

export interface TestTabProps {
  mappingId: string;
  store: WorkingCopyStore;
  /** The open mapping's test session: the source document and the processing log, which outlive the tab. */
  session: TestSession;
  browserState: BrowserStateStore;
}

const viewKey = (pane: 'source' | 'result') => `test.${pane}.view`;
const isMode = (value: unknown): value is DocumentMode => DOCUMENT_MODES.includes(value as DocumentMode);

/** The preview parameters of the signature (#167) in one direction, as rows of a pane's parameter table. */
function parameterRows(parameters: readonly PreviewParameter[], direction: PreviewParameter['direction']): ParameterRow[] {
  return parameters.filter((p) => p.direction === direction && p.name.trim()).map((p) => ({ id: `param:${p.id}`, label: p.name, initial: '' }));
}

const HEADER_ROWS: ParameterRow[] = Object.entries(HEADER_DEFAULTS).map(([key, value]) => ({ id: `header:${key}`, label: key, initial: value }));

function LogBody({ lines }: { lines: readonly LogLine[] }) {
  return (
    <ol className="processing-lines">
      {lines.map((line, i) => (
        <li key={i} className={line.detail ? 'trace-detail' : ''}>
          {line.time && <time>{line.time}</time>}
          {line.message}
        </li>
      ))}
    </ol>
  );
}

/**
 * The Test tab (the prototype's TestPanel): the source document and the result of the last run side by side, the Run
 * simulation bar, and the processing log with the last run and the latest compile. Run sends the working copy and the
 * source document to the service (ADR-0013), in read mode too, as in SAP PO (#177): there the source is a throw-away
 * input, and only edit mode loads a file or changes the test instances. The run's trace feeds the display queue. The
 * trace level, the parameters and the test instances are previews (ADR-0028) and never reach the service.
 */
export function TestTab({ mappingId, store, session, browserState }: TestTabProps) {
  const editing = useStore(store, (s) => s.editing);
  const model = useStore(store, (s) => s.workingCopy);
  const lastRun = useStore(store, (s) => s.lastRun);
  const stale = useStore(store, selectTraceStale);
  const source = useStore(session, (s) => s.source);
  const running = useStore(session, (s) => s.running);
  const runFailed = useStore(session, (s) => s.runFailed);
  const runLog = useStore(session, (s) => s.runLog);
  const compileLog = useStore(session, (s) => s.compileLog);
  const preview = testPreview(store, browserState, mappingId);
  const library = testInstances(store, browserState, mappingId);
  // the signature's parameters as this edit session has them (a draft, ADR-0028)
  const signatureParameters = useStore(signatureMock(store, browserState, mappingId), (s) => s.value.parameters);
  const trace = useStore(preview, (s) => s.traceLevel);
  const values = useStore(preview, (s) => s.values);
  const [views, setViews] = useState(() => ({
    source: readPanelPreference(browserState, viewKey('source'), 'Text', isMode),
    result: readPanelPreference(browserState, viewKey('result'), 'Text', isMode),
  }));
  const [expanded, setExpanded] = useState(false);
  const layout = useSplitLayout(browserState, 'test.layout', 0.25);
  // the shell shows this tab only once both messages are chosen
  const src = formatOf(model.source!);
  const dst = formatOf(model.target!);
  const lines = logLines(compileLog, runLog);
  const text = logText(lines);
  const output = !runFailed && lastRun?.kind === 'output' ? lastRun.output : null;

  const setView = (pane: 'source' | 'result', mode: DocumentMode) => {
    setViews((previous) => ({ ...previous, [pane]: mode }));
    savePanelPreference(browserState, viewKey(pane), mode);
  };
  const run = () => void session.getState().runTest(trace);

  return (
    <section
      className="mapping-test"
      aria-label="Test"
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || event.defaultPrevented || layout.view === 'split' || expanded) return;
        event.preventDefault();
        layout.setView('split');
      }}
    >
      <div role="toolbar" aria-label="Test instance actions" className="test-instance-bar panel">
        <InstanceTools
          library={library}
          format={src}
          resource={() => ({ text: source, values })}
          onLoad={(text) => testInput(store).getState().load(text)}
          editing={editing}
          disabled={running}
        />
        <PaneSizeTools upper pane="input and result" label="Input and result layout" view={layout.view} onChange={layout.setView} />
      </div>
      <VerticalSplitLayout
        className="test-split-layout"
        label="Resize processing log"
        lowerId="processing-log"
        initialShare={layout.share}
        onCommit={layout.setShare}
        view={layout.view}
        upper={
          <SideBySide className="test-panes" label="Resize input and result panels" leftId="test-input-pane" browserState={browserState} widthKey={`test.${src}.width`}>
            <TestDocument
              side="Input"
              format={src}
              text={source}
              mode={views.source}
              onMode={(mode) => setView('source', mode)}
              edit={(value) => session.getState().setSource(value)}
              readOnly={running}
              parameters={[
                { title: 'Import Parameters', rows: parameterRows(signatureParameters, 'Import') },
                { title: 'Header Parameters', rows: HEADER_ROWS },
              ]}
              values={values}
              onParameter={(id, value) => preview.getState().setValue(id, value)}
              // the parameter values are kept in the browser: read mode leaves them as they are
              valuesReadOnly={!editing || running}
            />
            <TestDocument
              side="Output"
              format={dst}
              text={output}
              failed={runFailed}
              stale={output !== null && stale}
              mode={views.result}
              onMode={(mode) => setView('result', mode)}
              readOnly={false}
              parameters={[{ title: 'Export Parameters', rows: parameterRows(signatureParameters, 'Export') }]}
              values={values}
            />
          </SideBySide>
        }
        toolbar={
          <Toolbar role="toolbar" aria-label="Simulation controls" className="simulation-toolbar" inset={{ default: 'insetSm' }}>
            <ToolbarContent>
              <ToolbarItem>
                <Button size="sm" icon={<PlayIcon />} isDisabled={running} isLoading={running} title="Start the Transformation" onClick={run}>
                  Run simulation
                </Button>
              </ToolbarItem>
              <ToolbarItem variant="separator" />
              <ToolbarItem>
                <label className="trace-level">
                  Trace Level
                  <select aria-label="Trace Level" value={trace} disabled={!editing || running} onChange={(e) => preview.getState().setTraceLevel(e.target.value as TraceLevel)}>
                    {TRACE_LEVELS.map((level) => (
                      <option key={level}>{level}</option>
                    ))}
                  </select>
                </label>
                <PreviewBadge feature="Trace level" issue={TRACE_LEVEL_ISSUE} />
              </ToolbarItem>
              <ToolbarItem align={{ default: 'alignEnd' }}>
                <PaneSizeTools pane="processing log" label="Processing log layout" view={layout.view} onChange={layout.setView} />
              </ToolbarItem>
            </ToolbarContent>
          </Toolbar>
        }
      >
        <section className="panel test-log" aria-labelledby="processing-log-heading" tabIndex={0}>
          <header>
            <h3 id="processing-log-heading">Processing log</h3>
            {lines.length > 0 && (
              <>
                {runLog && <small>Last run · {runLog.level}</small>}
                <Button size="sm" variant="link" onClick={() => setExpanded(true)}>
                  Expand log
                </Button>
              </>
            )}
          </header>
          <CopySurface className="log-content" label="processing log" text={text}>
            {lines.length > 0 ? <LogBody lines={lines} /> : <p role="status">{running ? 'Running the simulation…' : 'Ready.'}</p>}
          </CopySurface>
        </section>
      </VerticalSplitLayout>
      {expanded && (
        <Modal isOpen variant="medium" aria-label="Processing Log" onClose={() => setExpanded(false)}>
          <ModalHeader title="Processing Log" description={runLog ? `Simulation · ${runLog.level}` : 'Compile of the working copy'} />
          <ModalBody>
            <CopySurface className="log-content" label="processing log" text={text}>
              <LogBody lines={lines} />
            </CopySurface>
          </ModalBody>
          <ModalFooter>
            <Button onClick={() => setExpanded(false)}>Close</Button>
          </ModalFooter>
        </Modal>
      )}
    </section>
  );
}
