import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { displayQueue, fields } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const inputFile = fileURLToPath(new URL('../../corpus/cases/fn/removeContexts/001/input.xml', import.meta.url));

/** The Display Queue of removeContexts on All's canvas. */
async function removeContextsQueue(page: Page) {
  await fields(page, 'target').getByRole('button', { name: 'All', exact: true }).click();
  return displayQueue(page, /^Node function removeContexts/);
}

/** The output column of removeContexts: one context with the three Skus of the corpus input. */
async function expectTracedSkus(page: Page) {
  const queue = page.getByRole('dialog', { name: 'Display Queue' });
  const out = queue.getByRole('table', { name: 'removeContexts queue' });
  await expect(out.getByRole('columnheader', { name: 'removeContexts:out' })).toBeVisible();
  for (const [row, sku] of [[1, 'a'], [2, 'b'], [3, 'c']] as const) await expect(out.getByRole('row').nth(row)).toContainText(sku);
  await expect(queue).toContainText('removeContexts:out · 1 context, 3 slots');
  await expect(queue).not.toContainText('stale');
}

// The owner's journey of #177 on the real service: Display Queue before any run loads the corpus input.xml (edit mode),
// runs the working copy on it and shows the traced queue; reopened in read mode with that source, it runs at once.
test('@stack Display Queue loads a test file, runs it on the service and shows the queue; with a source it runs at once', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?mapping=mappings/remove-contexts#definition');
  await page.getByRole('button', { name: 'Edit', exact: true }).click();

  const queue = await removeContextsQueue(page);
  await queue.getByLabel('Test file').setInputFiles(inputFile);

  await expectTracedSkus(page);
  await page.screenshot({ path: '.logs/177-queue-file-run-stack.png', animations: 'disabled' });

  // the source loaded in edit mode stays in the browser: a reload opens in read mode, and the queue runs it at once
  await page.reload();
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeVisible();
  await removeContextsQueue(page);
  await expectTracedSkus(page);
  await page.screenshot({ path: '.logs/177-queue-auto-run.png', animations: 'disabled' });
  expect(problems).toEqual([]);
});
