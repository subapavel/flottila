import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient } from '../../../api/client';
import { fakeApiClient } from '../../../api/fake/fake-api';
import type { BrowserStateStore } from '../../browser-state/browser-state';
import { selectUnsaved, type WorkingCopyStore } from '../../store/working-copy';
import type { AuthoringId, TargetFieldMapping } from '../../../generated/mapping-model';
import { studioStore } from '../../shell/Studio';
import { StudioShell } from '../../shell/StudioShell';
import { expressionGraph } from './expression-graph';
import { arrange, layoutOf } from './expression-layout';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const NOTE = `/${ORDER}/Note`;
const MAPPING = 'mappings/order-id-copy';

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

/** The studio on the Definition tab with OrderId's mapping selected, over a store the test holds. */
async function renderCanvas(options: { api?: ApiClient; editing?: boolean; target?: string } = {}): Promise<WorkingCopyStore> {
  const api = options.api ?? fakeApiClient();
  const store = studioStore(api, MAPPING, await api.getMapping(MAPPING));
  store.getState().setTab('definition');
  store.getState().selectTarget(options.target ?? ORDER_ID);
  if (options.editing) store.getState().startEditing();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(
    <QueryClientProvider client={queryClient}>
      <StudioShell mappingId={MAPPING} api={api} store={store} browserState={memoryBrowserState()} listHref="/" />
    </QueryClientProvider>,
  );
  return store;
}

const mappingOf = (store: WorkingCopyStore, target = ORDER_ID): TargetFieldMapping => store.getState().workingCopy.targetFieldMappings![target]!;
const boxOf = (store: WorkingCopyStore, fn: string): AuthoringId =>
  Object.entries(mappingOf(store).boxes).find(([, box]) => box.kind === 'function' && box.fn === fn)![0];
const canvas = () => page.getByRole('group', { name: 'Mapping canvas' });
const node = (name: string | RegExp) => canvas().getByRole('group', { name });
const rail = (name: string) => page.getByRole('toolbar', { name: 'Mapping tools' }).getByRole('button', { name, exact: true });
const catalogue = () => page.getByRole('region', { name: 'Function catalogue' });
const menuItem = (name: string) => page.getByRole('menuitem', { name, exact: true });
const targetRow = (name: string) =>
  page.getByRole('table', { name: 'target fields' }).getByRole('row').filter({ has: page.getByRole('button', { name, exact: true }) });
const footer = () => page.getByRole('region', { name: 'Field mapping canvas' }).getByText(/nodes · /);
const integers = (layout: TargetFieldMapping['layout']) =>
  Object.values({ ...layout?.boxes, ...(layout?.target && { t: layout.target }) }).every(({ x, y }) => Number.isInteger(x) && Number.isInteger(y));

