import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { ApiProblem, type ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { orderStructure } from '../../api/fake/order-structure';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { MappingModel } from '../../generated/mapping-model';
import { signatureMockKey } from '../mock/signature-mock';
import { Studio } from '../shell/Studio';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_XSD = '../structures/skeleton/order.xsd';

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function memoryBrowserState(): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

function renderStudio(api: ApiClient, mappingId: string, options: { startEditing?: boolean; browserState?: BrowserStateStore } = {}) {
  history.replaceState(null, '', '#signature');
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <Studio mappingId={mappingId} api={api} browserState={options.browserState ?? memoryBrowserState()} startEditing={options.startEditing} listHref="/?api=fake" />,
  );
}

function unmount() {
  root?.unmount();
  host?.remove();
  root = undefined;
}

const button = (name: string) => page.getByRole('button', { name, exact: true });
const messages = (side: 'Source' | 'Target') => page.getByRole('region', { name: `${side} messages` });
const table = (side: 'Source' | 'Target') => page.getByRole('table', { name: `${side} signature` });
/** The `n`th message row of a side, from 1. */
const row = (side: 'Source' | 'Target', n: number) => table(side).getByRole('row').nth(n);
const tool = (side: 'Source' | 'Target', name: string) => page.getByRole('toolbar', { name: `${side} message actions` }).getByRole('button', { name, exact: true });
const choice = (side: 'source' | 'target') => page.getByRole('dialog', { name: `Choose ${side} message` });
const blank = (): Record<string, MappingModel> => ({ 'mappings/blank': { formatVersion: 1 } });

