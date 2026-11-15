import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { canvas, fields, openStudio, selectTarget } from './studio';

// The layout contract of spec #135, on the studio (ADR-0028): on a 1440 x 900 window the page fills the window and never
// scrolls; the Definition tab shows both message panes, the canvas with its tools and the function catalogue, and the
// Test tab shows Run simulation, the result and the processing log.
const ORDER_ID = '/Q{urn:flottila:corpus:skeleton}Order/OrderId';

test.use({ viewport: { width: 1440, height: 900 } });

/** How far the document scrolls vertically: 0 when the page fits the window. */
const verticalScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollHeight - document.documentElement.clientHeight);

test('at 1440 x 900 the studio fills the window without a page scroll, on the Definition and on the Test tab', async ({ page }) => {
  const problems = collectProblems(page);
  await openStudio(page, 'mappings/order-id-copy', 'definition', { fake: true });
  await selectTarget(page, ORDER_ID);

  await expect(page.getByRole('tab', { name: 'Definition' })).toHaveAttribute('aria-selected', 'true');
  await expect(fields(page, 'source')).toBeInViewport();
  await expect(fields(page, 'target')).toBeInViewport();
  await expect(canvas(page)).toBeInViewport();
  // the canvas fits its pane: its tools and its footer are fully shown, not scrolled away
  await expect(page.getByRole('toolbar', { name: 'Mapping tools' })).toBeInViewport({ ratio: 1 });
  await expect(page.getByText('2 nodes · 1 connections')).toBeInViewport({ ratio: 1 });
  await expect(page.getByRole('region', { name: 'Function catalogue' })).toBeInViewport();
  expect(await verticalScroll(page)).toBe(0);

  await page.getByRole('tab', { name: 'Test' }).click();

  await expect(page.getByRole('button', { name: 'Run simulation' })).toBeInViewport();
  // the result region is wholly on screen, not pushed below the fold, and so is the processing log's head
  await expect(page.getByRole('region', { name: 'Result', exact: true })).toBeInViewport({ ratio: 1 });
  await expect(page.getByRole('heading', { name: 'Processing log' })).toBeInViewport({ ratio: 1 });
  expect(await verticalScroll(page)).toBe(0);
  expect(problems).toEqual([]);
});
