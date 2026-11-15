// Licence check over every entry of package-lock.json (dev and transitive included).
// Allowed list: CLAUDE.md. Exceptions by package name: licence-exceptions.json (ADR-0017); an
// exception covers an entry only if the lockfile marks it dev: true (dev-only build tools).
// Usage: node scripts/check-licences.mjs [lockfile] [exceptions-file]
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = (f) => fileURLToPath(new URL(f, import.meta.url));
const lockPath = process.argv[2] ?? here('../package-lock.json');
const exceptionsPath = process.argv[3] ?? here('./licence-exceptions.json');

const ALLOWED = new Set([
  // EDL 1.0 is BSD-3-Clause in SPDX, so it needs no entry of its own.
  'Apache-2.0', 'MIT', 'MIT-0', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', '0BSD',
  'MPL-2.0', 'EPL-1.0', 'EPL-2.0',
]);

// SPDX expression: OR (lowest) < AND < parentheses. Unknown syntax is not allowed.
function allowed(expr) {
  const tokens = expr.match(/\(|\)|[^\s()]+/g) ?? [];
  let i = 0;
  const parseOr = () => {
    let ok = parseAnd();
    while (tokens[i] === 'OR') { i++; const r = parseAnd(); ok = ok || r; }
    return ok;
  };
  const parseAnd = () => {
    let ok = parseAtom();
    while (tokens[i] === 'AND') { i++; const r = parseAtom(); ok = ok && r; }
    return ok;
  };
  const parseAtom = () => {
    const t = tokens[i++];
    if (t === '(') {
      const ok = parseOr();
      if (tokens[i++] !== ')') throw new Error('missing )');
      return ok;
    }
    if (t === undefined || t === ')' || t === 'AND' || t === 'OR') throw new Error('unexpected token');
    return ALLOWED.has(t);
  };
  try {
    const ok = parseOr();
    return i === tokens.length && ok;
  } catch {
    return false;
  }
}

const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
const exceptions = new Set(Object.keys(JSON.parse(readFileSync(exceptionsPath, 'utf8')).exceptions));
const failures = [];
let checked = 0;
for (const [path, pkg] of Object.entries(lock.packages ?? {})) {
  if (path === '' || pkg.link) continue;
  checked++;
  const name = pkg.name ?? path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length);
  const excepted = exceptions.has(name);
  if (excepted && pkg.dev === true) continue;
  const licence = pkg.license;
  const why = excepted ? '; its exception covers dev dependencies only' : '';
  if (typeof licence !== 'string' || licence === '') {
    failures.push(`${name} (${path}): no license field${why}`);
  } else if (!allowed(licence)) {
    failures.push(`${name} (${path}): licence "${licence}" is not on the CLAUDE.md list${why}`);
  }
}
if (failures.length > 0) {
  console.error(`Licence check FAILED (${failures.length} of ${checked} packages):`);
  for (const f of failures) console.error(`  ${f}`);
  process.exit(1);
}
console.log(`Licence check passed: ${checked} packages, ${exceptions.size} per-package exceptions.`);
