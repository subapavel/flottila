# MAP-12a - the ratchet check compares a commit with a baseline it is handed (class C: a gate's logic)

Row: `grep -n "^| MAP-12a " agent/BACKLOG.md`. Decision D-164 (2) with its bracket of 2026-11-16. First of three
parts (12a the script's mode, 12b the lookup and the workflows, 12c the week's record and the end of the switch).
Design: `agent/work/MAP-12-designer.md`, opposed by `agent/work/MAP-12-advisor.md`; today's script, its files and its
tests, line by line: `agent/work/MAP-12-scout.md`. Read the scout's file first - every output format below follows
the script's existing ones. Expensive to reverse (a command line, exit codes): `draft — review at hand-over`.

Rules of this area: D-153 as D-164 amends it - under `mapping/` the root Design rules do not bind; the root Tests,
Working rules and Context hygiene do, and so does `mapping/CLAUDE.md`: read it before writing. Its lines 24-25 are
what this mode enforces: an oracle case or a ratchet entry is never weakened, skipped or deleted to go green, and
`ratchet/` is never edited by hand in a task - only the verifier regenerates it.

## Why

`java scripts/ratchet.java check` compares the build's reports with the `ratchet/` files of the same commit. A commit
that lowers a row together with its code, removes a row or drops a listed case passes: nothing holds the committed
rows themselves. This part gives the script a second mode that is handed the `ratchet/` tree of an earlier commit
and holds the commit's rows against it. Which commit that is, and where the mode runs, is 12b's.

No way through for a decrease made on purpose is built. `regen` refuses every drop and never removes a listed case
(`ratchet.java`:120-124, :146-147, :178-179, :199-203), and the rule file forbids a hand edit: a lowered or removed
row can only come from breaking a rule, so the mode has nothing to excuse. How a row leaves when its module leaves
(the service and the editor, D-158) is the owner's, asked with MAP-1's design.

## The mode

`java scripts/ratchet.java check --baseline <dir>` (cwd = `mapping/`, as today). `<dir>` is a copy of an earlier
commit's `mapping/ratchet/` tree: `coverage.tsv`, `mutation.tsv`, `bench.tsv`, `oracle/*.txt`.

Order: (0) the command line and `<dir>` are validated, and both `mutation.tsv` files are read for their shape,
before any line goes to stdout - an exit 2 prints to stderr only. (1) Everything plain `check` does, in its order
and with its lines, except the NOT_RUN lines that point 2 replaces; after the coverage block (the last plain
coverage line) come the report failures of modules that have no row, then the MEASURED lines of point 3, in the
order the script lists its modules. (2) The baseline comparison of point 1, after the last plain line. (3) The
verdict.

