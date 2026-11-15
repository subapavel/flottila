import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import type { ApiClient, TestResult } from '../../../api/client';
import { fakeApiClient } from '../../../api/fake/fake-api';
import { testSourceKey, type BrowserStateStore } from '../../browser-state/browser-state';
import type { StudioTab, WorkingCopyStore } from '../../store/working-copy';
import { testInstancesKey, type TestInstance } from '../../mock/test-instances';
import { studioStore } from '../../shell/Studio';
import { StudioShell } from '../../shell/StudioShell';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const MAPPING = 'mappings/order-id-copy';
const INPUT = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-1</OrderId></Order>';
const OTHER = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-2</OrderId></Order>';
const DEAD_END = 'No trace: run the simulation on the Test tab.';

/** A run of order-id-copy: the source-field box a1 gave two contexts, the target field mapping t1 two OrderIds. */
const run: TestResult = {
  kind: 'output',
  output: '<Order/>',
  diagnostics: [],
  trace: {
    a1: { kind: 'queue', contexts: [['PO-1', ''], [null]], capped: false, slotCount: 3, contextCount: 2 },
    t1: {
      kind: 'occurrences',
      min: 1,
      max: 1,
      capped: false,
      parents: [
        {
          parentSlot: 1,
          created: 1,
          slots: [
            { slot: 1, status: 'created' },
            { slot: 2, status: 'truncated' },
          ],
        },
      ],
    },
  },
};

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const kept = (id: string, name: string, text: string, lastUsed: number): TestInstance => ({ id, name, format: 'XML', text, values: {}, lastUsed });

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

interface RenderOptions {
  api?: ApiClient;
  editing?: boolean;
  browserState?: BrowserStateStore;
  tab?: StudioTab;
}

/** The studio on the Definition tab (or `tab`) with OrderId's mapping selected, in edit mode unless told, after the run `last` when given. */
async function renderDefinition(last?: TestResult, options: RenderOptions = {}): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab(options.tab ?? 'definition');
  store.getState().selectTarget(ORDER_ID);
  if (options.editing ?? true) store.getState().startEditing();
  if (last) store.getState().recordRun(store.getState().workingCopy, last);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <QueryClientProvider client={queryClient}>
      <StudioShell mappingId={MAPPING} api={api} store={store} browserState={options.browserState ?? memoryBrowserState()} listHref="/" />
    </QueryClientProvider>,
  );
  return store;
}

const canvas = () => page.getByRole('group', { name: 'Mapping canvas' });
const node = (name: RegExp) => canvas().getByRole('group', { name });
const dialog = () => page.getByRole('dialog', { name: 'Display Queue' });
const testFile = () => dialog().getByLabelText('Test file');
const queueRow = (i: number) => dialog().getByRole('table', { name: 'OrderId queue' }).getByRole('row').nth(i);
const runDiagnostics = () => dialog().getByRole('list', { name: 'Run diagnostics' });
const sourceInput = () => page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' });
const processingLog = () => page.getByRole('region', { name: 'Processing log' });
const scenario = (result: TestResult) => fakeApiClient(undefined, { test: { [MAPPING]: result } });

async function openQueue(name: RegExp) {
  await node(name).click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Display Queue', exact: true }).click();
}

