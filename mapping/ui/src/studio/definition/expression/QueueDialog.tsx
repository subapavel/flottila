import { Alert, Button, Label, Modal, ModalBody, ModalHeader, Switch } from '@patternfly/react-core';
import { FileImportIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import type { TraceEntry } from '../../../api/client';
import type { BrowserStateStore } from '../../browser-state/browser-state';
import { currentInstance, type TestInstancesStore } from '../../mock/test-instances';
import { parseDocument, type DocumentFormat } from '../../shared/documents';
import type { TestSession } from '../../shared/test-session';
import { displayEntry, type ShownValue } from './display-queue';
import { TARGET, type ExpressionGraph, type ExpressionNode } from './expression-graph';
import { readPanelPreference, savePanelPreference } from '../../shared/panel-preferences';
import { queueColumns, type QueueCell } from './queue-columns';
import './queue.css';

/** The most rows the dialog shows, as in the prototype. */
const MAX_ROWS = 500;
const NUMBERS_KEY = 'definition.queue.numbers';
const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean';
const STATUS = { created: 'created', suppressed: 'suppressed (SUPPRESS)', truncated: 'truncated (maxOccurs)' } as const;

export interface QueueDialogProps {
  graph: ExpressionGraph;
  /** The node whose queue shows, a box or the target field, as the graph has it now. */
  node: ExpressionNode;
  /** The last test run's trace by authoring id; none before a run. */
  trace: Readonly<Record<string, TraceEntry>> | undefined;
  /** Whether the trace is a stale trace (GLOSSARY): it still shows, marked. */
  stale: boolean;
  /** The target field mapping's authoring id, under which the trace keeps the target field's occurrences. */
  mappingId: string | undefined;
  browserState: BrowserStateStore;
  /** What a dialog without a trace runs (#177). */
  test: QueueTest;
  onClose: () => void;
}

/** The test run of a Display Queue without a trace (#177): the open mapping's test session and what it runs on. */
export interface QueueTest {
  session: TestSession;
  /** The test instances: the latest of the source's format is the data of an empty source. */
  library: TestInstancesStore;
  /** The source message's format, which a test file must have. */
  format: DocumentFormat;
  /** The trace level the run shows in the log (a preview, never sent). */
  level: string;
  /** Whether the studio is in edit mode: only then is a test file offered. */
  editing: boolean;
}

/**
 * The body of a dialog without a trace (#177): while a run is under way, that it runs; else, without a source document,
 * a test file to load in edit mode or, in read mode, where one is loaded; else nothing (`RunFailure` shows a failed run).
 */
function NoTrace({ test: { session, format, level, editing } }: { test: QueueTest }) {
  // an empty source with a test instance takes the latest when the dialog opens (useRunWithoutTrace)
  const empty = useStore(session, (s) => s.source === '');
  const running = useStore(session, (s) => s.running);
  const file = useRef<HTMLInputElement>(null);

  const load = async (event: ChangeEvent<HTMLInputElement>) => {
    // the input is cleared after each read, so a change always brings a file
    const chosen = event.target.files![0]!;
    event.target.value = '';
    let text: string;
    try {
      text = await chosen.text();
      parseDocument(text, format);
    } catch (error) {
      session.getState().failRun(level, `${chosen.name} is not a valid ${format} test input: ${(error as Error).message}`);
      return;
    }
    session.getState().setSource(text);
    await session.getState().runTest(level);
  };

  if (running) return <p role="status" className="queue-note">Running the simulation…</p>;
  if (!empty) return null;
  if (!editing) return <p className="queue-note">No test data yet. A test document can be loaded in edit mode.</p>;
  return (
    <div className="queue-load">
      <p className="queue-note">No test data yet. Load a test file: the simulation runs on it and the queue shows here.</p>
      <Button variant="secondary" icon={<FileImportIcon />} onClick={() => file.current?.click()}>
        Load test file
      </Button>
      <input ref={file} type="file" hidden aria-label="Test file" accept={`.${format.toLowerCase()}`} onChange={(e) => void load(e)} />
    </div>
  );
}

/** The diagnostics of the last run when it failed, was refused or did not start (#177): its lines between Start and End. */
function RunFailure({ session }: { session: TestSession }) {
  // a failed run always has its log section; a run under way is not failed yet
  const failure = useStore(session, (s) => (s.runFailed && !s.running ? s.runLog : null));
  if (failure === null) return null;
  return (
    <Alert variant="danger" isInline title="The simulation failed" className="queue-failure">
      <ul aria-label="Run diagnostics">
        {failure.lines
          .filter((line) => line.time === undefined)
          .map((line, i) => (
            <li key={i}>{line.message}</li>
          ))}
      </ul>
    </Alert>
  );
}

/**
 * Runs the test once when the dialog opens without a trace and with test data (#177): the source document, else the
 * latest test instance, loaded into the source first. In read mode too: a test runs there, as in SAP PO.
 */
function useRunWithoutTrace(traced: boolean, { session, library, format, level }: QueueTest) {
  useEffect(() => {
    if (traced) return;
    if (session.getState().source === '') {
      const latest = currentInstance(library.getState().value, format);
      if (latest === undefined) return;
      session.getState().loadInstance(latest);
    }
    void session.getState().runTest(level);
  }, [traced, session, library, format, level]);
}

function Value({ value }: { value: ShownValue }) {
  switch (value.kind) {
    case 'value':
      return <code>{value.text}</code>;
    case 'empty':
      return <code>""</code>;
    case 'suppress':
      return (
        <Label isCompact color="grey">
          SUPPRESS
        </Label>
      );
  }
}

function Cell({ cell }: { cell: QueueCell }) {
  if (cell.kind === 'value') return <Value value={cell.value} />;
  return <small>{cell.kind === 'end' ? 'End' : 'Boundary'}</small>;
}

/**
 * The Display Queue of a node (the prototype's Queue dialog, GLOSSARY: Display queue): the queues the last test run traced
 * for its inputs and output, read by authoring id, a row per value and per context's end; the target field adds its
 * occurrences per parent slot. After an edit the trace is a stale trace: it still shows, marked, until the next run.
 * Without a trace it runs the test on the test data, or asks for a test file in edit mode (#177, as SAP Integration
 * Suite does); a failed run shows its diagnostics here as in the processing log.
 */
export function QueueDialog({ graph, node, trace, stale, mappingId, browserState, test, onClose }: QueueDialogProps) {
  useRunWithoutTrace(trace !== undefined, test);
  const [numbers, setNumbers] = useState(() => readPanelPreference(browserState, NUMBERS_KEY, false, isBoolean));
  const { name, columns } = queueColumns(graph, node, trace ?? {});
  const count = Math.max(0, ...columns.map((c) => c.cells?.length ?? 0));
  const occurrences = node.id === TARGET && mappingId !== undefined ? trace?.[mappingId] : undefined;
  const shownOccurrences = occurrences === undefined ? undefined : displayEntry(occurrences);
  const changeNumbers = (checked: boolean) => {
    setNumbers(checked);
    savePanelPreference(browserState, NUMBERS_KEY, checked);
  };

  return (
    <Modal isOpen onClose={onClose} variant="medium" aria-label="Display Queue">
      <ModalHeader title="Display Queue" description={trace === undefined ? 'No test run yet' : 'Traced queue · last test run'} />
      <ModalBody>
        <div className="queue-bar">
          <strong>
            <i>ƒ</i> {name}
          </strong>
          <Switch id="queue-row-numbers" label="Row numbers" isChecked={numbers} onChange={(_, checked) => changeNumbers(checked)} />
        </div>
        <RunFailure session={test.session} />
        {trace === undefined ? (
          <NoTrace test={test} />
        ) : (
          <>
            {stale && (
              <p className="queue-note">
                <Label color="orange" isCompact>
                  stale
                </Label>{' '}
                The working copy changed since this run.
              </p>
            )}
            <div className="queue-scroll">
              <Table aria-label={`${name} queue`} role="table" variant="compact" gridBreakPoint="" className="queue-table">
                <Thead>
                  <Tr>
                    {numbers && <Th>#</Th>}
                    {columns.map((c) => (
                      <Th key={c.label}>{c.label}</Th>
                    ))}
                  </Tr>
                </Thead>
                <Tbody>
                  {Array.from({ length: Math.min(MAX_ROWS, Math.max(1, count)) }, (_, i) => (
                    <Tr key={i}>
                      {numbers && <Td>{i}</Td>}
                      {columns.map((c) => {
                        const cell = c.cells?.[i];
                        return (
                          <Td key={c.label} className={cell && cell.kind !== 'value' ? `q-${cell.kind}` : undefined}>
                            {cell ? <Cell cell={cell} /> : c.cells === undefined && i === 0 ? <em>Not evaluated in the last test run</em> : null}
                          </Td>
                        );
                      })}
                    </Tr>
                  ))}
                </Tbody>
              </Table>
            </div>
            {columns
              .filter((c) => c.size !== undefined)
              .map((c) => (
                <p key={c.label} className="queue-note">
                  {c.label} · {c.size}
                  {c.capped && ` · capped: ${c.capped}`}
                </p>
              ))}
            {count > MAX_ROWS && (
              <p className="queue-note">
                Showing the first {MAX_ROWS} of {count} rows.
              </p>
            )}
            {shownOccurrences?.kind === 'occurrences' && (
              <section className="queue-occurrences" aria-label="Occurrences">
                <h4>
                  Occurrences of {name}, {shownOccurrences.min}..{shownOccurrences.max ?? 'unbounded'}
                </h4>
                {shownOccurrences.capped && <p className="queue-note">capped: {shownOccurrences.capped}</p>}
                <ol aria-label="Parent slots">
                  {shownOccurrences.rows.map((row) =>
                    row.kind === 'left-out' ? (
                      <li key={`${row.from}-${row.to}`}>
                        <em>
                          parent slots {row.from}–{row.to} not in the trace
                        </em>
                      </li>
                    ) : (
                      <li key={row.parentSlot}>
                        parent slot {row.parentSlot}: {row.created} created
                        {row.minOccurs && ` · po:MINOCCURS, fewer than ${shownOccurrences.min}`}
                        <ul>
                          {row.slots.map(({ slot, status }) => (
                            <li key={slot}>
                              slot {slot} {STATUS[status]}
                            </li>
                          ))}
                        </ul>
                      </li>
                    ),
                  )}
                </ol>
              </section>
            )}
          </>
        )}
      </ModalBody>
    </Modal>
  );
}
