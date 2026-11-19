import { readdirSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Every folder of src is a module of the UI; main.tsx finds the modules, so neither the shell (the files directly in
// src), nor the development server, nor another module imports one. What two share is a file directly in src or in
// server.
const src = join(import.meta.dirname, 'src');
const modules = readdirSync(src, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);
const folders = readdirSync(src, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => relative(src, join(entry.parentPath, entry.name)).split(sep));

/** The rules that refuse an import, a re-export or an `import()` whose path matches `path`, with `message`. */
function refuse(path, message) {
  return {
    'no-restricted-imports': ['error', { patterns: [{ regex: path.source, message }] }],
    'no-restricted-syntax': ['error', { selector: `ImportExpression[source.value=/${path.source}/u]`, message }],
  };
}

/** A path into one of the folders `names`, after `start`, a pattern that leads to src. */
const into = (start, names) => new RegExp(`^${start}(?:${names.join('|')})(?:/|$)`, 'u');

const boundary = [
  {
    files: ['src/*.{ts,tsx}'],
    rules: refuse(into('(?:\\./|\\.\\./src/)', modules), 'The shell imports no module: main.tsx finds them.'),
  },
  ...folders.map(([module, ...below]) => ({
    files: [`src/${[module, ...below].join('/')}/*.{ts,tsx}`],
    rules: refuse(
      into(`(?:\\.\\./){${below.length + 1}}(?:\\.\\./src/)?`, modules.filter((name) => name !== module)),
      'A module imports no other module: what two share is a file directly in src or in server.',
    ),
  })),
  {
    files: ['server/*.ts'],
    rules: refuse(into('\\.\\./src/', modules), 'The development server imports no module: it finds their server.ts.'),
  },
  {
    files: ['*.{ts,js}'],
    rules: refuse(into('\\./src/', modules), 'The configuration imports no module: the development server finds them.'),
  },
];

export default defineConfig([
  globalIgnores(['dist', 'e2e/fake/dist', 'src/mapping/generated']),
  js.configs.recommended,
  tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
  ...boundary,
]);
