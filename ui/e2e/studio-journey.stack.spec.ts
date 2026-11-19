import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';
import { edit, fields, node, openMappings, openStudio } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER = 'Q{urn:rail8:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const transformOf = (page: Page, name: RegExp) => node(page, name).evaluate((element) => (element as HTMLElement).style.transform);

// The studio's author journey over the real node: the Mappings screen, New mapping, the messages chosen on
// Signature, a wire and a moved box on Definition, Save; the page loaded again opens the saved file on the tab it was
// left on, with the wire and the box where it was put.
test('@stack list, New mapping, Signature, a wire and a moved box on Definition, Save; a reload keeps the wire and the position', async ({ page }) => {
  const problems = collectProblems(page);
  const mapping = 'mappings/e2e-journey';
  await openMappings(page);

  await page.getByRole('button', { name: 'New mapping' }).click();
  const modal = page.getByRole('dialog', { name: 'New mapping' });
  await modal.getByRole('textbox', { name: 'Name' }).fill('e2e-journey');
  await modal.getByRole('textbox', { name: 'Package' }).fill('mappings');
  await modal.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('tab', { name: 'Signature' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();

  for (const side of ['source', 'target']) {
    await page.getByRole('button', { name: `Add ${side} message` }).click();
    const choice = page.getByRole('dialog', { name: `Choose ${side} message` });
    await choice.getByRole('textbox', { name: 'Bundle' }).fill('../structures/skeleton/order.xsd');
    await choice.getByRole('textbox', { name: 'Root element' }).fill(ORDER);
    await choice.getByRole('button', { name: 'Apply' }).click();
    await expect(choice).toBeHidden();
  }

  await page.getByRole('tab', { name: 'Definition', exact: true }).click();
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await fields(page, 'source').getByRole('button', { name: 'OrderId', exact: true }).dblclick();
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();
  await expect(page.getByText('2 nodes · 1 connections')).toBeVisible();

  // the source field box moves; React Flow moves a node from the second drag event on
  const before = await transformOf(page, /^Source field OrderId/);
  const box = (await node(page, /^Source field OrderId/).boundingBox())!;
  await page.mouse.move(box.x + 40, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + 50, box.y + 40, { steps: 5 });
  await page.mouse.move(box.x + 60, box.y + 90, { steps: 5 });
  await page.mouse.up();
  await expect.poll(() => transformOf(page, /^Source field OrderId/)).not.toBe(before);
  const moved = await transformOf(page, /^Source field OrderId/);

  const put = page.waitForResponse((response) => response.request().method() === 'PUT');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await put).status()).toBe(200);
  await expect(page.getByRole('heading', { name: /Mapping e2e-journey saved/ })).toBeVisible();

  // the file holds the wire and the box's position
  const saved = JSON.parse(readFileSync(join(stackWorkspace, `${mapping}.mapping.json`), 'utf8'));
  const fieldMapping = saved.targetFieldMappings[ORDER_ID];
  expect(fieldMapping.boxes[fieldMapping.result]).toEqual({ kind: 'source', field: ORDER_ID });
  expect(fieldMapping.layout.boxes[fieldMapping.result]).toBeDefined();

  // the page loaded again: the mapping opens from the list on the tab it was left on
  await openStudio(page, mapping);
  await expect(page.getByRole('tab', { name: 'Definition', exact: true })).toHaveAttribute('aria-selected', 'true');
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();
  await expect(page.getByText('2 nodes · 1 connections')).toBeVisible();
  expect(await transformOf(page, /^Source field OrderId/)).toBe(moved);

  // opened again it is read-only; Edit makes it editable, with nothing to save
  await edit(page);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(problems).toEqual([]);
});
