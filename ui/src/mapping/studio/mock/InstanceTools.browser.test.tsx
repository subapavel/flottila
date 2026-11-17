import '@patternfly/react-core/dist/styles/base.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { strToU8, zipSync } from 'fflate';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import type { ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { testSourceKey, type BrowserStateStore } from '../browser-state/browser-state';
import { selectUnsaved, type WorkingCopyStore } from '../store/working-copy';
import { testInput, testInstances, testInstancesKey, type TestInstance } from './test-instances';
import { studioStore } from '../Studio';
import { StudioFrame } from '../StudioFrame';

const MAPPING = 'mappings/order-id-copy';
const KEY = testInstancesKey(MAPPING);
const INPUT = '<Order xmlns="urn:rail8:corpus:skeleton"><OrderId>PO-1</OrderId></Order>';
const OTHER = '<Order xmlns="urn:rail8:corpus:skeleton"><OrderId>PO-2</OrderId></Order>';

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const kept = (id: string, name: string, text: string, lastUsed: number): TestInstance => ({ id, name, format: 'XML', text, values: {}, lastUsed });

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

const ORDER_ID = '/Q{urn:rail8:corpus:skeleton}Order/OrderId';

/** The studio on the Definition tab with OrderId's mapping selected, in edit mode unless told. */
async function renderStudio(options: { api?: ApiClient; editing?: boolean; browserState?: BrowserStateStore } = {}): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab('definition');
  store.getState().selectTarget(ORDER_ID);
  if (options.editing ?? true) store.getState().startEditing();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <QueryClientProvider client={queryClient}>
      <StudioFrame mappingId={MAPPING} api={api} store={store} browserState={options.browserState ?? memoryBrowserState()} />
    </QueryClientProvider>,
  );
  return store;
}

const bar = () => page.getByRole('group', { name: 'Queue test input' });
const tool = (name: string) => bar().getByRole('button', { name, exact: true });
const select = () => bar().getByRole('combobox', { name: 'Queue test input' });
const file = () => bar().getByLabelText('Test instance file');
const studioButton = (name: string) => page.getByRole('button', { name, exact: true });
/** The source document the test session keeps in the browser in edit mode. */
const keptSource = (browserState: { values: Map<string, unknown> }) => browserState.values.get(testSourceKey(MAPPING));
const xml = (text: string, name = 'order.xml') => new File([text], name, { type: 'application/xml' });

async function addInstance(name: string) {
  await tool('Add Test Instance').click();
  const dialog = page.getByRole('dialog', { name: 'Add Test Instance' });
  await dialog.getByRole('textbox', { name: 'Test instance name' }).fill(name);
  await dialog.getByRole('button', { name: 'OK' }).click();
}

/** Opens the Display Queue of OrderId's source-field box: with no trace yet it runs the test session's source. */
async function runFromQueue() {
  await page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name: /^Source field OrderId/ }).click({ button: 'right' });
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