beforeEach(async () => {
  await page.viewport(1440, 900);
  history.replaceState(null, '', window.location.pathname);
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  history.replaceState(null, '', window.location.pathname);
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Display Queue of a box (#158)', () => {
  test('shows the queue the last test run traced for the box, by its authoring id, a row per value and context end', async () => {
    await renderDefinition(run);

    await openQueue(/^Source field OrderId/);

    const table = dialog().getByRole('table', { name: 'OrderId queue' });
    await expect.element(table.getByRole('columnheader', { name: 'OrderId:out' })).toBeVisible();
    const rows = table.getByRole('row');
    await expect.element(rows.nth(1)).toHaveTextContent('PO-1');
    await expect.element(rows.nth(2)).toHaveTextContent('""');
    await expect.element(rows.nth(3)).toHaveTextContent('Boundary');
    await expect.element(rows.nth(4)).toHaveTextContent('SUPPRESS');
    await expect.element(rows.nth(5)).toHaveTextContent('End');
    await expect.element(dialog()).toMatchTextContent('2 contexts, 3 slots');
    await expect.element(dialog()).not.toMatchTextContent(/stale/);
  });

  test("the target field's queue shows its result box's queue and its occurrences", async () => {
    await renderDefinition(run);

    await openQueue(/^Target field OrderId/);

    const table = dialog().getByRole('table', { name: 'OrderId queue' });
    await expect.element(table.getByRole('columnheader', { name: /^OrderId:/ })).toBeVisible();
    await expect.element(table.getByRole('row').nth(1)).toHaveTextContent('PO-1');
    await expect.element(dialog()).toMatchTextContent('parent slot 1: 1 created');
    await expect.element(dialog()).toMatchTextContent('slot 2 truncated (maxOccurs)');
  });

  test('after an edit the trace is marked stale until the next run', async () => {
    const store = await renderDefinition(run);
    await openQueue(/^Source field OrderId/);
    await expect.element(dialog().getByRole('table', { name: 'OrderId queue' })).toBeVisible();

    store.getState().setDescription('An edit after the run');

    await expect.element(dialog()).toMatchTextContent('stale');
    await expect.element(dialog()).toMatchTextContent('The working copy changed since this run.');
    await expect.element(dialog().getByRole('table', { name: 'OrderId queue' }).getByRole('row').nth(1)).toHaveTextContent('PO-1');
  });

  test('a function shows a column per input and its output; an untraced one says so, a capped one what it cut', async () => {
    const store = await renderDefinition();
    const mapping = store.getState().workingCopy.targetFieldMappings![ORDER_ID]!;
    // OrderId through toUpperCase into the target: a1 -> f1 -> target
    store.setState((s) => ({
      workingCopy: {
        ...s.workingCopy,
        targetFieldMappings: {
          [ORDER_ID]: { ...mapping, result: 'f1', boxes: { ...mapping.boxes, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] } } },
        },
      },
    }));
    const many = Array.from({ length: 501 }, (_, i) => `v${i}`);
    store.getState().recordRun(store.getState().workingCopy, {
      kind: 'output',
      output: '<Order/>',
      diagnostics: [],
      trace: {
        a1: { kind: 'queue', contexts: [many], capped: true, slotCount: 900, contextCount: 3 },
        t1: {
          kind: 'occurrences',
          min: 1,
          capped: true,
          parents: [
            { parentSlot: 1, created: 0, slots: [] },
            { parentSlot: 4, created: 1, slots: [{ slot: 1, status: 'suppressed' }] },
          ],
        },
      },
    });

    await openQueue(/^Node function toUpperCase/);
    const table = dialog().getByRole('table', { name: 'toUpperCase queue' });
    await expect.element(table.getByRole('columnheader', { name: 'toUpperCase:out' })).toBeVisible();
    await expect.element(table.getByRole('row').nth(1)).toHaveTextContent('v0Not evaluated in the last test run');
    await expect.element(dialog()).toMatchTextContent('capped: showing the first 501 of 900 values, in 1 of 3 contexts');
    await expect.element(dialog()).toMatchTextContent('Showing the first 500 of 502 rows.');
    await expect.poll(() => table.getByRole('row').length).toBe(501);

    await dialog().getByText('Row numbers').click();
    await expect.element(table.getByRole('columnheader', { name: '#' })).toBeVisible();
    await expect.element(table.getByRole('row').nth(1)).toHaveTextContent('0v0Not evaluated in the last test run');
  });

  test("the target's occurrences mark a parent slot below the minimum, slots left out of the trace and a cap", async () => {
    const store = await renderDefinition();
    store.getState().recordRun(store.getState().workingCopy, {
      kind: 'output',
      output: '<Order/>',
      diagnostics: [],
      trace: {
        a1: { kind: 'atomic', type: 'xs:string', value: 'one' },
        t1: {
          kind: 'occurrences',
          min: 1,
          capped: true,
          parents: [
            { parentSlot: 1, created: 0, slots: [] },
            { parentSlot: 4, created: 1, slots: [{ slot: 1, status: 'suppressed' }] },
          ],
        },
      },
    });

    await openQueue(/^Target field OrderId/);

    await expect.element(dialog().getByRole('table', { name: 'OrderId queue' }).getByRole('row').nth(1)).toHaveTextContent('one');
    const slots = dialog().getByRole('list', { name: 'Parent slots' });
    await expect.element(dialog()).toMatchTextContent('Occurrences of OrderId, 1..unbounded');
    await expect.element(slots).toMatchTextContent('parent slot 1: 0 created · po:MINOCCURS, fewer than 1');
    await expect.element(slots).toMatchTextContent('parent slots 2–3 not in the trace');
    await expect.element(slots).toMatchTextContent('slot 1 suppressed (SUPPRESS)');
    await expect.element(dialog()).toMatchTextContent('capped: showing 2 of 4 parent slots');
  });

  test('undo that removes the box whose queue is open closes the dialog', async () => {
    const store = await renderDefinition(run);
    store.getState().insertBox(ORDER_ID, 'toUpperCase', { x: 300, y: 200 });
    await openQueue(/^Node function toUpperCase/);
    await expect.element(dialog().getByRole('table', { name: 'toUpperCase queue' })).toBeVisible();

    store.getState().undo();

    await expect.element(dialog()).not.toBeInTheDocument();
    await expect.element(node(/^Source field OrderId/)).toBeVisible();
  });

  test('the tool rail opens the queue of the selected node', async () => {
    await renderDefinition(run);
    const rail = page.getByRole('toolbar', { name: 'Mapping tools' }).getByRole('button', { name: 'Display Queue', exact: true });
    await expect.element(rail).toBeDisabled();

    await node(/^Source field OrderId/).click();
    await rail.click();

    await expect.element(dialog().getByRole('table', { name: 'OrderId queue' })).toBeVisible();
  });
});

