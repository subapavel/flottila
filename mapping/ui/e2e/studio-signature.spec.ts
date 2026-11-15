import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';

// The Overview and Signature tabs on the fake API (#155): a new mapping chooses its messages on Signature, saves them,
// and the Overview names them; a second message is a preview, a draft that makes the mapping unsaved until Save (ADR-0028).
test('New mapping, Add source message and Add target message on Signature, Save; the Overview names both messages', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');
  await page.getByRole('button', { name: 'New mapping' }).click();
  const modal = page.getByRole('dialog', { name: 'New mapping' });
  await modal.getByRole('textbox', { name: 'Name' }).fill('order-copy');
  await modal.getByRole('textbox', { name: 'Package' }).fill('mappings');
  await modal.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('tab', { name: 'Signature' })).toHaveAttribute('aria-selected', 'true');

  for (const side of ['source', 'target']) {
    await page.getByRole('button', { name: `Add ${side} message` }).click();
    const choice = page.getByRole('dialog', { name: `Choose ${side} message` });
    await choice.getByRole('textbox', { name: 'Bundle' }).fill('../structures/skeleton/order.xsd');
    await choice.getByRole('textbox', { name: 'Root element' }).fill('Q{urn:flottila:corpus:skeleton}Order');
    await choice.getByRole('button', { name: 'Apply' }).click();
    await expect(choice).toBeHidden();
  }
  await expect(page.getByRole('table', { name: 'Target signature' }).getByRole('button', { name: 'Choose message 1' })).toHaveText('Order');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Mapping order-copy saved/ })).toBeVisible();

  await page.getByRole('toolbar', { name: 'Source message actions' }).getByRole('button', { name: 'Insert row' }).click();
  await expect(page.getByRole('table', { name: 'Source signature' }).getByRole('row')).toHaveCount(3);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();

  await page.getByRole('tab', { name: 'Overview' }).click();
  const details = page.getByRole('region', { name: 'Overview' });
  await expect(details.getByRole('definition')).toHaveText(['order-copy', 'mappings/order-copy', 'mappings', 'Order', 'Order']);
  expect(problems).toEqual([]);
});
