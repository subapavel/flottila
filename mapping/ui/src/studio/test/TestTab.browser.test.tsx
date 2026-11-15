import '@patternfly/react-core/dist/styles/base.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient, Diagnostic, TestResult } from '../../api/client';
import { fakeApiClient, type FakeScenario } from '../../api/fake/fake-api';
import { testSourceKey, type BrowserStateStore } from '../browser-state/browser-state';
import { selectUnsaved, type WorkingCopyStore } from '../store/working-copy';
import { readInstance } from '../mock/test-instances';
import { studioStore } from '../shell/Studio';
import { StudioShell } from '../shell/StudioShell';

const MAPPING = 'mappings/order-id-copy';
const INPUT = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-1</OrderId></Order>';
const OUTPUT = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-1</OrderId></Order>';
const TIME = String.raw`\d{2}:\d{2}:\d{2}`;

const unreachable: Diagnostic = {
  severity: 'warning',
  code: 'unreachable-box',
  message: 'the box is not reached from the result',
  location: { kind: 'authoring-id', authoringId: 'a1' },
};
const output: TestResult = {
  kind: 'output',
  output: OUTPUT,
  diagnostics: [unreachable],
  trace: { a1: { kind: 'queue', contexts: [['PO-1']], capped: false, slotCount: 1, contextCount: 1 } },
};

function memoryBrowserState(): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

/** The studio on the Test tab over a store the test holds. */
async function renderTest(options: { api?: ApiClient; editing?: boolean; browserState?: BrowserStateStore } = {}): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab('test');
  if (options.editing ?? true) store.getState().startEditing();
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

