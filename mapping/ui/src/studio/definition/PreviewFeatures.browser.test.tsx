import '@patternfly/react-core/dist/styles/base.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient, StructureLoaded } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { orderStructure } from '../../api/fake/order-structure';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import type { MappingModel } from '../../generated/mapping-model';
import { definitionPreviewKey } from '../mock/definition-preview';
import { studioStore } from '../shell/Studio';
import { StudioShell } from '../shell/StudioShell';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const MAPPING = 'mappings/order-id-copy';
const KEY = definitionPreviewKey(MAPPING);

function memoryBrowserState(): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

interface Rendered {
  store: WorkingCopyStore;
  browserState: ReturnType<typeof memoryBrowserState>;
  /** Every request the studio sent to the service, as JSON. */
  sent: () => string;
  original: MappingModel;
}

/** The studio in edit mode on the Definition tab with OrderId's mapping selected; every request to the service is recorded. */
async function renderDefinition(overrides: Partial<ApiClient> = {}): Promise<Rendered> {
  const fake = fakeApiClient();
  const calls: unknown[] = [];
  const api: ApiClient = { ...fake, ...overrides };
  for (const name of ['saveMapping', 'compile', 'runTest', 'refresh'] as const) {
    const real = fake[name] as (...args: unknown[]) => Promise<unknown>;
    (api as unknown as Record<string, unknown>)[name] = (...args: unknown[]) => {
      calls.push([name, args]);
      return real(...args);
    };
  }
  const original = (await api.getMapping(MAPPING)).model;
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab('definition');
  store.getState().selectTarget(ORDER_ID);
  store.getState().startEditing();
  const browserState = memoryBrowserState();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <QueryClientProvider client={queryClient}>
      <StudioShell mappingId={MAPPING} api={api} store={store} browserState={browserState} listHref="/" />
    </QueryClientProvider>,
  );
  return { store, browserState, sent: () => JSON.stringify(calls), original };
}

const fields = () => page.getByRole('region', { name: 'target message' }).getByRole('table', { name: 'target fields' });
const field = (name: string) => fields().getByRole('button', { name, exact: true });
const canvas = () => page.getByRole('group', { name: 'Mapping canvas' });
const node = (name: string | RegExp) => canvas().getByRole('group', { name });
const menuItem = (name: string) => page.getByRole('menuitem', { name, exact: true });
const catalogue = () => page.getByRole('region', { name: 'Field mapping canvas' }).getByRole('region', { name: 'Function catalogue' });
const tool = (name: string) => page.getByRole('toolbar', { name: 'Mapping tools' }).getByRole('button', { name, exact: true });
const studioButton = (name: string) => page.getByRole('button', { name, exact: true });

async function save() {
  await studioButton('Save').click();
  await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
}

async function addVariable(parent: string, name: string) {
  await field(parent).click({ button: 'right' });
  await menuItem('Add Variable').click();
  const dialog = page.getByRole('dialog', { name: 'Add Variable' });
  await dialog.getByRole('textbox', { name: 'Variable name' }).fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();
}