1. The commit's rows against the baseline's. Each difference is one problem, printed as `FAIL baseline <subject>`:
   - every line of every `<dir>/oracle/<file>` is in the commit's `ratchet/oracle/<file>`: else
     `oracle/<file> <case> removed` (a file the commit lacks: one line per case of it);
   - every row of `<dir>/coverage.tsv` is a row of the commit's: else `coverage <module> removed`; its lines and
     its branches are not lower: else `coverage <module> lines <old> -> <new>`,
     `coverage <module> branches <old> -> <new>`. The keys of both files are read through the MOVED map
     (`ratchet.java`:58-59, as plain `check` reads the commit's at :133); a subject prints the new key;
   - every row of `<dir>/mutation.tsv` is a row of the commit's: else `mutation <module> removed`; its survivors
     are not higher: else `mutation <module> survivors <old> -> <new>`;
   - every (scenario, machine) of `<dir>/bench.tsv` is a row of the commit's: else
     `bench <scenario> <machine> removed`. Presence only; scores are not compared (ADR-0019:35 stands).
   A row or case the commit adds is no problem. Equal values are no problem.
2. Reports the commit must have. A module is required when the commit's `coverage.tsv` has a row for it that is not
   a `ui/` row, or when it has production code by the script's own rule (:415-443: `engine/*` but `engine/oracle`,
   and `service`, with a `.java` under `src/main/java` other than `module-info.java`).
   - a required module with no `target/site/jacoco/jacoco.csv` file: one problem, whether it has a row, code or
     both - `FAIL baseline report coverage <module>: no JaCoCo report`, printed in place of plain `check`'s
     `NOT_RUN coverage <module>` when the module has a row;
   - a module with a row whose csv is a file and yields no figures (only its header):
     `FAIL baseline report coverage <module>: the report has no figures for its row`, in place of the `NOT_RUN`
     line. A module with production code, no row and a header-only csv is no problem and prints nothing: the file
     is its report (a module whose first code is interfaces);
   - one of the two oracle lists plain `check` reads (`corpus.txt`, `differential.txt`, :115-125) with no surefire
     report under `engine/oracle/target/surefire-reports`: `FAIL baseline report oracle/<file>: no surefire
     reports`, in place of `NOT_RUN oracle/<file> (<n> cases)`. (Point 1 compares every file under `<dir>/oracle/`;
     this point covers the two lists that have a report.)
   - `ui/` coverage rows and mutation rows keep their `NOT_RUN` lines and are no problem (the backend job builds no
     UI report; a push runs no mutation testing). That gap is named in the ADR, not closed here.
3. For every module whose csv it measured it prints
   `MEASURED coverage <module> lines <covered>/<total> branches <covered>/<total>` - the sums the percentages
   come from - after the coverage block, as the order above says.

Exit codes: 0 with `RATCHET CHECK OK`; 1 with `RATCHET FAIL: <n> problem(s)`, n counting plain and baseline
problems together; 2 for a wrong command line, to stderr, with no stdout line - the usage line, now
`... check [--baseline <dir>] | regen ...`, when `--baseline` has no `<dir>` or more arguments follow;
`baseline <dir>: not a directory`; `baseline <dir>: no <name>` when `coverage.tsv`, `mutation.tsv`, `bench.tsv` or
the directory `oracle` is missing in it (today `readLines` would read a missing file as empty and pass, :774-777);
`baseline mode needs three-column mutation rows: <row>` for a two-column row in either `mutation.tsv`.

Plain `check` and `bench` do not change: same lines, same exit codes, on the same tree. `regen --all` changes in
one point, so that the two rules are one: a module with production code whose csv holds only its header is not
`FAIL regen: no JaCoCo report for <module> (it has production code)` (:424 tests the measured map, from which
:312-313 drops such a csv) - the file existing is the report, and regen writes no row for it.

## Acceptance

