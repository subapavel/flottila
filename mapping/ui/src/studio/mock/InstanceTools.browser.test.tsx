import '@patternfly/react-core/dist/styles/base.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { strToU8, zipSync } from 'fflate';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import type { ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { testSourceKey, type BrowserStateStore } from '../browser-state/browser-state';
import { selectUnsaved, type StudioTab, type WorkingCopyStore } from '../store/working-copy';
import { testInstances, testInstancesKey, type TestInstance } from './test-instances';
import { studioStore } from '../shell/Studio';
import { StudioShell } from '../shell/StudioShell';

const MAPPING = 'mappings/order-id-copy';
const KEY = testInstancesKey(MAPPING);
const INPUT = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-1</OrderId></Order>';
const OTHER = '<Order xmlns="urn:flottila:corpus:skeleton"><OrderId>PO-2</OrderId></Order>';

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const kept = (id: string, name: string, text: string, lastUsed: number): TestInstance => ({ id, name, format: 'XML', text, values: {}, lastUsed });

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

async function renderStudio(options: { api?: ApiClient; editing?: boolean; browserState?: BrowserStateStore; tab?: StudioTab } = {}): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab(options.tab ?? 'test');
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

const bar = () => page.getByRole('toolbar', { name: 'Test instance actions' });
const tool = (name: string) => bar().getByRole('button', { name, exact: true });
const select = () => bar().getByRole('combobox', { name: 'Select test instance' });
const input = () => page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' });
const studioButton = (name: string) => page.getByRole('button', { name, exact: true });

