import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { TREE_FIELDS, TREE_MAPPING } from '../../api/fake/generated-tree';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { selectUnsaved, type WorkingCopyStore } from '../store/working-copy';
import { studioStore } from '../shell/Studio';
import { StudioShell } from '../shell/StudioShell';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const NOTE = `/${ORDER}/Note`;
const MAPPING = 'mappings/order-id-copy';

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
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

/** The studio shell on the Definition tab over a store the test holds, wired to `api` as the studio wires it. */
async function renderDefinition(
  options: { api?: ApiClient; mappingId?: string; browserState?: BrowserStateStore; editing?: boolean } = {},
): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const mappingId = options.mappingId ?? MAPPING;
  const store = studioStore(api, mappingId, await api.getMapping(mappingId));
  store.getState().setTab('definition');
  if (options.editing) store.getState().startEditing();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  render(
    <QueryClientProvider client={queryClient}>
      <StudioShell mappingId={mappingId} api={api} store={store} browserState={options.browserState ?? memoryBrowserState()} listHref="/" />
    </QueryClientProvider>,
  );
  return store;
}

const pane = (side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` });
const fields = (side: 'source' | 'target') => pane(side).getByRole('table', { name: `${side} fields` });
const field = (side: 'source' | 'target', name: string) => fields(side).getByRole('button', { name, exact: true });
const row = (side: 'source' | 'target', name: string) => fields(side).getByRole('row').filter({ has: page.getByRole('button', { name, exact: true }) });
const viewButton = (side: 'source' | 'target', mode: string) =>
  pane(side).getByRole('group', { name: `${side === 'source' ? 'Source' : 'Target'} message view` }).getByRole('button', { name: mode });
const tool = (side: 'source' | 'target', name: string) => pane(side).getByRole('toolbar', { name: `${side} tree tools` }).getByRole('button', { name, exact: true });

beforeEach(async () => {
  // the reference width: below 1000 px and 720 px the panes hide the Description and Type columns
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

describe('the Definition tab panes (#156)', () => {
  test('shows the source and target messages in the table view: Structure, Occurrences, Type and Description', async () => {
    await renderDefinition();

    await expect.element(pane('source').getByRole('heading', { level: 2, name: 'Order' })).toBeVisible();
    await expect.element(pane('source')).toMatchTextContent('Source message');
    await expect.element(pane('target')).toMatchTextContent('Target message');
    await expect.element(pane('source').getByText('order.xsd')).toBeVisible();
    await expect.element(viewButton('source', 'Table')).toHaveAttribute('aria-pressed', 'true');
    const headers = fields('target').getByRole('columnheader');
    await expect.element(headers).toHaveLength(4);
    for (const [i, name] of ['Structure', 'Occurrences', 'Type', 'Description'].entries()) {
      await expect.element(headers.nth(i)).toHaveTextContent(name);
    }
    await expect.element(row('target', 'Note').getByRole('cell').nth(1)).toHaveTextContent('0..unbounded');
    await expect.element(row('target', 'Note').getByRole('cell').nth(2)).toHaveTextContent('xsd:string');
    await expect.element(row('target', 'Quantity').getByRole('cell').nth(2)).toHaveTextContent('xsd:integer');
    await expect.element(row('target', '@channel')).toBeVisible();
  });

  test('marks the mapped target field and the source field the mapping reads as connected, and no other', async () => {
    await renderDefinition();

    await expect.element(row('target', 'OrderId').getByRole('img', { name: 'Connected' })).toBeVisible();
    await expect.element(row('source', 'OrderId').getByRole('img', { name: 'Connected' })).toBeVisible();
    await expect.element(row('target', 'Note').getByRole('img', { name: 'Connected' })).not.toBeInTheDocument();
    await expect.element(row('source', 'Note').getByRole('img', { name: 'Connected' })).not.toBeInTheDocument();
  });

  test('the tree view shows the Structure column only, and the view mode is kept per side in browser state', async () => {
    const browserState = memoryBrowserState();
    await renderDefinition({ browserState });

    await viewButton('target', 'Tree').click();

    await expect.element(fields('target').getByRole('columnheader')).toHaveLength(1);
    await expect.element(viewButton('target', 'Tree')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(fields('source').getByRole('columnheader')).toHaveLength(4);
    expect(browserState.values.get('user:studio:definition.target.view')).toBe('Tree');
  });

  test('opens each side in the view mode browser state keeps, and in the table view when it keeps none or a stray one', async () => {
    await renderDefinition({ browserState: memoryBrowserState({ 'user:studio:definition.source.view': 'Text', 'user:studio:definition.target.view': 'Graph' }) });

    await expect.element(viewButton('source', 'Text')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(viewButton('target', 'Table')).toHaveAttribute('aria-pressed', 'true');
  });

  test('the text view shows the XSD generated from the loaded structure', async () => {
    await renderDefinition();

    await viewButton('source', 'Text').click();

    const xsd = pane('source').getByLabelText('Source XSD source');
    await expect.element(xsd).toMatchTextContent(/<xsd:schema xmlns:xsd="http:\/\/www.w3.org\/2001\/XMLSchema">/);
    await expect.element(xsd.getByRole('button', { name: '<xsd:element name="OrderId" type="xsd:string"/>' })).toBeVisible();
    await expect.element(xsd.getByRole('button', { name: '<xsd:element name="Note" type="xsd:string" minOccurs="0" maxOccurs="unbounded"/>' })).toBeVisible();
    await expect.element(xsd.getByRole('button', { name: '<xsd:attribute name="channel" type="xsd:string"/>' })).toBeVisible();
    await expect.element(fields('source')).not.toBeInTheDocument();
  });

  test('selecting a target row selects its target field mapping; a source row selects only in its own pane', async () => {
    const store = await renderDefinition();

    await field('target', 'OrderId').click();

    expect(store.getState().selectedTarget).toBe(ORDER_ID);
    await expect.element(field('target', 'OrderId')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(page.getByRole('toolbar', { name: 'Mapping expression' })).toMatchTextContent('/Order/OrderId');
    await field('source', 'Note').click();
    expect(store.getState().selectedTarget).toBe(ORDER_ID);
    await expect.element(field('source', 'Note')).toHaveAttribute('aria-pressed', 'true');
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('the mapping expression area asks for a target field while none is selected', async () => {
    await renderDefinition();

    await expect.element(page.getByRole('region', { name: 'Field mapping canvas' })).toHaveTextContent('Select a target field to see its mapping expression.');
  });

  test('in edit mode a source row dropped on a target row adds a source-field box to its mapping and selects it', async () => {
    const store = await renderDefinition({ editing: true });

    await userEvent.dragAndDrop(row('source', 'Note'), row('target', 'Note'));

    const mapping = store.getState().workingCopy.targetFieldMappings?.[NOTE];
    expect(Object.values(mapping?.boxes ?? {})).toEqual([{ kind: 'source', field: NOTE }]);
    expect(store.getState().selectedTarget).toBe(NOTE);
    expect(selectUnsaved(store.getState())).toBe(true);
    await expect.element(row('target', 'Note').getByRole('img', { name: 'Connected' })).toBeVisible();
  });

  test('in edit mode a double click on a source row adds its box to the selected target field mapping', async () => {
    const store = await renderDefinition({ editing: true });
    await field('target', 'Note').click();

    await field('source', 'Quantity').dblClick();

    const boxes = Object.values(store.getState().workingCopy.targetFieldMappings?.[NOTE]?.boxes ?? {});
    expect(boxes).toEqual([{ kind: 'source', field: `/${ORDER}/Quantity` }]);
  });

  test('in edit mode a double click on a source row with no target field selected changes nothing', async () => {
    const store = await renderDefinition({ editing: true });

    await field('source', 'Quantity').dblClick();

    await expect.element(field('source', 'Quantity')).toHaveAttribute('aria-pressed', 'true');
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
  });

  test('a refused field refuses the drop: no box is added and the store records why', async () => {
    const fake = fakeApiClient();
    const refused = { code: 'unsupported-construct', side: 'target' as const, file: 'order.xsd', line: 3, column: 1 };
    const structure = await fake.loadStructure({ base: 'mappings', bundle: ['../structures/skeleton/order.xsd'], root: { element: ORDER } });
    const withRefusal = structure.kind === 'loaded' ? { ...structure, types: { [structure.root.type!]: structure.types[structure.root.type!]!.map((f) => (f.step === 'Note' ? { ...f, refusal: refused } : f)) } } : structure;
    const store = await renderDefinition({ api: { ...fake, loadStructure: async () => withRefusal }, editing: true });

    await userEvent.dragAndDrop(row('source', 'Quantity'), row('target', 'Note'));

    await expect.poll(() => store.getState().refusal).not.toBeNull();
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
  });

  test('in view mode the rows are not draggable, and a drop or a double click changes nothing', async () => {
    const store = await renderDefinition();
    await field('target', 'Note').click();

    await expect.element(row('source', 'Note')).not.toHaveAttribute('draggable', 'true');
    await userEvent.dragAndDrop(row('source', 'Note'), row('target', 'Note'));
    await field('source', 'Quantity').dblClick();

    expect(store.getState().workingCopy).toBe(store.getState().baseline);
  });

  test('the 20,128-field structure opens fully expanded and renders fewer than 50 rows in the DOM', async () => {
    await renderDefinition({ mappingId: TREE_MAPPING });

    await viewButton('source', 'Tree').click();
    await expect.element(field('source', 'Tree')).toBeVisible();
    const scroll = document.querySelector('#source-message-pane .fields-scroll')!;
    // every field has a row of at least 20 px: the scroll height holds all 20,128 of them
    expect(scroll.scrollHeight).toBeGreaterThanOrEqual(TREE_FIELDS * 20);
    const rendered = () => document.querySelectorAll('#source-message-pane tbody tr').length;
    expect(rendered()).toBeGreaterThan(5);
    expect(rendered()).toBeLessThan(50);
    scroll.scrollTop = scroll.scrollHeight;
    // scrolled to the end, the root's row has left the DOM
    await expect.element(field('source', 'Tree')).not.toBeInTheDocument();
    expect(rendered()).toBeLessThan(50);
    expect(document.querySelectorAll('#target-message-pane tbody tr').length).toBeLessThan(50);
  });

  test('Expand Tree and Collapse Tree, and a row caret, open and close the tree', async () => {
    await renderDefinition();

    await tool('source', 'Expand Tree').click();
    await tool('source', 'Collapse Tree').click();
    await expect.element(fields('source').getByRole('row')).toHaveLength(2);
    await fields('source').getByRole('button', { name: 'Expand Order' }).click();
    await expect.element(field('source', 'OrderId')).toBeVisible();
    await expect.element(tool('source', 'Expand Tree')).toBeVisible();
    await fields('source').getByRole('button', { name: 'Collapse Order' }).click();
    await expect.element(field('source', 'OrderId')).not.toBeInTheDocument();
  });

  test('the search shows the matching fields and their ancestors; Escape closes it and shows all again', async () => {
    await renderDefinition();

    await tool('target', 'Open Search').click();
    await pane('target').getByRole('textbox', { name: 'Filter target fields' }).fill('date');

    await expect.element(field('target', 'DeliveryDate')).toBeVisible();
    await expect.element(field('target', 'Order')).toBeVisible();
    await expect.element(field('target', 'OrderId')).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await expect.element(field('target', 'OrderId')).toBeVisible();
    await expect.element(pane('target').getByRole('textbox', { name: 'Filter target fields' })).not.toBeInTheDocument();
  });

  test('Enter in the search selects the first matching field', async () => {
    const store = await renderDefinition();

    await tool('target', 'Open Search').click();
    await pane('target').getByRole('textbox', { name: 'Filter target fields' }).fill('note');
    await userEvent.keyboard('{Enter}');

    expect(store.getState().selectedTarget).toBe(NOTE);
  });

  test('a search with no match says so', async () => {
    await renderDefinition();

    await tool('source', 'Open Search').click();
    await pane('source').getByRole('textbox', { name: 'Filter source fields' }).fill('nothing-like-this');

    await expect.element(pane('source').getByText('No matching fields.')).toBeVisible();
  });

  test('the search marks the matching lines of the text view', async () => {
    await renderDefinition();
    await viewButton('source', 'Text').click();

    await tool('source', 'Open Search').click();
    await pane('source').getByRole('textbox', { name: 'Filter source fields' }).fill('Quantity');

    const marked = document.querySelectorAll('#source-message-pane .xml-line.find-match');
    expect([...marked].map((line) => line.textContent)).toEqual(['        <xsd:element name="Quantity" type="xsd:integer" minOccurs="0"/>']);
  });

  test('Filter Tree shows the mapped fields only, and its options the unmapped, direct and indirect ones', async () => {
    await renderDefinition();

    await tool('target', 'Filter Tree').click();
    await expect.element(field('target', 'OrderId')).toBeVisible();
    await expect.element(field('target', 'Note')).not.toBeInTheDocument();

    await tool('target', 'Filter Tree options').click();
    await page.getByRole('menuitem', { name: 'Show Unmapped Fields Only' }).click();
    await expect.element(field('target', 'Note')).toBeVisible();
    await expect.element(field('target', 'OrderId')).not.toBeInTheDocument();

    await tool('target', 'Filter Tree options').click();
    await page.getByRole('menuitem', { name: 'Show Fields Mapped Directly Only' }).click();
    await expect.element(field('target', 'OrderId')).toBeVisible();

    await tool('target', 'Filter Tree options').click();
    await page.getByRole('menuitem', { name: 'Show Fields Mapped Indirectly Only' }).click();
    await expect.element(pane('target').getByText('No matching fields.')).toBeVisible();

    await tool('target', 'Filter Tree options').click();
    await page.getByRole('menuitem', { name: 'Switch Off' }).click();
    await expect.element(field('target', 'Note')).toBeVisible();
    await expect.element(field('target', 'OrderId')).toBeVisible();
  });

  test('the source filter offers mapped and unmapped only', async () => {
    await renderDefinition();

    await tool('source', 'Filter Tree options').click();

    await expect.element(page.getByRole('menuitem', { name: 'Show Unmapped Fields Only' })).toBeVisible();
    await expect.element(page.getByRole('menuitem', { name: 'Show Fields Mapped Directly Only' })).not.toBeInTheDocument();
    await page.getByRole('menuitem', { name: 'Switch On' }).click();
    await expect.element(field('source', 'OrderId')).toBeVisible();
    await expect.element(field('source', 'Note')).not.toBeInTheDocument();
  });

  test('Show Full Path shows the selected field path below the rows, a bare / while none is selected', async () => {
    await renderDefinition();

    await tool('target', 'Show Full Path').click();
    await expect.element(pane('target').getByText('/', { exact: true })).toBeVisible();
    await field('target', 'Note').click();

    await expect.element(pane('target').getByText('/Order/Note', { exact: true })).toBeVisible();
    await expect.element(tool('target', 'Show Full Path')).toHaveAttribute('aria-pressed', 'true');
    await tool('target', 'Show Full Path').click();
    await expect.element(pane('target').getByText('/Order/Note', { exact: true })).not.toBeInTheDocument();
  });

  test('Show Mapping Column switches to the table and shows each target field mapping as text', async () => {
    await renderDefinition({ browserState: memoryBrowserState({ 'user:studio:definition.target.view': 'Tree' }) });

    await tool('target', 'Show Mapping Column').click();

    await expect.element(viewButton('target', 'Table')).toHaveAttribute('aria-pressed', 'true');
    await expect.element(row('target', 'OrderId').getByRole('cell').nth(1)).toHaveTextContent('/Order/OrderId');
    await expect.element(row('target', 'Note').getByRole('cell').nth(1)).toHaveTextContent('No mapping for /Order/Note');
  });

  test('the selection column selects fields, and Show Selected Fields shows those only', async () => {
    await renderDefinition();

    await tool('target', 'Show Selection Column').click();
    await row('target', 'Note').getByRole('switch', { name: 'Select /Order/Note' }).click();
    await tool('target', 'Filter Tree options').click();
    await page.getByRole('menuitem', { name: 'Show Selected Fields' }).click();

    await expect.element(field('target', 'Note')).toBeVisible();
    await expect.element(field('target', 'OrderId')).not.toBeInTheDocument();
  });

  test('the selection column options select all, none and the inverse of the visible fields', async () => {
    await renderDefinition();
    const selected = () => fields('target').getByRole('switch', { checked: true });

    await tool('target', 'Selection column options').click();
    await page.getByRole('menuitem', { name: 'Select All' }).click();
    await expect.element(selected()).toHaveLength(10);
    await row('target', 'Note').getByRole('switch').click();
    await tool('target', 'Selection column options').click();
    await page.getByRole('menuitem', { name: 'Inverse Selection' }).click();
    await expect.element(selected()).toHaveLength(1);
    await tool('target', 'Selection column options').click();
    await page.getByRole('menuitem', { name: 'Select None' }).click();
    await expect.element(selected()).toHaveLength(0);
    await tool('target', 'Selection column options').click();
    await page.getByRole('menuitem', { name: 'Hide Selection Column' }).click();
    await expect.element(fields('target').getByRole('switch')).toHaveLength(0);
  });

  test('Save Document Source downloads the XSD text', async () => {
    const clicked: { download: string; href: string }[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ download: this.download, href: this.href });
    });
    try {
      await renderDefinition();

      await tool('source', 'Save Document Source').click();

      expect(clicked).toEqual([{ download: 'order.xsd', href: expect.stringMatching(/^blob:/) }]);
    } finally {
      click.mockRestore();
    }
  });

  test('the message format leads to the Signature tab in edit mode and is disabled in view mode', async () => {
    const store = await renderDefinition();
    await expect.element(pane('source').getByRole('button', { name: 'Change source message' })).toBeDisabled();

    store.getState().startEditing();
    await pane('source').getByRole('button', { name: 'Change source message' }).click();

    await expect.poll(() => store.getState().tab).toBe('signature');
  });

  test('a JSON message shows its format as JSON and its value types as the service names them', async () => {
    const order = (await fakeApiClient().getMapping(MAPPING)).model;
    const json = { ...order, source: { ...order.source!, root: { json: '$' as const } }, target: { ...order.target!, root: { json: '$' as const } } };
    await renderDefinition({ api: fakeApiClient({ [MAPPING]: json }) });

    await expect.element(pane('source').getByRole('button', { name: 'Change source message' })).toHaveTextContent('JSON');
    await expect.element(row('source', 'Note').getByRole('cell').nth(2)).toHaveTextContent('xs:string');
  });

  test('a splitter dragged with the pointer keeps its new share', async () => {
    const browserState = memoryBrowserState();
    await renderDefinition({ browserState });
    const sides = page.getByRole('separator', { name: 'Resize source and target panels' });
    await expect.element(field('target', 'Note')).toBeVisible();

    await userEvent.dragAndDrop(sides, pane('target').getByRole('heading', { level: 2 }));

    await expect.poll(() => browserState.values.get('user:studio:definition.width')).toBeGreaterThan(0.5);
  });

  test('the splitters resize with the keyboard and keep their share in browser state', async () => {
    const browserState = memoryBrowserState();
    await renderDefinition({ browserState });

    const sides = page.getByRole('separator', { name: 'Resize source and target panels' });
    await expect.element(sides).toHaveAttribute('aria-valuenow', '50');
    (sides.element() as HTMLElement).focus();
    await userEvent.keyboard('{ArrowRight}');
    await expect.element(sides).toHaveAttribute('aria-valuenow', '55');
    expect(browserState.values.get('user:studio:definition.width')).toBeCloseTo(0.55);

    const halves = page.getByRole('separator', { name: 'Resize mapping area' });
    await expect.element(halves).toHaveAttribute('aria-valuenow', '40');
    (halves.element() as HTMLElement).focus();
    await userEvent.keyboard('{End}');
    await expect.element(halves).toHaveAttribute('aria-valuenow', '100');
    expect(browserState.values.get('user:studio:definition.layout')).toEqual({ view: 'split', share: 1 });
  });

  test('a structure the service refuses shows its diagnostics in the pane', async () => {
    const fake = fakeApiClient();
    await renderDefinition({
      api: {
        ...fake,
        loadStructure: async () => ({
          kind: 'refused',
          diagnostics: [{ severity: 'error', code: 'schema-unreadable', message: 'no such file', location: { kind: 'file-position', file: 'x.xsd', line: 1, column: 1 } }],
        }),
      },
    });

    await expect.element(pane('source').getByRole('list', { name: 'Source message diagnostics' })).toHaveTextContent('error schema-unreadable: no such file');
  });

  test('a structure that fails to load shows the error in the pane', async () => {
    const fake = fakeApiClient();
    await renderDefinition({ api: { ...fake, loadStructure: () => Promise.reject(new Error('service down')) } });

    await expect.element(pane('target').getByRole('alert')).toHaveTextContent('service down');
  });
});

describe('the orphaned target field mappings (GLOSSARY: Orphaned target field mapping, #174)', () => {
  const MISSING = `/${ORDER}/Missing`;
  /** order-id-copy with a second target field mapping, at Missing, a target field the order structure lacks. */
  async function orphanedTargetApi(): Promise<ApiClient> {
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.targetFieldMappings![MISSING] = { id: 't2', result: 'a2', boxes: { a2: { kind: 'source', field: ORDER_ID } } };
    return fakeApiClient({ [MAPPING]: model });
  }
  const orphans = () => pane('target').getByRole('region', { name: 'Orphaned target field mappings' });
  const open = () => orphans().getByRole('button', { name: 'Open /Order/Missing' });
  const remove = () => orphans().getByRole('button', { name: 'Delete /Order/Missing' });
  const canvasPanel = () => page.getByRole('region', { name: 'Field mapping canvas' });

  test('a mapping whose target field mappings all have their rows shows no group', async () => {
    await renderDefinition();

    await expect.element(field('target', 'OrderId')).toBeVisible();
    await expect.element(orphans()).not.toBeInTheDocument();
  });

  test('are listed in a group of their own in the target pane; Open shows the expression on the canvas', async () => {
    const store = await renderDefinition({ api: await orphanedTargetApi() });

    await expect.element(orphans()).toMatchTextContent('/Order/Missing');
    await expect.element(orphans().getByRole('listitem')).toHaveLength(1);
    await expect.element(field('target', 'Missing')).not.toBeInTheDocument();
    await expect.element(pane('source').getByRole('region', { name: 'Orphaned target field mappings' })).not.toBeInTheDocument();
    await expect.element(open()).toHaveAttribute('aria-pressed', 'false');

    await open().click();

    expect(store.getState().selectedTarget).toBe(MISSING);
    await expect.element(open()).toHaveAttribute('aria-pressed', 'true');
    await expect.element(page.getByRole('toolbar', { name: 'Mapping expression' })).toMatchTextContent('/Order/Missing');
    await expect.element(page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name: /^Source field OrderId\. Complete/ })).toBeVisible();
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('in view mode Delete is disabled', async () => {
    await renderDefinition({ api: await orphanedTargetApi() });

    await expect.element(remove()).toBeDisabled();
  });

  test('Delete in edit mode removes it with its boxes as one undo step, and the canvas it showed asks for a target field', async () => {
    const store = await renderDefinition({ api: await orphanedTargetApi(), editing: true });
    await open().click();

    await remove().click();

    expect(store.getState().workingCopy.targetFieldMappings?.[MISSING]).toBeUndefined();
    expect(Object.keys(store.getState().workingCopy.targetFieldMappings ?? {})).toEqual([ORDER_ID]);
    expect(store.getState().past).toHaveLength(1);
    expect(store.getState().selectedTarget).toBeNull();
    await expect.element(orphans()).not.toBeInTheDocument();
    await expect.element(canvasPanel()).toHaveTextContent('Select a target field to see its mapping expression.');

    store.getState().undo();

    await expect.element(orphans()).toMatchTextContent('/Order/Missing');
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('Delete leaves another selected target field selected', async () => {
    const store = await renderDefinition({ api: await orphanedTargetApi(), editing: true });
    await field('target', 'OrderId').click();

    await remove().click();

    await expect.element(orphans()).not.toBeInTheDocument();
    expect(store.getState().selectedTarget).toBe(ORDER_ID);
    await expect.element(page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name: /^Target field OrderId\. Complete/ })).toBeVisible();
  });
});

describe('the store refusals (#174)', () => {
  const refused = { code: 'unsupported-construct', side: 'target' as const, file: 'order.xsd', line: 3, column: 1 };
  /** The fake ApiClient with the order structure's Note refused, on both sides, as a field refusal marks it (ADR-0027). */
  async function refusedNoteApi(): Promise<ApiClient> {
    const fake = fakeApiClient();
    const structure = await fake.loadStructure({ base: 'mappings', bundle: ['../structures/skeleton/order.xsd'], root: { element: ORDER } });
    if (structure.kind !== 'loaded') throw new Error('the fake order structure did not load');
    const type = structure.root.type!;
    const withRefusal = { ...structure, types: { ...structure.types, [type]: structure.types[type]!.map((f) => (f.step === 'Note' ? { ...f, refusal: refused } : f)) } };
    return { ...fake, loadStructure: async () => withRefusal };
  }
  const refusalAlert = () => page.getByRole('heading', { name: /The target field \/Q\{urn:flottila:corpus:skeleton\}Order\/Note is refused: unsupported-construct, declared at order.xsd:3:1/ });
  const dismiss = () => page.getByRole('button', { name: 'Dismiss refusal' });

  test('a refused drop shows why in a warning alert, which the author dismisses; the next refusal shows again', async () => {
    const store = await renderDefinition({ api: await refusedNoteApi(), editing: true });

    await userEvent.dragAndDrop(row('source', 'Quantity'), row('target', 'Note'));

    await expect.element(refusalAlert()).toBeVisible();
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
    await dismiss().click();
    await expect.element(refusalAlert()).not.toBeInTheDocument();

    await userEvent.dragAndDrop(row('source', 'Quantity'), row('target', 'Note'));

    await expect.element(refusalAlert()).toBeVisible();
  });

  test('a command that applies clears the alert', async () => {
    const store = await renderDefinition({ api: await refusedNoteApi(), editing: true });
    await userEvent.dragAndDrop(row('source', 'Quantity'), row('target', 'Note'));
    await expect.element(refusalAlert()).toBeVisible();

    await userEvent.dragAndDrop(row('source', 'Quantity'), row('target', 'Quantity'));

    await expect.poll(() => store.getState().refusal).toBeNull();
    await expect.element(refusalAlert()).not.toBeInTheDocument();
  });
});

describe('the definition bar, the expression header and the field menus (#157)', () => {
  const bar = (name: string) => page.getByRole('toolbar', { name: 'Definition actions' }).getByRole('button', { name, exact: true });
  const header = (name: string) => page.getByRole('toolbar', { name: 'Mapping expression' }).getByRole('button', { name, exact: true });
  const menuItem = (name: string) => page.getByRole('menuitem', { name, exact: true });

  test('Maximize and Minimize hide one area, Restore split view and Escape bring both back, kept in browser state', async () => {
    const browserState = memoryBrowserState();
    await renderDefinition({ browserState });

    await header('Maximize mapping expression').click();
    // a hidden area leaves the accessibility tree
    await expect.element(pane('source')).not.toBeInTheDocument();
    expect(browserState.values.get('user:studio:definition.layout')).toMatchObject({ view: 'maximized' });
    await header('Restore split view').click();
    await expect.element(pane('source')).toBeVisible();
    await bar('Maximize message trees').click();
    await expect.element(page.getByRole('region', { name: 'Field mapping canvas' })).not.toBeInTheDocument();
    await bar('Restore split view').click();
    await header('Minimize mapping expression').click();
    await expect.element(page.getByRole('region', { name: 'Field mapping canvas' })).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByRole('region', { name: 'Field mapping canvas' })).toBeVisible();
  });

  test('Text Preview lists each mapped target field with its expression; a click opens it in the data flow editor', async () => {
    const store = await renderDefinition();

    await bar('Text Preview').click();
    const line = page.getByRole('region', { name: 'Text Preview' }).getByRole('button', { name: '/Order/OrderId = /Order/OrderId' });
    await line.click();

    expect(store.getState().selectedTarget).toBe(ORDER_ID);
    await expect.element(bar('Data Flow Editor')).toHaveAttribute('aria-pressed', 'true');
  });

  test('Text Preview says when nothing is mapped', async () => {
    await renderDefinition({ mappingId: 'mappings/order-unmapped' });

    await bar('Text Preview').click();

    await expect.element(page.getByRole('region', { name: 'Text Preview' })).toMatchTextContent('No mapped fields.');
  });

  test('Clear Mapping asks, then clears every expression as one undo step; Cancel keeps them', async () => {
    const store = await renderDefinition({ editing: true });

    await bar('Clear Mapping').click();
    await page.getByRole('dialog', { name: 'Clear Mapping' }).getByRole('button', { name: 'Cancel' }).click();
    expect(selectUnsaved(store.getState())).toBe(false);
    await bar('Clear Mapping').click();
    await page.getByRole('button', { name: 'Clear mapping' }).click();

    expect(store.getState().workingCopy.targetFieldMappings).toBeUndefined();
    expect(store.getState().past).toHaveLength(1);
  });

  test('Select Message leads to the Signature tab; in view mode the editing tools are disabled', async () => {
    const store = await renderDefinition();
    for (const name of ['Select Message', 'Clear Mapping', 'Map Selected Fields and Substructures if Names Are Identical']) await expect.element(bar(name)).toBeDisabled();
    store.getState().startEditing();

    await bar('Select Message').click();
    await menuItem('Target Message').click();

    await expect.poll(() => store.getState().tab).toBe('signature');
  });

  test('Dependencies shows the field connections into the selected target field; a connection selects it; Hide All hides them', async () => {
    const browserState = memoryBrowserState();
    const store = await renderDefinition({ browserState });
    store.getState().selectTarget(ORDER_ID);

    await bar('Dependencies').click();
    const link = page.getByRole('group', { name: 'Field connections' }).getByRole('button', { name: 'OrderId → OrderId' });
    await expect.element(link).toHaveAttribute('aria-pressed', 'true');
    expect(browserState.values.get(`user:studio:definition.${MAPPING}.dependencies`)).toEqual({ visible: true, targets: [ORDER_ID] });
    // the two OrderId rows are level: the curve is a flat line, which has no box to click into
    store.getState().selectTarget(null);
    link.element().dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(store.getState().selectedTarget).toBe(ORDER_ID);
    store.getState().selectTarget(null);
    (link.element() as SVGGElement).focus();
    await userEvent.keyboard('{Tab}');
    expect(store.getState().selectedTarget).toBeNull();
    (link.element() as SVGGElement).focus();
    await userEvent.keyboard('{Enter}');
    expect(store.getState().selectedTarget).toBe(ORDER_ID);

    await bar('Dependencies options').click();
    await menuItem('Hide All').click();
    await expect.element(page.getByRole('group', { name: 'Field connections' })).not.toBeInTheDocument();
    await expect.element(page.getByRole('separator', { name: 'Resize source and target panels' })).toBeVisible();
  });

  test('the field menu of a source field: Where Used selects the target field that reads it; Dependencies Show All', async () => {
    const store = await renderDefinition();

    await field('source', 'OrderId').click({ button: 'right' });
    await menuItem('Where Used').click();
    await menuItem('/Order/OrderId').click();
    expect(store.getState().selectedTarget).toBe(ORDER_ID);

    await field('target', 'OrderId').click({ button: 'right' });
    await expect.element(menuItem('Add Variable')).toBeDisabled();
    await menuItem('Dependencies').click();
    await menuItem('Show All').click();
    await expect.element(page.getByRole('group', { name: 'Field connections' }).getByRole('button', { name: 'OrderId → OrderId' })).toBeInTheDocument();
  });
});