async function addFromCatalogue(group: string, fn: string) {
  const toggle = catalogue().getByRole('button', { name: new RegExp(`^${group}`) });
  if ((await toggle.element()).getAttribute('aria-expanded') !== 'true') await toggle.click();
  await catalogue().getByRole('button', { name: new RegExp(`^${fn}\\b`) }).first().click();
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
  // every test fails on console.error (ADR-0013)
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the mapping expression canvas (#157)', () => {
  test('shows the selected target field mapping, laid out in memory: a file without a layout stays saved', async () => {
    const store = await renderCanvas();

    await expect.element(page.getByRole('toolbar', { name: 'Mapping expression' })).toMatchTextContent('/Order/OrderId');
    await expect.element(node(/^Source field OrderId\. Complete/)).toBeVisible();
    await expect.element(node(/^Target field OrderId\. Complete/)).toBeVisible();
    await expect.element(footer()).toHaveTextContent('2 nodes · 1 connections');
    expect(mappingOf(store).layout).toBeUndefined();
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('says what to do while no target field is selected', async () => {
    const store = await renderCanvas();
    store.getState().selectTarget(null);

    await expect.element(page.getByText('Select a target field to see its mapping expression.')).toBeVisible();
  });

  test('a target field without a mapping shows its target alone', async () => {
    await renderCanvas({ target: NOTE });

    await expect.element(node(/^Target field Note\. Incomplete/)).toBeVisible();
    await expect.element(footer()).toHaveTextContent('1 nodes · 0 connections');
  });

  test('in view mode the editing tools are disabled and nodes do not move', async () => {
    const store = await renderCanvas();

    for (const name of ['Add node', 'Delete Floating Objects', 'Layout mapping', 'Paste']) await expect.element(rail(name)).toBeDisabled();
    await catalogue().getByRole('button', { name: /^Text/ }).click();
    await expect.element(catalogue().getByRole('button', { name: /^concat\b/ })).toBeDisabled();
    await userEvent.dragAndDrop(node(/^Source field OrderId/), node(/^Target field OrderId/), { steps: 5 });
    expect(mappingOf(store).layout).toBeUndefined();
  });

  test('add-function: a click in the drilldown adds the box as one undo step, the shown layout written in integers', async () => {
    const store = await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId/)).toBeVisible();

    await addFromCatalogue('Text', 'concat');

    await expect.element(node(/^Node function concat\. Detached/)).toBeVisible();
    const mapping = mappingOf(store);
    expect(mapping.boxes[boxOf(store, 'concat')]).toEqual({ kind: 'function', fn: 'concat' });
    expect(Object.keys(mapping.layout!.boxes!).sort()).toEqual(['a1', boxOf(store, 'concat')].sort());
    expect(integers(mapping.layout)).toBe(true);
    expect(store.getState().past).toHaveLength(1);
    await expect.element(footer()).toHaveTextContent('3 nodes · 1 connections');
  });

  test('a function dragged from the drilldown onto the canvas is added where it is dropped', async () => {
    const store = await renderCanvas({ editing: true });
    await catalogue().getByRole('button', { name: /^Text/ }).click();

    await userEvent.dragAndDrop(catalogue().getByRole('button', { name: /^concat\b/ }), page.getByTestId('rf__wrapper'), { targetPosition: { x: 60, y: 40 } });

    await expect.element(node(/^Node function concat/)).toBeVisible();
    expect(integers(mappingOf(store).layout)).toBe(true);
    expect(store.getState().past).toHaveLength(1);
  });

  test('wire: Connect to in the node menu wires the source field into an input of the function', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    const concat = boxOf(store, 'concat');

    await node(/^Source field OrderId/).click({ button: 'right' });
    await menuItem('Connect to').click();
    await menuItem('concat · a').click();

    expect(mappingOf(store).boxes[concat]).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1'] });
    await expect.element(footer()).toHaveTextContent('3 nodes · 2 connections');
    await expect.element(node(/^Node function concat\. Detached/)).toBeVisible();
  });

  test('an occupied input is replaced: the function connected to the target takes the place of the source field', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    const concat = boxOf(store, 'concat');

    await node(/^Node function concat/).click({ button: 'right' });
    await menuItem('Connect to').click();
    await menuItem('OrderId · OrderId').click();

    expect(mappingOf(store).result).toBe(concat);
    await expect.element(node(/^Source field OrderId\. Detached/)).toBeVisible();
    await expect.element(footer()).toHaveTextContent('3 nodes · 1 connections');
  });

  test('a wire that would close a cycle is not offered, nor one the input already has', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    store.getState().insertBox(ORDER_ID, 'toUpperCase');
    const concat = boxOf(store, 'concat');
    const upper = boxOf(store, 'toUpperCase');
    store.getState().wire(ORDER_ID, concat, upper, 0);

    await node(/^Node function toUpperCase/).click({ button: 'right' });
    await menuItem('Connect to').click();

    await expect.element(menuItem('OrderId · OrderId')).toBeVisible();
    await expect.element(page.getByRole('menuitem', { name: /^concat · / })).not.toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    await node(/^Node function concat/).click({ button: 'right' });
    await menuItem('Connect to').click();
    await expect.element(page.getByRole('menuitem', { name: /^toUpperCase · / })).not.toBeInTheDocument();
  });

  test('Disconnect in the menus: the node loses its wires, a wire is removed, each one undo step', async () => {
    const store = await renderCanvas({ editing: true });

    await node(/^Source field OrderId/).click({ button: 'right' });
    await menuItem('Disconnect').click();

    expect(mappingOf(store).result).toBeNull();
    expect(store.getState().past).toHaveLength(1);
    store.getState().undo();
    await expect.element(footer()).toHaveTextContent('2 nodes · 1 connections');
    await canvas().getByRole('group', { name: /^Edge from a1/ }).click({ button: 'right' });
    await menuItem('Disconnect').click();
    expect(mappingOf(store).result).toBeNull();
  });

  test('Delete in the node menu deletes the box and its wires; the target cannot be deleted', async () => {
    const store = await renderCanvas({ editing: true });

    await node(/^Target field OrderId/).click({ button: 'right' });
    await expect.element(menuItem('Delete')).toBeDisabled();
    await userEvent.keyboard('{Escape}');
    await node(/^Source field OrderId/).click({ button: 'right' });
    await menuItem('Delete').click();

    // the delete box command drops a mapping left without boxes
    expect(store.getState().workingCopy.targetFieldMappings).toBeUndefined();
    await expect.element(footer()).toHaveTextContent('1 nodes · 0 connections');
  });

  test('parameter edit: the gear opens the parameters, Apply saves them all as one undo step', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'substring');
    const substring = boxOf(store, 'substring');
    const before = store.getState().past.length;

    await node(/^Node function substring/).getByRole('button', { name: 'Parameters for substring' }).click();
    const dialog = page.getByRole('dialog', { name: 'Function parameters · substring' });
    await dialog.getByLabelText('Start Position').fill('1.5');
    await dialog.getByRole('button', { name: 'Apply' }).click();
    await expect.element(dialog.getByRole('alert')).toHaveTextContent('Start Position must be an integer.');
    await dialog.getByLabelText('Start Position').fill('2');
    await dialog.getByLabelText('Character Count').fill('3');
    await dialog.getByRole('button', { name: 'Apply' }).click();

    await expect.element(dialog).not.toBeInTheDocument();
    expect(mappingOf(store).boxes[substring]).toMatchObject({ params: { start: '2', length: '3' } });
    expect(store.getState().past).toHaveLength(before + 1);
    await expect.element(node(/^Node function substring/)).toMatchTextContent('start 2 · length 3');
  });

  test('in view mode the parameters only show, with Close', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'substring');
    store.getState().discard();
    store.getState().insertBox(ORDER_ID, 'substring');
    store.setState({ editing: false });

    await node(/^Node function substring/).getByRole('button', { name: 'Parameters for substring' }).click();
    const dialog = page.getByRole('dialog', { name: 'Function parameters · substring' });
    await expect.element(dialog.getByLabelText('Start Position')).toBeDisabled();
    await expect.element(dialog.getByRole('button', { name: 'Apply' })).not.toBeInTheDocument();
    await dialog.getByText('Close', { exact: true }).click();
    await expect.element(dialog).not.toBeInTheDocument();
  });

  test('drag: a moved node writes an integer layout and the mapping becomes unsaved', async () => {
    const store = await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId/)).toBeVisible();

    await userEvent.dragAndDrop(node(/^Source field OrderId/), node(/^Target field OrderId/), { steps: 5 });

    const layout = mappingOf(store).layout!;
    const shown = arrange(expressionGraph(ORDER_ID, mappingOf(store), {}));
    expect(layout.boxes!.a1).not.toEqual(shown.a1);
    expect(layout.target).toEqual(shown[':target']);
    expect(integers(layout)).toBe(true);
    expect(selectUnsaved(store.getState())).toBe(true);
    expect(store.getState().past).toHaveLength(1);
  });

  test('a node moved with the arrow keys keeps its new place', async () => {
    const store = await renderCanvas({ editing: true });
    await node(/^Source field OrderId/).click();

    await userEvent.keyboard('{ArrowDown}');

    const shown = arrange(expressionGraph(ORDER_ID, mappingOf(store), {}));
    expect(mappingOf(store).layout!.boxes!.a1).toEqual({ x: shown.a1!.x, y: shown.a1!.y + 5 });
  });

  test('Lay Out Mapping is one undo step back to the dagre layout', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().setPositions(ORDER_ID, { boxes: { a1: { x: 400, y: 300 } }, target: { x: 0, y: 0 } });
    const before = store.getState().past.length;

    await rail('Layout mapping').click();

    expect(mappingOf(store).layout).toEqual(layoutOf(arrange(expressionGraph(ORDER_ID, mappingOf(store), {}))));
    expect(store.getState().past).toHaveLength(before + 1);
    store.getState().undo();
    expect(mappingOf(store).layout!.boxes!.a1).toEqual({ x: 400, y: 300 });
  });

  test('Delete Floating Objects deletes the boxes without a path to the target', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');

    await rail('Delete Floating Objects').click();

    expect(Object.keys(mappingOf(store).boxes)).toEqual(['a1']);
    await expect.element(rail('Delete Floating Objects')).toBeDisabled();
  });

  test('Copy and Paste put a copy of the branch beside it', async () => {
    const store = await renderCanvas({ editing: true });
    await node(/^Source field OrderId/).click();

    await rail('Copy').click();
    await rail('Paste').click();

    const boxes = Object.values(mappingOf(store).boxes);
    expect(boxes).toHaveLength(2);
    expect(boxes.every((box) => box.kind === 'source')).toBe(true);
    await expect.element(footer()).toHaveTextContent('3 nodes · 1 connections');
  });

  test("the canvas selection is the store's (ADR-0028, UI slice): a click selects the box, a paste selects the pasted copy instead", async () => {
    const store = await renderCanvas({ editing: true });
    const [original] = Object.keys(mappingOf(store).boxes);
    const shownSelected = (id: string) => canvas().element().querySelector(`.react-flow__node[data-id="${id}"]`)?.classList.contains('selected');
    await node(/^Source field OrderId/).click();
    await expect.poll(() => store.getState().selected).toEqual([original]);

    await rail('Copy').click();
    await rail('Paste').click();

    const pasted = Object.keys(mappingOf(store).boxes).find((id) => id !== original)!;
    expect(store.getState().selected).toEqual([pasted]);
    await expect.poll(() => shownSelected(pasted)).toBe(true);
    expect(shownSelected(original!)).toBe(false);
  });

  test('the Delete key deletes the selected box', async () => {
    const store = await renderCanvas({ editing: true });
    await node(/^Source field OrderId/).click();

    await userEvent.keyboard('{Delete}');

    expect(store.getState().workingCopy.targetFieldMappings).toBeUndefined();
  });

  test('Show and Hide functions toggle the drilldown', async () => {
    await renderCanvas();

    await rail('Hide functions').click();
    await expect.element(catalogue()).not.toBeInTheDocument();
    await rail('Show functions').click();
    await expect.element(catalogue()).toBeVisible();
  });

  test('Add node opens the drilldown in a dialog that adds the function and closes', async () => {
    const store = await renderCanvas({ editing: true });

    await rail('Add node').click();
    const dialog = page.getByRole('dialog', { name: 'Add node' });
    await dialog.getByRole('button', { name: /^Text/ }).click();
    await dialog.getByRole('button', { name: /^concat\b/ }).click();

    await expect.element(dialog).not.toBeInTheDocument();
    expect(boxOf(store, 'concat')).toBeDefined();
  });

  test('the Context submenu of a source field box offers its ancestors, the parent checked, through the set context command', async () => {
    const store = await renderCanvas({ editing: true });

    await node(/^Source field OrderId/).click({ button: 'right' });
    await menuItem('Context').click();
    const parent = page.getByRole('menuitemradio', { name: 'Order' });
    await expect.element(parent).toHaveAttribute('aria-checked', 'true');
    await parent.click();

    // a context equal to the parent is left out (ADR-0010): the model stays as it is
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: ORDER_ID });
    expect(store.getState().past).toHaveLength(0);
  });
});

