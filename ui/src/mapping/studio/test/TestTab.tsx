import {
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Content,
  FormSelect,
  FormSelectOption,
  List,
  ListItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  Stack,
  StackItem,
  Title,
  Toolbar,
  ToolbarContent,
  ToolbarGroup,
  ToolbarItem,
} from '@patternfly/react-core';
import { PlayIcon } from '@patternfly/react-icons';
import { useState } from 'react';
import { useStore } from 'zustand';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { InstanceTools } from '../mock/InstanceTools';
import { PreviewBadge } from '../mock/PreviewBadge';
import { signatureMock, type PreviewParameter } from '../mock/signature-mock';
import { testInput, testInstances } from '../mock/test-instances';
import { HEADER_DEFAULTS, testPreview, TRACE_LEVEL_ISSUE, TRACE_LEVELS, type TraceLevel } from '../mock/test-preview';
import { CopyTextButton } from '../shared/CopyTextButton';
import { formatOf } from '../shared/documents';
import { PaneSizeTools } from '../shared/PaneSizeTools';
import { readPanelPreference, savePanelPreference } from '../shared/panel-preferences';
import { SideBySide, useSplitLayout, VerticalSplitLayout } from '../shared/SplitLayout';
import { logLines, logText, type LogLine, type TestSession } from '../shared/test-session';
import { selectTraceStale, type WorkingCopyStore } from '../store/working-copy';
import { DOCUMENT_MODES, TestDocument, type DocumentMode, type ParameterRow } from './TestDocument';

export interface TestTabProps {
  mappingId: string;
  store: WorkingCopyStore;
  /** The open mapping's test session: the source document and the processing log, which outlive the tab. */
  session: TestSession;
  browserState: BrowserStateStore;
}

/** The height of the panes and the processing log together. */
const SPLIT_HEIGHT = '42rem';

const viewKey = (pane: 'source' | 'result') => `test.${pane}.view`;
const isMode = (value: unknown): value is DocumentMode => DOCUMENT_MODES.includes(value as DocumentMode);

/** The preview parameters of the signature in one direction, as rows of a pane's parameter table. */
function parameterRows(parameters: readonly PreviewParameter[], direction: PreviewParameter['direction']): ParameterRow[] {
  return parameters.filter((p) => p.direction === direction && p.name.trim()).map((p) => ({ id: `param:${p.id}`, label: p.name, initial: '' }));
}

const HEADER_ROWS: ParameterRow[] = Object.entries(HEADER_DEFAULTS).map(([key, value]) => ({ id: `header:${key}`, label: key, initial: value }));

/** The lines of the processing log in the monospace font: a section's time before its first line, its details indented. */
function LogBody({ lines }: { lines: readonly LogLine[] }) {
  return (
    <List component="ol" isPlain style={{ fontFamily: 'var(--pf-t--global--font--family--mono)', fontSize: 'var(--pf-t--global--font--size--body--sm)' }}>
      {lines.map((line, i) => (
        <ListItem key={i} style={line.detail ? { paddingInlineStart: 'var(--pf-t--global--spacer--lg)' } : undefined}>
          {line.time && <time style={{ marginInlineEnd: 'var(--pf-t--global--spacer--sm)', color: 'var(--pf-t--global--text--color--subtle)' }}>{line.time}</time>}
          {line.message}
        </ListItem>
      ))}
    </List>
  );
}

