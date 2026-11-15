import { describe, expect, test } from 'vitest';
import { describeRefusal, refusalOf, type BoxRefusal } from './command';

describe('why a command was refused, in words', () => {
  test.each<[BoxRefusal['code'], string]>([
    ['box-cycle', 'The wire would close a cycle: box b1 already feeds the box it would read.'],
    ['cross-target-field-reference', 'Box b1 belongs to another target field mapping.'],
    ['duplicate-authoring-id', 'The id b1 is already used.'],
    ['unknown-box-reference', 'There is no box b1.'],
    ['wrong-arity', 'Box b1 has no input slot there.'],
    ['context-not-ancestor', 'That context is not a proper ancestor of the field box b1 reads.'],
    ['unknown-parameter', 'Box b1 has no such parameter to set.'],
    ['wrong-parameter-type', 'That value has the wrong type for the parameter of box b1.'],
    ['invalid-parameter-value', 'That value is not one the parameter of box b1 takes.'],
  ])('%s', (code, text) => {
    expect(describeRefusal({ code, id: 'b1' })).toBe(text);
  });

  test('refused-field names the side, the field, the reason and the declaration (ADR-0027)', () => {
    const refusal = { code: 'ambiguous-field-path', side: 'both' as const, file: 'same-step.xsd', line: 14, column: 65 };

    expect(describeRefusal({ code: 'refused-field', side: 'source', field: '/Doc/Note', refusal })).toBe(
      'The source field /Doc/Note is refused: ambiguous-field-path, declared at same-step.xsd:14:65.',
    );
  });
});

describe("a refused result's refusal", () => {
  test('drops only the kind', () => {
    const refusal = { code: 'ambiguous-field-path', side: 'both' as const, file: 'a.xsd', line: 1, column: 2 };

    expect(refusalOf({ kind: 'refused', code: 'box-cycle', id: 'b1' })).toEqual({ code: 'box-cycle', id: 'b1' });
    expect(refusalOf({ kind: 'refused', code: 'refused-field', side: 'target', field: '/A', refusal })).toEqual({
      code: 'refused-field',
      side: 'target',
      field: '/A',
      refusal,
    });
  });
});