describe('the functions drilldown (#157)', () => {
  test('lists the catalogue under the prototype groups; a function it lacks is disabled and names its follow-up issue', async () => {
    await renderCanvas({ editing: true });

    for (const [group, count] of [
      ['Arithmetic', 21],
      ['Text', 16],
      ['Node Functions', 11],
    ] as const) {
      await expect.element(catalogue().getByRole('button', { name: new RegExp(`^${group}`) })).toMatchTextContent(String(count));
    }
    await catalogue().getByRole('button', { name: /^Constants/ }).click();
    const sender = catalogue().getByRole('button', { name: /^sender/ });
    await expect.element(sender).toHaveAttribute('aria-disabled', 'true');
    await expect.element(sender).toHaveAccessibleDescription('Not available yet (#172)');
    // Value mapping is a preview function since #160 (PreviewFeatures.browser.test)
    await catalogue().getByRole('button', { name: /^Conversions/ }).click();
    await expect.element(catalogue().getByRole('button', { name: /Value mapping/ })).not.toHaveAttribute('aria-disabled');
  });

  test('a search opens every group that matches and says when nothing does', async () => {
    await renderCanvas();

    await catalogue().getByRole('searchbox', { name: 'Search functions' }).fill('upper');
    await expect.element(catalogue().getByRole('button', { name: /^toUpperCase\b/ })).toBeVisible();
    await catalogue().getByRole('button', { name: /^Text/ }).click();
    await expect.element(catalogue().getByRole('button', { name: /^toUpperCase\b/ })).not.toBeInTheDocument();
    await catalogue().getByRole('searchbox', { name: 'Search functions' }).fill('zzz');
    await expect.element(catalogue()).toMatchTextContent('No matching functions.');
  });
});