/** Fills the message choice with an element root and applies it. */
async function chooseElement(side: 'source' | 'target', bundle: string, element: string) {
  await choice(side).getByRole('textbox', { name: 'Bundle' }).fill(bundle);
  await choice(side).getByRole('textbox', { name: 'Root element' }).fill(element);
  await choice(side).getByRole('button', { name: 'Apply' }).click();
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  unmount();
  history.replaceState(null, '', window.location.pathname);
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Signature tab (the prototype Signature)', () => {
  test('lists the source and target of the mapping file, read-only in view mode', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy');

    for (const side of ['Source', 'Target'] as const) {
      await expect.element(messages(side).getByRole('heading', { name: new RegExp(`^${side} Message\\(s\\)`) })).toBeVisible();
      await expect.element(messages(side).getByLabelText(`${side} messages chosen`)).toHaveTextContent('1');
      await expect.element(messages(side)).toMatchTextContent(/Packagemappings/);
      await expect.element(table(side).getByRole('row')).toHaveLength(2);
      await expect.element(row(side, 1).getByRole('combobox', { name: 'Message type' })).toHaveValue('element');
      await expect.element(row(side, 1).getByRole('button', { name: 'Choose message 1' })).toHaveTextContent('Order');
      await expect.element(row(side, 1).getByRole('button', { name: 'Choose message 1' })).toHaveAttribute('title', ORDER_XSD);
      await expect.element(row(side, 1).getByRole('button', { name: 'Choose namespace or ID 1' })).toHaveTextContent('urn:flottila:corpus:skeleton');
      await expect.element(row(side, 1).getByRole('combobox', { name: 'Occurrence' })).toHaveValue('1');
      await expect.element(tool(side, 'Insert row')).toBeDisabled();
      await expect.element(row(side, 1).getByRole('button', { name: 'Choose message 1' })).toBeDisabled();
    }
    await expect.element(page.getByRole('button', { name: /^Add (source|target) message$/ })).not.toBeInTheDocument();
    await expect.element(page.getByRole('region', { name: 'Signature parameters' })).toBeVisible();
    await expect.element(page.getByRole('textbox', { name: 'Parameter name' })).not.toBeInTheDocument();
  });

  test('a mapping in the top folder shows a dash for its package', async () => {
    const corpus = (await fakeApiClient().getMapping('mappings/order-id-copy')).model;
    renderStudio(fakeApiClient({ top: corpus }), 'top');

    await expect.element(messages('Source')).toMatchTextContent(/Package—/);
  });

  test('a new mapping offers Add source message and Add target message; a choice loads the structure, sets the side and refreshes', async () => {
    const fake = fakeApiClient(blank());
    const loadStructure = vi.fn(fake.loadStructure);
    const refresh = vi.fn(fake.refresh);
    const saveMapping = vi.fn(fake.saveMapping);
    renderStudio({ ...fake, loadStructure, refresh, saveMapping }, 'mappings/blank', { startEditing: true });
    await expect.element(table('Source').getByRole('row')).toHaveLength(1);
    await expect.element(messages('Source')).toMatchTextContent(/No source message yet\./);
    await expect.poll(() => refresh.mock.calls.length).toBe(1);

    await button('Add source message').click();
    await expect.element(choice('source')).toMatchTextContent(/The source structure of the mapping, loaded when you apply\./);
    await chooseElement('source', ORDER_XSD, ORDER);

    await expect.element(choice('source')).not.toBeInTheDocument();
    await expect.element(row('Source', 1).getByRole('button', { name: 'Choose message 1' })).toHaveTextContent('Order');
    await expect.element(button('Add source message')).not.toBeInTheDocument();
    expect(loadStructure).toHaveBeenCalledWith({ base: 'mappings', bundle: [ORDER_XSD], root: { element: ORDER } });
    await expect.poll(() => refresh.mock.calls.length).toBe(2);
    expect(refresh.mock.calls[1]![1].source).toEqual({ bundle: [ORDER_XSD], root: { element: ORDER }, fingerprint: orderStructure.fingerprint });

    await tool('Target', 'Insert row').click();
    await chooseElement('target', ORDER_XSD, ORDER);
    await expect.element(row('Target', 1).getByRole('button', { name: 'Choose message 1' })).toHaveTextContent('Order');
    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping blank saved/ })).toBeVisible();
    const saved = saveMapping.mock.calls[0]![1];
    expect([saved.source?.fingerprint, saved.target?.fingerprint]).toEqual([orderStructure.fingerprint, orderStructure.fingerprint]);
  });

  test("the structure a choice loads is the ['structure'] query the Definition tab shows: it loads no second time", async () => {
    const fake = fakeApiClient(blank());
    const loadStructure = vi.fn(fake.loadStructure);
    renderStudio({ ...fake, loadStructure }, 'mappings/blank', { startEditing: true });
    await button('Add source message').click();
    await chooseElement('source', ORDER_XSD, ORDER);
    await expect.element(choice('source')).not.toBeInTheDocument();
    await button('Add target message').click();
    await chooseElement('target', ORDER_XSD, ORDER);
    await expect.element(choice('target')).not.toBeInTheDocument();
    // a choice always loads, so that a structure fixed on disk is read again
    expect(loadStructure).toHaveBeenCalledTimes(2);

    await page.getByRole('tab', { name: 'Definition' }).click();

    await expect.element(page.getByRole('table', { name: 'source fields' }).getByRole('button', { name: 'Order', exact: true })).toBeVisible();
    expect(loadStructure).toHaveBeenCalledTimes(2);
  });

  test('a structure the service refuses stays in the dialog with its diagnostics, and the working copy stays', async () => {
    renderStudio(fakeApiClient(blank()), 'mappings/blank', { startEditing: true });
    await button('Add target message').click();

    await chooseElement('target', 'nope.xsd', ORDER);

    await expect.element(choice('target').getByRole('heading', { name: /The structure could not be loaded/ })).toBeVisible();
    await expect.element(choice('target').getByRole('list', { name: 'Message diagnostics' })).toMatchTextContent(/error schema-unreadable: the fake API has no structure for mappings\/nope\.xsd/);
    await choice('target').getByRole('button', { name: 'Cancel' }).click();
    await expect.element(choice('target')).not.toBeInTheDocument();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('a load that fails shows its problem in the dialog', async () => {
    const problem = new ApiProblem({ type: 'about:blank', title: 'Service Unavailable', status: 503, detail: 'offline' });
    renderStudio({ ...fakeApiClient(blank()), loadStructure: () => Promise.reject(problem) }, 'mappings/blank', { startEditing: true });
    await button('Add source message').click();

    await chooseElement('source', ORDER_XSD, ORDER);

    await expect.element(choice('source').getByRole('heading', { name: /Service Unavailable/ })).toBeVisible();
    await expect.element(choice('source').getByRole('button', { name: 'Apply' })).toBeEnabled();
    await choice('source').getByRole('button', { name: 'Cancel' }).click();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('a refresh that fails after a choice says so, until the message is dismissed', async () => {
    const fake = fakeApiClient(blank());
    let calls = 0;
    const refresh: ApiClient['refresh'] = (...args) =>
      ++calls === 1 ? fake.refresh(...args) : Promise.reject(new ApiProblem({ type: 'about:blank', title: 'Bad Gateway', status: 502, detail: '' }));
    renderStudio({ ...fake, refresh }, 'mappings/blank', { startEditing: true });
    await button('Add source message').click();

    await chooseElement('source', ORDER_XSD, ORDER);

    const notice = page.getByRole('status').filter({ hasText: 'The structures could not be refreshed' });
    await expect.element(notice).toHaveTextContent('The structures could not be refreshed: Bad Gateway');
    await expect.element(button('Save')).toBeEnabled();
    await page.getByRole('button', { name: 'Dismiss signature message' }).click();
    await expect.element(notice).not.toBeInTheDocument();
  });

  test('Apply waits for a bundle and a root element, and Enter in a field applies nothing before', async () => {
    const fake = fakeApiClient(blank());
    const loadStructure = vi.fn(fake.loadStructure);
    renderStudio({ ...fake, loadStructure }, 'mappings/blank', { startEditing: true });
    await button('Add source message').click();
    const apply = choice('source').getByRole('button', { name: 'Apply' });
    await expect.element(apply).toBeDisabled();

    await choice('source').getByRole('textbox', { name: 'Bundle' }).fill(ORDER_XSD);
    await expect.element(apply).toBeDisabled();
    await choice('source').getByRole('textbox', { name: 'Root element' }).click();
    await userEvent.keyboard('{Enter}');

    expect(loadStructure).not.toHaveBeenCalled();
    await choice('source').getByRole('combobox', { name: 'Root' }).selectOptions('json');
    await expect.element(apply).toBeEnabled();
  });

  test('a second Enter while the structure loads sends nothing more', async () => {
    const fake = fakeApiClient(blank());
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const loadStructure = vi.fn((...args: Parameters<ApiClient['loadStructure']>) => answered.then(() => fake.loadStructure(...args)));
    renderStudio({ ...fake, loadStructure }, 'mappings/blank', { startEditing: true });
    await button('Add source message').click();
    await choice('source').getByRole('textbox', { name: 'Bundle' }).fill(ORDER_XSD);
    await choice('source').getByRole('textbox', { name: 'Root element' }).fill(ORDER);
    await userEvent.keyboard('{Enter}');
    await expect.element(choice('source').getByRole('button', { name: 'Loading... Apply' })).toBeDisabled();

    await userEvent.keyboard('{Enter}');

    answer();
    await expect.element(choice('source')).not.toBeInTheDocument();
    expect(loadStructure).toHaveBeenCalledTimes(1);
  });

  test('changing a message uses the same choice, started from its bundle and root', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });
    await row('Target', 1).getByRole('button', { name: 'Choose namespace or ID 1' }).click();
    await expect.element(choice('target').getByRole('textbox', { name: 'Bundle' })).toHaveValue(ORDER_XSD);
    await expect.element(choice('target').getByRole('textbox', { name: 'Root element' })).toHaveValue(ORDER);

    await choice('target').getByRole('textbox', { name: 'Root element' }).fill('Q{urn:flottila:corpus:skeleton}Invoice');
    await choice('target').getByRole('button', { name: 'Apply' }).click();

    await expect.element(row('Target', 1).getByRole('button', { name: 'Choose message 1' })).toHaveTextContent('Invoice');
    await expect.element(button('Save')).toBeEnabled();
  });

  test('the Type select opens the choice for that kind of root: a WSDL message asks for its interface, operation and fault', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });

    await row('Source', 1).getByRole('combobox', { name: 'Message type' }).selectOptions('wsdl');

    const dialog = choice('source');
    await expect.element(dialog.getByRole('combobox', { name: 'Root' })).toHaveValue('wsdl');
    await expect.element(dialog.getByRole('textbox', { name: 'Bundle' })).toHaveValue(ORDER_XSD);
    await dialog.getByRole('textbox', { name: 'Interface' }).fill('Q{urn:example:orders}OrderPort');
    await dialog.getByRole('textbox', { name: 'Operation' }).fill('PlaceOrder');
    await expect.element(dialog.getByRole('textbox', { name: 'Fault' })).not.toBeInTheDocument();
    await dialog.getByRole('combobox', { name: 'Direction' }).selectOptions('fault');
    await expect.element(dialog.getByRole('button', { name: 'Apply' })).toBeDisabled();
    await dialog.getByRole('textbox', { name: 'Fault' }).fill('Rejected');
    await dialog.getByRole('button', { name: 'Apply' }).click();

    await expect.element(row('Source', 1).getByRole('button', { name: 'Choose message 1' })).toHaveTextContent('PlaceOrder');
    await expect.element(row('Source', 1).getByRole('button', { name: 'Choose namespace or ID 1' })).toHaveTextContent('urn:example:orders');
    await expect.element(row('Source', 1).getByRole('combobox', { name: 'Message type' })).toHaveValue('wsdl');
  });
});

