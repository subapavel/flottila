import { describe, expect, test } from 'vitest';
import { ApiProblem, describeError, isSaveConflict } from './client';

describe('a save conflict', () => {
  test('is a 409 mapping-changed problem, and nothing else', () => {
    const problem = (type: string, status: number) => new ApiProblem({ type, title: 't', status, detail: '' });

    expect(isSaveConflict(problem('urn:flottila:mapping:problem:mapping-changed', 409))).toBe(true);
    expect(isSaveConflict(problem('urn:flottila:mapping:problem:mapping-exists', 409))).toBe(false);
    expect(isSaveConflict(new Error('urn:flottila:mapping:problem:mapping-changed'))).toBe(false);
  });
});

describe('describing a failed call', () => {
  test('a refused model shows its title, detail and diagnostics', () => {
    const diagnostic = {
      severity: 'error' as const,
      code: 'box-cycle',
      message: 'a cycle',
      location: { kind: 'authoring-id' as const, authoringId: 't1' },
    };
    const error = new ApiProblem({ type: 'urn:x', title: 'Model refused', status: 422, detail: 'stage 2', diagnostics: [diagnostic] });

    expect(describeError(error)).toEqual({ title: 'Model refused', detail: 'stage 2', diagnostics: [diagnostic] });
  });

  test('a problem without diagnostics shows none', () => {
    const error = new ApiProblem({ type: 'urn:x', title: 'Not found', status: 404, detail: 'no mapping m' });

    expect(describeError(error)).toEqual({ title: 'Not found', detail: 'no mapping m', diagnostics: [] });
  });

  test('an error that is no problem, such as a lost connection, shows its message', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toEqual({ title: 'Failed to fetch', detail: '', diagnostics: [] });
    expect(describeError('offline')).toEqual({ title: 'offline', detail: '', diagnostics: [] });
  });
});
