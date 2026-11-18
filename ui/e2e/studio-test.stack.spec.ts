import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { displayQueue, edit, fields, openStudio, processingLog, result, runTest } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const corpus = (path: string) => new URL(`../../mapping/corpus/${path}`, import.meta.url);
const input = readFileSync(corpus('cases/fn/removeContexts/001/input.xml'), 'utf8');
const expected = readFileSync(corpus('cases/fn/removeContexts/001/expected.xml'), 'utf8');

// A concrete corpus mapping runs in the studio's Test tab on the real service. Sku through removeContexts onto All
// (fn/removeContexts/001); the Display Queue of removeContexts shows what the run traced.
test('@stack the studio runs a corpus mapping on the service: the Result pane shows its output, the queue its trace', async ({ page }) => {
  const problems = collectProblems(page);
  await openStudio(page, 'mappings/remove-contexts', 'Test');
  await edit(page);

  await runTest(page, input);
  for (const line of expected.split('\n').filter((l) => l.includes('<All>'))) {
    await expect(result(page)).toContainText(line.trim());
  }
  await expect(processingLog(page)).toContainText('Executed successfully');

  await page.getByRole('tab', { name: 'Definition', exact: true }).click();
  await fields(page, 'target').getByRole('button', { name: 'All', exact: true }).click();
  const queue = await displayQueue(page, /^Node function removeContexts/);

  // the output of removeContexts: one context with the three Skus
  const out = queue.getByRole('table', { name: 'removeContexts queue' });
  await expect(out.getByRole('columnheader', { name: 'removeContexts:out' })).toBeVisible();
  await expect(queue).toContainText('removeContexts:out · 1 context, 3 slots');
  await expect(queue).not.toContainText('stale');
  expect(problems).toEqual([]);
});