describe("a function's deviation as a neutral note, its fidelity level otherwise internal (GLOSSARY: Fidelity, ADR-0026, #207)", () => {
  /** The deviation note of `divide`, the function the ticket names: plain behaviour first, the SAP PO difference last. */
  const DIVIDE = 'Divides with a fixed number of decimal places (as in XPath), so a very small quotient can become 0. SAP PO keeps 17 significant digits.';

  test('the served catalogue carries the reworded note of divide', async () => {
    expect((await fakeApiClient().getCatalogue()).divide!.fidelity).toEqual({ level: 'deviation', text: DIVIDE });
  });

  test('in the Add node catalogue a deviation is only a tooltip and a description; every function looks the same', async () => {
    await renderCanvas({ editing: true });

    await rail('Add node').click();
    const dialog = page.getByRole('dialog', { name: 'Add node' });
    await dialog.getByRole('button', { name: /^Arithmetic/ }).click();
    await dialog.getByRole('button', { name: /^Text/ }).click();
    const entry = (fn: string) => dialog.getByRole('button', { name: fn, exact: true });
    await expect.element(entry('divide')).toHaveAccessibleDescription(DIVIDE);
    await expect.element(entry('divide')).toHaveAttribute('title', DIVIDE);
    // an exact function and an assumed one (concat) say nothing of their fidelity
    for (const fn of ['abs', 'concat']) {
      await expect.element(entry(fn)).toHaveAccessibleDescription('');
      await expect.element(entry(fn)).not.toHaveAttribute('title');
    }
    // no mark: an entry shows its symbol and its name only
    for (const fn of ['divide', 'abs', 'concat']) expect(entry(fn).element().textContent).toBe(`ƒ${fn}`);
  });

  // a node's description is React Flow's keyboard help, so the note ends the node's accessible name
  test('a placed box with a deviation has the note in its hover hint and at the end of its name, and no mark', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'divide');
    store.getState().insertBox(ORDER_ID, 'concat');
    store.getState().insertBox(ORDER_ID, 'abs');
    const hint = (name: RegExp) => node(name).element().querySelector('.map-node')!.getAttribute('data-mapping-hint');
    const label = (name: RegExp) => node(name).element().getAttribute('aria-label');

    await expect.element(node(/^Node function divide/)).toBeVisible();
    expect(label(/^Node function divide/)).toBe(`Node function divide. Detached: this object has no connections. ${DIVIDE}`);
    expect(hint(/^Node function divide/)).toContain(DIVIDE);
    for (const name of [/^Node function abs/, /^Node function concat/]) {
      expect(label(name)).toMatch(/^Node function \w+\. Detached: this object has no connections\.$/);
      expect(hint(name)).not.toMatch(/fidelity|assumed|exact|deviation/i);
    }
    for (const name of [/^Node function divide/, /^Node function abs/, /^Node function concat/]) {
      expect(node(name).element().querySelector('.pf-v6-c-label')).toBeNull();
      expect(node(name).element().textContent).not.toMatch(/[≠?]/);
    }
  });

  test('a function box the catalogue lacks, in a hand-written model, shows without a note', async () => {
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.targetFieldMappings![ORDER_ID]!.boxes.f1 = { kind: 'function', fn: 'noSuchFunction' };
    await renderCanvas({ api: fakeApiClient({ [MAPPING]: model }) });

    await expect.element(node(/^Node function noSuchFunction/)).toBeVisible();
    expect(node(/^Node function noSuchFunction/).element().getAttribute('aria-label')).toBe('Node function noSuchFunction. Detached: this object has no connections.');
  });
});

