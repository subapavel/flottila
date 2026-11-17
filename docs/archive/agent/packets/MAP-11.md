# MAP-11 - the mapping engine's coverage floor is the platform's (class B)

Row: `grep -n "^| MAP-11 " agent/BACKLOG.md`. Decision D-164 (1) (the owner's yes of 2026-11-16 to ISSUE-13 item 1):
every mapping engine module that has code fails `verify` below 90 % of lines or 90 % of branches - the floor of the
platform's `api` and `core`. `draft — review at hand-over` until MAP-1's design says where the engine's modules live.

No behaviour of the engine changes and no test is written: this row moves three build properties and two documents.
A build threshold has no committed red; step 0 shows the check alive instead, with no file changed.

## Read first

- `agent/work/MAP-9-numbers.md` :22-37 - every module's measured line and branch figure at f1be875c, with its floor.
- `agent/work/MAP-9-scout.md` - where the mapping's floors live (the poms' lines, the JaCoCo `check` execution).
- `mapping/pom.xml` :65-67 (the default: 0.90 lines, 0.80 branches), :352-372 (the `jacoco-check` execution, BUNDLE).
- `mapping/engine/structure/pom.xml` :15-17 and `mapping/engine/structure-import/pom.xml` :15-17 (0.80 lines, 0.00
  branches, each under a comment naming ADR-0019).
- `mapping/docs/adr/0019-ratchet-and-floors.md` :3 (Status), :20, :65-76 (the floor table).

## What changes

- `mapping/pom.xml`: `jacoco.min.branches` becomes 0.90; the comment above the two properties says what is true after
  the change - every engine module with code takes 90 % of lines and of branches (ADR-0019 as D-164 amends it); the
  service overrides.
- `mapping/engine/structure/pom.xml`, `mapping/engine/structure-import/pom.xml`: the two override properties and their
  comment go, so both modules inherit the default - one place states the floor. `pit.skip` and its comment stay
  (MAP-10 measures mutation testing there; whether the two join the nightly run is the owner's, with those numbers).
- Stays as it is: `mapping/service/pom.xml` (80 % lines, no branch floor - D-164: the service and the editor keep
  theirs until they go); `engine/oracle` and `bench/jmh` (skipped; no main code); `engine/udf-api`, `engine/udf`
  (only `module-info.java`: the default binds them with their first code); the editor's Vitest thresholds; every
  workflow; `mapping/scripts/**`; the ratchet's committed files, unless a figure moved (none should: no code changes).

## Acceptance

1. The check is alive (step 0, no file changed): with `-Djacoco.min.lines=0.95` the build of `engine/structure` fails
   in JaCoCo's `check`, naming the bundle, the lines ratio and the minimum; with `-Djacoco.min.branches=0.99` the
   build of `engine/mapping` fails the same way on branches. Measured today: `structure` 93.22 % of lines (110 of
   118), `mapping` 98.92 % of branches; every module upstream of each is above the value used.
2. After step 1, `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` is green, and the effective floor of every
   engine module with code is 0.90 and 0.90 - shown from the build's own output or `help:effective-pom`, per module:
   xdm, ir, runtime, serializer, mapping, xslt-export, api, structure, structure-import. The service's stays 0.80 and
   0.00.
3. Every module's line and branch figure after step 1 stands beside `agent/work/MAP-9-numbers.md`'s; a figure that
   moved is named. The mapping's ratchet check (`java scripts/ratchet.java check`, run in `mapping/` after the build)
   passes on the tree; its committed files are untouched if nothing moved.
4. Documents (the architect, after step 1's hand-back): the floor table of the mapping's ADR-0019 says the new
   figures, marked `draft — review at hand-over` until MAP-1; the row of the planned `bytecode` module (:73,
   ADR-0021) stays - its "as above" takes the new figures; the path `ui/src/editor/store` (:20, :76) is written
   `ui/src/studio/store`, as the Status line already reads it (the mapping's F-34). `docs/ARCHITECTURE.md` §12
   ("Whose rules") says the engine's floor, with a changelog entry; the gate clause (:155-157) is not touched -
   D-164 makes no gate condition. D-164 and D-153's bracket say item (1) is built.
5. After the push: the `mapping-ci` run on the commit is read on GitHub, its Windows job by name - a branch figure
   can differ by platform, and the floor now binds branches where it did not.

## Not this row's

The ratchet's check on GitHub and the nightly comparison (MAP-12); the rule files (MAP-13); mutation testing on
`structure` and `structure-import` (MAP-10 measures; a floor there is decided after it); the service's and the
editor's floors; any test added to raise a figure - if a module is below 90 % after step 1, stop and hand back the
module and the figure: that is a finding for the architect, never a lowered floor and never an exclusion.

## Step 0 - the verifier (no file changed)

One Maven at a time: every command behind `flock /tmp/rail8-maven.lock`, output to the named file, read its tail only.

- `./mvnw -B -ntp -f mapping/pom.xml -pl engine/structure -am verify -Djacoco.min.lines=0.95`
  to `agent/work/MAP-11-live-lines.log`. Expected: BUILD FAILURE in the structure module, at `jacoco-check`.
- `./mvnw -B -ntp -f mapping/pom.xml -pl engine/mapping -am verify -Djacoco.min.branches=0.99`
  to `agent/work/MAP-11-live-branches.log`. Expected: BUILD FAILURE in the mapping module, at `jacoco-check`.
- `git status --short mapping/` prints nothing afterwards.

Report the observed line of each failure (module, counter, ratio, minimum) and the exit code. If a run fails
elsewhere or passes, report that and stop: the expectation is the architect's and may be wrong.

## Step 1 - the implementer (allowed paths: `mapping/pom.xml`, `mapping/engine/structure/pom.xml`,
## `mapping/engine/structure-import/pom.xml`)

The three edits of "What changes", nothing else. Then the build of acceptance 2 once, behind the lock, output to
`agent/work/MAP-11-verify.log`. Hand back: the three hunks by file and line, the build's last lines, and for each
of the nine modules the floor in force (how it was read) - at most 40 lines. The diff goes to `agent/work/MAP-11.diff`.

## Step 2 - the architect: the documents of acceptance 4

## Step 3 - the verifier, then the reviewer [B]

The verifier: acceptance 2 and 3 on the tree as it will be committed (the same build, to
`agent/work/MAP-11-verify-2.log`; the figures from each module's `target/site/jacoco/jacoco.csv`; the ratchet check);
evidence lines to `agent/work/MAP-11-evidence.txt` with the words `VERIFIED_PASS`, `EXECUTED_FAIL`, `NOT_RUN`,
`ENVIRONMENT_BLOCKED`. Then the reviewer on `agent/work/MAP-11.diff`, the evidence and the documents.
