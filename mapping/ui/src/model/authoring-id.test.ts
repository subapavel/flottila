import { expect, test } from 'vitest';
import { newAuthoringId } from './authoring-id';

test('an authoring id is 10 lowercase Crockford base32 characters drawn from the random source (ADR-0010)', () => {
  // Each byte selects one of the 32 characters by its low five bits, so 62 selects the 31st, 'y'.
  const bytes = [0, 9, 10, 17, 18, 20, 21, 26, 31, 32 + 30];
  const fill = (target: Uint8Array) => {
    target.set(bytes);
    return target;
  };

  expect(newAuthoringId(fill)).toBe('09ahjmntzy');
});

test('the default random source gives ids of the schema pattern', () => {
  const ids = new Set(Array.from({ length: 20 }, () => newAuthoringId()));

  for (const id of ids) expect(id).toMatch(/^[0-9a-hjkmnp-tv-z]{10}$/);
  expect(ids.size).toBe(20);
});