async function conversions() {
  await catalogue().getByRole('button', { name: /^Conversions/ }).click();
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

describe('mapping variables, a preview feature (#163)', () => {
  test('Add Variable on a structure field adds a preview variable under it, a draft Save keeps in the browser and never sends', async () => {
    const { store, browserState, sent, original } = await renderDefinition();
    await field('OrderId').click({ button: 'right' });
    await expect.element(menuItem('Add Variable')).toBeDisabled();
    await userEvent.keyboard('{Escape}');

    await addVariable('Order', 'total');

    const row = fields().getByRole('row', { name: /^Variable total/ });
    await expect.element(row).toBeVisible();
    await expect.element(row.getByText('Preview')).toBeVisible();
    await expect.element(studioButton('Save')).toBeEnabled();
    expect(store.getState().workingCopy).toEqual(original);
    // a parent takes a name once
    await field('Order').click({ button: 'right' });
    await menuItem('Add Variable').click();
    const again = page.getByRole('dialog', { name: 'Add Variable' });
    await again.getByRole('textbox', { name: 'Variable name' }).fill('total');
    await expect.element(again.getByRole('button', { name: 'Create' })).toBeDisabled();
    await again.getByRole('button', { name: 'Cancel' }).click();
    await save();
    expect(browserState.values.get(KEY)).toMatchObject({ variables: [`/${ORDER}/$total`] });
    expect(sent()).not.toMatch(/\$total|preview/);
  });

  test('a variable has its own expression, all preview; Add node lists the variables in scope and adds one as a Mapping variable node', async () => {
    const { store, sent, original } = await renderDefinition();
    await addVariable('Order', 'total');
    // a box copied from a target field's canvas is not pasted into a variable's
    await node(/^Source field OrderId/).click();
    await tool('Copy').click();

    await field('Variable total').click();
    await expect.element(node(/^Mapping variable total/)).toBeVisible();
    await expect.element(tool('Paste')).toBeDisabled();
    await page.getByRole('region', { name: 'source message' }).getByRole('button', { name: 'OrderId', exact: true }).dblClick();
    await expect.element(node(/^Source field OrderId/).getByText('Preview')).toBeVisible();
    await catalogue().getByRole('button', { name: /^Text/ }).click();
    await catalogue().getByRole('button', { name: /^substring\b/ }).click();
    await node(/^Node function substring/).getByRole('button', { name: 'Parameters for substring' }).click();
    const parameters = page.getByRole('dialog', { name: 'Function parameters · substring' });
    await parameters.getByLabelText('Start Position').fill('2');
    await parameters.getByLabelText('Character Count').fill('3');
    await parameters.getByRole('button', { name: 'Apply' }).click();
    await expect.element(node(/^Node function substring/)).toMatchTextContent('start 2 · length 3');
    expect(store.getState().workingCopy).toEqual(original);

    await field('OrderId').click();
    await tool('Add node').click();
    const dialog = page.getByRole('dialog', { name: 'Add node' });
    await expect.element(dialog.getByRole('region', { name: 'Variables' }).getByText('Preview')).toBeVisible();
    await dialog.getByRole('button', { name: /^total\b/ }).click();

    await expect.element(node(/^Mapping variable total/)).toBeVisible();
    expect(store.getState().workingCopy).toEqual(original);
    await save();
    expect(sent()).not.toMatch(/\$total|preview|substring/);
  });

  test('Add variable to expression in the variable row menu puts it on a target field in scope; Discard drops the variable and its canvas', async () => {
    await renderDefinition();
    await addVariable('Order', 'total');
    await field('Variable total').click();

    await field('Variable total').click({ button: 'right' });
    await menuItem('Add variable to expression').click();
    await menuItem(`/Order/OrderId`).click();
    await expect.element(node(/^Mapping variable total/)).toBeVisible();
    await expect.element(node(/^Target field OrderId/)).toBeVisible();

    await field('Variable total').click();
    await studioButton('Cancel').click();
    await page.getByRole('dialog', { name: 'Discard changes?' }).getByRole('button', { name: 'Discard changes' }).click();
    await expect.element(field('Variable total')).not.toBeInTheDocument();
    await expect.element(page.getByText('Select a target field to see its mapping expression.')).toBeVisible();
  });
});

describe('a field named like a variable (#163)', () => {
  test('a JSON property `$total` stays a target field: Add Variable does not take its name, and selecting it shows its own canvas', async () => {
    const type = '/Q{urn:flottila:corpus:skeleton}Order';
    const dollar = { kind: 'json-property' as const, step: '$total', namespace: '', localName: '$total', min: 0, max: 1, valueType: 'xs:string', nillable: false };
    const structure: StructureLoaded = { ...orderStructure, types: { ...orderStructure.types, [type]: [...orderStructure.types[type]!, dollar] } };
    await renderDefinition({ loadStructure: () => Promise.resolve(structure) });

    await field('Order').click({ button: 'right' });
    await menuItem('Add Variable').click();
    const dialog = page.getByRole('dialog', { name: 'Add Variable' });
    await dialog.getByRole('textbox', { name: 'Variable name' }).fill('total');
    await expect.element(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    await field('$total').click();
    await expect.element(node(/^Target field \$total/)).toBeVisible();
  });
});

describe('grouped expressions, a preview feature (#164)', () => {
  test('Add target to expression puts another target field on this canvas; Ungroup takes it off; the model never changes', async () => {
    const { store, sent, original } = await renderDefinition();
    await field('Note').click({ button: 'right' });

    await menuItem('Add target to expression').click();

    await expect.element(node(/^Target field Note/)).toBeVisible();
    await expect.element(node(/^Target field OrderId/)).toBeVisible();
    await expect.element(node(/^Target field Note/).getByText('Preview')).toBeVisible();
    await field('OrderId').click({ button: 'right' });
    await expect.element(menuItem('Add target to expression')).toBeDisabled();
    await userEvent.keyboard('{Escape}');
    // a field grouped already is not grouped twice
    await field('Note').click({ button: 'right' });
    await expect.element(menuItem('Add target to expression')).toBeDisabled();
    await userEvent.keyboard('{Escape}');
    expect(store.getState().workingCopy).toEqual(original);
    await save();
    expect(sent()).not.toMatch(/preview/);

    await node(/^Target field Note/).click({ button: 'right' });
    await menuItem('Ungroup').click();
    await expect.element(node(/^Target field Note/)).not.toBeInTheDocument();
    await expect.element(studioButton('Save')).toBeEnabled();
  });
});

describe('value mapping and lookups, preview functions (#166, #165)', () => {
  test('Value mapping is added from the drilldown as a preview node; its properties and its wire stay out of the model', async () => {
    const { store, sent, original } = await renderDefinition();
    await conversions();
    const entry = catalogue().getByRole('button', { name: /^Value mapping/ });
    await expect.element(entry).toHaveAccessibleDescription('Preview: kept in this browser only, never in the mapping file (#166)');

    await entry.click();
    await expect.element(node(/^Node function Value mapping/)).toBeVisible();
    await node(/^Node function Value mapping/).getByRole('button', { name: 'Parameters for Value mapping' }).click();
    const dialog = page.getByRole('dialog', { name: 'Value Mapping Properties' });
    // no SAP namespace by default (ADR-0028, Terms)
    await expect.element(dialog.getByRole('textbox', { name: 'Value-Mapping Context' })).toHaveValue('');
    await dialog.getByRole('radio', { name: 'Use Default Value' }).click();
    await dialog.getByRole('textbox', { name: 'Default Value' }).fill('unknown');
    await dialog.getByRole('button', { name: 'Apply' }).click();
    await expect.element(node(/^Node function Value mapping/)).toMatchTextContent(/vmdefault unknown/);
    // its input is its own and the target's is taken: nowhere to connect; a preview node is no box to copy
    await node(/^Node function Value mapping/).click({ button: 'right' });
    await expect.element(menuItem('Properties')).toBeVisible();
    await expect.element(menuItem('Connect to')).not.toBeInTheDocument();
    await expect.element(menuItem('Copy')).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await expect.element(tool('Copy')).toBeDisabled();

    await node(/^Source field OrderId/).click({ button: 'right' });
    await menuItem('Connect to').click();
    await menuItem('Value mapping · a').click();
    await expect.element(node(/^Node function Value mapping/)).not.toMatchTextContent(/Missing/);
    expect(store.getState().workingCopy).toEqual(original);
    await save();
    expect(sent()).not.toMatch(/Value mapping|valuemap|unknown|preview/);
  });

  test('RFC Lookup chooses its function module; JDBC Lookup offers no table; the channel waits for a parameter', async () => {
    const { store, original } = await renderDefinition();
    await conversions();
    await catalogue().getByRole('button', { name: /^RFC Lookup/ }).click();
    await node(/^Node function RFC Lookup/).dblClick();
    const dialog = page.getByRole('dialog', { name: 'RFC Lookup' });
    await expect.element(dialog.getByRole('combobox', { name: 'Communication Channel' })).toBeDisabled();

    await dialog.getByRole('button', { name: 'Choose RFC module' }).click();
    const picker = page.getByRole('dialog', { name: 'Choose Function Module' });
    await expect.element(picker.getByRole('button', { name: 'Apply' })).toBeDisabled();
    await picker.getByRole('button', { name: 'SXIDEMO_AIRL_FLIGHT_CHECKAVAIL' }).click();
    await picker.getByRole('button', { name: 'Apply' }).click();
    await expect.element(page.getByRole('dialog', { name: 'RFC Lookup' })).toMatchTextContent(/SXIDEMO_AIRL_FLIGHT_CHECKAVAIL/);
    await page.getByRole('dialog', { name: 'RFC Lookup' }).getByRole('button', { name: 'Apply' }).click();

    await catalogue().getByRole('button', { name: /^JDBC Lookup/ }).click();
    await node(/^Node function JDBC Lookup/).dblClick();
    await page.getByRole('dialog', { name: 'JDBC Lookup' }).getByRole('button', { name: 'Choose database table' }).click();
    await expect.element(page.getByRole('dialog', { name: 'Choose External Definition' })).toMatchTextContent(/No Suitable Data Found/);
    expect(store.getState().workingCopy).toEqual(original);
  });
});
