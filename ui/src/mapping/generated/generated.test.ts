import { readdirSync, readFileSync } from 'node:fs';
import { expect, test } from 'vitest';

// The types here are generated from the mapping contract by the old editor's `npm run gen:types`, whose build checks
// them against the contract; this copy must stay byte for byte the same as that output.
const here = new URL('./', import.meta.url);
const generated = new URL('../../../../mapping/ui/src/generated/', import.meta.url);

test('the generated types are the same as those generated from the mapping contract', () => {
  const names = readdirSync(generated).sort();
  expect(readdirSync(here).filter((name) => !name.endsWith('.test.ts')).sort()).toEqual(names);
  for (const name of names) expect(readFileSync(new URL(name, here), 'utf8'), name).toBe(readFileSync(new URL(name, generated), 'utf8'));
});