/**
 * The Test tab: the test instance tools, the source document and the result of the last run side by side, the
 * Run simulation bar, and the processing log with the last run and the latest compile. Run sends the working copy and
 * the source document to the service, in read mode too, as in SAP PO: there the source is a throw-away input, and only
 * edit mode loads a file or changes the test instances. The run's trace feeds the display queue. The trace level, the
 * parameters and the test instances are previews and never reach the service. Escape restores the split view.
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
  // the signature's parameters as this edit session has them (a draft)
  const signatureParameters = useStore(signatureMock(store, browserState, mappingId), (s) => s.value.parameters);
  const trace = useStore(preview, (s) => s.traceLevel);
  const values = useStore(preview, (s) => s.values);
  const [views, setViews] = useState(() => ({
    source: readPanelPreference(browserState, viewKey('source'), 'Text', isMode),
    result: readPanelPreference(browserState, viewKey('result'), 'Text', isMode),
  }));
  const [expanded, setExpanded] = useState(false);
  const layout = useSplitLayout(browserState, 'test.layout', 0.25);
  // the studio shows this tab only once both messages are chosen
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
    <Stack
      component="section"
      aria-label="Test"
      onKeyDown={(event) => {
        if (event.key !== 'Escape' || event.defaultPrevented || layout.view === 'split' || expanded) return;
        event.preventDefault();
        layout.setView('split');
      }}
    >
      <StackItem>
        <Toolbar role="toolbar" aria-label="Test instance actions">
          <ToolbarContent>
            <ToolbarGroup variant="action-group-plain" alignItems="center">
              <InstanceTools
                library={library}
                format={src}
                resource={() => ({ text: source, values })}
                onLoad={(loaded) => testInput(store).getState().load(loaded)}
                editing={editing}
                disabled={running}
              />
            </ToolbarGroup>
            <ToolbarItem align={{ default: 'alignEnd' }}>
              <PaneSizeTools upper pane="input and result" label="Input and result layout" view={layout.view} onChange={layout.setView} />
            </ToolbarItem>
          </ToolbarContent>
        </Toolbar>
      </StackItem>
      <StackItem style={{ display: 'flex', flexDirection: 'column', height: SPLIT_HEIGHT }}>
        <VerticalSplitLayout
          label="Resize processing log"
          lowerId={`${mappingId}-processing-log`}
          initialShare={layout.share}
          onCommit={layout.setShare}
          view={layout.view}
          upper={
            <SideBySide label="Resize input and result panels" leftId="test-input-pane" browserState={browserState} widthKey={`test.${src}.width`}>
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
            <Toolbar role="toolbar" aria-label="Simulation controls">
              <ToolbarContent>
                <ToolbarItem>
                  <Button size="sm" icon={<PlayIcon />} isDisabled={running} isLoading={running} title="Start the Transformation" onClick={run}>
                    Run simulation
                  </Button>
                </ToolbarItem>
                <ToolbarItem variant="separator" />
                <ToolbarGroup alignItems="center">
                  <ToolbarItem>
                    <FormSelect
                      aria-label="Trace Level"
                      value={trace}
                      isDisabled={!editing || running}
                      onChange={(_event, value) => preview.getState().setTraceLevel(value as TraceLevel)}
                    >
                      {TRACE_LEVELS.map((level) => (
                        <FormSelectOption key={level} value={level} label={level} />
                      ))}
                    </FormSelect>
                  </ToolbarItem>
                  <ToolbarItem>
                    <PreviewBadge feature="Trace level" issue={TRACE_LEVEL_ISSUE} />
                  </ToolbarItem>
                </ToolbarGroup>
                <ToolbarItem align={{ default: 'alignEnd' }}>
                  <PaneSizeTools pane="processing log" label="Processing log layout" view={layout.view} onChange={layout.setView} />
                </ToolbarItem>
              </ToolbarContent>
            </Toolbar>
          }
        >
          <Card isFullHeight isCompact component="section" aria-labelledby={`${mappingId}-processing-log-heading`} tabIndex={0} style={{ minHeight: 0 }}>
            <CardHeader
              actions={{
                actions: lines.length > 0 && (
                  <>
                    {runLog && <Content component="small">Last run · {runLog.level}</Content>}
                    <Button size="sm" variant="link" onClick={() => setExpanded(true)}>
                      Expand log
                    </Button>
                    <CopyTextButton label="processing log" text={text} />
                  </>
                ),
                hasNoOffset: true,
              }}
            >
              <CardTitle>
                <Title headingLevel="h2" size="md" id={`${mappingId}-processing-log-heading`}>
                  Processing log
                </Title>
              </CardTitle>
            </CardHeader>
            <CardBody style={{ flex: '1 1 0', minHeight: 0, overflow: 'auto' }}>
              {lines.length > 0 ? (
                <LogBody lines={lines} />
              ) : (
                <Content component="p" role="status">
                  {running ? 'Running the simulation…' : 'Ready.'}
                </Content>
              )}
            </CardBody>
          </Card>
        </VerticalSplitLayout>
      </StackItem>
      {expanded && (
        <Modal isOpen variant="medium" aria-label="Processing Log" onClose={() => setExpanded(false)}>
          <ModalHeader title="Processing Log" description={runLog ? `Simulation · ${runLog.level}` : 'Compile of the working copy'} />
          <ModalBody>
            <CopyTextButton label="processing log" text={text} />
            <LogBody lines={lines} />
          </ModalBody>
          <ModalFooter>
            <Button onClick={() => setExpanded(false)}>Close</Button>
          </ModalFooter>
        </Modal>
      )}
    </Stack>
  );
}