const scenario = (fixtures: FakeScenario) => fakeApiClient(undefined, fixtures);
const source = () => page.getByRole('region', { name: 'Source', exact: true });
const result = () => page.getByRole('region', { name: 'Result', exact: true });
const log = () => page.getByRole('region', { name: 'Processing log' });
const runButton = () => page.getByRole('button', { name: 'Run simulation' });

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
  // every test fails on console.error (ADR-0013)
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Test tab (#158)', () => {
  test('Run sends the working copy and the source document; the Result pane shows the output and the log the run', async () => {
    const api = scenario({ test: { [MAPPING]: output } });
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderTest({ api });
    await expect.element(log()).toMatchTextContent(/Ready\./);
    await expect.element(result()).toMatchTextContent('Run the simulation to display the XML result.');

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();

    await expect.element(result()).toMatchTextContent(OUTPUT);
    // only the working copy and the input go to the service: the trace level and parameters are previews (ADR-0028)
    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: INPUT });
    await expect.element(log()).toMatchTextContent(new RegExp(`${TIME}Start of test`));
    await expect.element(log()).toMatchTextContent('Warning unreachable-box: the box is not reached from the result');
    await expect.element(log()).toMatchTextContent('Executed successfully');
    await expect.element(log()).toMatchTextContent(new RegExp(`${TIME}End of test$`));
    expect(store.getState().lastRun).toEqual(output);
  });

  test('a run compile refused shows its diagnostics in the log, and the Result pane that it failed', async () => {
    const refused: TestResult = {
      kind: 'refused',
      diagnostics: [{ severity: 'error', code: 'unconnected-target-field', message: 'the result is not connected', location: { kind: 'authoring-id', authoringId: 't1' } }],
    };
    await renderTest({ api: scenario({ test: { [MAPPING]: refused } }) });

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();

    await expect.element(log()).toMatchTextContent('Error unconnected-target-field: the result is not connected');
    await expect.element(log()).toMatchTextContent('Compile refused the working copy: nothing ran.');
    await expect.element(result()).toMatchTextContent('The simulation failed.');
  });

  test('a runtime error names its code, message and authoring id in the log', async () => {
    const failed: TestResult = { kind: 'error', error: { code: 'po:MINOCCURS', message: 'Row occurs 0 times', authoringId: 't1' }, diagnostics: [], trace: {} };
    await renderTest({ api: scenario({ test: { [MAPPING]: failed } }) });

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();

    await expect.element(log()).toMatchTextContent('Runtime error po:MINOCCURS: Row occurs 0 times (t1)');
    await expect.element(result()).toMatchTextContent('The simulation failed.');
  });

  test('a run the service answers with an error puts its title in the log', async () => {
    // the fake has no corpus case for this input
    await renderTest();

    await source().getByRole('textbox', { name: 'Input XML' }).fill('<Order/>');
    await runButton().click();

    await expect.element(log()).toMatchTextContent('The test could not run: Invalid request');
    await expect.element(result()).toMatchTextContent('The simulation failed.');
  });

  test('the latest compile of the working copy lists its diagnostics in the log, with its time', async () => {
    const missing: Diagnostic = { severity: 'error', code: 'unknown-function', message: 'no function named foo', location: { kind: 'authoring-id', authoringId: 'a1' } };
    const store = await renderTest({ api: scenario({ compile: { [MAPPING]: [missing] } }) });

    store.getState().setDescription('An edit compiles the working copy');

    await expect.element(log()).toMatchTextContent(new RegExp(`${TIME}Compile of the working copy`));
    await expect.element(log()).toMatchTextContent('Error unknown-function: no function named foo');
  });

  test('the result shows as a tree of its fields and values', async () => {
    await renderTest({ api: scenario({ test: { [MAPPING]: output } }) });
    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();
    await expect.element(result()).toMatchTextContent(OUTPUT);

    await result().getByRole('button', { name: 'Tree', exact: true }).click();

    const tree = result().getByRole('table', { name: 'Result structure and values' });
    await expect.element(tree.getByRole('row', { name: /OrderId PO-1/ })).toBeVisible();
  });

  // the owner's rule of #177 replaces #158's "Run needs edit mode": a test runs in read mode too, as in SAP PO
  test('in read mode Run runs the source, edited as a throw-away input: never kept, never unsaved, no leave guard', async () => {
    const api = scenario({ test: { [MAPPING]: output } });
    const runTest = vi.spyOn(api, 'runTest');
    const browserState = memoryBrowserState();
    const store = await renderTest({ api, editing: false, browserState });

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await expect.element(runButton()).toBeEnabled();
    await runButton().click();

    await expect.element(result()).toMatchTextContent(OUTPUT);
    expect(runTest).toHaveBeenCalledExactlyOnceWith(MAPPING, { model: store.getState().workingCopy, input: INPUT });
    expect(browserState.values.has(testSourceKey(MAPPING))).toBe(false);
    expect(selectUnsaved(store.getState())).toBe(false);
    const leaving = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(leaving);
    expect(leaving.defaultPrevented).toBe(false);
    // the parameter values are kept in the browser, so read mode leaves them as they are
    await source().getByRole('button', { name: 'Parameters' }).click();
    await expect.element(source().getByRole('textbox', { name: 'ConversationId' })).toHaveAttribute('readonly');
  });

  test('in edit mode the source is kept in the browser', async () => {
    const browserState = memoryBrowserState();
    await renderTest({ browserState });

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);

    expect(browserState.values.get(testSourceKey(MAPPING))).toBe(INPUT);
  });

  test('the trace level and the parameters are previews', async () => {
    await renderTest();

    const controls = page.getByRole('toolbar', { name: 'Simulation controls' });
    await expect.element(controls.getByRole('combobox', { name: 'Trace Level' })).toHaveValue('Warning');
    await expect.element(controls.getByText('Preview')).toBeVisible();
    await source().getByRole('button', { name: 'Parameters' }).click();
    await expect.element(source().getByRole('table', { name: 'Header Parameters' })).toBeVisible();
    await expect.element(source().getByText('Preview').first()).toBeVisible();
  });

  test('Pretty Print indents the source and, for display only, the result; a text that does not parse says so', async () => {
    await renderTest({ api: scenario({ test: { [MAPPING]: output } }) });
    const input = source().getByRole('textbox', { name: 'Input XML' });
    await input.fill('<?xml version="1.0"?><Order><OrderId>PO-1</OrderId></Order>');

    await source().getByRole('button', { name: 'Pretty Print' }).click();
    await expect.element(input).toHaveValue('<?xml version="1.0"?>\n<Order>\n  <OrderId>PO-1</OrderId>\n</Order>');

    await runButton().click();
    await expect.element(result()).toMatchTextContent(OUTPUT);
    await result().getByRole('button', { name: 'Pretty Print' }).click();
    expect(result().element().querySelector('pre')!.textContent).toBe('<Order xmlns="urn:flottila:corpus:skeleton">\n  <OrderId>PO-1</OrderId>\n</Order>');

    await input.fill('<Order>');
    await source().getByRole('button', { name: 'Pretty Print' }).click();
    await expect.element(source().getByRole('alert')).toMatchTextContent('Cannot process this XML document. The text is not well-formed XML.');
    await source().getByRole('button', { name: 'Tree', exact: true }).click();
    await expect.element(source().getByRole('alert')).not.toBeInTheDocument();
    await expect.element(source().getByRole('status')).toHaveTextContent('The XML cannot be displayed as a tree. Open Text to correct it.');
  });

  test('the tree collapses and expands, by branch or all at once, and shows the full path of the selected row', async () => {
    await renderTest();
    await source().getByRole('textbox', { name: 'Input XML' }).fill('<Order id="7"><Line><Sku>a</Sku></Line><Line><Sku>b</Sku></Line></Order>');
    await source().getByRole('button', { name: 'Tree', exact: true }).click();
    const tree = source().getByRole('table', { name: 'Input structure and values' });
    await expect.element(tree.getByRole('row', { name: /@id 7/ })).toBeVisible();
    await expect.element(tree.getByRole('button', { name: 'Line[1]' })).toBeVisible();
    // nothing selected: the path is the root's
    await source().getByRole('button', { name: 'Show Full Path' }).click();
    await expect.element(source().getByRole('code').last()).toHaveTextContent('/Order');

    // the heading row, then Order, @id, Line, Sku, Line[1], Sku
    await tree.getByRole('button', { name: 'Collapse Line[1]' }).click();
    await expect.poll(() => tree.getByRole('row').length).toBe(6);
    await tree.getByRole('button', { name: 'Expand Line[1]' }).click();
    await expect.poll(() => tree.getByRole('row').length).toBe(7);

    await source().getByRole('button', { name: 'Collapse Tree' }).click();
    await expect.poll(() => tree.getByRole('row').length).toBe(2);
    await source().getByRole('button', { name: 'Expand Tree' }).click();
    await expect.poll(() => tree.getByRole('row').length).toBe(7);

    await tree.getByRole('button', { name: 'Sku' }).last().click();
    await expect.element(source().getByRole('code').last()).toHaveTextContent('/Order/Line[1]/Sku');
  });

  test('Save Document Source downloads the document as it shows, zipped as the prototype does (#170)', async () => {
    const created = vi.spyOn(URL, 'createObjectURL');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      await renderTest();
      await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);

      await source().getByRole('button', { name: 'Save Document Source' }).click();

      expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('schema.zip');
      expect(await readInstance(created.mock.calls[0]![0] as Blob, 'XML')).toBe(INPUT);
      await expect.element(result().getByRole('button', { name: 'Save Document Source' })).toBeDisabled();
    } finally {
      created.mockRestore();
      click.mockRestore();
    }
  });

  test('the parameters of the preview signature and the header values a run would carry stay in the browser', async () => {
    const browserState = memoryBrowserState();
    browserState.write('mapping:mappings/order-id-copy:preview:signature', {
      source: [],
      target: [],
      parameters: [
        { id: 'p1', name: 'Plant', type: 'string', direction: 'Import', description: '' },
        { id: 'p2', name: 'Total', type: 'integer', direction: 'Export', description: '' },
        { id: 'p3', name: ' ', type: 'string', direction: 'Import', description: '' },
      ],
    });
    await renderTest({ browserState });

    await source().getByRole('button', { name: 'Parameters' }).click();
    const imports = source().getByRole('table', { name: 'Import Parameters' });
    await expect.poll(() => imports.getByRole('row').length).toBe(2);
    await imports.getByRole('textbox', { name: 'Plant' }).fill('1000');
    await expect.element(source().getByRole('table', { name: 'Header Parameters' }).getByRole('textbox', { name: 'Profiling' })).toHaveValue('off');
    await result().getByRole('button', { name: 'Parameters' }).click();
    await expect.element(result().getByRole('table', { name: 'Export Parameters' }).getByRole('row', { name: 'Total' })).toBeVisible();

    expect(browserState.read('mapping:mappings/order-id-copy:preview:test')).toEqual({ traceLevel: 'Warning', values: { 'param:p1': '1000' } });
    await source().getByRole('button', { name: 'Document' }).click();
    await expect.element(source().getByRole('textbox', { name: 'Input XML' })).toBeVisible();
  });

  test('without preview parameters the tables say there are none', async () => {
    await renderTest();
    await result().getByRole('button', { name: 'Parameters' }).click();
    await expect.element(result()).toMatchTextContent('No export parameters defined.');
    await source().getByRole('button', { name: 'Parameters' }).click();
    await expect.element(source()).toMatchTextContent('No import parameters defined.');
  });

  test('a JSON message shows as JSON: its source, its result and their trees', async () => {
    const api = scenario({ test: { [MAPPING]: { kind: 'output', output: '{"total":[1,null]}', diagnostics: [], trace: {} } } });
    const store = await renderTest({ api });
    // the signature says the format: a JSON root on both sides
    store.setState((s) => ({ workingCopy: { ...s.workingCopy, source: { ...s.workingCopy.source!, root: { json: '$' } }, target: { ...s.workingCopy.target!, root: { json: '$' } } } }));
    await source().getByRole('textbox', { name: 'Input JSON' }).fill('{"a":{"b":"x"}}');
    await source().getByRole('button', { name: 'Pretty Print' }).click();
    await expect.element(source().getByRole('textbox', { name: 'Input JSON' })).toHaveValue('{\n  "a": {\n    "b": "x"\n  }\n}');
    await runButton().click();
    await expect.element(result()).toMatchTextContent('{"total":[1,null]}');

    await result().getByRole('button', { name: 'Tree', exact: true }).click();
    const tree = result().getByRole('table', { name: 'Result structure and values' });
    await expect.element(tree.getByRole('row', { name: /Result Result \{…\}/ })).toBeVisible();
    await expect.element(tree.getByRole('row', { name: /total total \[2\]/ })).toBeVisible();
    await expect.element(tree.getByRole('row', { name: /^\[1\] null/ })).toBeVisible();
  });

  test('Expand log shows the log in a dialog; the log can be copied', async () => {
    await renderTest({ api: scenario({ test: { [MAPPING]: output } }) });
    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();
    await expect.element(log()).toMatchTextContent('Executed successfully');
    await expect.element(log()).toMatchTextContent('Last run · Warning');

    await log().getByRole('button', { name: 'Expand log' }).click();

    const dialog = page.getByRole('dialog', { name: 'Processing Log' });
    await expect.element(dialog).toMatchTextContent('Simulation · Warning');
    await expect.element(dialog).toMatchTextContent('Executed successfully');
    await expect.element(dialog.getByRole('button', { name: 'Copy processing log to clipboard' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Close', exact: true }).last().click();
    await expect.element(dialog).not.toBeInTheDocument();
  });

  test('a compile alone fills the log and its dialog says so', async () => {
    const store = await renderTest();
    store.getState().setDescription('An edit');
    await expect.element(log()).toMatchTextContent('No diagnostics.');
    await expect.element(log()).not.toMatchTextContent('Last run');

    await log().getByRole('button', { name: 'Expand log' }).click();

    await expect.element(page.getByRole('dialog', { name: 'Processing Log' })).toMatchTextContent('Compile of the working copy');
  });

  test('the views and the split are kept; Escape restores the split; a stale result is marked', async () => {
    const browserState = memoryBrowserState();
    const store = await renderTest({ api: scenario({ test: { [MAPPING]: output } }), browserState });
    await result().getByRole('button', { name: 'Tree', exact: true }).click();
    expect(browserState.read('user:studio:test.result.view')).toBe('Tree');

    await page.getByRole('button', { name: 'Maximize processing log' }).click();
    await expect.element(source()).not.toBeInTheDocument();
    await log().click();
    await userEvent.keyboard('{Escape}');
    await expect.element(source()).toBeVisible();

    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);
    await runButton().click();
    await expect.element(result().getByRole('table', { name: 'Result structure and values' })).toBeVisible();
    await expect.element(result()).not.toMatchTextContent('stale');
    store.getState().setDescription('An edit after the run');
    await expect.element(result()).toMatchTextContent('stale');
  });

  test('the source document stays when the tab is left and opened again', async () => {
    const store = await renderTest();
    await source().getByRole('textbox', { name: 'Input XML' }).fill(INPUT);

    store.getState().setTab('overview');
    await expect.element(source()).not.toBeInTheDocument();
    store.getState().setTab('test');

    await expect.element(source().getByRole('textbox', { name: 'Input XML' })).toHaveValue(INPUT);
  });
});
