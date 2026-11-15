import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { displayQueue, dropFunction, fieldRow, link, node, result, runTest, save, selectTarget, setParameters } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

// The studio's author path on the fake API (#117, ADR-0028). The fake runs no engine: a run answers the corpus case
// link/direct-copy/001 when the field links match it, with an empty trace, so the display queues show that nothing
// was traced; the @stack suite shows their contents.
const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';
const input = readFileSync(new URL('../../corpus/cases/link/direct-copy/001/input.xml', import.meta.url), 'utf8');

test('the author path: list, open, link by drag and drop, a function, a constant and a parameter, save, run, display queues', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');

  await page.getByRole('row').filter({ hasText: 'order-unmapped' }).getByRole('link', { name: 'Open in studio' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'order-unmapped' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await link(page, ORDER_ID, ORDER_ID);
  await expect((await fieldRow(page, 'target', ORDER_ID)).getByRole('img', { name: 'Connected' })).toBeVisible();
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();

  // a function and a constant from the function catalogue, beside the link's source box; the result stays the link
  await selectTarget(page, ORDER_ID);
  await dropFunction(page, 'Text', 'substring', { x: 60, y: 40 });
  await dropFunction(page, 'Constants', 'constant', { x: 60, y: 160 });
  // three boxes and the target field
  await expect(page.getByText('4 nodes · 1 connections')).toBeVisible();
  await page.getByRole('toolbar', { name: 'Mapping tools' }).getByRole('button', { name: 'Layout mapping' }).click();
  await expect(node(page, /^Source field OrderId\. Complete/)).toBeVisible();

  // a box parameter and the constant's value, in their parameter dialogs; each card shows its parameters
  await setParameters(page, 'substring', { 'Start Position': '3' });
  await expect(node(page, /^Node function substring/)).toContainText('start 3');
  await setParameters(page, 'constant', { Value: 'X' });
  await expect(node(page, /^Node function constant/)).toContainText('value X');

  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  await save(page, 'order-unmapped');

  await runTest(page, input);
  await expect(result(page)).toContainText('<OrderId>PO 4711</OrderId>');

  // the display queue of the function box, then of the target field, back on the Definition tab
  await page.getByRole('tab', { name: 'Definition' }).click();
  await selectTarget(page, ORDER_ID);
  const boxQueue = await displayQueue(page, /^Node function substring/);
  await expect(boxQueue.getByRole('table', { name: 'substring queue' })).toContainText('Not evaluated in the last test run');
  await boxQueue.getByRole('button', { name: 'Close' }).click();
  await expect(boxQueue).toBeHidden();
  const fieldQueue = await displayQueue(page, /^Target field OrderId/);
  await expect(fieldQueue.getByRole('table', { name: 'OrderId queue' })).toContainText('Not evaluated in the last test run');
  await expect(fieldQueue.getByRole('columnheader', { name: 'OrderId:OrderId' })).toBeVisible();
  expect(problems).toEqual([]);
});
