import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';
import { canvas, dropFunction, fieldRow, link, node, nodeId, nodeMenu, openStudio, result, runTest, saveOverService, selectTarget, setParameters } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

// GATE check 2 (#117): editing in the studio over the real service, and an attribute field mapped and run (#102).
// Both mappings are workspace copies of order-id-copy without target field mappings, made by the server command.
const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const CHANNEL = `${ORDER}/@channel`;
const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);

/** Opens a mapping in the studio on Definition, in edit mode. */
async function open(page: Page, mapping: string) {
  await openStudio(page, mapping, 'definition');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
}

test('@stack GATE 2: a field link by drag and drop, a function and a constant are saved to the canonical file; a save over a changed file answers 409', async ({ page, request }) => {
  const problems = collectProblems(page);
  const mapping = 'mappings/e2e-edit';
  const file = join(stackWorkspace, `${mapping}.mapping.json`);
  await open(page, mapping);

  await link(page, ORDER_ID, ORDER_ID);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  await selectTarget(page, ORDER_ID);
  await dropFunction(page, 'Text', 'concat', { x: 60, y: 40 });
  await dropFunction(page, 'Constants', 'constant', { x: 60, y: 160 });
  // three boxes and the target field
  await expect(page.getByText('4 nodes · 1 connections')).toBeVisible();
  const [source, concat, constant] = [await nodeId(page, /^Source field OrderId/), await nodeId(page, /^Node function concat/), await nodeId(page, /^Node function constant/)];

  // the constant's value is its box parameter, set in its parameter dialog
  await setParameters(page, 'constant', { Value: '-X' });
  await expect(node(page, /^Node function constant/)).toContainText('value -X');
  // dropped boxes may overlap: lay the expression out, then wire OrderId and the constant into concat, and concat into
  // the target field
  await page.getByRole('toolbar', { name: 'Mapping tools' }).getByRole('button', { name: 'Layout mapping' }).click();
  await nodeMenu(page, /^Source field OrderId/, 'Connect to', 'concat · a');
  await nodeMenu(page, /^Node function constant/, 'Connect to', 'concat · b');
  await nodeMenu(page, /^Node function concat/, 'Connect to', 'OrderId · OrderId');
  await expect(node(page, /^Target field OrderId\. Complete/)).toBeVisible();
  await expect(node(page, /^Node function concat\. Complete/)).toBeVisible();

  const answer = await saveOverService(page, 'e2e-edit');

  // the file on disk is the canonical form the service answered the save with, and the one it serves
  const saved = readFileSync(file);
  expect(answer.status()).toBe(200);
  expect(Buffer.from(await answer.body()).equals(saved)).toBe(true);
  expect(Buffer.from(await (await request.get(`/api/mappings/${mapping}`)).body()).equals(saved)).toBe(true);
  const boxes = JSON.parse(saved.toString('utf8')).targetFieldMappings[ORDER_ID].boxes;
  expect(boxes[concat]).toEqual({ kind: 'function', fn: 'concat', inputs: [source, constant] });
  expect(boxes[constant]).toEqual({ kind: 'function', fn: 'constant', params: { value: '-X' } });

  // the saved graph runs
  await runTest(page, readFileSync(corpus('cases/link/direct-copy/001/input.xml'), 'utf8'));
  await expect(result(page)).toContainText('<OrderId>PO 4711-X</OrderId>');

  // the file changes on disk; the next save is refused with 409, writes nothing, and the dialog offers Reload
  const changed = saved.toString('utf8').replace('"-X"', '"-DISK"');
  writeFileSync(file, changed);
  await page.getByRole('tab', { name: 'Definition' }).click();
  await selectTarget(page, ORDER_ID);
  await setParameters(page, 'constant', { Value: '-Y' });
  const refused = page.waitForResponse((response) => response.request().method() === 'PUT');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await refused).status()).toBe(409);
  const dialog = page.getByRole('dialog', { name: 'Save conflict' });
  await expect(dialog).toBeVisible();
  expect(readFileSync(file, 'utf8')).toBe(changed);

  await dialog.getByRole('button', { name: 'Reload' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await expect(node(page, /^Node function constant/)).toContainText('value -DISK');
  // Chromium logs the refused PUT itself as a console error; that one line, and nothing else, is expected
  expect(problems).toEqual(['Failed to load resource: the server responded with a status of 409 (Conflict)']);
});

test('@stack a schema changed on disk: opening the mapping refreshes it, marks the orphaned reference, and a remap fixes it', async ({ page }) => {
  const problems = collectProblems(page);
  // e2e-refresh is order-id-copy (box a1 reads OrderId) over a source schema of its own; OrderId becomes OrderNumber
  const schema = join(stackWorkspace, 'structures', 'e2e-refresh', 'order.xsd');
  writeFileSync(schema, readFileSync(schema, 'utf8').replace('name="OrderId"', 'name="OrderNumber"'));
  await open(page, 'mappings/e2e-refresh');

  await expect(page.getByRole('status').filter({ hasText: 'The structures changed. Orphaned references: 1' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  // the target field whose mapping reads the lost field is marked, and so is box a1, which reads it
  await expect((await fieldRow(page, 'target', ORDER_ID)).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
  await selectTarget(page, ORDER_ID);
  await expect(node(page, /^Source field OrderId/).getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
  expect(await nodeId(page, /^Source field OrderId/)).toBe('a1');

  // Remap in the node menu points the box at OrderNumber: it keeps its id and its wire into the target
  await nodeMenu(page, /^Source field OrderId/, 'Remap');
  const dialog = page.getByRole('dialog', { name: 'Remap · OrderId' });
  await dialog.getByLabel('Source field', { exact: true }).selectOption(`${ORDER}/OrderNumber`);
  await dialog.getByRole('button', { name: 'Remap', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(node(page, /^Source field OrderNumber\. Complete/)).toBeVisible();
  expect(await nodeId(page, /^Source field OrderNumber/)).toBe('a1');
  await expect(page.getByText('2 nodes · 1 connections')).toBeVisible();
  await expect(canvas(page).getByRole('img', { name: 'Orphaned reference' })).toHaveCount(0);
  await expect((await fieldRow(page, 'target', ORDER_ID)).getByRole('img', { name: 'Connected' })).toBeVisible();

  const input = readFileSync(corpus('cases/link/direct-copy/001/input.xml'), 'utf8').replaceAll('OrderId>', 'OrderNumber>');
  await runTest(page, input);
  await expect(result(page)).toContainText('<OrderId>PO 4711</OrderId>');
  expect(problems).toEqual([]);
});

test('@stack an attribute source field and an attribute target field map by drag and drop and run (#102)', async ({ page }) => {
  const problems = collectProblems(page);
  await open(page, 'mappings/e2e-attribute');

  await link(page, ORDER_ID, ORDER_ID);
  await link(page, CHANNEL, CHANNEL);
  await expect((await fieldRow(page, 'target', CHANNEL)).getByRole('img', { name: 'Connected' })).toBeVisible();
  await expect(node(page, /^Source field @?channel\. Complete/)).toBeVisible();
  await runTest(page, readFileSync(corpus('cases/link/attribute/001/input.xml'), 'utf8'));

  await expect(result(page)).toContainText('channel="web"');
  await expect(result(page)).toContainText('<OrderId>PO 4711</OrderId>');
  expect(problems).toEqual([]);
});
