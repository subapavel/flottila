import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import { checkOptions, shownFields, type Options, type Problem, type SettingsDescription } from './settings';

/** The cases the platform's check runs too, so that the form and the deploy judge alike. */
const shared = JSON.parse(readFileSync(new URL('../../../platform/api/src/test/resources/dev/rail8/api/settings/check-cases.json', import.meta.url), 'utf8')) as {
  description: SettingsDescription;
  cases: { name: string; direction: 'in' | 'out'; options: Options; problems: Problem[] }[];
};

describe('checkOptions', () => {
  test.each(shared.cases)('$name', ({ direction, options, problems }) => {
    expect(checkOptions(shared.description, options, direction)).toEqual(problems);
  });

  test('a component that describes nothing is not judged here', () => {
    expect(checkOptions({ format: 1, described: false, tabs: [], fields: [], rules: [] }, { anything: 1 }, 'in')).toEqual([]);
  });

  test('a field of a type the form does not know makes no claim on its value', () => {
    const description: SettingsDescription = {
      format: 1,
      described: true,
      tabs: [],
      fields: [{ name: 'headers', type: 'map', help: 'Headers.', tab: 'general', required: true, directions: ['out'] }],
      rules: [],
    };
    expect(checkOptions(description, { headers: [1, 2] }, 'out')).toEqual([]);
    expect(checkOptions(description, {}, 'out')).toEqual([{ path: '/headers', message: 'is required' }]);
  });
});

describe('shownFields', () => {
  test('a field is shown while the field it names has one of its values, set or by default', () => {
    const shown = (options: Options) => [...shownFields(shared.description, options)].filter((n) => ['moveTo', 'archive', 'archiveDir'].includes(n));
    expect(shown({})).toEqual([]);
    expect(shown({ afterSuccess: 'move' })).toEqual(['moveTo', 'archive']);
    expect(shown({ afterSuccess: 'move', archive: true })).toEqual(['moveTo', 'archive', 'archiveDir']);
    expect(shown({ archive: true })).toEqual([]);
  });
});
