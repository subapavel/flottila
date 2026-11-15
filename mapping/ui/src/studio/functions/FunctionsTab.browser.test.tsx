import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { ApiProblem, type ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { functionsKey } from '../mock/functions-store';
import { Studio, studioStore } from '../shell/Studio';
import { StudioShell } from '../shell/StudioShell';
import { emptyFunctions, newFunction, type FunctionsState } from '../mock/function-model';

const MAPPING = 'mappings/order-id-copy';
const UNMAPPED = 'mappings/order-unmapped';

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function render(node: ReactNode) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(node);
}

function unmount() {
  root?.unmount();
  host?.remove();
  root = undefined;
}

/** The studio on the Functions tab, as a page load with `#functions` opens it. */
function openFunctions(options: { api?: ApiClient; browserState?: BrowserStateStore; startEditing?: boolean; mappingId?: string } = {}) {
  history.replaceState(null, '', `${window.location.pathname}#functions`);
  render(
    <Studio
      mappingId={options.mappingId ?? MAPPING}
      api={options.api ?? fakeApiClient()}
      browserState={options.browserState ?? memoryBrowserState()}
      startEditing={options.startEditing}
      listHref="/?api=fake"
    />,
  );
}

/** The shell on the Functions tab in edit mode over a store the test holds, on `order-unmapped`, which has both messages. */
async function renderShell(api: ApiClient, browserState: BrowserStateStore) {
  const store = studioStore(api, UNMAPPED, await api.getMapping(UNMAPPED));
  store.getState().setTab('functions');
  store.getState().startEditing();
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StudioShell mappingId={UNMAPPED} api={api} store={store} browserState={browserState} listHref="/?api=fake" />
    </QueryClientProvider>,
  );
  return store;
}

