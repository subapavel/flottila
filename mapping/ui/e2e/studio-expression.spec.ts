import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const canvas = (page: Page) => page.getByRole('group', { name: 'Mapping canvas' });
const node = (page: Page, name: RegExp) => canvas(page).getByRole('group', { name });
const fields = (page: Page, side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });
const transformOf = (page: Page, name: RegExp) => node(page, name).evaluate((element) => (element as HTMLElement).style.transform);

// The mapping expression on the fake API (#157): a new mapping wires a source field through a function into its target
// field, moves the function and saves; opened again, the wires and the positions are kept.
test('wire, parameters and a drag survive Save and opening the mapping again', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');
  await page.getByRole('button', { name: 'New mapping' }).click();
  const modal = page.getByRole('dialog', { name: 'New mapping' });
  await modal.getByRole('textbox', { name: 'Name' }).fill('order-expression');
  await modal.getByRole('textbox', { name: 'Package' }).fill('mappings');
  await modal.getByRole('button', { name: 'Create' }).click();
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

  const catalogue = page.getByRole('region', { name: 'Function catalogue' });
  await catalogue.getByRole('button', { name: /^Text/ }).click();
  await page.screenshot({ path: '.logs/157-drilldown.png' });
  // the deviation note of the served catalogue, its only trace in the studio (#207)
  await expect(catalogue.getByRole('button', { name: /^substring\b/ })).toHaveAccessibleDescription(
    'Start and length count Unicode characters (code points). SAP PO counts UTF-16 units, so the cut differs after an emoji or another character beyond the Basic Multilingual Plane.',
  );
  await catalogue.getByRole('button', { name: /^substring\b/ }).click();
  await node(page, /^Source field OrderId/).click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Connect to', exact: true }).click();
  await page.getByRole('menuitem', { name: 'substring · value', exact: true }).click();
  await node(page, /^Node function substring/).click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Connect to', exact: true }).click();
  await page.getByRole('menuitem', { name: 'OrderId · OrderId', exact: true }).click();
  await expect(node(page, /^Node function substring\. Complete/)).toBeVisible();

  await node(page, /^Node function substring/).getByRole('button', { name: 'Parameters for substring' }).click();
  const parameters = page.getByRole('dialog', { name: 'Function parameters · substring' });
  await parameters.getByLabel('Character Count').fill('3');
  await page.screenshot({ path: '.logs/157-parameters.png' });
  await parameters.getByRole('button', { name: 'Apply' }).click();
  await expect(parameters).toBeHidden();

  const box = (await node(page, /^Node function substring/).boundingBox())!;
  await page.mouse.move(box.x + 40, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + 50, box.y + 40, { steps: 5 });
  await page.mouse.move(box.x + 60, box.y + 90, { steps: 5 });
  await page.mouse.up();
  const moved = await transformOf(page, /^Node function substring/);
  await expect(page.getByText('3 nodes · 2 connections')).toBeVisible();
  await page.screenshot({ path: '.logs/157-definition-edit.png' });

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Mapping order-expression saved/ })).toBeVisible();

  // Back to the list and Forward opens the mapping again, from the same fake service
  await page.goBack();
  await expect(page.getByRole('button', { name: 'New mapping' })).toBeVisible();
  await page.goForward();
  await page.getByRole('tab', { name: 'Definition' }).click();
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();

  await expect(page.getByText('3 nodes · 2 connections')).toBeVisible();
  await expect(node(page, /^Node function substring\. Complete/)).toBeVisible();
  await expect(node(page, /^Node function substring/)).toContainText('length 3');
  expect(await transformOf(page, /^Node function substring/)).toBe(moved);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
  await page.screenshot({ path: '.logs/157-definition-view.png' });
  expect(problems).toEqual([]);
});
