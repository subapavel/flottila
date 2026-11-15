import { describe, expect, test, vi } from 'vitest';
import type { ApiClient, Diagnostic, StoredMapping, TestRequest, TestResult } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { testSourceKey, type BrowserStateStore } from '../browser-state/browser-state';
import { createWorkingCopyStore } from '../store/working-copy';
import { compileLines, createTestSession, followCompiles, logLines, logText, runLines, stamp } from './test-session';

const MAPPING = 'mappings/order-id-copy';
const at = (seconds: number) => new Date(2026, 10, 10, 13, 5, seconds);
const warning: Diagnostic = { severity: 'warning', code: 'w', message: 'a warning', location: { kind: 'authoring-id', authoringId: 'a1' } };
const error: Diagnostic = { ...warning, severity: 'error', code: 'e', message: 'an error' };

/** The store of an open mapping, its ports wired to `api` as the studio wires them. */
const storeOf = (api: ApiClient, opened: StoredMapping) =>
  createWorkingCopyStore(opened, {
    newId: () => 'n1',
    save: (model, etag) => api.saveMapping(MAPPING, model, etag),
    load: () => api.getMapping(MAPPING),
    compile: (model, signal) => api.compile(MAPPING, model, signal).then((compiled) => compiled.diagnostics),
  });

/** A session's run port wired to `api`, as `useTestSession`'s mutation wires it. */
const runOf = (api: ApiClient) => (request: TestRequest) => api.runTest(MAPPING, request);

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

describe('the processing log lines', () => {
  test('a time is hours, minutes and seconds on a 24-hour clock', () => {
    expect(stamp(at(9))).toBe('13:05:09');
  });

  test("a compile's section lists its diagnostics, none, or the error it failed with", () => {
    expect(compileLines(at(1), [error, warning], null)).toEqual([
      { time: '13:05:01', message: 'Compile of the working copy' },
      { message: 'Error e: an error', detail: true },
      { message: 'Warning w: a warning', detail: true },
    ]);
    expect(compileLines(at(1), [], null)[1]).toEqual({ message: 'No diagnostics.', detail: true });
    expect(compileLines(at(1), [error], 'Service unavailable')).toEqual([
      { time: '13:05:01', message: 'Compile of the working copy' },
      { message: 'The mapping could not be compiled: Service unavailable', detail: true },
    ]);
  });

  test("a run's section opens and closes with its times, around its diagnostics and outcome", () => {
    const output: TestResult = { kind: 'output', output: '<a/>', diagnostics: [warning], trace: {} };
    expect(runLines(at(2), at(3), { result: output })).toEqual([
      { time: '13:05:02', message: 'Start of test' },
      { message: 'Warning w: a warning', detail: true },
      { message: 'Executed successfully' },
      { time: '13:05:03', message: 'End of test' },
    ]);
    expect(runLines(at(2), at(3), { result: { kind: 'refused', diagnostics: [] } })[1]).toEqual({ message: 'Compile refused the working copy: nothing ran.' });
    const located: TestResult = { kind: 'error', error: { code: 'po:X', message: 'stopped', authoringId: 'b2' }, diagnostics: [], trace: {} };
    expect(runLines(at(2), at(3), { result: located })[1]).toEqual({ message: 'Runtime error po:X: stopped (b2)' });
    const unlocated: TestResult = { kind: 'error', error: { code: 'po:X', message: 'stopped' }, diagnostics: [], trace: {} };
    expect(runLines(at(2), at(3), { result: unlocated })[1]).toEqual({ message: 'Runtime error po:X: stopped' });
    expect(runLines(at(2), at(3), { failure: 'Invalid request' })[1]).toEqual({ message: 'The test could not run: Invalid request' });
  });

  test('the sections show in the order they began, and as text a detail is indented', () => {
    const run = { at: 2, lines: [{ time: '13:05:02', message: 'Start of test' }] };
    const compile = { at: 1, lines: [{ time: '13:05:01', message: 'Compile of the working copy' }, { message: 'Error e: an error', detail: true }] };
    const lines = logLines(run, null, compile);
    expect(lines.map((l) => l.message)).toEqual(['Compile of the working copy', 'Error e: an error', 'Start of test']);
    expect(logText(lines)).toBe('13:05:01 Compile of the working copy\n  Error e: an error\n13:05:02 Start of test');
  });
});

