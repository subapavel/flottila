import { createStore } from 'zustand/vanilla';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import { perWorkingCopy } from './preview-draft';

/** The follow-up issue of the trace level in test runs (spec #152, preview features). */
export const TRACE_LEVEL_ISSUE = 171;

/** The prototype's trace levels; the engine's trace has no levels yet (#171). */
export const TRACE_LEVELS = ['No trace', 'Warning', 'Info', 'Debug', 'All'] as const;
export type TraceLevel = (typeof TRACE_LEVELS)[number];

/** The prototype's message header parameters of a test run, with their default values (testData.js `headerDefaults`). */
export const HEADER_DEFAULTS: Readonly<Record<string, string>> = Object.fromEntries(
  (
    'ConversationId Interface InterfaceNamespace MessageId ProcessingMode Profiling ReceiverName ReceiverNamespace ReceiverParty ' +
    'ReceiverPartyAgency ReceiverPartyScheme ReceiverService ReceiverSystem RefToMessageId SenderName SenderNamespace SenderParty ' +
    'SenderPartyAgency SenderPartyScheme SenderService SenderSystem TimeSent VersionMajor VersionMinor'
  )
    .split(' ')
    .map((key) => [key, key === 'Profiling' ? 'off' : /^(Sender|Receiver)(Party|Service|System)$/.test(key) ? `Test_${key}` : '']),
);

interface TestPreviewData {
  traceLevel: TraceLevel;
  /** The parameter values of a test run by `param:<id>` or `header:<name>`; a value not set shows its default. */
  values: Record<string, string>;
}

export interface TestPreviewState extends TestPreviewData {
  setTraceLevel: (level: TraceLevel) => void;
  setValue: (id: string, value: string) => void;
}

/** The key of a mapping's test previews in the browser state (ADR-0028: mock state per mapping id). */
export const testPreviewKey = (mappingId: string) => `mapping:${mappingId}:preview:test`;

function isData(value: unknown): value is TestPreviewData {
  const data = value as Partial<TestPreviewData> | null;
  return (
    typeof data === 'object' &&
    data !== null &&
    TRACE_LEVELS.includes(data.traceLevel as TraceLevel) &&
    typeof data.values === 'object' &&
    data.values !== null &&
    Object.values(data.values).every((v) => typeof v === 'string')
  );
}

/**
 * The Test tab's preview features of one mapping (ADR-0028): the trace level (#171) and the parameter values of a test
 * run (#167), read from and written to the browser state under the mapping's id. Nothing here reaches the working copy
 * or the `ApiClient`: a run sends the working copy and the source document only.
 */
export function createTestPreview(browserState: BrowserStateStore, mappingId: string) {
  const key = testPreviewKey(mappingId);
  const kept = browserState.read(key);
  const initial: TestPreviewData = isData(kept) ? kept : { traceLevel: 'Warning', values: {} };
  const store = createStore<TestPreviewState>()((set) => ({
    ...initial,
    setTraceLevel: (traceLevel) => set({ traceLevel }),
    setValue: (id, value) => set((state) => ({ values: { ...state.values, [id]: value } })),
  }));
  store.subscribe(({ traceLevel, values }) => browserState.write(key, { traceLevel, values }));
  return store;
}

export type TestPreviewStore = ReturnType<typeof createTestPreview>;

/** The Test tab's previews of the open mapping, one per working copy store, so that a test instance can set the values. */
export const testPreview = (workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): TestPreviewStore =>
  perWorkingCopy(workingCopy, testPreviewKey(mappingId), () => createTestPreview(browserState, mappingId));
