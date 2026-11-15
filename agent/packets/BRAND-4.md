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

## Rulings on the test-author's seven questions (architect, 2026-11-15)

The red tests are approved as written (`ci/test_name_guard.py`, 13 methods, red observed: `agent/work/BRAND-4-red.log`).

1. The allow-list is `ci/name-allowlist.txt` under the root of the repository the guard runs in (the git top level of the working
   directory), read from disk whether it is tracked or not.
2. A malformed entry is named by the allow-list's path and its physical line number: `ci/name-allowlist.txt:<n>: …`.
3. An unused entry is named the same way — path, line number — followed by the entry's text.
4. A finding in a line is `path:line: text` (the text cut at 160 characters). A finding in a path and a finding in a file that is not
   UTF-8 start with the path and carry no line number; after the path they say which of the two it is.
5. One finding per line however often the name occurs in it; one finding per file that is not UTF-8; a file whose path and content both
   hold the name gives the path finding and the line findings, each on its own.
6. An occurrence is credited to the longest literal that covers it among the entries whose glob matches the file; an entry credited with
   nothing in the whole run is unused. So the real allow-list holds a shorter literal only where it occurs on its own.
7. The name is found in any letter case; an allow-list literal is matched exactly as written.

## Rulings on the implementer's questions (architect, 2026-11-15)

1. `agent/cloud/README.md` names the owner's cloud environment, which still carries the old name until the owner renames it (an owner
   action, like the repository): a literal entry for the backticked name, scoped to that file.
2. `docs/OPERATIONS-BRIEF.md` names the mapping engine's repository as it was called when the brief was written (the owner's text, D-147):
   a literal entry `flottila-mapping`, scoped to that file. The brief is not edited.
3. The real-tree test can only pass with the guard's own files tracked: the architect stages them before the verifier's run.
4. Confirmed as built: a `*` entry takes the credit before any literal in the files it matches; a literal also covers an occurrence in
   a path and in the bytes of a file that is not UTF-8; an empty literal cell is malformed (exit 2); the address literals are allowed in
   any file, the prototype names only in their own files.
5. Longest-literal crediting (ruling 6 above) and the empty literal cell get a test each: a guard that credits the shortest literal
   passed every unit test — the test-author adds the two.

## After review r1 (FAIL, four points; architect, 2026-11-15)

1. Ruling 5 of the first list had no test (one finding per line; one per file that is not UTF-8; a path finding beside the line
   findings): the test-author adds it.
2. Two confirmed behaviours had no test — a `*` entry takes the credit before a literal over the same file; a literal covers a path and
   the bytes of a file that is not UTF-8: the test-author adds them.
3. The kind of a path finding and of a not-UTF-8 finding is asserted by its wording, not only by the missing line number.
4. A tracked symbolic link: the guard scans the link's own text (what git stores, `os.readlink`), never the target it points to — a
   red test first, then the guard. The tree holds no tracked symbolic link today.
5. An allow-list that is not UTF-8 is a malformed allow-list: exit 2, not a crash — a red test first, then the guard.
Accepted as limits, not changed: a literal is not word-bounded (the repository address also covers a longer name that starts with it);
an entry `** | *` would exempt the whole tree and nothing refuses it — the allow-list is a reviewed file; the working tree is read, not
the index (CI's tree is the commit); UTF-16 text, deflated archive entries and a name split across lines are not seen.