async function addInstance(name: string) {
  await tool('Add Test Instance').click();
  const dialog = page.getByRole('dialog', { name: 'Add Test Instance' });
  await dialog.getByRole('textbox', { name: 'Test instance name' }).fill(name);
  await dialog.getByRole('button', { name: 'OK' }).click();
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

describe('the test instance library, a preview feature (#170)', () => {
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
    await input().fill(INPUT);

    await addInstance('Order PO-1');

    await expect.element(select()).toHaveDisplayValue('Order PO-1');
    await expect.element(studioButton('Save')).toBeEnabled();
    expect(browserState.values.has(KEY)).toBe(false);
    await page.getByRole('button', { name: 'Run simulation' }).click();
    await studioButton('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(browserState.values.get(KEY)).toMatchObject([{ name: 'Order PO-1', format: 'XML', text: INPUT }]);
    await expect.poll(() => compile.mock.calls.length).toBeGreaterThan(0);
    // the library never reaches the service: Save, compile and test requests carry no trace of it
    expect(JSON.stringify([saveMapping.mock.calls, compile.mock.calls.map(([, model]) => model), runTest.mock.calls])).not.toMatch(/PO-1"|Order PO-1|instance/);
  });

  test('a name the mapping has already is refused in the dialog', async () => {
    await renderStudio({ browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1)] }) });

    await tool('Add Test Instance').click();
    const dialog = page.getByRole('dialog', { name: 'Add Test Instance' });
    await expect.element(dialog.getByRole('button', { name: 'OK' })).toBeDisabled();
    await dialog.getByRole('textbox', { name: 'Test instance name' }).fill('First');
    await dialog.getByRole('button', { name: 'OK' }).click();

    await expect.element(dialog.getByRole('alert')).toHaveTextContent('This name already exists.');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect.element(studioButton('Save')).toBeDisabled();
  });

  test('selecting an instance loads its document; Save Test Instance writes the source over it; Delete asks and removes it', async () => {
    await renderStudio({ browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] }) });
    await expect.element(select()).toHaveDisplayValue('Second');
    await expect.element(input()).toHaveValue(OTHER);

    await select().selectOptions('First');
    await expect.element(input()).toHaveValue(INPUT);
    await input().fill(`${INPUT} `);
    await tool('Save Test Instance').click();
    await expect.element(bar().getByRole('status')).toHaveTextContent('Test instance saved. Save the mapping to keep it in this browser.');

    await select().selectOptions('Second');
    await expect.element(input()).toHaveValue(OTHER);
    await select().selectOptions('First');
    await expect.element(input()).toHaveValue(`${INPUT} `);

    await tool('Delete Test Instance').click();
    const dialog = page.getByRole('dialog', { name: 'Delete Test Instance' });
    await expect.element(dialog).toMatchTextContent('Delete First from this browser?');
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect.element(select()).toHaveDisplayValue('Second');
    await expect.element(select().getByRole('option')).toHaveLength(1);
    await expect.element(input()).toHaveValue(OTHER);
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
    await renderStudio();
    const file = page.getByLabelText('Test instance file');

    await file.upload(new File([zipSync({ 'instance.xml': strToU8(INPUT) })], 'schema.zip', { type: 'application/zip' }));
    await expect.element(input()).toHaveValue(INPUT);
    await file.upload(new File([OTHER], 'other.xml', { type: 'application/xml' }));
    await expect.element(input()).toHaveValue(OTHER);
    await file.upload(new File([zipSync({ 'readme.txt': strToU8('x') })], 'other.zip', { type: 'application/zip' }));

    await expect.element(bar().getByRole('status')).toHaveTextContent('Could not read this file. Choose a valid XML test input.');
    await expect.element(input()).toHaveValue(OTHER);
    await expect.element(studioButton('Save')).toBeDisabled();
  });

  // the owner's rule of #177 replaces the prototype's disabled select: in read mode an instance can be chosen for a run
  test('in read mode Load, Add, Save and Delete stay disabled; the select loads the latest instance, or the one chosen, and changes nothing', async () => {
    const library = [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)];
    const browserState = memoryBrowserState({ [KEY]: library });
    const store = await renderStudio({ editing: false, browserState });

    for (const name of ['Load Test Instance', 'Add Test Instance', 'Save Test Instance', 'Delete Test Instance']) await expect.element(tool(name)).toBeDisabled();
    await expect.element(select()).toBeEnabled();
    await expect.element(select()).toHaveDisplayValue('Second');
    await expect.element(input()).toHaveValue(OTHER);

    await select().selectOptions('First');

    await expect.element(input()).toHaveValue(INPUT);
    await expect.element(select()).toHaveDisplayValue('First');
    expect(selectUnsaved(store.getState())).toBe(false);
    expect(browserState.values.get(KEY)).toEqual(library);
    expect(browserState.values.has(testSourceKey(MAPPING))).toBe(false);
    store.getState().setTab('overview');
    store.getState().setTab('test');
    await expect.element(select()).toHaveDisplayValue('First');
    await expect.element(input()).toHaveValue(INPUT);

    // edit mode shows the library's current instance, the one Save and Delete change
    store.getState().startEditing();
    await expect.element(select()).toHaveDisplayValue('Second');
  });

  test('a source typed in read mode is what Add Test Instance keeps after Edit, from the Queue test input too', async () => {
    const browserState = memoryBrowserState();
    const store = await renderStudio({ editing: false, browserState });
    await input().fill(INPUT);
    store.getState().startEditing();
    store.getState().setTab('definition');
    const tools = page.getByRole('group', { name: 'Queue test input' });

    await tools.getByRole('button', { name: 'Add Test Instance' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add Test Instance' });
    await dialog.getByRole('textbox', { name: 'Test instance name' }).fill('Typed');
    await dialog.getByRole('button', { name: 'OK' }).click();

    await expect.element(tools.getByRole('combobox', { name: 'Queue test input' })).toHaveDisplayValue('Typed');
    expect(testInstances(store, browserState, MAPPING).getState().value).toMatchObject([{ name: 'Typed', text: INPUT }]);
  });

  test('in read mode the Queue test input chooses an instance for the Test tab too', async () => {
    const store = await renderStudio({ editing: false, tab: 'definition', browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] }) });
    const tools = page.getByRole('group', { name: 'Queue test input' });
    await expect.element(tools.getByRole('button', { name: 'Load Test Instance' })).toBeDisabled();

    await tools.getByRole('combobox', { name: 'Queue test input' }).selectOptions('First');
    store.getState().setTab('test');

    await expect.element(input()).toHaveValue(INPUT);
    await expect.element(select()).toHaveDisplayValue('First');
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('the Mapping expression offers the same library as the queue test input; a choice there loads the Test tab source', async () => {
    const store = await renderStudio({ tab: 'definition', browserState: memoryBrowserState({ [KEY]: [kept('a', 'First', INPUT, 1), kept('b', 'Second', OTHER, 2)] }) });
    const tools = page.getByRole('group', { name: 'Queue test input' });
    await expect.element(tools.getByText('Preview')).toBeVisible();
    await expect.element(tools.getByRole('button', { name: 'Generate Instance' })).not.toBeInTheDocument();

    // a queue test input must parse, unlike the Test tab's source
    await tools.getByLabelText('Test instance file').upload(new File(['<broken'], 'broken.xml', { type: 'application/xml' }));
    await expect.element(tools.getByRole('status')).toHaveTextContent('Could not read this file. Choose a valid XML test input.');
    await tools.getByRole('combobox', { name: 'Queue test input' }).selectOptions('First');
    store.getState().setTab('test');

    await expect.element(input()).toHaveValue(INPUT);
    await expect.element(select()).toHaveDisplayValue('First');
  });
});