/** Whether the page asks before it unloads: a `beforeunload` listener cancelled the event. */
function asksBeforeUnload(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

/** A browser state that already holds `state` for the mapping. */
function keeping(state: FunctionsState, mappingId = MAPPING): BrowserStateStore {
  const browserState = memoryBrowserState();
  browserState.write(functionsKey(mappingId), state);
  return browserState;
}

const button = (name: string) => page.getByRole('button', { name, exact: true });
const functionActions = () => page.getByRole('toolbar', { name: 'Function actions' });
const source = (name: string) => page.getByRole('textbox', { name: `Source code for ${name}` });

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  unmount();
  history.replaceState(null, '', window.location.pathname);
  // every test fails on console.error (ADR-0013)
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Functions tab, a preview kept in the browser (ADR-0028, ADR-0030, #169)', () => {
  test('a function edit makes the studio unsaved: Save and Check are enabled and the page asks before it unloads', async () => {
    openFunctions({ startEditing: true });
    await expect.element(button('Save')).toBeDisabled();
    expect(asksBeforeUnload()).toBe(false);

    await functionActions().getByRole('button', { name: 'Insert row' }).click();

    await expect.element(button('Save')).toBeEnabled();
    await expect.element(button('Check')).toBeEnabled();
    expect(asksBeforeUnload()).toBe(true);
  });

  test('Discard reverts the functions to the last saved ones', async () => {
    const saved = { ...emptyFunctions(), functions: [{ ...newFunction('calculate1', 'f1'), body: 'return in0;' }] };
    const browserState = keeping(saved);
    openFunctions({ browserState, startEditing: true });
    await button('Select function calculate1').click();
    await source('calculate1').fill('return "draft";');
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    await expect.element(button('Select function calculate2')).toBeVisible();

    await button('Cancel').click();
    await page.getByRole('dialog', { name: 'Discard changes?' }).getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(button('Edit')).toBeVisible();
    await expect.element(button('Select function calculate2')).not.toBeInTheDocument();
    await button('Select function calculate1').click();
    await expect.element(source('calculate1')).toHaveValue('return in0;');
    expect(browserState.read(functionsKey(MAPPING))).toEqual(saved);
    await button('Edit').click();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('Save keeps the functions in the browser with the model save, which sends the working copy without them', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    const api = { ...fake, saveMapping };
    const browserState = memoryBrowserState();
    const store = await renderShell(api, browserState);
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    await source('calculate1').fill('return "kept in the browser";');
    expect(browserState.read(functionsKey(UNMAPPED))).toBeUndefined();

    await button('Save').click();

    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    const [, sent] = saveMapping.mock.calls[0]!;
    expect(sent).toEqual(store.getState().workingCopy);
    expect(JSON.stringify(sent)).not.toMatch(/calculate1|kept in the browser/);
    unmount();
    openFunctions({ api, browserState, mappingId: UNMAPPED });
    await button('Select function calculate1').click();
    await expect.element(source('calculate1')).toHaveValue('return "kept in the browser";');
  });

  test('a refused save and a save conflict keep the draft', async () => {
    const fake = fakeApiClient();
    const refused = new ApiProblem({ type: 'urn:flottila:mapping:problem:internal', title: 'Internal error', status: 500, detail: 'disk full' });
    const conflict = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
    const saveMapping = vi.fn().mockRejectedValueOnce(refused).mockRejectedValueOnce(conflict);
    const browserState = memoryBrowserState();
    await renderShell({ ...fake, saveMapping }, browserState);
    await functionActions().getByRole('button', { name: 'Insert row' }).click();

    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Internal error/ })).toBeVisible();
    await button('Save').click();
    await page.getByRole('dialog', { name: 'Save conflict' }).getByRole('button', { name: 'Keep editing' }).click();

    await expect.element(button('Save')).toBeEnabled();
    await expect.element(button('Select function calculate1')).toBeVisible();
    expect(browserState.read(functionsKey(UNMAPPED))).toBeUndefined();
  });

  test('Reload after a save conflict drops the draft with the working copy', async () => {
    const fake = fakeApiClient();
    const conflict = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
    await renderShell({ ...fake, saveMapping: () => Promise.reject(conflict) }, memoryBrowserState());
    await functionActions().getByRole('button', { name: 'Insert row' }).click();

    await button('Save').click();
    await page.getByRole('dialog', { name: 'Save conflict' }).getByRole('button', { name: 'Reload' }).click();

    await expect.element(button('Select function calculate1')).not.toBeInTheDocument();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('view mode is read-only: only Export Source Text works', async () => {
    const fn = { ...newFunction('calculate1', 'f1'), body: 'return in0;' };
    openFunctions({ browserState: keeping({ ...emptyFunctions(), imports: [...emptyFunctions().imports, 'java.util.function.*'], functions: [fn] }) });

    await expect.element(page.getByRole('heading', { name: 'Functions and Java Areas' })).toBeVisible();
    for (const name of ['Insert row', 'Delete row', 'Move up', 'Move down']) {
      await expect.element(functionActions().getByRole('button', { name })).toBeDisabled();
      await expect.element(page.getByRole('toolbar', { name: 'Import actions' }).getByRole('button', { name })).toBeDisabled();
    }
    await expect.element(button('Import Source Text')).toBeDisabled();
    await expect.element(button('Export Source Text')).toBeEnabled();
    await expect.element(page.getByRole('combobox', { name: 'JDK Compliance' })).toBeDisabled();
    await expect.element(page.getByRole('textbox', { name: 'Import instruction 7' })).toHaveAttribute('readonly');
    await expect.element(source('Attributes and Methods')).toHaveAttribute('readonly');

    await button('Select function calculate1').click();
    await expect.element(source('calculate1')).toHaveAttribute('readonly');
    await expect.element(page.getByRole('textbox', { name: 'Method name' })).not.toBeInTheDocument();
    await expect.element(page.getByRole('textbox', { name: 'Title' })).toBeDisabled();
    await expect.element(page.getByRole('combobox', { name: 'Execution Kind' })).toBeDisabled();
    await expect.element(page.getByRole('toolbar', { name: 'Variable actions' }).getByRole('button', { name: 'Insert row' })).toBeDisabled();
  });

  test('a mapping without both messages keeps the functions read-only in edit mode', async () => {
    const api = fakeApiClient();
    await api.createMapping({ id: 'mappings/fresh' });
    openFunctions({ api, mappingId: 'mappings/fresh', startEditing: true });

    await expect.element(button('Save')).toBeVisible();
    await expect.element(functionActions().getByRole('button', { name: 'Insert row' })).toBeDisabled();
    await expect.element(source('Attributes and Methods')).toHaveAttribute('readonly');
  });

  test('shows the class from the mapping name, its package, and a Preview badge naming #169', async () => {
    openFunctions();

    await expect.element(page.getByText('order_id_copy', { exact: true })).toBeVisible();
    await expect.element(page.getByText('mappings', { exact: true })).toBeVisible();
    await expect.element(page.getByText('Class attributes and shared methods are available to all functions in this mapping.')).toBeVisible();
    await expect.element(page.getByText('}', { exact: true })).not.toBeInTheDocument();
    await button('init').click();
    await expect.element(page.getByText('}', { exact: true })).toBeVisible();
    await page.getByText('Preview', { exact: true }).hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('User-defined functions: a preview kept in this browser only, never in the mapping file (#169).');
  });

  test('a mapping in the top folder is in the default package', async () => {
    const api = fakeApiClient();
    await api.createMapping({ id: 'top' });
    openFunctions({ api, mappingId: 'top' });

    await expect.element(page.getByText('(default package)')).toBeVisible();
  });
});

