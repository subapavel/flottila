import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { displayQueue, fields, openStudio, processingLog, result } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);
const inputFile = fileURLToPath(corpus('cases/link/direct-copy/001/input.xml'));
const input = readFileSync(inputFile, 'utf8');
const expected = readFileSync(corpus('cases/link/direct-copy/001/expected.xml'), 'utf8');

// The Display Queue before a test run on the fake API (#177): in read mode it says where a test document is loaded; in
// edit mode it offers a test file, whose content becomes the Test tab's source, runs it and shows the box's queue (the
// fake traces nothing).
test('Display Queue before a run: read mode points to edit mode, edit mode loads a test file, runs it and shows the queue', async ({ page }) => {
  const problems = collectProblems(page);
  await openStudio(page, 'mappings/order-id-copy', 'definition', { fake: true });
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();

  let queue = await displayQueue(page, /^Source field OrderId/);
  await expect(queue).toContainText('No test data yet. A test document can be loaded in edit mode.');
  await expect(queue.getByLabel('Test file')).toHaveCount(0);
  await page.screenshot({ path: '.logs/177-queue-read-mode.png', animations: 'disabled' });
  await queue.getByRole('button', { name: 'Close' }).click();

  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  queue = await displayQueue(page, /^Source field OrderId/);
  await expect(queue.getByRole('button', { name: 'Load test file' })).toBeVisible();
  await page.screenshot({ path: '.logs/177-queue-upload.png', animations: 'disabled' });
  await queue.getByLabel('Test file').setInputFiles(inputFile);

  await expect(queue.getByRole('table', { name: 'OrderId queue' })).toContainText('Not evaluated in the last test run');
  await queue.getByRole('button', { name: 'Close' }).click();
  await page.getByRole('tab', { name: 'Test' }).click();
  await expect(page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' })).toHaveValue(input);
  await expect(result(page)).toHaveText(expected);
  await expect(processingLog(page)).toContainText('Executed successfully');
  expect(problems).toEqual([]);
});

// Read mode runs a test too (#177, the owner's rule in place of #158's): the source is a throw-away input, which a
// reload does not bring back.
test('in read mode a test runs on a throw-away source, which the browser does not keep', async ({ page }) => {
  const problems = collectProblems(page);
  await openStudio(page, 'mappings/order-id-copy', 'test', { fake: true });
  const source = page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' });

  await source.fill(input);
  await page.getByRole('button', { name: 'Run simulation' }).click();

  await expect(result(page)).toHaveText(expected);
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeVisible();
  await page.reload();
  await expect(source).toHaveValue('');
  expect(problems).toEqual([]);
});
