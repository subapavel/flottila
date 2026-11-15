import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const canvas = (page: Page) => page.getByRole('group', { name: 'Mapping canvas' });
const node = (page: Page, name: RegExp) => canvas(page).getByRole('group', { name });
const fields = (page: Page, side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });
const transformOf = (page: Page, name: RegExp) => node(page, name).evaluate((element) => (element as HTMLElement).style.transform);

/** The selectors of every style rule the page loaded that styles the old editor's `fm-` classes (spec #135). */
const oldEditorRules = (page: Page) =>
  page.evaluate(() =>
    [...document.styleSheets]
      .flatMap((sheet) => [...sheet.cssRules])
      .flatMap((rule) => (rule instanceof CSSStyleRule && /\.fm-/.test(rule.selectorText) ? [rule.selectorText] : [])),
  );

// The studio's author journey over the real service (#161, ADR-0028): the start page, New mapping, the messages chosen
// on Signature, a wire and a moved box on Definition, Save; the page reloaded opens the saved file with the wire and the
// box where it was put. The page loads no style of the old editor, which is gone.
test('@stack list, New mapping, Signature, a wire and a moved box on Definition, Save; a reload keeps the wire and the position', async ({ page }) => {
  const problems = collectProblems(page);
  const mapping = 'mappings/e2e-journey';
  await page.goto('/');
  await expect(page.getByRole('table', { name: 'Mappings' }).getByRole('link', { name: 'Open in studio' }).first()).toBeVisible();
  expect(await oldEditorRules(page)).toEqual([]);

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

  await page.getByRole('tab', { name: 'Definition' }).click();
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

  await page.reload();
  await expect(page.getByRole('heading', { level: 1, name: 'e2e-journey' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Definition' })).toHaveAttribute('aria-selected', 'true');
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();
  await expect(page.getByText('2 nodes · 1 connections')).toBeVisible();
  expect(await transformOf(page, /^Source field OrderId/)).toBe(moved);

  // opened again it is read-only; Edit makes it editable, with nothing to save
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(await oldEditorRules(page)).toEqual([]);
  expect(problems).toEqual([]);
});
