# Packet BRAND-4 (class A) — the old name does not come back

Row: `agent/BACKLOG.md` BRAND-4. Decisions: D-148 (history keeps the old name), D-149 (opponent W1:
a guard with an allow-list that states its reasons). Runs after BRAND-1 commit C, when the rename
tool's own report of remaining occurrences is empty.

## What is built

- `ci/name_guard.py` — Python 3, standard library only, in the style of `ci/coverage_check.py` and
  `ci/ledger_check.py`. Run from the repository root as `python3 ci/name_guard.py`; exit 0 when
  clean, exit 1 with one line per finding (`path:line: text`, at most 160 characters of the line)
  and a count on the last line; exit 2 on a malformed allow-list.
- `ci/name-allowlist.txt` — the allow-list, one entry per line, `#` comments and blank lines
  ignored: `<path glob> | <literal or *> | <reason>`.
- `ci/test_name_guard.py` — `unittest`, run as `python3 -m unittest ci.test_name_guard` (the way
  `.github/workflows/ci.yml` runs `ci.test_coverage_check`).
- One step in `.github/workflows/ci.yml` beside the other guards: the guard's test, then the guard.

## Acceptance (the red tests; each builds its own small git repository in a temp directory)

1. The guard reads **tracked** files only (`git ls-files -z`); an untracked or ignored file with the
   name in it is not a finding.
2. An occurrence of `flottila` in any letter case (`flottila`, `Flottila`, `FLOTTILA_NODE_ID`,
   `io.flottila.api`) in a tracked file is a finding that names the file and the line number.
3. A tracked **path** that holds the name (`bin/flottila`, `src/io/flottila/A.java`) is a finding
   even when the file's content is clean.
4. A file that is not valid UTF-8 (a PNG, a jar) is scanned as bytes and does not crash the guard;
   the name inside it is a finding reported with the path alone.
5. An entry `<glob> | * | <reason>` allows every occurrence in the files the glob matches, path
   included. `**` crosses directories, `*` does not (`docs/archive/**` matches
   `docs/archive/a/b.md`; `deploy/postgres/V*.sql` does not match `deploy/postgres/x/V1.sql`).
6. An entry `<glob> | <literal> | <reason>` allows only that literal: on a line holding
   `github.com/subapavel/flottila` and `flottila-core`, the URL is allowed and `flottila-core` is
   still a finding. The longest allowed literal at a position wins (`flottila-mapping` allowed does
   not allow `flottila-mapper`).
7. An entry without a reason, with an empty glob, or with fewer than three cells makes the guard
   exit 2 and name the allow-list line.
8. An entry that allows nothing in the tree is itself a finding ("unused allow-list entry"), so the
   list shrinks when its reason goes away.
9. With no findings the guard prints one line with the number of files scanned and exits 0.
10. On the real tree (the test runs the guard on the repository it lives in, from its root): exit 0.
    This is the last test to turn green; it is red until the allow-list is complete.

No test sleeps, none is skipped, none shells out to anything but `git` and the guard.

## The allow-list's content (the implementer fills it; every entry with its reason)

- History, whole files (D-148): `docs/PROGRESS.md`, `docs/DECISIONS.md`, `docs/archive/**`,
  `docs/findings/**`, `docs/review/**`, `agent/packets/**`, `agent/archive/**`, `agent/work/**`,
  `agent/review/**`, `agent/patches/**`, `agent/wip/**`, `issues/**`, `bench/RESULTS.md`,
  `agent/BACKLOG.md` (its done rows are history; the tool translates the open ones),
  the changelog of `docs/ARCHITECTURE.md` — by the literals its rows hold, not the whole file.
- Applied migrations, whole files: `deploy/postgres/V*.sql` (comments only; byte-identical forever).
- The repository's address, by literal in any file: `github.com/subapavel/flottila`,
  `subapavel/flottila`, `subapavel/flottila-mapping`, and the checkout paths
  `/home/user/flottila`, `~/flottila` — until the owner renames the repository.
- The prototype's names, by literal: `flottila-m1`, `flottila-proto`.
- The rename's own files, whole: `agent/tools/rail8_rename.py`, `ci/name_guard.py`,
  `ci/test_name_guard.py`, `ci/name-allowlist.txt`.
- Anything else the guard finds on the tree after commit C is **not** allow-listed by the
  implementer: it is reported in the hand-back as a question (it is either a miss of commit C or a
  new class of history).

## Paths

Allowed: `ci/name_guard.py`, `ci/test_name_guard.py`, `ci/name-allowlist.txt`,
`.github/workflows/ci.yml` (one step). Nothing else.

## Hand-back (at most 40 lines)

The red observed (the test-author: which tests fail and why, with the log path under
`agent/work/`); the implementer: the tests green, the guard's output on the tree, the allow-list's
entry count, and every finding it did not allow-list.
