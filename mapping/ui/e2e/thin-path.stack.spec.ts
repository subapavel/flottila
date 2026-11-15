import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';
import { fields, link, node, openStudio, result, runTest, saveOverService } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';
const input = readFileSync(new URL('../../corpus/cases/link/direct-copy/001/input.xml', import.meta.url), 'utf8');

test('@stack the real service: open, link, save, run a test, and the saved file is canonical', async ({ page, request }) => {
  const problems = collectProblems(page);

  await openStudio(page, 'mappings/order-id-copy', 'definition');

  await expect(fields(page, 'source')).toBeVisible();
  await expect(fields(page, 'target')).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await link(page, ORDER_ID, ORDER_ID);
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();
  expect((await saveOverService(page, 'order-id-copy')).status()).toBe(200);
  await runTest(page, input);

  await expect(result(page)).toContainText('<OrderId>PO 4711</OrderId>');
  expect(problems).toEqual([]);

  const saved = readFileSync(join(stackWorkspace, 'mappings', 'order-id-copy.mapping.json'));
  expect(saved.toString('utf8')).toContain('"result"');
  const response = await request.get('/api/mappings/mappings/order-id-copy');
  expect(response.ok()).toBe(true);
  expect(Buffer.from(await response.body()).equals(saved)).toBe(true);
});
