import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { fields, link, node, openStudio, result, runTest, save } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';
const input = readFileSync(new URL('../../corpus/cases/link/direct-copy/001/input.xml', import.meta.url), 'utf8');

test('the studio opens a mapping on the fake API, links a field, saves and runs a test', async ({ page }) => {
  const problems = collectProblems(page);

  await openStudio(page, 'mappings/order-unmapped', 'definition', { fake: true });

  await expect(fields(page, 'source')).toBeVisible();
  await expect(fields(page, 'target')).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await link(page, ORDER_ID, ORDER_ID);
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  await save(page, 'order-unmapped');
  await runTest(page, input);

  await expect(result(page)).toContainText('<OrderId>PO 4711</OrderId>');
  expect(problems).toEqual([]);
});
