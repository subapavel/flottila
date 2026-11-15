import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { collectProblems } from './problems';
import { fieldRow, openStudio, processingLog, result, runTest, type Side } from './studio';

// a tall window: the message panes, maximised, show the gate structures without scrolling their virtualised rows
test.use({ viewport: { width: 1440, height: 1800 } });

// GATE check 1 (#100): the gate structures of corpus/structures/gate, read by the real service from its workspace and
// shown in the studio's message panes (Table view), each pane fully open as it opens.
const PO = 'Q{urn:flottila:corpus:gate:po}';
const INV = 'Q{urn:flottila:corpus:gate:invoice}';
const PTY = 'Q{urn:flottila:corpus:gate:party}';
const PURCHASE_ORDER = `/${PO}PurchaseOrder`;
const INVOICE = `/${INV}Invoice`;
const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);

/** Opens a mapping in the studio on Definition with the message trees maximised; a test run needs edit mode, so it starts editing. */
async function open(page: Page, mapping: string) {
  await openStudio(page, mapping, 'definition');
  await page.getByRole('button', { name: 'Maximize message trees' }).click();
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
}

/**
 * The row of a field shows its value type (none for a structure field) and its effective occurrence. An XML pane names
 * an XSD type with the prefix `xsd:`, as the prototype does; a JSON pane shows the value type as the structure has it.
 */
async function expectField(page: Page, side: Side, path: string, valueType: string, occurrence: string) {
  const row = await fieldRow(page, side, path);
  await expect(row.locator('td.field-type-column')).toHaveText(valueType);
  await expect(row.locator('td.occurs')).toHaveText(occurrence);
}

/** Opens a collapsed row: a recursive type stays closed below its first level as the pane opens. */
async function expand(page: Page, side: Side, path: string) {
  await (await fieldRow(page, side, path)).getByRole('button', { name: /^Expand / }).click();
}

test('@stack the XSD gate structures show their types and effective occurrences, as source and as target', async ({ page }) => {
  const problems = collectProblems(page);
  await open(page, 'mappings/gate-po-invoice');

  // purchase-order: three files, an include and an import
  await expectField(page, 'source', PURCHASE_ORDER, '', '1..1');
  await expectField(page, 'source', `${PURCHASE_ORDER}/@issued`, 'xsd:date', '0..1');
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}OrderId`, 'xsd:string', '1..1');
  // the two branches of a choice, each declared 1..1
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Delivery`, '', '0..1');
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Pickup`, 'xsd:string', '0..1');
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Line`, '', '1..unbounded');
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Line/@no`, 'xsd:integer', '1..1');
  // the substitution group of the abstract head Product: its two members
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Line/${PO}Article`, '', '0..1');
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Line/${PO}Service`, '', '0..1');
  // a simple-content leaf with attributes
  await expectField(page, 'source', `${PURCHASE_ORDER}/${PO}Line/${PO}Price`, 'xsd:decimal', '1..1');

  // invoice: an element reference across files, and a recursive type
  await expectField(page, 'target', INVOICE, '', '1..1');
  await expectField(page, 'target', `${INVOICE}/${PTY}Party`, '', '1..1');
  await expectField(page, 'target', `${INVOICE}/${INV}Item`, '', '1..unbounded');
  await expectField(page, 'target', `${INVOICE}/${INV}Date`, 'xsd:date', '0..1');
  await expand(page, 'target', `${INVOICE}/${INV}Section/${INV}Section`);
  await expectField(page, 'target', `${INVOICE}/${INV}Section/${INV}Section/${INV}Section`, '', '0..unbounded');
  await expectField(page, 'target', `${INVOICE}/${INV}Section/${INV}Section/${INV}Title`, 'xsd:string', '1..1');
  expect(problems).toEqual([]);
});

test('@stack the JSON gate structure as a source: its tree, then a test run shows the output', async ({ page }) => {
  const problems = collectProblems(page);
  await open(page, 'mappings/gate-order-po');

  await expectField(page, 'source', '/$', '', '1..1');
  await expectField(page, 'source', '/$/id', 'xs:string', '1..1');
  await expectField(page, 'source', '/$/issued', 'xs:date', '0..1');
  // an array of objects
  await expectField(page, 'source', '/$/lines', '', '1..unbounded');
  // a nullable type list
  await expectField(page, 'source', '/$/note', 'xs:string', '0..1');
  // allOf over a $ref
  await expectField(page, 'source', '/$/customer/name', 'xs:string', '1..1');
  await expectField(page, 'source', '/$/customer/number', 'xs:string', '1..1');
  // the branches of a oneOf
  await expectField(page, 'source', '/$/delivery/address', '', '0..1');
  await expectField(page, 'source', '/$/delivery/pickup', 'xs:string', '0..1');
  await expectField(page, 'target', PURCHASE_ORDER, '', '1..1');

  await runTest(page, readFileSync(corpus('cases/gate/jsonToXsd/001/input.json'), 'utf8'), 'JSON');

  const output = result(page);
  await expect(output).toContainText('PurchaseOrder');
  for (const text of ['issued="2026-04-01"', '>J-200<', '>Grace Hopper<', '>Arlington<', 'no="2"', '>A-3<', 'currency="USD">1.50<']) {
    await expect(output).toContainText(text);
  }
  expect(problems).toEqual([]);
});

test('@stack the JSON gate structure loads as a target, and a test run gives json-target-unsupported', async ({ page }) => {
  const problems = collectProblems(page);
  await open(page, 'mappings/gate-invoice-order');

  await expectField(page, 'source', INVOICE, '', '1..1');
  await expectField(page, 'target', '/$', '', '1..1');
  await expectField(page, 'target', '/$/lines', '', '1..unbounded');

  await runTest(page, readFileSync(corpus('cases/gate/jsonTarget/001/input.xml'), 'utf8'));

  // a run that compile refuses shows the refusal and its diagnostics in the processing log, and no result
  await expect(processingLog(page)).toContainText('Compile refused the working copy: nothing ran.');
  await expect(processingLog(page)).toContainText('json-target-unsupported');
  await expect(page.getByRole('region', { name: 'Result', exact: true })).toContainText('The simulation failed.');
  await expect(result(page)).toBeHidden();
  expect(problems).toEqual([]);
});