describe('editing functions (the prototype FunctionsTab)', () => {
  test('Insert row adds a function after the selected one; Move and Delete reorder and remove, selecting a neighbour', async () => {
    openFunctions({ startEditing: true });
    const insert = functionActions().getByRole('button', { name: 'Insert row' });
    const names = () => [...document.querySelectorAll('.udf-function-row > button')].map((b) => b.textContent);

    await expect.element(functionActions().getByRole('button', { name: 'Delete row' })).toBeDisabled();
    await insert.click();
    await insert.click();
    await button('Select function calculate1').click();
    await insert.click();
    await expect.poll(names).toEqual(['calculate1', 'calculate3', 'calculate2']);
    await expect.element(functionActions().getByRole('button', { name: 'Move up' })).toBeEnabled();

    await functionActions().getByRole('button', { name: 'Move down' }).click();
    await expect.poll(names).toEqual(['calculate1', 'calculate2', 'calculate3']);
    await expect.element(functionActions().getByRole('button', { name: 'Move down' })).toBeDisabled();
    await functionActions().getByRole('button', { name: 'Move up' }).click();
    await expect.poll(names).toEqual(['calculate1', 'calculate3', 'calculate2']);

    await functionActions().getByRole('button', { name: 'Delete row' }).click();
    await expect.poll(names).toEqual(['calculate1', 'calculate2']);
    await expect.element(button('Select function calculate1')).toHaveAttribute('aria-pressed', 'true');
    await functionActions().getByRole('button', { name: 'Delete row' }).click();
    await expect.element(button('Select function calculate2')).toHaveAttribute('aria-pressed', 'true');
    await functionActions().getByRole('button', { name: 'Delete row' }).click();
    await expect.element(button('Attributes and Methods')).toHaveAttribute('aria-pressed', 'true');
    expect(names()).toEqual([]);
  });

  test('the method name commits a free Java identifier and refuses others', async () => {
    openFunctions({ startEditing: true });
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    const name = page.getByRole('textbox', { name: 'Method name' });

    for (const [value, issue] of [
      ['9lives', 'Enter a valid Java identifier.'],
      ['concat', 'This name is already in use.'],
      ['init', 'This name is already in use.'],
      ['calculate1', 'This name is already in use.'],
    ]) {
      await name.fill(value!);
      await userEvent.keyboard('{Enter}');
      await expect.element(page.getByRole('alert')).toHaveTextContent(issue!);
      await expect.element(name).toHaveAttribute('aria-invalid', 'true');
    }
    await name.click();
    await userEvent.keyboard('{Escape}');
    await expect.element(name).toHaveValue('calculate2');
    await expect.element(page.getByRole('alert')).not.toBeInTheDocument();
    // leaving the field without a draft commits nothing
    await page.getByRole('textbox', { name: 'Title' }).click();
    await expect.element(name).toHaveValue('calculate2');

    await name.fill(' total ');
    await userEvent.keyboard('{Enter}');
    await expect.element(button('Select function total')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(page.getByRole('heading', { name: 'total' })).toBeVisible();
  });

  test('title, description, execution kind and category; the execution kind shapes the declaration', async () => {
    const browserState = memoryBrowserState();
    openFunctions({ browserState, startEditing: true });
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    await expect.element(page.getByText('public static String calculate1(String var1, MappingRun run) {')).toBeVisible();

    await page.getByRole('textbox', { name: 'Title' }).fill('Total');
    await page.getByRole('textbox', { name: 'Description' }).fill('Sums the lines');
    await page.getByRole('textbox', { name: 'Category' }).fill('Orders');
    await page.getByRole('combobox', { name: 'Execution Kind' }).selectOptions('Context');

    await expect.element(page.getByText('public static void calculate1(Context var1, ContextOut out, MappingRun run) {')).toBeVisible();
    await button('Save').click();
    await expect.element(button('Save')).toBeDisabled();
    expect((browserState.read(functionsKey(MAPPING)) as FunctionsState).functions[0]).toMatchObject({
      title: 'Total',
      description: 'Sums the lines',
      category: 'Orders',
      execution: 'Context',
    });
  });

  test('signature variables: insert, kind and Java type, names, title, description, move and delete', async () => {
    openFunctions({ startEditing: true });
    await functionActions().getByRole('button', { name: 'Insert row' }).click();
    const actions = page.getByRole('toolbar', { name: 'Variable actions' });
    const declaration = () => document.querySelector('.udf-declaration code')!.textContent;

    await expect.element(actions.getByRole('button', { name: 'Delete row' })).toBeDisabled();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await expect.poll(declaration).toBe('public static String calculate1(String var1, String var2, MappingRun run) {');
    await page.getByRole('combobox', { name: 'Variable kind' }).nth(1).selectOptions('Parameter');
    await page.getByRole('combobox', { name: 'Java type' }).nth(1).selectOptions('int');
    await expect.poll(declaration).toBe('public static String calculate1(String var1, @Parameter("var2") int var2, MappingRun run) {');
    await page.getByRole('combobox', { name: 'Variable kind' }).nth(1).selectOptions('Argument');
    await expect.poll(declaration).toBe('public static String calculate1(String var1, String var2, MappingRun run) {');

    await page.getByRole('textbox', { name: 'Variable name' }).nth(1).fill('run');
    await userEvent.keyboard('{Enter}');
    await expect.element(page.getByRole('alert')).toHaveTextContent('This name is already in use.');
    await page.getByRole('textbox', { name: 'Variable name' }).nth(1).fill('qty');
    await userEvent.keyboard('{Enter}');
    await page.getByRole('textbox', { name: 'Variable title' }).nth(1).fill('Quantity');
    await page.getByRole('textbox', { name: 'Variable description' }).nth(1).fill('Ordered');
    await expect.element(page.getByRole('textbox', { name: 'Variable title' }).nth(1)).toHaveValue('Quantity');
    await expect.element(page.getByRole('textbox', { name: 'Variable description' }).nth(1)).toHaveValue('Ordered');

    await actions.getByRole('button', { name: 'Move up' }).click();
    await expect.poll(declaration).toBe('public static String calculate1(String qty, String var1, MappingRun run) {');
    await expect.element(actions.getByRole('button', { name: 'Move up' })).toBeDisabled();
    await actions.getByRole('button', { name: 'Move down' }).click();
    await expect.poll(declaration).toBe('public static String calculate1(String var1, String qty, MappingRun run) {');
    await actions.getByRole('button', { name: 'Delete row' }).click();
    await expect.poll(declaration).toBe('public static String calculate1(String var1, MappingRun run) {');
    await expect.element(actions.getByRole('button', { name: 'Delete row' })).toBeDisabled();

    // with no row selected Insert appends; with one selected, it inserts after it
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await page.getByRole('textbox', { name: 'Variable title' }).nth(0).click();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await expect.poll(declaration).toBe('public static String calculate1(String var1, String var3, String var2, MappingRun run) {');
  });

  test('init has a description and box parameters, cleanUp neither; both say when they run', async () => {
    const browserState = memoryBrowserState();
    openFunctions({ browserState, startEditing: true });

    await button('init').click();
    await expect.element(page.getByText('Called before mapping.')).toBeVisible();
    await expect.element(page.getByRole('textbox', { name: 'Title' })).not.toBeInTheDocument();
    await page.getByRole('textbox', { name: 'Description' }).fill('Prepares the run');
    await page.getByRole('toolbar', { name: 'Variable actions' }).getByRole('button', { name: 'Insert row' }).click();
    await expect.element(page.getByText('public static void init(@Parameter("var1") String var1, MappingRun run) {')).toBeVisible();
    await expect.element(page.getByRole('group', { name: 'Signature Variables' }).getByText('Description', { exact: true })).not.toBeInTheDocument();
    await expect.element(page.getByRole('combobox', { name: 'Variable kind' })).not.toBeInTheDocument();
    await source('init').fill('run.state().clear();');
    await button('Save').click();
    await expect.element(button('Save')).toBeDisabled();
    expect((browserState.read(functionsKey(MAPPING)) as FunctionsState).javaAreas.init).toEqual({
      body: 'run.state().clear();',
      description: 'Prepares the run',
      variables: [expect.objectContaining({ kind: 'Parameter', name: 'var1' })],
    });

    await button('cleanUp').click();
    await expect.element(page.getByText('Called after mapping.')).toBeVisible();
    await expect.element(page.getByText('public static void cleanUp(MappingRun run) {')).toBeVisible();
    await expect.element(page.getByRole('textbox', { name: 'Description' })).not.toBeInTheDocument();
    await expect.element(page.getByRole('group', { name: 'Signature Variables' })).not.toBeInTheDocument();
  });
});

describe('Export and Import Source Text', () => {
  test('Export Source Text downloads the class, also in view mode, and logs it', async () => {
    const created = vi.spyOn(URL, 'createObjectURL');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      openFunctions({ browserState: keeping({ ...emptyFunctions(), functions: [{ ...newFunction('calculate1', 'f1'), body: 'return in0;' }] }) });

      await button('Export Source Text').click();

      const dialog = page.getByRole('dialog', { name: 'Processing Log' });
      await expect.element(dialog.getByText('Exported order_id_copy.java')).toBeVisible();
      expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe('order_id_copy.java');
      const text = await (created.mock.calls[0]![0] as Blob).text();
      expect(text).toMatch(/^package mappings;/);
      expect(text).toContain('public class order_id_copy {');
      expect(text).toContain('return in0;');
      await dialog.getByRole('contentinfo').getByRole('button', { name: 'Close' }).click();
      await expect.element(dialog).not.toBeInTheDocument();
      await expect.element(page.getByRole('status').filter({ hasText: 'Exported order_id_copy.java' })).toBeVisible();
    } finally {
      created.mockRestore();
      click.mockRestore();
    }
  });

  test('Import Source Text reads the sections of an exported file back, and says why it refuses another', async () => {
    const browserState = keeping({ ...emptyFunctions(), functions: [newFunction('calculate1', 'f1')] });
    const state = (browserState.read(functionsKey(MAPPING)) as FunctionsState);
    const { exportSource } = await import('./function-source');
    const exported = exportSource(MAPPING, state).replace(/(\/\/ beginning of calculate1 \w+\n)/, '$1return in0.trim();');
    openFunctions({ browserState, startEditing: true });
    const input = page.getByLabelText('Java source file');
    const dialog = page.getByRole('dialog', { name: 'Processing Log' });
    const choose = vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      await button('Import Source Text').click();
      expect(choose.mock.contexts).toEqual([input.element()]);
    } finally {
      choose.mockRestore();
    }

    await input.upload(new File([exported], 'order_id_copy.java', { type: 'text/plain' }));

    await expect.element(dialog.getByText('Imported source text from order_id_copy.java')).toBeVisible();
    await dialog.getByRole('contentinfo').getByRole('button', { name: 'Close' }).click();
    await button('Select function calculate1').click();
    await expect.element(source('calculate1')).toHaveValue('return in0.trim();');

    await input.upload(new File(['class Other {}'], 'Other.java', { type: 'text/plain' }));

    await expect.element(dialog.getByText('Import failed. Choose a source file exported for these functions. Check its section markers.')).toBeVisible();
    await dialog.getByRole('contentinfo').getByRole('button', { name: 'Close' }).click();
    await expect.element(source('calculate1')).toHaveValue('return in0.trim();');
  });
});