describe('several source or target messages, a preview feature (#168)', () => {
  test('a second message is marked preview, a draft Save keeps in the browser, and never reaches the model or the service', async () => {
    const fake = fakeApiClient();
    const loadStructure = vi.fn(fake.loadStructure);
    const saveMapping = vi.fn(fake.saveMapping);
    const refresh = vi.fn(fake.refresh);
    const browserState = memoryBrowserState();
    renderStudio({ ...fake, loadStructure, saveMapping, refresh }, 'mappings/order-id-copy', { startEditing: true, browserState });
    const original = (await fake.getMapping('mappings/order-id-copy')).model;

    await tool('Source', 'Insert row').click();

    await expect.element(table('Source').getByRole('row')).toHaveLength(3);
    await expect.element(row('Source', 2)).toHaveClass('sig-selected');
    await expect.element(row('Source', 2).getByText('Preview')).toBeVisible();
    await expect.element(row('Source', 1).getByText('Preview')).not.toBeInTheDocument();
    await row('Source', 2).getByText('Preview').hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('Several source or target messages: a preview kept in this browser only, never in the mapping file (#168).');
    await row('Source', 2).getByRole('button', { name: 'Choose message 2' }).click();
    await expect.element(choice('source')).toMatchTextContent(/A further message is a preview: it is kept in this browser only, never in the mapping file\./);
    await chooseElement('source', 'extra.xsd', 'Q{urn:example:extra}Extra');

    await expect.element(row('Source', 2).getByRole('button', { name: 'Choose message 2' })).toHaveTextContent('Extra');
    await expect.element(messages('Source').getByLabelText('Source messages chosen')).toHaveTextContent('2');
    await row('Source', 2).getByRole('combobox', { name: 'Occurrence' }).selectOptions('0..unbounded');
    expect(loadStructure).not.toHaveBeenCalled();
    // ADR-0028 (owner, 2026-11-10): a preview edit is a draft of the edit session, kept by Save
    expect(browserState.values.has(signatureMockKey('mappings/order-id-copy'))).toBe(false);
    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(browserState.values.get(signatureMockKey('mappings/order-id-copy'))).toMatchObject({
      source: [{ choice: { bundle: ['extra.xsd'], root: { element: 'Q{urn:example:extra}Extra' } }, occurrence: '0..unbounded' }],
      target: [],
    });
    expect(saveMapping.mock.calls[0]![1]).toEqual(original);
    expect(JSON.stringify([saveMapping.mock.calls, refresh.mock.calls])).not.toMatch(/extra/i);

    unmount();
    renderStudio(fake, 'mappings/order-id-copy', { browserState });
    await expect.element(row('Source', 2).getByRole('button', { name: 'Choose message 2' })).toHaveTextContent('Extra');
  });

  test('Insert row goes after the selected row; Move up, Move down and Delete row act on the preview messages only', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });
    const name = (n: number) => row('Target', n).getByRole('button', { name: `Choose message ${n}` });
    const label = async (n: number, element: string) => {
      await name(n).click();
      await chooseElement('target', 'x.xsd', element);
    };
    await tool('Target', 'Insert row').click();
    await label(2, 'Bravo');
    await row('Target', 1).getByRole('button', { name: 'Select row 1' }).click();
    await expect.element(tool('Target', 'Delete row')).toBeDisabled();
    await expect.element(tool('Target', 'Move down')).toBeDisabled();
    await expect.element(tool('Target', 'Move up')).toBeDisabled();
    await tool('Target', 'Insert row').click();
    await label(2, 'Alpha');
    await tool('Target', 'Insert row').click();
    await label(3, 'Charlie');
    await expect.element(table('Target')).toMatchTextContent(/Order.*Alpha.*Charlie.*Bravo/);

    await row('Target', 3).getByRole('button', { name: 'Select row 3' }).click();
    await tool('Target', 'Move up').click();
    await expect.element(table('Target')).toMatchTextContent(/Order.*Charlie.*Alpha.*Bravo/);
    await expect.element(tool('Target', 'Move up')).toBeDisabled();
    await tool('Target', 'Move down').click();
    await tool('Target', 'Move down').click();
    await expect.element(table('Target')).toMatchTextContent(/Order.*Alpha.*Bravo.*Charlie/);
    await expect.element(tool('Target', 'Move down')).toBeDisabled();

    await tool('Target', 'Delete row').click();
    await expect.element(table('Target')).toMatchTextContent(/Order.*Alpha.*Bravo/);
    await expect.element(table('Target').getByRole('row')).toHaveLength(4);
    await expect.element(row('Target', 3)).toHaveClass('sig-selected');
    await row('Target', 2).getByRole('button', { name: 'Select row 2' }).click();
    await tool('Target', 'Delete row').click();
    await expect.element(row('Target', 2)).toHaveClass('sig-selected');
    await tool('Target', 'Delete row').click();
    await expect.element(table('Target').getByRole('row')).toHaveLength(2);
    await expect.element(tool('Target', 'Delete row')).toBeDisabled();
    // back to the saved preview messages: no draft is left
    await expect.element(button('Save')).toBeDisabled();
  });

  test('Copy, Cut and Paste Object move a message between rows; the file message is copied but never cut or pasted over', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });
    await tool('Source', 'Insert row').click();
    await expect.element(tool('Source', 'Copy Object')).toBeDisabled();
    await expect.element(tool('Source', 'Paste Object')).toBeDisabled();
    await row('Source', 1).getByRole('button', { name: 'Select row 1' }).click();
    await expect.element(tool('Source', 'Cut Object')).toBeDisabled();

    await tool('Source', 'Copy Object').click();
    await expect.element(tool('Source', 'Paste Object')).toBeDisabled();
    await row('Source', 2).getByRole('button', { name: 'Select row 2' }).click();
    await tool('Source', 'Paste Object').click();

    await expect.element(row('Source', 2).getByRole('button', { name: 'Choose message 2' })).toHaveTextContent('Order');
    await tool('Target', 'Insert row').click();
    await row('Source', 2).getByRole('button', { name: 'Select row 2' }).click();
    await tool('Source', 'Cut Object').click();
    await expect.element(row('Source', 2).getByRole('button', { name: 'Choose message 2' })).toHaveTextContent('Choose…');
    await row('Target', 2).getByRole('button', { name: 'Select row 2' }).click();
    await tool('Target', 'Paste Object').click();
    await expect.element(row('Target', 2).getByRole('button', { name: 'Choose message 2' })).toHaveTextContent('Order');
    // ADR-0028 (owner, 2026-11-10): a preview edit makes the studio unsaved
    await expect.element(button('Save')).toBeEnabled();
  });

  test('without a file message Insert row chooses it; Discard drops the preview message with the file message', async () => {
    renderStudio(fakeApiClient(blank()), 'mappings/blank', { startEditing: true });
    await tool('Source', 'Insert row').click();
    await chooseElement('source', ORDER_XSD, ORDER);
    await tool('Source', 'Insert row').click();
    await expect.element(table('Source').getByRole('row')).toHaveLength(3);

    await button('Cancel').click();
    await page.getByRole('dialog', { name: 'Discard changes?' }).getByRole('button', { name: 'Discard changes' }).click();

    // ADR-0028 (owner, 2026-11-10): Discard reverts the preview draft too; #155 kept the preview message
    await expect.element(table('Source').getByRole('row')).toHaveLength(1);
    await expect.element(button('Add source message')).toBeDisabled();
    await expect.element(button('Edit')).toBeEnabled();
  });

  test('a refused save and a save conflict keep the preview messages as a draft', async () => {
    const fake = fakeApiClient();
    const problem = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
    const failure = new ApiProblem({ type: 'about:blank', title: 'Service Unavailable', status: 503, detail: 'offline' });
    const saveMapping = vi.fn().mockRejectedValueOnce(failure).mockRejectedValueOnce(problem);
    const browserState = memoryBrowserState();
    renderStudio({ ...fake, saveMapping }, 'mappings/order-id-copy', { startEditing: true, browserState });
    await tool('Source', 'Insert row').click();

    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Service Unavailable/ })).toBeVisible();
    await button('Save').click();
    await page.getByRole('dialog', { name: 'Save conflict' }).getByRole('button', { name: 'Keep editing' }).click();

    await expect.element(table('Source').getByRole('row')).toHaveLength(3);
    await expect.element(button('Save')).toBeEnabled();
    expect(browserState.values.has(signatureMockKey('mappings/order-id-copy'))).toBe(false);
  });
});

