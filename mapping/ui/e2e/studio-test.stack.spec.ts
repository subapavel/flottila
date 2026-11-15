import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';

test.use({ viewport: { width: 1440, height: 900 } });

const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);
const input = readFileSync(corpus('cases/fn/removeContexts/001/input.xml'), 'utf8');
const expected = readFileSync(corpus('cases/fn/removeContexts/001/expected.xml'), 'utf8');

// The owner's goal for #158: a concrete corpus mapping runs in the studio's Test tab on the real service. Sku through
// removeContexts onto All (fn/removeContexts/001); the Display Queue of removeContexts shows what the run traced.
test('@stack the studio runs a corpus mapping on the service: the Result pane shows its output, the queue its trace', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?mapping=mappings/remove-contexts#test');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  await page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' }).fill(input);
  await page.getByRole('button', { name: 'Run simulation' }).click();

  const result = page.getByRole('region', { name: 'Result', exact: true }).locator('pre');
  for (const line of expected.split('\n').filter((l) => l.includes('<All>'))) {
    await expect(result).toContainText(line.trim());
  }
  await expect(page.getByRole('region', { name: 'Processing log' })).toContainText('Executed successfully');
  await expect(page.getByRole('button', { name: 'Run simulation' })).toBeEnabled();
  await page.screenshot({ path: '.logs/158-test-run-stack.png', animations: 'disabled' });

  await page.getByRole('tab', { name: 'Definition' }).click();
  await page.getByRole('region', { name: 'target message' }).getByRole('table', { name: 'target fields' }).getByRole('button', { name: 'All', exact: true }).click();
  await page.getByRole('group', { name: 'Mapping canvas' }).getByRole('group', { name: /^Node function removeContexts/ }).click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Display Queue', exact: true }).click();

  // the output of removeContexts: one context with the three Skus
  const queue = page.getByRole('dialog', { name: 'Display Queue' });
  const out = queue.getByRole('table', { name: 'removeContexts queue' });
  await expect(out.getByRole('columnheader', { name: 'removeContexts:out' })).toBeVisible();
  await expect(queue).toContainText('removeContexts:out · 1 context, 3 slots');
  await expect(queue).not.toContainText('stale');
  await page.screenshot({ path: '.logs/158-display-queue.png', animations: 'disabled' });
  expect(problems).toEqual([]);
});
