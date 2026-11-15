import { readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from 'vitest';

// On a case-insensitive file system (Windows, macOS) the import './inspector/Inspector' may resolve to
// 'inspector.ts' before 'Inspector.tsx' (#131). No two files may share a path once case and extension are ignored.
const src = fileURLToPath(new URL('.', import.meta.url));

test('no two files under src share a path ignoring letter case and extension', () => {
  const byKey = new Map<string, string[]>();
  for (const entry of readdirSync(src, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const path = relative(src, join(entry.parentPath, entry.name)).replaceAll('\\', '/');
    const key = path.replace(/\.[^./]+$/, '').toLowerCase();
    byKey.set(key, [...(byKey.get(key) ?? []), path]);
  }
  expect([...byKey.values()].filter((paths) => paths.length > 1)).toEqual([]);
});
