import { expect, test } from '@playwright/test';

// The default suite has no service behind the preview's /api proxy, and the start page lists the mappings at start:
// over HTTP that answers 502, which Chromium logs as a console error. So the smoke test starts on the fake API.
test('the start page loads without console errors', async ({ page }) => {
  const problems: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(message.text());
  });
  page.on('pageerror', (error) => problems.push(error.message));

  await page.goto('/?api=fake');

  await expect(page.getByRole('heading', { name: 'Mappings', exact: true })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Mappings' }).getByRole('link', { name: 'Open in studio' })).toHaveCount(3);
  expect(problems).toEqual([]);
});

// The start page shows "Loading the mappings" (an h2) while the list is on its way. A held answer keeps that state
// on screen, so the Mappings heading must be found among it, not by luck of timing.
test('the Mappings heading is unique while the list is still loading', async ({ page }) => {
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route('**/api/mappings', async (route) => {
    await held;
    await route.fulfill({ json: [{ id: 'a/one', name: 'one', package: 'a' }] });
  });

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Loading the mappings' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Mappings', exact: true })).toBeVisible();
  release();
  await expect(page.getByRole('table', { name: 'Mappings' })).toBeVisible();
});
