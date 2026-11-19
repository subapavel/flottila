import { expect, type Locator, type Page } from '@playwright/test';

/** The helpers of the studio scenarios: the shell's Mappings screen, the studio's panes, its canvas and its Test tab. */
export type Side = 'source' | 'target';

/** The table of the Mappings screen. */
export const mappingsTable = (page: Page) => page.getByRole('table', { name: 'Mappings' });

/** Loads the application and opens its Mappings screen from the navigation. */
export async function openMappings(page: Page) {
  await page.goto('/');
  await page.getByRole('navigation', { name: 'Screens' }).getByRole('button', { name: 'Mappings', exact: true }).click();
  await expect(mappingsTable(page).getByRole('button', { name: 'Open in studio' }).first()).toBeVisible();
}

/**
 * Loads the application and opens mapping `mapping` (its id, `<package>/<name>`) from the Mappings screen in its studio
 * tab, waits for the mapping's title and shows the studio's tab `tab`; without `tab`, the studio stays on the tab it
 * opened on.
 */
export async function openStudio(page: Page, mapping: string, tab?: string) {
  await openMappings(page);
  const name = mapping.slice(mapping.lastIndexOf('/') + 1);
  const row = mappingsTable(page).getByRole('row').filter({ has: page.getByRole('cell', { name, exact: true }) });
  await row.getByRole('button', { name: 'Open in studio' }).click();
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  if (tab) await page.getByRole('tab', { name: tab, exact: true }).click();
}

/** Starts editing the open mapping. */
export const edit = (page: Page) => page.getByRole('button', { name: 'Edit', exact: true }).click();

/** The field table of a message pane on the Definition tab. */
export const fields = (page: Page, side: Side) => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });

/**
 * The row of field `path` in a pane, scrolled into the pane's virtualised view: the pane renders only the rows near its
 * scrolled view, so the scroll moves down half a view at a time from the top until the row is there.
 */
export async function fieldRow(page: Page, side: Side, path: string): Promise<Locator> {
  const row = fields(page, side).locator(`tr[data-field-path="${path}"]`);
  const scroll = page.getByRole('region', { name: `${side} message` }).locator('[data-fields-scroll]');
  await expect(fields(page, side).locator('tbody tr[data-field-path]').first()).toBeAttached();
  // the pane renders the rows of a new scroll position on a later frame: each scroll waits two frames before the next look
  await scroll.evaluate(
    (element) =>
      new Promise<void>((resolve) => {
        element.scrollTop = 0;
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }),
  );
  for (let step = 0; step < 200 && (await row.count()) === 0; step++) {
    const moved = await scroll.evaluate(
      (element) =>
        new Promise<boolean>((resolve) => {
          const before = element.scrollTop;
          element.scrollTop = before + element.clientHeight / 2;
          const after = element.scrollTop;
          requestAnimationFrame(() => requestAnimationFrame(() => resolve(after !== before)));
        }),
    );
    if (!moved && (await row.count()) === 0) break;
  }
  await expect(row).toBeAttached();
  return row;
}

/** The field button of a row: its name, the last step of its path without a namespace or an attribute's `@`. */
const fieldButton = (row: Locator, path: string) => {
  const step = path.slice(path.lastIndexOf('/') + 1).replace(/^Q\{[^}]*\}/, '').replace(/^@/, '');
  return row.getByRole('button', { name: step, exact: true });
};

/** Selects target field `path`: its expression shows on the canvas. */
export async function selectTarget(page: Page, path: string) {
  await fieldButton(await fieldRow(page, 'target', path), path).click();
}

/** Drags the source row of `source` onto the target row of `target` in edit mode: a field link. */
export async function link(page: Page, source: string, target: string) {
  const to = await fieldRow(page, 'target', target);
  const from = await fieldRow(page, 'source', source);
  await from.dragTo(to);
}

export const canvas = (page: Page) => page.getByRole('group', { name: 'Mapping canvas' });
/** A node of the canvas by its accessible name: its role, its name and its state. */
export const node = (page: Page, name: RegExp) => canvas(page).getByRole('group', { name });

/** The authoring id of a canvas node, the box it stands for. */
export async function nodeId(page: Page, name: RegExp): Promise<string> {
  return (await node(page, name).getAttribute('data-id'))!;
}

/**
 * Chooses `items` in turn from the context menu of a node, as a right click opens it. An item that opens a flyout, such
 * as Connect to, is a button of the menu; the others are menu items.
 */
export async function nodeMenu(page: Page, name: RegExp, ...items: string[]) {
  await node(page, name).click({ button: 'right' });
  for (const [i, item] of items.entries()) {
    const menu = page.getByRole('menu');
    if (i < items.length - 1) await menu.getByRole('button', { name: item, exact: true }).click();
    else await menu.getByRole('menuitem', { name: item, exact: true }).click();
  }
}

/** Opens the Display Queue of a node; the dialog is returned. */
export async function displayQueue(page: Page, name: RegExp): Promise<Locator> {
  await nodeMenu(page, name, 'Display Queue');
  const queue = page.getByRole('dialog', { name: 'Display Queue' });
  await expect(queue).toBeVisible();
  return queue;
}

/** Sets the parameters of function `fn` on its node, by label, in its parameter dialog, and applies them. */
export async function setParameters(page: Page, fn: string, values: Record<string, string>) {
  await node(page, new RegExp(`^Node function ${fn}\\b`)).getByRole('button', { name: `Parameters for ${fn}` }).click();
  const dialog = page.getByRole('dialog', { name: `Function parameters · ${fn}` });
  for (const [label, value] of Object.entries(values)) await dialog.getByLabel(label, { exact: true }).fill(value);
  await dialog.getByRole('button', { name: 'Apply' }).click();
  await expect(dialog).toBeHidden();
}

/** Runs a test of the working copy on `input` on the Test tab, in edit mode: the source document is the input. */
export async function runTest(page: Page, input: string, format: 'XML' | 'JSON' = 'XML') {
  await page.getByRole('tab', { name: 'Test', exact: true }).click();
  await page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: `Input ${format}` }).fill(input);
  await page.getByRole('button', { name: 'Run simulation' }).click();
  await expect(page.getByRole('button', { name: 'Run simulation' })).toBeEnabled();
}

/** The result document of the last run, shown as text. */
export const result = (page: Page) => page.getByRole('region', { name: 'Result', exact: true }).locator('pre');
/** The processing log of the Test tab. */
export const processingLog = (page: Page) => page.getByRole('region', { name: 'Processing log' });

/** Saves in edit mode: the studio says so, and Save stays disabled until the next change. */
export async function save(page: Page, name: string) {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: new RegExp(`Mapping ${name} saved`) })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
}

/** Saves through the API and returns its answer to the PUT. */
export async function saveThroughApi(page: Page, name: string) {
  const put = page.waitForResponse((response) => response.request().method() === 'PUT');
  await save(page, name);
  return put;
}

/** Drops function `fn` of catalogue group `group` from the function catalogue onto the canvas, at `at` in it. */
export async function dropFunction(page: Page, group: string, fn: string, at: { x: number; y: number }) {
  const catalogue = page.getByRole('region', { name: 'Function catalogue' });
  const entry = catalogue.getByRole('button', { name: new RegExp(`^${fn}\\b`) }).first();
  if (!(await entry.isVisible())) await catalogue.getByRole('button', { name: new RegExp(`^${group}`) }).click();
  await entry.dragTo(canvas(page), { targetPosition: at });
}