describe('import instructions and archives (the prototype Dependencies)', () => {
  test('the initial imports are fixed; added ones are edited, moved and deleted after them', async () => {
    const browserState = memoryBrowserState();
    openFunctions({ browserState, startEditing: true });
    const actions = page.getByRole('toolbar', { name: 'Import actions' });
    // the added import instructions as the draft has them
    const imports = () => [...document.querySelectorAll<HTMLInputElement>('[aria-label^="Import instruction"]')].map((input) => input.value);

    await page.getByRole('cell', { name: 'java.util.*', exact: true }).click();
    await expect.element(actions.getByRole('button', { name: 'Delete row' })).toBeDisabled();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await expect.element(page.getByRole('textbox', { name: 'Import instruction 7' })).toHaveFocus();
    await userEvent.keyboard('java.util.function.*');
    await expect.element(actions.getByRole('button', { name: 'Move up' })).toBeDisabled();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await page.getByRole('textbox', { name: 'Import instruction 8' }).fill('java.util.stream.*');
    await expect.element(actions.getByRole('button', { name: 'Move down' })).toBeDisabled();

    await actions.getByRole('button', { name: 'Move up' }).click();
    await expect.poll(imports).toEqual(['java.util.stream.*', 'java.util.function.*']);
    await actions.getByRole('button', { name: 'Move down' }).click();
    await expect.poll(imports).toEqual(['java.util.function.*', 'java.util.stream.*']);
    await actions.getByRole('button', { name: 'Delete row' }).click();
    await expect.poll(imports).toEqual(['java.util.function.*']);
    await page.getByRole('textbox', { name: 'Import instruction 7' }).click();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await page.getByRole('textbox', { name: 'Import instruction 8' }).fill('java.util.stream.*');
    await page.getByRole('textbox', { name: 'Import instruction 7' }).click();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await expect.poll(imports).toEqual(['java.util.function.*', '', 'java.util.stream.*']);
  });

  test('an archive and its namespace are chosen in the catalogue, and cut, copied and pasted', async () => {
    const browserState = memoryBrowserState();
    openFunctions({ browserState, startEditing: true });
    // the archives as the draft has them, an empty field shown as Choose…
    const archives = () =>
      [...document.querySelectorAll('[aria-label="Archives Used"] tbody tr')].map((row) => {
        const [name, namespace] = [...row.querySelectorAll('.sig-lookup span')].map((span) => (span.textContent === 'Choose…' ? '' : span.textContent));
        return { name, namespace };
      });
    await button('Archives Used').click();
    const actions = page.getByRole('toolbar', { name: 'Archive actions' });
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await actions.getByRole('button', { name: 'Insert row' }).click();
    await expect.element(actions.getByRole('button', { name: 'Copy Object' })).toBeDisabled();

    await page.getByRole('button', { name: 'Choose archive name 1' }).click();
    const catalogue = page.getByRole('dialog', { name: 'Choose Imported Archive' });
    await expect.element(catalogue.getByRole('button', { name: 'Apply' })).toBeDisabled();
    await catalogue.getByRole('textbox', { name: 'Search catalog' }).fill('example');
    await catalogue.getByRole('button', { name: 'Go' }).click();
    await expect.element(catalogue.getByRole('status')).toHaveTextContent('Match: JAVA_Audit');
    await catalogue.getByRole('button', { name: 'Go' }).click();
    await expect.element(catalogue.getByRole('status')).toHaveTextContent('Match: JAVA_ExampleServiceQuery');
    await catalogue.getByRole('textbox', { name: 'Search catalog' }).fill('nothing like it');
    await catalogue.getByRole('button', { name: 'Go' }).click();
    await expect.element(catalogue.getByRole('status')).toHaveTextContent('No match.');
    await catalogue.getByRole('button', { name: 'Refresh results' }).click();
    await expect.element(catalogue.getByRole('textbox', { name: 'Search catalog' })).toHaveValue('');
    await catalogue.getByRole('button', { name: 'JAVA_JSONXML' }).click();
    await expect.element(catalogue.getByRole('button', { name: 'JAVA_JSONXML' })).toHaveAttribute('aria-pressed', 'true');
    await catalogue.getByRole('button', { name: 'Apply' }).click();
    await expect.poll(() => archives()[0]).toEqual({ name: 'JAVA_JSONXML', namespace: 'urn:example:common:functions' });

    await page.getByRole('button', { name: 'Choose archive namespace 2' }).click();
    const namespaces = page.getByRole('dialog', { name: 'Choose Namespace' });
    await expect.element(namespaces.getByRole('row').first()).toHaveTextContent('Namespace');
    await namespaces.getByRole('cell', { name: 'urn:example:common:mappings' }).dblClick();
    await expect.poll(() => archives()[1]).toEqual({ name: '', namespace: 'urn:example:common:mappings' });
    await page.getByRole('button', { name: 'Choose archive name 2' }).click();
    await page.getByRole('dialog', { name: 'Choose Imported Archive' }).getByRole('button', { name: 'Cancel' }).click();
    await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();

    await expect.element(actions.getByRole('button', { name: 'Move up' })).toBeEnabled();
    await page.getByRole('button', { name: 'Choose archive name 1' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect.element(actions.getByRole('button', { name: 'Paste Object' })).toBeDisabled();
    await actions.getByRole('button', { name: 'Cut Object' }).click();
    await expect.poll(() => archives()[0]).toEqual({ name: '', namespace: '' });
    await actions.getByRole('button', { name: 'Paste Object' }).click();
    await expect.poll(() => archives()[0]).toEqual({ name: 'JAVA_JSONXML', namespace: 'urn:example:common:functions' });
    await actions.getByRole('button', { name: 'Copy Object' }).click();
    await page.getByRole('button', { name: 'Choose archive namespace 2' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await actions.getByRole('button', { name: 'Paste Object' }).click();
    await expect.poll(archives).toEqual([
      { name: 'JAVA_JSONXML', namespace: 'urn:example:common:functions' },
      { name: 'JAVA_JSONXML', namespace: 'urn:example:common:functions' },
    ]);
    await actions.getByRole('button', { name: 'Delete row' }).click();
    await expect.poll(archives).toHaveLength(1);
    await button('Import Instructions').click();
    await expect.element(page.getByRole('table', { name: 'Import Instructions' })).toBeVisible();
  });
});
