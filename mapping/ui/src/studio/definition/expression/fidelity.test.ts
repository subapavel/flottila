import { describe, expect, test } from 'vitest';
import { deviationNote } from './fidelity';

describe('deviationNote (GLOSSARY: Fidelity, ADR-0026, #207)', () => {
  test("is a deviation's catalogue text, as it stands", () => {
    expect(deviationNote({ level: 'deviation', text: 'Rounds half to even. SAP PO rounds half up.' })).toBe('Rounds half to even. SAP PO rounds half up.');
  });

  test('is nothing for an exact function or an assumed reading: the level is internal', () => {
    expect(deviationNote({ level: 'exact' })).toBeUndefined();
    expect(deviationNote({ level: 'assumed' })).toBeUndefined();
  });
});
