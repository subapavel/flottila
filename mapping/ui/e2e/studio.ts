import { expect, type Locator, type Page } from '@playwright/test';

/** The helpers of the studio journeys (ADR-0028): its panes, the canvas of the selected target field and the Test tab. */
export type Side = 'source' | 'target';

/** Opens mapping `mapping` in the studio on `tab`, over the fake ApiClient when `fake`, and waits for its title. */
export async function openStudio(page: Page, mapping: string, tab: string, { fake = false } = {}) {
  await page.goto(`/?${fake ? 'api=fake&' : ''}mapping=${encodeURIComponent(mapping)}#${tab}`);
  await expect(page.getByRole('heading', { level: 1, name: mapping.slice(mapping.lastIndexOf('/') + 1) })).toBeVisible();
}

/** The field table of a message pane on the Definition tab. */
export const fields = (page: Page, side: Side) => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });

/**
 * The row of field `path` in a pane, scrolled into the pane's virtualised view: the pane renders only the rows near its
 * scrolled view, so the scroll moves down half a view at a time from the top until the row is there.
 */
export async function fieldRow(page: Page, side: Side, path: string): Promise<Locator> {
  const row = fields(page, side).locator(`tr[data-field-path="${path}"]`);
  const scroll = page.getByRole('region', { name: `${side} message` }).locator('.fields-scroll');
  await expect(fields(page, side).locator('tbody tr').first()).toBeAttached();
  await scroll.evaluate((element) => (element.scrollTop = 0));
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

/** Selects target field `path`: its expression shows on the canvas. */
export async function selectTarget(page: Page, path: string) {
  await (await fieldRow(page, 'target', path)).locator('button.field-name').click();
}

/** Drags the source row of `source` onto the target row of `target` in edit mode: a field link (the link command). */
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

/** Chooses `items` in turn from the context menu of a node, as a right click opens it. */
export async function nodeMenu(page: Page, name: RegExp, ...items: string[]) {
  await node(page, name).click({ button: 'right' });
  for (const item of items) await page.getByRole('menuitem', { name: item, exact: true }).click();
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
  await page.getByRole('tab', { name: 'Test' }).click();
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

/** Saves over the service and returns its answer to the PUT. */
export async function saveOverService(page: Page, name: string) {
  const put = page.waitForResponse((response) => response.request().method() === 'PUT');
  await save(page, name);
  return put;
}

/** Drops function `fn` of catalogue group `group` from the function catalogue onto the canvas, at `at` in it. */
export async function dropFunction(page: Page, group: string, fn: string, at: { x: number; y: number }) {
  const catalogue = page.getByRole('region', { name: 'Function catalogue' });
  const entry = catalogue.getByRole('button', { name: new RegExp(`^${fn}\\b`) });
  if (!(await entry.isVisible())) await catalogue.getByRole('button', { name: new RegExp(`^${group}`) }).click();
  await entry.dragTo(canvas(page).getByTestId('rf__wrapper'), { targetPosition: at });
}
