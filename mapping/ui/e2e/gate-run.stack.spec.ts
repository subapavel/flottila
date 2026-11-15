import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';
import { displayQueue, node, openStudio, processingLog, result, runTest, selectTarget } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

// GATE check 3 (#117): test runs in the studio over the real service, and their display queues.
const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);

/** Opens a mapping in the studio on Definition, in edit mode: a test run needs it. */
async function open(page: Page, mapping: string) {
  await openStudio(page, mapping, 'definition');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
}

/** Runs a test of the working copy on the corpus file `input`. */
const run = (page: Page, input: string) => runTest(page, readFileSync(corpus(input), 'utf8'), input.endsWith('.json') ? 'JSON' : 'XML');

/** The parent slots of a target field's display queue, each with its slots. */
const parentSlots = (queue: Locator) => queue.getByRole('list', { name: 'Parent slots' }).locator(':scope > li');

test('@stack GATE 3: a test run of the unsaved working copy, and the display queues of a function and of target fields', async ({ page }) => {
  const problems = collectProblems(page);
  // ifWithoutElse(Sku, createIf(Qty)) onto Item, V reads Qty (fn/ifWithoutElse/005)
  const mapping = 'mappings/boolean-ifWithoutElse-suppress';
  await open(page, mapping);

  // an unsaved edit: keepSuppressValues makes the false condition of line 3 a SUPPRESS slot
  await selectTarget(page, '/Out/Item');
  await node(page, /^Node function ifWithoutElse/).getByRole('button', { name: 'Parameters for ifWithoutElse' }).click();
  const parameters = page.getByRole('dialog', { name: 'Function parameters · ifWithoutElse' });
  await parameters.getByText('Keep SUPPRESS Values').click();
  await parameters.getByRole('button', { name: 'Apply' }).click();
  await expect(node(page, /^Node function ifWithoutElse/)).toContainText('keepSuppressValues true');
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  await run(page, 'cases/fn/ifWithoutElse/005/input.xml');

  // the saved file gives V 1 and TRUE (lines 1 and 3); the working copy gives the Qty of lines 1 and 4
  await expect(result(page)).toContainText('<V>1</V>');
  await expect(result(page)).toContainText('<V>true</V>');
  await expect(result(page)).not.toContainText('<V>TRUE</V>');
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  expect(readFileSync(join(stackWorkspace, `${mapping}.mapping.json`)).equals(readFileSync(corpus(`${mapping}.mapping.json`)))).toBe(true);

  // the function box: one context, with createIf's SUPPRESS of line 2 and the kept SUPPRESS of line 3
  await page.getByRole('tab', { name: 'Definition' }).click();
  await selectTarget(page, '/Out/Item');
  const boxQueue = await displayQueue(page, /^Node function ifWithoutElse/);
  const out = boxQueue.getByRole('table', { name: 'ifWithoutElse queue' });
  await expect(out.getByRole('columnheader', { name: 'ifWithoutElse:out' })).toBeVisible();
  await expect(out.locator('tbody tr td:last-child')).toHaveText(['""', 'SUPPRESS', 'SUPPRESS', '""', 'End']);
  await expect(boxQueue).toContainText('ifWithoutElse:out · 1 context, 4 slots');
  await boxQueue.getByRole('button', { name: 'Close' }).click();

  // the target field Item: its parent slot, the root, creates two of four slots and suppresses two
  const itemQueue = await displayQueue(page, /^Target field Item/);
  await expect(itemQueue.getByRole('heading', { name: /^Occurrences of Item/ })).toBeVisible();
  await expect(parentSlots(itemQueue)).toHaveCount(1);
  await expect(parentSlots(itemQueue).first()).toContainText('parent slot 1: 2 created');
  await expect(parentSlots(itemQueue).first().getByRole('listitem')).toHaveText([
    'slot 1 created',
    'slot 2 suppressed (SUPPRESS)',
    'slot 3 suppressed (SUPPRESS)',
    'slot 4 created',
  ]);
  await itemQueue.getByRole('button', { name: 'Close' }).click();

  // the target field V: one V in each Item, by the Item's slot; the suppressed slots 2 and 3 made no Item
  await selectTarget(page, '/Out/Item/V');
  const vQueue = await displayQueue(page, /^Target field V/);
  await expect(vQueue.getByRole('heading', { name: /^Occurrences of V/ })).toBeVisible();
  await expect(parentSlots(vQueue)).toHaveText([/^parent slot 1: 1 created/, 'parent slots 2–3 not in the trace', /^parent slot 4: 1 created/]);
  expect(problems).toEqual([]);
});

test('@stack GATE 3: a po:MINOCCURS run shows its QName and authoring id, which leads to the target field', async ({ page }) => {
  const problems = collectProblems(page);
  // Row is 1..2 and createIf gives it no value (target/minOccurs/001)
  await open(page, 'mappings/create-if-row');
  await run(page, 'cases/target/minOccurs/001/input.xml');

  await expect(processingLog(page)).toContainText(/Runtime error po:MINOCCURS: .* \(T1\)/);

  // the target field mapping T1 is Row's: its display queue marks the parent slot that raised the error
  await page.getByRole('tab', { name: 'Definition' }).click();
  await selectTarget(page, '/Create/Row');
  const rowQueue = await displayQueue(page, /^Target field Row/);
  await expect(rowQueue.getByRole('heading', { name: /^Occurrences of Row/ })).toBeVisible();
  await expect(parentSlots(rowQueue).first()).toContainText('parent slot 1');
  await expect(parentSlots(rowQueue).first()).toContainText('po:MINOCCURS');
  expect(problems).toEqual([]);
});

test('@stack a mapping with a JSON source runs and shows its output (#98)', async ({ page }) => {
  const problems = collectProblems(page);
  // $/lines/sku through removeContexts and toUpperCase onto All (structure/jsonFunctions/001)
  await open(page, 'mappings/json-functions');
  await run(page, 'cases/structure/jsonFunctions/001/input.json');

  for (const line of ['<All>A</All>', '<All>B</All>', '<All>C</All>']) {
    await expect(result(page)).toContainText(line);
  }
  expect(problems).toEqual([]);
});
