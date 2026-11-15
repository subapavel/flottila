import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { canvas, fields, node, openStudio } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const SAME_STEP = '../structures/refused/same-step.xsd';

/** Chooses side `side`'s message on the Signature tab, in edit mode, from its bundle and root element. */
async function chooseMessage(page: Page, side: 'Source' | 'Target', bundle: string, element: string) {
  await page.getByRole('table', { name: `${side} signature` }).getByRole('button', { name: 'Choose namespace or ID 1' }).click();
  const dialog = page.getByRole('dialog', { name: `Choose ${side.toLowerCase()} message` });
  await dialog.getByRole('textbox', { name: 'Bundle' }).fill(bundle);
  await dialog.getByRole('textbox', { name: 'Root element' }).fill(element);
  await dialog.getByRole('button', { name: 'Apply' }).click();
  await expect(dialog).toBeHidden();
}

// Orphaned target field mappings, drag-to-remap and store refusals on the fake API (#174): order-id-copy's messages
// become the same-step structure, so its OrderId target field mapping and the box in it read fields that are gone.
test('the orphaned target field mapping is listed and opened; a dropped source row remaps its box, a refused one says why; Delete removes it', async ({ page }) => {
  const problems = collectProblems(page);
  await openStudio(page, 'mappings/order-id-copy', 'signature', { fake: true });
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await chooseMessage(page, 'Source', SAME_STEP, 'Doc');
  await chooseMessage(page, 'Target', SAME_STEP, 'Doc');
  await page.getByRole('tab', { name: 'Definition' }).click();

  const orphans = page.getByRole('region', { name: 'target message' }).getByRole('region', { name: 'Orphaned target field mappings' });
  await expect(orphans).toContainText('/Order/OrderId');
  await orphans.getByRole('button', { name: 'Open /Order/OrderId' }).click();
  const box = node(page, /^Source field OrderId/);
  await expect(box.getByRole('img', { name: 'Orphaned reference' })).toBeVisible();
  await page.screenshot({ path: '.logs/174-orphaned-group.png' });

  // a refused source field refuses the drop as it refuses a link (ADR-0027), and the alert says why
  await fields(page, 'source').locator('tr[data-field-path="/Doc/Note"]').first().dragTo(box);
  const refusal = page.getByRole('heading', { name: /The source field \/Doc\/Note is refused: ambiguous-field-path/ });
  await expect(refusal).toBeVisible();
  await page.screenshot({ path: '.logs/174-refusal.png' });
  await page.getByRole('button', { name: 'Dismiss refusal' }).click();
  await expect(refusal).toBeHidden();

  await fields(page, 'source').locator('tr[data-field-path="/Doc/Code"]').dragTo(box);
  await expect(node(page, /^Source field Code\. Complete/)).toBeVisible();
  await expect(canvas(page).getByRole('img', { name: 'Orphaned reference' })).toHaveCount(0);

  await orphans.getByRole('button', { name: 'Delete /Order/OrderId' }).click();
  await expect(orphans).toBeHidden();
  await expect(page.getByText('Select a target field to see its mapping expression.')).toBeVisible();
  expect(problems).toEqual([]);
});