1. Red first (the test-author): `mapping/scripts/test/ratchet-baseline-test.sh`, in the harness of its siblings
   (`ratchet-moved-test.sh`: a scratch tree, `(cd "$tmp" && java "$java_src" "$@" 2>&1)`, the `check` helper). A red
   case asserts the exit code AND the line that names the problem - today the mode exits 2 with the usage line, so
   a case that asserts only "exit 2" is not red. Cases, each on a scratch tree whose plain `check` passes:
   a. a coverage row lowered together with its report (93 -> 92, the report at 92): plain `check` exits 0;
      `--baseline` exits 1 with `FAIL baseline coverage engine/structure lines 93 -> 92`; the same for branches;
   b. equal rows, and a raised row: exit 0, `RATCHET CHECK OK`, no `FAIL baseline` line;
   c. a coverage row removed; a mutation row removed; a bench row removed; a corpus case removed; an oracle file
      removed (one line per case): exit 1 with each subject, and `<n> problem(s)` counts them;
   d. a mutation row's survivors raised 0 -> 1: exit 1; lowered 11 -> 10: exit 0;
   e. MOVED: a baseline row `ui/src/editor/store` and a commit row `ui/src/studio/store` is not a removal; a
      lowered figure on that pair prints the new key;
   f. reports: a listed oracle file with no surefire reports fails and prints no `NOT_RUN oracle/` line; a row with
      no csv fails once (a module with a row AND production code: one problem, not two); a row with a header-only
      csv fails with the "no figures" line; a module with production code, no row and no csv fails; the same
      module with a header-only csv passes and prints nothing for it; a module with only `module-info.java` and no
      csv passes; a `ui/` row with no summary and a mutation row with no report print `NOT_RUN` and do not fail;
   g. `MEASURED coverage <m> lines c/t branches c/t` is printed for a measured module, with the csv's sums;
   h. exit 2, each with its message on stderr and nothing on stdout: `--baseline` alone; a third argument; `<dir>`
      missing; `<dir>` without `mutation.tsv`; without `oracle/`; a two-column mutation row in the baseline, and
      in the commit - the last on a tree that would otherwise print FAIL lines, to show the order;
   i. `regen --all` on a tree with a code module whose csv is header-only: today `FAIL regen: no JaCoCo report`,
      exit 1; after: no such line (red today). A code module with no csv file still fails regen.
   Characterization, green on today's script and marked so in the file: on the tree of case (a), plain `check`
   prints no line containing `baseline` and exits 0 - the output of today's script on that tree, kept in the test
   as the expected text, is what the changed script must print.
   The existing `ratchet-moved-test.sh`, `ratchet-mutation-test.sh`, `ratchet-bench-test.sh` stay green, unchanged.
2. The implementer: `mapping/scripts/ratchet.java` only, plus one line in `.github/workflows/mapping-ci.yml` - the
   new test joins the step that runs its siblings (:70-76). No other workflow change (12b's).
3. On the real tree after the mapping's build (`./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify`), in
   `mapping/`: plain `check` prints the same lines as before this change (the script at HEAD and the changed one
   run on the same tree, their outputs diffed); `check --baseline <a copy of HEAD's ratchet/>` exits 0 and prints
   ten `MEASURED` lines (nine engine modules and the service; `agent/work/MAP-9-numbers.md` :24-36) or the evidence
   says which module it did not measure and why; with one row of the copy raised by one it exits 1 on that row.
4. Documents (the architect, after the implementer): the mapping's ADR-0019 gains the mode and the ways the check
   still passes while a figure falls (the advisor's item 13); `mapping/ratchet/README.md` names the mode. Where the
   check runs is 12b's sentence, not written here.

## Not this part's

The lookup of the baseline commit, `ratchet-ci.sh`, the date, both workflows beyond the one test line, the nightly
comparison, the wiring test, `mapping/docs/WORKFLOW.md` (12b); the week's record (12c); the editor's rows in CI
(MAP-15); any floor (MAP-11); a way for a row to fall or leave on purpose (the owner's, with MAP-1); the old gate's
scripts (MAP-7); a tolerance - there is none.

## Steps

1. Test-author [C]: acceptance 1. Allowed path: `mapping/scripts/test/ratchet-baseline-test.sh` (new). No Maven is
   needed: the script runs from source on JDK 25. Show the red: the whole file run against today's script, output
   to `agent/work/MAP-12a-red.log`, every red case failing for the reason it names, the characterization case
   passing. Hand back at most 40 lines.
2. The architect reads the test file and approves it.
3. Implementer [C]: acceptance 2. Evidence: the new test and the three siblings green
   (`agent/work/MAP-12a-green.log`); `mapping/scripts/test/function-families-test.sh` untouched.
4. The architect: acceptance 4.
5. Verifier: acceptance 3 behind the Maven lock, every script test of `mapping-ci.yml`:70-89 once, evidence to
   `agent/work/MAP-12a-evidence.txt`. Then the reviewer [C] on `agent/work/MAP-12a.diff` and the evidence; after
   the push the `mapping-ci` run is read by job name.