describe('wires moved by hand, several deleted, and where a new node shows (#157)', () => {
  const edge = (id: string) => page.getByTestId(`rf__edge-${id}`);
  const wireEndOf = (id: string) => page.elementLocator(edge(id).element().querySelector('.react-flow__edgeupdater-target')!);
  const input = (fn: string, n: number) => node(new RegExp(`^Node function ${fn}`)).getByRole('group', { name: new RegExp(`^Input ${n}: `) });

  test('a function added by a click shows below every node, at their left edge', async () => {
    const store = await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId/)).toBeVisible();
    const shown = arrange(expressionGraph(ORDER_ID, mappingOf(store), {}));

    await addFromCatalogue('Text', 'concat');

    const placed = Object.values(shown);
    expect(mappingOf(store).layout!.boxes![boxOf(store, 'concat')]).toEqual({ x: Math.min(...placed.map((p) => p.x)), y: Math.max(...placed.map((p) => p.y)) + 72 });
  });

  test('a node added outside the view is brought into it', async () => {
    const store = await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId/)).toBeVisible();
    const shown = arrange(expressionGraph(ORDER_ID, mappingOf(store), {}));

    store.getState().insertBox(ORDER_ID, 'concat', { x: 4000, y: 3000 }, layoutOf(shown));
    // React Flow keeps a new node hidden until it is measured, which under load can take longer than a poll's 1 s
    await expect.element(node(/^Node function concat/)).toBeVisible();

    const within = () => {
      const card = node(/^Node function concat/).element().getBoundingClientRect();
      const view = canvas().element().getBoundingClientRect();
      return card.left >= view.left && card.right <= view.right && card.top >= view.top && card.bottom <= view.bottom;
    };
    await expect.poll(within, { timeout: 5000 }).toBe(true);
  });

  test('the end of a wire dragged onto another input moves the wire there (rewire)', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    const concat = boxOf(store, 'concat');
    await expect.element(input('concat', 1)).toBeVisible();

    await userEvent.dragAndDrop(wireEndOf(`a1->:target`), input('concat', 1));

    expect(mappingOf(store).result).toBeNull();
    expect(mappingOf(store).boxes[concat]).toEqual({ kind: 'function', fn: 'concat', inputs: ['a1'] });
  });

  test('an occupied input dragged onto another input moves its wire there', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    const concat = boxOf(store, 'concat');
    store.getState().wire(ORDER_ID, 'a1', concat, 0);
    await expect.element(input('concat', 2)).toBeVisible();

    await userEvent.dragAndDrop(input('concat', 1), input('concat', 2));

    expect(mappingOf(store).boxes[concat]).toEqual({ kind: 'function', fn: 'concat', inputs: [null, 'a1'] });
  });

  test('marks no box of a mapping whose source fields the source structure has, and offers no remap', async () => {
    await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId\. Complete/)).toBeVisible();

    await node(/^Source field OrderId/).click({ button: 'right' });

    await expect.element(menuItem('Context')).toBeVisible();
    await expect.element(menuItem('Remap')).not.toBeInTheDocument();
    await expect.element(canvas().getByRole('img', { name: 'Orphaned reference' })).not.toBeInTheDocument();
    await expect.element(targetRow('OrderId').getByRole('img', { name: 'Connected' })).toBeVisible();
  });

  test('two selected wires are both removed by the Delete key', async () => {
    const store = await renderCanvas({ editing: true });
    store.getState().insertBox(ORDER_ID, 'concat');
    const concat = boxOf(store, 'concat');
    store.getState().wire(ORDER_ID, 'a1', concat, 0);
    store.getState().wire(ORDER_ID, 'a1', concat, 1);
    await expect.element(edge(`a1->${concat}#1`)).toBeInTheDocument();

    (edge(`a1->${concat}#0`).element() as HTMLElement).focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard('{Control>}');
    (edge(`a1->${concat}#1`).element() as HTMLElement).focus();
    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard('{/Control}');
    await userEvent.keyboard('{Delete}');

    expect(mappingOf(store).boxes[concat]).toEqual({ kind: 'function', fn: 'concat', inputs: [null, null] });
    expect(mappingOf(store).result).toBe('a1');
  });
});

