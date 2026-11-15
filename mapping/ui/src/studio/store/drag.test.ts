import { describe, expect, test } from 'vitest';
import { SOURCE_FIELD_DRAG } from './drag';

describe('the source field drag type', () => {
  test('is the editor-own MIME type application/x-flottila-source-field', () => {
    expect(SOURCE_FIELD_DRAG).toBe('application/x-flottila-source-field');
  });
});
