import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';

test.use({ viewport: { width: 1440, height: 900 } });

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const canvas = (page: Page) => page.getByRole('group', { name: 'Mapping canvas' });
const node = (page: Page, name: RegExp) => canvas(page).getByRole('group', { name });
const fields = (page: Page, side: 'source' | 'target') => page.getByRole('region', { name: `${side} message` }).getByRole('table', { name: `${side} fields` });
const menuItem = (page: Page, name: string) => page.getByRole('menuitem', { name, exact: true });
/** Waits for the dialogs' fade to end, so that a screenshot shows them as they stay. */
const settled = (page: Page) =>
  page.evaluate(() => Promise.all(document.getAnimations().filter((a) => a.effect?.getTiming().iterations !== Infinity).map((a) => a.finished)));

// The preview features on the fake API (#160): variables, grouped expressions, value mapping, lookups and test instances,
// each marked Preview, kept by Save in the browser only; one screenshot each.
test('variables, a grouped target, value mapping, lookups and test instances work as previews and Save keeps them', async ({ page }) => {
  const problems = collectProblems(page);
  await page.goto('/?api=fake');
  await page.getByRole('button', { name: 'New mapping' }).click();
  const modal = page.getByRole('dialog', { name: 'New mapping' });
  await modal.getByRole('textbox', { name: 'Name' }).fill('order-previews');
  await modal.getByRole('textbox', { name: 'Package' }).fill('mappings');
  await modal.getByRole('button', { name: 'Create' }).click();
  for (const side of ['source', 'target']) {
    await page.getByRole('button', { name: `Add ${side} message` }).click();
    const choice = page.getByRole('dialog', { name: `Choose ${side} message` });
    await choice.getByRole('textbox', { name: 'Bundle' }).fill('../structures/skeleton/order.xsd');
    await choice.getByRole('textbox', { name: 'Root element' }).fill(ORDER);
    await choice.getByRole('button', { name: 'Apply' }).click();
    await expect(choice).toBeHidden();
  }
  await page.getByRole('toolbar', { name: 'Source message actions' }).getByRole('button', { name: 'Insert row' }).click();
  await expect(page.getByRole('table', { name: 'Source signature' }).getByRole('row')).toHaveCount(3);
  await page.screenshot({ path: '.logs/160-signature.png' });

  await page.getByRole('tab', { name: 'Definition' }).click();
  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await fields(page, 'source').getByRole('button', { name: 'OrderId', exact: true }).dblclick();
  await expect(node(page, /^Source field OrderId/)).toBeVisible();

  await fields(page, 'target').getByRole('button', { name: 'Order', exact: true }).click({ button: 'right' });
  await menuItem(page, 'Add Variable').click();
  const variable = page.getByRole('dialog', { name: 'Add Variable' });
  await variable.getByRole('textbox', { name: 'Variable name' }).fill('total');
  await variable.getByRole('button', { name: 'Create' }).click();
  await fields(page, 'target').getByRole('button', { name: 'Variable total' }).click();
  await expect(node(page, /^Mapping variable total/)).toBeVisible();
  await page.screenshot({ path: '.logs/160-variables.png' });

  await fields(page, 'target').getByRole('button', { name: 'OrderId', exact: true }).click();
  await fields(page, 'target').getByRole('button', { name: 'Note', exact: true }).click({ button: 'right' });
  await menuItem(page, 'Add target to expression').click();
  await expect(node(page, /^Target field Note/)).toBeVisible();
  await page.screenshot({ path: '.logs/160-grouped.png' });

  const catalogue = page.getByRole('region', { name: 'Function catalogue' });
  await catalogue.getByRole('button', { name: /^Conversions/ }).click();
  await catalogue.getByRole('button', { name: /^Value mapping/ }).click();
  await node(page, /^Node function Value mapping/).getByRole('button', { name: 'Parameters for Value mapping' }).click();
  await expect(page.getByRole('dialog', { name: 'Value Mapping Properties' })).toBeVisible();
  await settled(page);
  await page.screenshot({ path: '.logs/160-value-mapping.png' });
  await page.getByRole('dialog', { name: 'Value Mapping Properties' }).getByRole('button', { name: 'Apply' }).click();

  await catalogue.getByRole('button', { name: /^RFC Lookup/ }).click();
  await node(page, /^Node function RFC Lookup/).dblclick();
  await page.getByRole('dialog', { name: 'RFC Lookup' }).getByRole('button', { name: 'Choose RFC module' }).click();
  await page.getByRole('dialog', { name: 'Choose Function Module' }).getByRole('button', { name: 'SXIDEMO_AIRL_FLIGHT_CHECKAVAIL' }).click();
  await page.getByRole('dialog', { name: 'Choose Function Module' }).getByRole('button', { name: 'Apply' }).click();
  await expect(page.getByRole('dialog', { name: 'RFC Lookup' })).toContainText('SXIDEMO_AIRL_FLIGHT_CHECKAVAIL');
  await settled(page);
  await page.screenshot({ path: '.logs/160-lookups.png' });
  await page.getByRole('dialog', { name: 'RFC Lookup' }).getByRole('button', { name: 'Apply' }).click();

  await page.getByRole('tab', { name: 'Test' }).click();
  const source = page.getByRole('region', { name: 'Source', exact: true }).getByRole('textbox', { name: 'Input XML' });
  const documents = {
    'first order': `<Order xmlns="urn:flottila:corpus:skeleton">\n  <OrderId>PO-1</OrderId>\n  <Note>first</Note>\n</Order>`,
    'second order': `<Order xmlns="urn:flottila:corpus:skeleton">\n  <OrderId>PO-2</OrderId>\n</Order>`,
  };
  for (const [name, text] of Object.entries(documents)) {
    await source.fill(text);
    await page.getByRole('button', { name: 'Add Test Instance' }).click();
    const instance = page.getByRole('dialog', { name: 'Add Test Instance' });
    await instance.getByRole('textbox', { name: 'Test instance name' }).fill(name);
    await instance.getByRole('button', { name: 'OK' }).click();
    await expect(instance).toBeHidden();
  }
  // choosing an instance loads its document into Source
  await page.getByRole('combobox', { name: 'Select test instance' }).selectOption('first order');
  await expect(source).toHaveValue(documents['first order']);
  await settled(page);
  await page.screenshot({ path: '.logs/160-test-instances.png' });

  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Mapping order-previews saved/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(problems).toEqual([]);
});
