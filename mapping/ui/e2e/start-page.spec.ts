import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { fieldRow, fields, node, openStudio, selectTarget } from './studio';

const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';

test('the start page lists the mappings on the fake API, opens one in the studio, and the brand goes back to the list', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');

  const list = page.getByRole('table', { name: 'Mappings' });
  await expect(list.locator('td[data-label="Name"]')).toHaveText(['order-id-copy', 'order-unmapped', 'tree-20128']);
  await list.getByRole('row').filter({ hasText: 'order-id-copy' }).getByRole('link', { name: 'Open in studio' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'order-id-copy' })).toBeVisible();
  await expect((await fieldRow(page, 'target', ORDER_ID)).getByRole('img', { name: 'Connected' })).toBeVisible();
  await expect(list).toBeHidden();

  await page.getByRole('link', { name: 'Flottila' }).click();

  await expect(list).toBeVisible();
  await expect(fields(page, 'target')).toBeHidden();
  expect(problems).toEqual([]);
});

test('the address opens a mapping on the tab its hash names, and a target field shows its expression', async ({ page }) => {
  const problems = collectProblems(page);

  await openStudio(page, 'mappings/order-id-copy', 'definition', { fake: true });
  await expect(page.getByRole('tab', { name: 'Definition' })).toHaveAttribute('aria-selected', 'true');
  await selectTarget(page, ORDER_ID);

  await expect(node(page, /^Target field OrderId\. Complete/)).toBeVisible();
  expect(problems).toEqual([]);
});
