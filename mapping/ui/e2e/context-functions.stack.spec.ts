import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { collectProblems } from './problems';
import { stackWorkspace } from './stack-workspace';
import { fields, openStudio, result, runTest } from './studio';

test.use({ viewport: { width: 1440, height: 900 } });

const MAPPING = 'mappings/remove-contexts';
const corpus = (path: string) => new URL(`../../corpus/${path}`, import.meta.url);
const input = readFileSync(corpus('cases/fn/removeContexts/001/input.xml'), 'utf8');
const expected = readFileSync(corpus('cases/fn/removeContexts/001/expected.xml'), 'utf8');

test('@stack a mapping with a context function opens, saves through the API byte-identical and runs to the expected output', async ({ page, request }) => {
  const problems = collectProblems(page);

  const original = readFileSync(corpus(`${MAPPING}.mapping.json`));
  const file = join(stackWorkspace, `${MAPPING}.mapping.json`);

  await openStudio(page, MAPPING, 'definition');
  await expect(fields(page, 'source')).toBeVisible();
  await expect(fields(page, 'target')).toBeVisible();
  // An unchanged mapping has nothing to save: in edit mode Save is disabled. What the studio's Save sends is the model
  // it loaded; the scenario sends that same model.
  await page.getByRole('button', { name: 'Edit', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();

  const loaded = await request.get(`/api/mappings/${MAPPING}`);
  expect(loaded.ok()).toBe(true);
  const saved = await request.put(`/api/mappings/${MAPPING}`, { data: await loaded.json() });
  expect(saved.ok()).toBe(true);
  expect(readFileSync(file).equals(original)).toBe(true);
  expect(Buffer.from(await (await request.get(`/api/mappings/${MAPPING}`)).body()).equals(original)).toBe(true);

  await runTest(page, input);
  for (const line of expected.split('\n').filter((l) => l.includes('<All>'))) {
    await expect(result(page)).toContainText(line.trim());
  }
  expect(problems).toEqual([]);
});
