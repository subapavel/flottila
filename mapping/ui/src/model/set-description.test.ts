import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { setDescription } from './set-description';

const described: MappingModel = { formatVersion: 1, description: 'Copies the order id.' };
const blank: MappingModel = { formatVersion: 1 };

describe('the set description command (GLOSSARY: Mapping description)', () => {
  test('sets the description and leaves the rest of the model', () => {
    const result = setDescription(described, { description: 'Copies the order id and the note.' });

    expect(result).toEqual({ kind: 'applied', model: { formatVersion: 1, description: 'Copies the order id and the note.' } });
    expect(described.description).toBe('Copies the order id.');
  });

  test('an empty description removes the member, as the canonical writer leaves an empty one out (ADR-0029)', () => {
    const result = setDescription(described, { description: '' });

    expect(result.kind === 'applied' && result.model).toEqual({ formatVersion: 1 });
  });

  test('the same description gives back the same model', () => {
    const result = setDescription(described, { description: 'Copies the order id.' });

    expect(result.kind === 'applied' && result.model).toBe(described);
  });

  test('an empty description on a model without one gives back the same model', () => {
    const result = setDescription(blank, { description: '' });

    expect(result.kind === 'applied' && result.model).toBe(blank);
  });
});