describe('the test session', () => {
  test('the source document is read from and, in edit mode, kept in the browser per mapping; anything but text starts empty', () => {
    const api = fakeApiClient();
    const browserState = memoryBrowserState({ [testSourceKey(MAPPING)]: '<kept/>' });
    const store = storeOf(api, { model: { formatVersion: 1 }, etag: '"e"' });
    store.getState().startEditing();
    const session = createTestSession(store, runOf(api), MAPPING, browserState);
    expect(session.getState().source).toBe('<kept/>');

    session.getState().setSource('<typed/>');

    expect(browserState.values.get(testSourceKey(MAPPING))).toBe('<typed/>');
    const other = memoryBrowserState({ [testSourceKey(MAPPING)]: 42 });
    expect(createTestSession(storeOf(api, { model: { formatVersion: 1 }, etag: '"e"' }), runOf(api), MAPPING, other).getState().source).toBe('');
  });

  test('in read mode the source is a throw-away input: the browser keeps none of it (#177)', () => {
    const api = fakeApiClient();
    const browserState = memoryBrowserState({ [testSourceKey(MAPPING)]: '<kept/>' });
    const session = createTestSession(storeOf(api, { model: { formatVersion: 1 }, etag: '"e"' }), runOf(api), MAPPING, browserState);

    session.getState().setSource('<typed/>');

    expect(session.getState().source).toBe('<typed/>');
    expect(browserState.values.get(testSourceKey(MAPPING))).toBe('<kept/>');
  });

  test('a run that cannot start is logged as a failed run with its reason (#177)', () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    const session = createTestSession(storeOf(api, { model: { formatVersion: 1 }, etag: '"e"' }), runOf(api), MAPPING, memoryBrowserState(), () => at(5));

    session.getState().failRun('Info', 'a.xml is not a valid XML test input');

    expect(session.getState()).toMatchObject({ running: false, runFailed: true, runLog: { at: at(5).getTime(), level: 'Info', lines: runLines(at(5), at(5), { failure: 'a.xml is not a valid XML test input' }) } });
    expect(runTest).not.toHaveBeenCalled();
  });

  test('a run sends only the working copy and the source, keeps the answer in the store and logs it; a second run waits', async () => {
    const result: TestResult = { kind: 'output', output: '<a/>', diagnostics: [], trace: {} };
    const api = fakeApiClient(undefined, { test: { [MAPPING]: result } });
    const runTest = vi.spyOn(api, 'runTest');
    const store = storeOf(api, await api.getMapping(MAPPING));
    const session = createTestSession(store, runOf(api), MAPPING, memoryBrowserState(), () => at(4));
    session.getState().setSource('<in/>');

    const first = session.getState().runTest('Debug');
    expect(session.getState().running).toBe(true);
    await session.getState().runTest('Debug');
    await first;

    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: '<in/>' });
    expect(store.getState().lastRun).toEqual(result);
    expect(session.getState()).toMatchObject({ running: false, runFailed: false, runLog: { at: at(4).getTime(), level: 'Debug' } });
  });

  test('a run that ends without output is failed: refused, a runtime error, or a failed call', async () => {
    const refused = fakeApiClient(undefined, { test: { [MAPPING]: { kind: 'refused', diagnostics: [error] } } });
    const store = storeOf(refused, await refused.getMapping(MAPPING));
    const session = createTestSession(store, runOf(refused), MAPPING, memoryBrowserState());
    await session.getState().runTest('Warning');
    expect(session.getState().runFailed).toBe(true);

    // the fake knows no case for this input: the call fails
    const failing = fakeApiClient();
    const other = createTestSession(storeOf(failing, await failing.getMapping(MAPPING)), runOf(failing), MAPPING, memoryBrowserState());
    other.getState().setSource('<unknown/>');
    await other.getState().runTest('Warning');
    expect(other.getState().runFailed).toBe(true);
    expect(other.getState().runLog!.lines[1]).toEqual({ message: 'The test could not run: Invalid request' });
  });

  test('each compile that answers becomes the compile section, stamped when it answered; other changes leave it', async () => {
    vi.useFakeTimers();
    try {
      const api = fakeApiClient(undefined, { compile: { [MAPPING]: [error] } });
      const store = storeOf(api, await api.getMapping(MAPPING));
      const session = createTestSession(store, runOf(api), MAPPING, memoryBrowserState());
      const stop = followCompiles(session, store, () => at(7));

      store.getState().select('a1');
      expect(session.getState().compileLog).toBeNull();
      store.getState().setDescription('changed');
      await vi.advanceTimersByTimeAsync(600);

      expect(session.getState().compileLog).toEqual({ at: at(7).getTime(), lines: compileLines(at(7), [error], null) });
      stop();
    } finally {
      vi.useRealTimers();
    }
  });
});
