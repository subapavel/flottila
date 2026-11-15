import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';

// The Mapping studio's shell on the fake API (ADR-0028, #154): the start page, New mapping, and the new mapping in
// the studio in edit mode on Signature; a listed mapping opens read-only on the tab its address names.
test('start page, New mapping, Create: the studio opens the new mapping in edit mode on Signature; Cancel returns to view mode', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');

  await page.getByRole('button', { name: 'New mapping' }).click();
  const modal = page.getByRole('dialog', { name: 'New mapping' });
  await modal.getByRole('textbox', { name: 'Name' }).fill('order-to-invoice');
  await modal.getByRole('textbox', { name: 'Package' }).fill('mappings');
  await modal.getByRole('button', { name: 'Create' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'order-to-invoice' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Signature' })).toHaveAttribute('aria-selected', 'true');
  await expect(page).toHaveURL(/\?api=fake&mapping=mappings%2Forder-to-invoice#signature$/);
  await page.getByRole('tab', { name: 'Definition' }).click();
  await expect(page.getByRole('heading', { name: 'Choose the source and target messages' })).toBeVisible();

  await page.getByRole('button', { name: 'Cancel' }).click();

  await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();
  expect(problems).toEqual([]);
});

test('Open in studio opens a listed mapping read-only on Definition', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');

  await page.getByRole('row').filter({ hasText: 'order-id-copy' }).getByRole('link', { name: 'Open in studio' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'order-id-copy' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Definition' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeVisible();
  expect(problems).toEqual([]);
});