describe('the test instance library, a preview feature', () => {
  test('carries the Preview badge; Add Test Instance keeps the source as a named instance, a draft that Save keeps in the browser', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.spyOn(fake, 'saveMapping');
    const compile = vi.spyOn(fake, 'compile');
    const runTest = vi.spyOn(fake, 'runTest');
    const browserState = memoryBrowserState();
    await renderStudio({ api: fake, browserState });
    await bar().getByText('Preview').hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('Test instances: a preview kept in this browser only, never in the mapping file (#170).');
    await bar().getByText('Preview').unhover();
    await expect.element(page.getByRole('tooltip')).not.toBeInTheDocument();
    await expect.element(tool('Save Test Instance')).toBeDisabled();
    await file().upload(xml(INPUT));
    await expect.poll(() => keptSource(browserState)).toBe(INPUT);

    await addInstance('Order PO-1');

    await expect.element(select()).toHaveDisplayValue('Order PO-1');
    await expect.element(studioButton('Save')).toBeEnabled();
    expect(browserState.values.has(KEY)).toBe(false);
    await runFromQueue();
    await expect.poll(() => runTest.mock.calls.length).toBe(1);
    await page.getByRole('dialog', { name: 'Display Queue' }).getByRole('button', { name: 'Close' }).first().click();
    await studioButton('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(browserState.values.get(KEY)).toMatchObject([{ name: 'Order PO-1', format: 'XML', text: INPUT }]);
    await expect.poll(() => compile.mock.calls.length).toBeGreaterThan(0);
    // the library never reaches the service: Save, compile and test requests carry no trace of it
    expect(JSON.stringify([saveMapping.mock.calls, compile.mock.calls.map(([, model]) => model), runTest.mock.calls.map(([, request]) => request.model)])).not.toMatch(
      /PO-1"|Order PO-1|instance/,
    );
  });

  test('a name the mapping has already is refused in the dialog', async () => {
    await renderStudio({ browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1)] }) });

    await tool('Add Test Instance').click();
    const dialog = page.getByRole('dialog', { name: 'Add Test Instance' });
    await expect.element(dialog.getByRole('button', { name: 'OK' })).toBeDisabled();
    await dialog.getByRole('textbox', { name: 'Test instance name' }).fill('First');
    await dialog.getByRole('button', { name: 'OK' }).click();

    await expect.element(dialog.getByRole('alert')).toHaveTextContent('Danger alert:This name already exists.');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect.element(studioButton('Save')).toBeDisabled();
  });

  test('selecting an instance loads its document; Save Test Instance writes the source over it; Delete asks and removes it', async () => {
    const browserState = memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] });
    await renderStudio({ browserState });
    await expect.element(select()).toHaveDisplayValue('Second');
    await expect.poll(() => keptSource(browserState)).toBe(OTHER);

    await select().selectOptions('First');
    await expect.poll(() => keptSource(browserState)).toBe(INPUT);
    await file().upload(xml(`${INPUT} `));
    await expect.poll(() => keptSource(browserState)).toBe(`${INPUT} `);
    await tool('Save Test Instance').click();
    await expect.element(bar().getByRole('status')).toHaveTextContent('Test instance saved. Save the mapping to keep it in this browser.');

    await select().selectOptions('Second');
    await expect.poll(() => keptSource(browserState)).toBe(OTHER);
    await select().selectOptions('First');
    await expect.poll(() => keptSource(browserState)).toBe(`${INPUT} `);

    await tool('Delete Test Instance').click();
    const dialog = page.getByRole('dialog', { name: 'Delete Test Instance' });
    await expect.element(dialog).toMatchTextContent('Delete First from this browser?');
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect.element(select()).toHaveDisplayValue('Second');
    await expect.element(select().getByRole('option')).toHaveLength(1);
    await expect.poll(() => keptSource(browserState)).toBe(OTHER);
  });

  test('Discard reverts the library to the saved instances', async () => {
    await renderStudio({ browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1)] }) });
    await addInstance('Second');
    await expect.element(select().getByRole('option')).toHaveLength(2);

    await studioButton('Cancel').click();
    await page.getByRole('dialog', { name: 'Discard changes?' }).getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(select().getByRole('option')).toHaveLength(1);
    await expect.element(select()).toHaveDisplayValue('First');
  });

  test('Load Test Instance reads a file or the instance.xml of a zip into the source; a zip without it says so', async () => {
    const browserState = memoryBrowserState();
    await renderStudio({ browserState });

    await file().upload(new File([zipSync({ 'instance.xml': strToU8(INPUT) })], 'schema.zip', { type: 'application/zip' }));
    await expect.poll(() => keptSource(browserState)).toBe(INPUT);
    await file().upload(xml(OTHER, 'other.xml'));
    await expect.poll(() => keptSource(browserState)).toBe(OTHER);
    await file().upload(new File([zipSync({ 'readme.txt': strToU8('x') })], 'other.zip', { type: 'application/zip' }));

    await expect.element(bar().getByRole('status')).toHaveTextContent('Could not read this file. Choose a valid XML test input.');
    expect(keptSource(browserState)).toBe(OTHER);
    await expect.element(studioButton('Save')).toBeDisabled();
  });

  // in read mode the select stays enabled, so an instance can be chosen for a run
  test('in read mode Load, Add, Save and Delete stay disabled; the select loads the latest instance, or the one chosen, and changes nothing', async () => {
    const library = [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)];
    const browserState = memoryBrowserState({ [KEY]: library });
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderStudio({ api, editing: false, browserState });

    for (const name of ['Load Test Instance', 'Add Test Instance', 'Save Test Instance', 'Delete Test Instance']) await expect.element(tool(name)).toBeDisabled();
    await expect.element(select()).toBeEnabled();
    await expect.element(select()).toHaveDisplayValue('Second');

    await select().selectOptions('First');

    await expect.element(select()).toHaveDisplayValue('First');
    expect(selectUnsaved(store.getState())).toBe(false);
    expect(browserState.values.get(KEY)).toEqual(library);
    expect(browserState.values.has(testSourceKey(MAPPING))).toBe(false);
    store.getState().setTab('overview');
    store.getState().setTab('definition');
    await expect.element(select()).toHaveDisplayValue('First');
    // the source is the chosen instance's document: the run takes it
    await runFromQueue();
    await expect.poll(() => runTest.mock.calls.map(([, request]) => request.input)).toEqual([INPUT]);
    await page.getByRole('dialog', { name: 'Display Queue' }).getByRole('button', { name: 'Close' }).first().click();

    // edit mode shows the library's current instance, the one Save and Delete change
    store.getState().startEditing();
    await expect.element(select()).toHaveDisplayValue('Second');
  });

  test('a source typed in read mode is what Add Test Instance keeps after Edit', async () => {
    const browserState = memoryBrowserState();
    const store = await renderStudio({ editing: false, browserState });
    await expect.element(tool('Add Test Instance')).toBeDisabled();
    // the Test tab's source, typed
    testInput(store).getState().load(INPUT);
    store.getState().startEditing();

    await addInstance('Typed');

    await expect.element(select()).toHaveDisplayValue('Typed');
    expect(testInstances(store, browserState, MAPPING).getState().value).toMatchObject([{ name: 'Typed', text: INPUT }]);
  });

  test('in read mode the Queue test input chooses the instance a run takes', async () => {
    const api = fakeApiClient();
    const runTest = vi.spyOn(api, 'runTest');
    const store = await renderStudio({ api, editing: false, browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] }) });
    await expect.element(tool('Load Test Instance')).toBeDisabled();

    await select().selectOptions('First');
    await runFromQueue();

    await expect.poll(() => runTest.mock.calls.map(([, request]) => request.input)).toEqual([INPUT]);
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('the queue test input offers no Generate; a file must parse; a choice there loads the source', async () => {
    const browserState = memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] });
    await renderStudio({ browserState });
    await expect.element(bar().getByText('Preview')).toBeVisible();
    await expect.element(bar().getByRole('button', { name: 'Generate Instance' })).not.toBeInTheDocument();

    // a queue test input must parse, unlike the Test tab's source
    await file().upload(xml('<broken', 'broken.xml'));
    await expect.element(bar().getByRole('status')).toHaveTextContent('Could not read this file. Choose a valid XML test input.');
    await expect.poll(() => keptSource(browserState)).toBe(OTHER);
    await select().selectOptions('First');

    await expect.poll(() => keptSource(browserState)).toBe(INPUT);
    await expect.element(select()).toHaveDisplayValue('First');
  });
});