describe('mapping parameters, a preview feature (#167)', () => {
  const parameters = () => page.getByRole('region', { name: 'Signature parameters' });
  const params = () => page.getByRole('table', { name: 'Mapping parameters' });
  const paramTool = (name: string) => page.getByRole('toolbar', { name: 'Parameter actions' }).getByRole('button', { name, exact: true });
  const names = () => params().getByRole('textbox', { name: 'Parameter name' });

  test('carry the Preview badge naming #167, and are kept in the browser, never in the model', async () => {
    const browserState = memoryBrowserState();
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true, browserState });
    await parameters().getByText('Preview').hover();
    await expect.element(page.getByRole('tooltip')).toHaveTextContent('Mapping parameters: a preview kept in this browser only, never in the mapping file (#167).');

    await names().nth(0).fill('date');

    await expect.element(names()).toHaveLength(2);
    await expect.element(names().nth(0)).toHaveValue('date');
    await expect.element(names().nth(1)).toHaveAttribute('placeholder', 'New parameter…');
    await expect.element(parameters().getByLabelText('Parameters')).toHaveTextContent('1');
    await params().getByRole('combobox', { name: 'Parameter type' }).selectOptions('integer');
    await params().getByRole('combobox', { name: 'Parameter direction' }).selectOptions('Export');
    await params().getByRole('textbox', { name: 'Parameter description' }).fill('the order date');
    await names().nth(0).fill('orderDate');
    // ADR-0028 (owner, 2026-11-10): a draft until Save
    expect(browserState.values.has(signatureMockKey('mappings/order-id-copy'))).toBe(false);
    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(browserState.values.get(signatureMockKey('mappings/order-id-copy'))).toMatchObject({
      parameters: [{ name: 'orderDate', type: 'integer', direction: 'Export', description: 'the order date' }],
    });
    await expect.element(params().getByRole('combobox', { name: 'Parameter type' })).toHaveTextContent('xsd:stringxsd:integer');
  });

  test('a parameter of a JSON side has no xsd: prefix; a parameter whose name is cleared stays', async () => {
    const json: MappingModel = { formatVersion: 1, source: { bundle: ['in.schema.json'], root: { json: '$' }, fingerprint: 'sha256:x' } };
    renderStudio(fakeApiClient({ 'mappings/json': json }), 'mappings/json', { startEditing: true });
    await names().nth(0).fill('x');

    await names().nth(0).fill('');

    await expect.element(names()).toHaveLength(2);
    await expect.element(names().nth(0)).toHaveAttribute('placeholder', 'Name');
    await expect.element(params().getByRole('combobox', { name: 'Parameter type' })).toHaveTextContent('stringinteger');
    await params().getByRole('combobox', { name: 'Parameter direction' }).selectOptions('Export');
    await expect.element(params().getByRole('combobox', { name: 'Parameter type' })).toHaveTextContent('xsd:stringxsd:integer');
  });

  test('Add parameter, Where Used, Refresh and Delete parameter (the prototype parameter actions)', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });
    await expect.element(paramTool('Delete parameter')).toBeDisabled();
    await expect.element(paramTool('Where Used')).toBeDisabled();

    await paramTool('Add parameter').click();
    await expect.element(names().nth(0)).toHaveFocus();
    await expect.element(paramTool('Where Used')).toBeDisabled();
    await userEvent.keyboard('zone');
    await paramTool('Add parameter').click();
    await userEvent.keyboard('date');

    await paramTool('Where Used').click();
    const dialog = page.getByRole('dialog', { name: 'Processing Log' });
    await expect.element(dialog).toMatchTextContent(/Parameter date is not in use/);
    await dialog.getByRole('contentinfo').getByRole('button', { name: 'Close' }).click();
    await expect.element(dialog).not.toBeInTheDocument();
    await expect.element(page.getByRole('heading', { name: 'Processing Log' })).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'Copy processing log to clipboard' })).toBeVisible();

    await paramTool('Refresh').click();
    await expect.element(names().nth(0)).toHaveValue('date');
    await expect.element(names().nth(1)).toHaveValue('zone');
    await expect.element(paramTool('Delete parameter')).toBeDisabled();
    await names().nth(1).click();
    await paramTool('Delete parameter').click();
    await expect.element(names()).toHaveLength(2);
    await expect.element(names().nth(0)).toHaveValue('date');
    // ADR-0028 (owner, 2026-11-10): a preview edit makes the studio unsaved
    await expect.element(button('Save')).toBeEnabled();
  });
});
