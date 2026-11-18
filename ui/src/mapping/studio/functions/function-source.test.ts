import { describe, expect, test } from 'vitest';
import { emptyFunctions, newFunction, type FunctionsState } from '../mock/function-model';
import { exportSource, importSource } from './function-source';

const MAPPING = 'mappings/order-id-copy';
const state: FunctionsState = {
  ...emptyFunctions(),
  imports: ['java.util.*', ''],
  javaAreas: { 'Attributes and Methods': { body: 'static final int N = 1;', description: '', variables: [] } },
  functions: [{ ...newFunction('calculate1', 'f1'), body: 'return in0;' }],
};

/** `text` with the body of the section `key` replaced by `body`. */
function withSection(text: string, key: string, body: string): string {
  return text.replace(new RegExp(`(// beginning of ${key} \\w+\\n)[^]*?(\\n// end of ${key} )`), `$1${body}$2`);
}

describe('exportSource', () => {
  test('is one class named after the mapping in its mapping package, each section between markers', () => {
    const text = exportSource(MAPPING, state);
    expect(text).toMatch(/^package mappings;\n\n\/\/ beginning of import [0-9a-f]{32}\nimport java\.util\.\*;\n\/\/ end of import [0-9a-f]{32}\n\npublic class order_id_copy \{\n/);
    expect(text).toContain('// beginning of attributes and methods');
    expect(text).toContain('static final int N = 1;');
    expect(text).toMatch(/public static String calculate1\(String var1, MappingRun run\) \{\n\/\/ beginning of calculate1 [0-9a-f]{32}\nreturn in0;\n\/\/ end of calculate1 [0-9a-f]{32}\n\}/);
    expect(text).toContain('public static void init(MappingRun run) {');
    expect(text).toContain('public static void cleanUp(MappingRun run) {');
    expect(text).not.toMatch(/com\.sap|Container|StreamTransformationException/);
  });

  test('a mapping in the top folder has no package line', () => {
    expect(exportSource('order-id-copy', state)).toMatch(/^\/\/ beginning of import /);
  });

  test('markers are the same for the same mapping, and differ between mappings', () => {
    const tags = (id: string) => new Set(exportSource(id, state).match(/[0-9a-f]{32}/g));
    expect(exportSource(MAPPING, state)).toBe(exportSource(MAPPING, state));
    expect(tags(MAPPING).size).toBe(5);
    expect([...tags(MAPPING)].filter((tag) => tags('other/order-id-copy').has(tag))).toEqual([]);
  });
});

describe('importSource', () => {
  test('reads back each section of an exported source, CRLF and a BOM included', () => {
    let text = exportSource(MAPPING, state);
    text = withSection(text, 'import', 'import java.time.*;\n\n  import java.util.regex.*;  ');
    text = withSection(text, 'calculate1', 'return in0.trim();');
    text = withSection(text, 'init', '// set up');
    const read = importSource(MAPPING, state, `\uFEFF${text.replace(/\n/g, '\r\n')}`);

    expect(read.imports).toEqual(['java.time.*', 'java.util.regex.*']);
    expect(read.functions[0]!.body).toBe('return in0.trim();');
    expect(read.javaAreas.init).toEqual({ body: '// set up', description: '', variables: [] });
    expect(read.javaAreas['Attributes and Methods']!.body).toBe('static final int N = 1;');
    expect(read.javaAreas.cleanUp!.body).toBe('');
    expect(state.functions[0]!.body).toBe('return in0;');
  });

  test.each([
    ['a missing section', (t: string) => t.replace(/\/\/ beginning of init [^]*?\/\/ end of init \w+\n/, ''), 'Choose a source file exported for these functions. Check its section markers.'],
    ['an unclosed section', (t: string) => t.replace(/\/\/ end of cleanUp \w+/, ''), 'Choose a source file exported for these functions. Check its section markers.'],
    ['a source of another mapping', () => exportSource('other/x', state), 'Choose a source file exported for these functions. Check its section markers.'],
    ['a section inside another', (t: string) => t.replace(/\/\/ end of init \w+\n/, ''), 'Overlapping or duplicate source sections.'],
    ['a section twice', (t: string) => `${t}\n${/\/\/ beginning of init [^]*?\/\/ end of init \w+/.exec(t)![0]}`, 'Overlapping or duplicate source sections.'],
    ['an end without its beginning', (t: string) => t.replace(/\/\/ beginning of init \w+\n/, ''), 'Source section markers do not match.'],
    ['an end of another name', (t: string) => t.replace(/\/\/ end of init/, '// end of cleanUp'), 'Source section markers do not match.'],
    ['a line that is no import', (t: string) => withSection(t, 'import', 'import java.util.*'), 'Invalid import instruction: import java.util.*'],
  ])('refuses %s', (_, change, message) => {
    expect(() => importSource(MAPPING, state, change(exportSource(MAPPING, state)))).toThrow(message);
  });
});