describe('the Display Queue before a test run (#177)', () => {
  test('in edit mode, with no source document and no test instance, it offers an XML test file in place of the message', async () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    await renderDefinition(undefined, { api });

    await openQueue(/^Source field OrderId/);

    await expect.element(testFile()).toHaveAttribute('accept', '.xml');
    await expect.element(dialog().getByRole('button', { name: 'Load test file' })).toBeEnabled();
    await expect.element(dialog()).not.toMatchTextContent(DEAD_END);
    expect(runTest).not.toHaveBeenCalled();
  });

  test('a JSON source message asks for a JSON test file', async () => {
    const store = await renderDefinition();
    store.setState((s) => ({ workingCopy: { ...s.workingCopy, source: { ...s.workingCopy.source!, root: { json: '$' } } } }));

    await openQueue(/^Source field OrderId/);

    await expect.element(testFile()).toHaveAttribute('accept', '.json');
  });

  test("a chosen file becomes the Test tab's source, runs on the working copy, and the box's queue shows in the dialog", async () => {
    const api = scenario(run);
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderDefinition(undefined, { api });
    await openQueue(/^Source field OrderId/);

    await testFile().upload(new File([INPUT], 'order.xml', { type: 'application/xml' }));

    await expect.element(queueRow(1)).toHaveTextContent('PO-1');
    await expect.element(dialog()).toMatchTextContent('Traced queue · last test run');
    await expect.element(runDiagnostics()).not.toBeInTheDocument();
    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: INPUT });
    store.getState().setTab('test');
    await expect.element(sourceInput()).toHaveValue(INPUT);
    await expect.element(processingLog()).toMatchTextContent('Executed successfully');
  });

  test('with a source document it runs at once, in read mode too, and shows the queue without an upload', async () => {
    const api = scenario(run);
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderDefinition(undefined, { api, editing: false, browserState: memoryBrowserState({ [testSourceKey(MAPPING)]: INPUT }) });

    await openQueue(/^Source field OrderId/);

    await expect.element(queueRow(1)).toHaveTextContent('PO-1');
    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: INPUT });
    await expect.element(testFile()).not.toBeInTheDocument();
  });

  test('with an empty source the latest test instance is loaded into the source and run', async () => {
    const api = scenario(run);
    const runTest = vi.spyOn(api, 'runTest');
    const browserState = memoryBrowserState({ [testInstancesKey(MAPPING)]: [kept('a', 'First', OTHER, 1), kept('b', 'Second', INPUT, 2)] });
    const store = await renderDefinition(undefined, { api, browserState, tab: 'test' });
    // the studio opened with the latest instance in the source; the author cleared it
    await expect.element(sourceInput()).toHaveValue(INPUT);
    await sourceInput().fill('');
    store.getState().setTab('definition');

    await openQueue(/^Source field OrderId/);

    await expect.element(queueRow(1)).toHaveTextContent('PO-1');
    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: INPUT });
    store.getState().setTab('test');
    await expect.element(sourceInput()).toHaveValue(INPUT);
  });

  test('in read mode, with no data, it says a test document can be loaded in edit mode, and offers no upload', async () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    await renderDefinition(undefined, { api, editing: false });

    await openQueue(/^Source field OrderId/);

    await expect.element(dialog()).toMatchTextContent('No test data yet. A test document can be loaded in edit mode.');
    await expect.element(testFile()).not.toBeInTheDocument();
    await expect.element(dialog().getByRole('button', { name: 'Load test file' })).not.toBeInTheDocument();
    expect(runTest).not.toHaveBeenCalled();
  });

  test('a file that does not parse says why in the dialog and in the processing log; the dialog stays open', async () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderDefinition(undefined, { api });
    await openQueue(/^Source field OrderId/);

    await testFile().upload(new File(['<broken'], 'broken.xml', { type: 'application/xml' }));

    const why = 'The test could not run: broken.xml is not a valid XML test input: The text is not well-formed XML.';
    await expect.element(dialog().getByRole('heading', { name: /The simulation failed/ })).toBeVisible();
    await expect.element(runDiagnostics()).toHaveTextContent(why);
    await expect.element(testFile()).toBeInTheDocument();
    expect(runTest).not.toHaveBeenCalled();
    store.getState().setTab('test');
    await expect.element(processingLog()).toMatchTextContent(why);
    await expect.element(sourceInput()).toHaveValue('');
  });

  test('a run the compile refuses shows its diagnostics in the dialog and in the processing log; the dialog stays open', async () => {
    const refused: TestResult = {
      kind: 'refused',
      diagnostics: [{ severity: 'error', code: 'unconnected-target-field', message: 'the result is not connected', location: { kind: 'authoring-id', authoringId: 't1' } }],
    };
    const store = await renderDefinition(undefined, { api: scenario(refused) });
    await openQueue(/^Source field OrderId/);

    await testFile().upload(new File([INPUT], 'order.xml', { type: 'application/xml' }));

    await expect.element(dialog().getByRole('heading', { name: /The simulation failed/ })).toBeVisible();
    await expect.element(runDiagnostics()).toMatchTextContent('Error unconnected-target-field: the result is not connected');
    await expect.element(runDiagnostics()).toMatchTextContent('Compile refused the working copy: nothing ran.');
    await expect.element(dialog().getByRole('table')).not.toBeInTheDocument();
    store.getState().setTab('test');
    await expect.element(processingLog()).toMatchTextContent('Error unconnected-target-field: the result is not connected');
  });

  test('a run that cannot reach the service says so in the dialog, in read mode too', async () => {
    // the fake has no corpus case for this input
    await renderDefinition(undefined, { editing: false, browserState: memoryBrowserState({ [testSourceKey(MAPPING)]: '<Order/>' }) });

    await openQueue(/^Source field OrderId/);

    await expect.element(runDiagnostics()).toHaveTextContent('The test could not run: Invalid request');
    await expect.element(dialog()).toBeVisible();
    // there is test data: neither the upload nor the read-mode message shows
    await expect.element(dialog()).not.toMatchTextContent('No test data yet');
  });

  test('while the run is under way the dialog says so, and an earlier failure hides until it ends', async () => {
    const api = scenario(run);
    let answer: (result: TestResult) => void = () => undefined;
    vi.spyOn(api, 'runTest').mockImplementation(() => new Promise((resolve) => (answer = resolve)));
    await renderDefinition(undefined, { api });
    await openQueue(/^Source field OrderId/);
    await testFile().upload(new File(['<broken'], 'broken.xml', { type: 'application/xml' }));
    await expect.element(runDiagnostics()).toBeVisible();

    await testFile().upload(new File([INPUT], 'order.xml', { type: 'application/xml' }));

    await expect.element(dialog().getByRole('status')).toHaveTextContent('Running the simulation…');
    await expect.element(runDiagnostics()).not.toBeInTheDocument();
    answer(run);
    await expect.element(queueRow(1)).toHaveTextContent('PO-1');
  });

  test('a stale trace still shows, marked, and no run starts', async () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderDefinition(run, { api, browserState: memoryBrowserState({ [testSourceKey(MAPPING)]: INPUT }) });
    store.getState().setDescription('An edit after the run');

    await openQueue(/^Source field OrderId/);

    await expect.element(dialog()).toMatchTextContent('The working copy changed since this run.');
    await expect.element(queueRow(1)).toHaveTextContent('PO-1');
    expect(runTest).not.toHaveBeenCalled();
  });
});