describe('an orphaned reference and its remap (GLOSSARY: Orphaned reference, Remap; ADR-0027)', () => {
  const GONE = `/${ORDER}/Gone`;
  /** order-id-copy with box a1 reading Gone, a field the order structure lacks. */
  async function orphanApi(): Promise<ApiClient> {
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.targetFieldMappings![ORDER_ID]!.boxes.a1 = { kind: 'source', field: GONE };
    return fakeApiClient({ [MAPPING]: model });
  }
  const remapDialog = () => page.getByRole('dialog', { name: 'Remap · Gone' });
  const sourceField = () => remapDialog().getByRole('combobox', { name: 'Source field' });

  test('marks the box on the canvas and its target field in the target pane; in view mode Remap is disabled', async () => {
    await renderCanvas({ api: await orphanApi() });

    await expect.element(node(/^Source field Gone/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
    await expect.element(node(/^Target field OrderId/).getByRole('img', { name: 'Orphaned reference' })).not.toBeInTheDocument();
    await expect.element(targetRow('OrderId').getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
    await expect.element(targetRow('OrderId').getByRole('img', { name: 'Connected' })).not.toBeInTheDocument();
    await expect.element(targetRow('Note').getByRole('img', { name: 'Orphaned reference' })).not.toBeInTheDocument();

    await node(/^Source field Gone/).click({ button: 'right' });

    await expect.element(menuItem('Remap')).toBeDisabled();
  });

  test('Remap in the node menu points the box at the chosen source field, keeping its id and its wire, as one undo step', async () => {
    const store = await renderCanvas({ api: await orphanApi(), editing: true });
    await node(/^Source field Gone/).click({ button: 'right' });
    await menuItem('Remap').click();
    await expect.element(remapDialog()).toMatchTextContent('The source structure has no field /Order/Gone.');
    await expect.element(remapDialog().getByRole('button', { name: 'Remap', exact: true })).toBeDisabled();

    await userEvent.selectOptions(sourceField(), ORDER_ID);
    await remapDialog().getByRole('button', { name: 'Remap', exact: true }).click();

    await expect.element(remapDialog()).not.toBeInTheDocument();
    expect(mappingOf(store)).toMatchObject({ result: 'a1', boxes: { a1: { kind: 'source', field: ORDER_ID } } });
    await expect.element(node(/^Source field OrderId\. Complete/)).toBeVisible();
    await expect.element(footer()).toHaveTextContent('2 nodes · 1 connections');
    await expect.element(canvas().getByRole('img', { name: 'Orphaned reference' })).not.toBeInTheDocument();
    await expect.element(targetRow('OrderId').getByRole('img', { name: 'Connected' })).toBeVisible();

    store.getState().undo();

    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: GONE });
    await expect.element(node(/^Source field Gone/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
  });

  test('Cancel leaves the box as it is', async () => {
    const store = await renderCanvas({ api: await orphanApi(), editing: true });
    await node(/^Source field Gone/).click({ button: 'right' });
    await menuItem('Remap').click();
    await userEvent.selectOptions(sourceField(), ORDER_ID);

    await remapDialog().getByRole('button', { name: 'Cancel' }).click();

    await expect.element(remapDialog()).not.toBeInTheDocument();
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: GONE });
    expect(selectUnsaved(store.getState())).toBe(false);
  });

  test('a refused source field refuses the remap as it refuses a link, and the box stays orphaned', async () => {
    // the same-step structure of #116: Doc's two Note fields are refused (ambiguous-field-path), Code is not
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.source = { bundle: ['../structures/refused/same-step.xsd'], fingerprint: 'sha256:d26971c5a6f2df7fa0bf9f7287ae22f1e93ef12dd6ea4ac8ce748990ba0ec32e', root: { element: 'Doc' } };
    model.targetFieldMappings![ORDER_ID]!.boxes.a1 = { kind: 'source', field: '/Doc/Gone' };
    const store = await renderCanvas({ api: fakeApiClient({ [MAPPING]: model }), editing: true });
    const remapTo = async (field: string) => {
      await node(/^Source field Gone/).click({ button: 'right' });
      await menuItem('Remap').click();
      await userEvent.selectOptions(sourceField(), field);
      await remapDialog().getByRole('button', { name: 'Remap', exact: true }).click();
    };

    await remapTo('/Doc/Note');

    expect(store.getState().refusal).toMatchObject({ code: 'refused-field', side: 'source', field: '/Doc/Note' });
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: '/Doc/Gone' });
    await expect.element(node(/^Source field Gone/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
    await remapTo('/Doc/Code');
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: '/Doc/Code' });
  });
});

