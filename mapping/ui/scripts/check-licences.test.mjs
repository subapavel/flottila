import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const path = (f) => fileURLToPath(new URL(f, import.meta.url));
const run = (lock, exceptions = 'no-exceptions.json') =>
  spawnSync(
    process.execPath,
    [path('./check-licences.mjs'), path(`./fixtures/${lock}.lock.json`), path(`./fixtures/${exceptions}`)],
    { encoding: 'utf8' },
  );

const passes = (lock, exceptions) => {
  const r = run(lock, exceptions);
  assert.equal(r.status, 0, r.stderr);
};
const failsNaming = (lock, name, exceptions) => {
  const r = run(lock, exceptions);
  assert.equal(r.status, 1, r.stdout);
  assert.match(r.stderr, new RegExp(`${name} \\(node_modules/${name}\\)`));
  return r;
};

test('an allowed package passes', () => passes('allowed'));
test('a disallowed licence fails and names the package', () => failsNaming('disallowed', 'gpl-pkg'));
test('no license field fails and names the package', () => {
  assert.match(failsNaming('no-license', 'nolicence-pkg').stderr, /no license field/);
});
test('AND needs every alternative allowed', () => failsNaming('and', 'and-pkg'));
test('OR passes if any alternative is allowed', () => passes('or'));
test('an excepted package passes', () => passes('excepted', 'exceptions.json'));
test('the same package fails without the exception', () => failsNaming('excepted', 'excepted-pkg'));
test('an exception covers a dev dependency only: as a non-dev dependency it fails', () => {
  const r = failsNaming('excepted-not-dev', 'excepted-pkg', 'exceptions.json');
  assert.match(r.stderr, /its exception covers dev dependencies only/);
});
test('a trailing unknown token fails closed', () => failsNaming('trailing', 'trailing-pkg'));
test('SEE LICENSE IN fails closed', () => failsNaming('see-licence', 'see-pkg'));
test('an unbalanced parenthesis fails closed', () => failsNaming('unbalanced', 'unbalanced-pkg'));
test('a dangling operator fails closed', () => failsNaming('dangling', 'dangling-pkg'));
