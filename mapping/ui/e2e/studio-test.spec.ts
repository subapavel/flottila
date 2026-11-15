import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';

test.use({ viewport: { width: 1440, height: 900 } });

const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);
const input = readFileSync(corpus('cases/link/direct-copy/001/input.xml'), 'utf8');
const expected = readFileSync(corpus('cases/link/direct-copy/001/expected.xml'), 'utf8');
const fields = (page: Page, side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });

// The Test tab on the fake API (#158): a corpus mapping runs on its corpus case, the result and the log show the run, and
// the box's Display Queue reads the run's trace (the fake traces nothing) and marks it stale after an edit.
test('the Test tab runs a corpus mapping on its case and shows the result and the log', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake&mapping=mappings/order-id-copy#test');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  const source = page.getByRole('region', { name: 'Source', exact: true });
  await source.getByRole('textbox', { name: 'Input XML' }).fill(input);
  await page.getByRole('button', { name: 'Run simulation' }).click();

  const result = page.getByRole('region', { name: 'Result', exact: true });
  await expect(result.locator('pre')).toHaveText(expected);
  const log = page.getByRole('region', { name: 'Processing log' });
  await expect(log).toContainText('Start of test');
  await expect(log).toContainText('Executed successfully');
  await expect(log).toContainText('End of test');
  await expect(page.getByRole('button', { name: 'Run simulation' })).toBeEnabled();
  await page.screenshot({ path: '.logs/158-test-run.png', animations: 'disabled' });

  await page.getByRole('tab', { name: 'Definition' }).click();
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  const node = page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name: /^Source field OrderId/ });
  await node.click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Display Queue', exact: true }).click();
  const queue = page.getByRole('dialog', { name: 'Display Queue' });
  await expect(queue.getByRole('table', { name: 'OrderId queue' })).toContainText('Not evaluated in the last test run');
  await expect(queue).not.toContainText('stale');
  await queue.getByRole('button', { name: 'Close' }).click();

  await page.getByRole('tab', { name: 'Overview' }).click();
  await page.getByRole('textbox', { name: 'Description' }).fill('Edited after the run');
  await page.getByRole('tab', { name: 'Definition' }).click();
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await node.click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Display Queue', exact: true }).click();
  await expect(queue).toContainText('The working copy changed since this run.');
  await page.screenshot({ path: '.logs/158-display-queue-fake.png', animations: 'disabled' });
  expect(problems).toEqual([]);
});