describe('drag-to-remap: a source row dropped on an orphaned box (GLOSSARY: Remap, #174)', () => {
  const GONE = `/${ORDER}/Gone`;
  /** order-id-copy with box a1 reading Gone, a field the order structure lacks. */
  async function orphanApi(): Promise<ApiClient> {
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.targetFieldMappings![ORDER_ID]!.boxes.a1 = { kind: 'source', field: GONE };
    return fakeApiClient({ [MAPPING]: model });
  }
  const sourceRow = (name: string) =>
    page.getByRole('table', { name: 'source fields' }).getByRole('row').filter({ has: page.getByRole('button', { name, exact: true }) });
  /**
   * Drops a source field's drag data on a canvas node, as a source row dragged in edit mode carries it: on the box the
   * node draws, inside React Flow's node wrapper, where a real drop lands.
   */
  const dropField = (wrapper: Element, field: string) => {
    const data = new DataTransfer();
    data.setData('application/x-flottila-source-field', field);
    wrapper.querySelector('.map-node')!.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
  };

  test('points the box at the dropped field, keeping its id and its wire, as one undo step', async () => {
    const store = await renderCanvas({ api: await orphanApi(), editing: true });
    await expect.element(node(/^Source field Gone/)).toBeVisible();

    await userEvent.dragAndDrop(sourceRow('Note'), node(/^Source field Gone/));

    await expect.poll(() => mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: NOTE });
    expect(mappingOf(store).result).toBe('a1');
    expect(store.getState().past).toHaveLength(1);
    await expect.element(node(/^Source field Note\. Complete/)).toBeVisible();
    await expect.element(canvas().getByRole('img', { name: 'Orphaned reference' })).not.toBeInTheDocument();
    await expect.element(targetRow('OrderId').getByRole('img', { name: 'Connected' })).toBeVisible();
  });

  test('a function dropped from the catalogue on the orphaned box adds a box, and the box stays orphaned', async () => {
    const store = await renderCanvas({ api: await orphanApi(), editing: true });
    await catalogue().getByRole('button', { name: /^Text/ }).click();

    await userEvent.dragAndDrop(catalogue().getByRole('button', { name: /^toUpperCase\b/ }).first(), node(/^Source field Gone/));

    await expect.poll(() => Object.keys(mappingOf(store).boxes)).toHaveLength(2);
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: GONE });
    await expect.element(node(/^Source field Gone/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
  });

  test('in view mode a dropped source field changes nothing', async () => {
    const store = await renderCanvas({ api: await orphanApi() });
    await expect.element(node(/^Source field Gone/)).toBeVisible();

    dropField(node(/^Source field Gone/).element(), NOTE);

    await expect.element(node(/^Source field Gone/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
  });

  test('a box that is not orphaned takes no source row', async () => {
    const store = await renderCanvas({ editing: true });
    await expect.element(node(/^Source field OrderId\. Complete/)).toBeVisible();

    dropField(node(/^Source field OrderId/).element(), NOTE);

    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: ORDER_ID });
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
  });

  test('a refused source field refuses the drop as it refuses a link: the alert says why, and the box stays orphaned', async () => {
    // the same-step structure of #116: Doc's two Note fields are refused (ambiguous-field-path), Code is not
    const { model } = await fakeApiClient().getMapping(MAPPING);
    model.source = { bundle: ['../structures/refused/same-step.xsd'], fingerprint: 'sha256:d26971c5a6f2df7fa0bf9f7287ae22f1e93ef12dd6ea4ac8ce748990ba0ec32e', root: { element: 'Doc' } };
    model.targetFieldMappings![ORDER_ID]!.boxes.a1 = { kind: 'source', field: '/Doc/Gone' };
    const store = await renderCanvas({ api: fakeApiClient({ [MAPPING]: model }), editing: true });
    await expect.element(node(/^Source field Gone/)).toBeVisible();

    await userEvent.dragAndDrop(sourceRow('Note').first(), node(/^Source field Gone/));

    await expect.element(page.getByRole('heading', { name: /The source field \/Doc\/Note is refused: ambiguous-field-path/ })).toBeVisible();
    expect(mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: '/Doc/Gone' });
    await userEvent.dragAndDrop(sourceRow('Code'), node(/^Source field Gone/));
    await expect.poll(() => mappingOf(store).boxes.a1).toEqual({ kind: 'source', field: '/Doc/Code' });
  });
});
