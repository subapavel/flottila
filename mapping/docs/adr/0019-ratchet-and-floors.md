# The ratchet is line-based text checked per ticket and regenerated per spec; fixed floors sit under it

Status: accepted (2026-10-30, grilling #14); floor table extended by ADR-0020 (`udf-api`, `udf`) and ADR-0021 (`bytecode`); `bench.tsv` rules set by ADR-0021; mutation measure amended by #87 (2026-11-05): surviving mutants; paths amended by F-34 (2026-11-10, owner): the UI store moved to `ui/src/studio/store` (#161), so its coverage row and 90 % floor below read `ui/src/studio/store`; the decisions are unchanged; a baseline mode of `check` added by MAP-12a (2026-11-16, D-164 (2), draft); where the check runs on GitHub, its baseline, the reporting week and the nightly comparison added by MAP-12b (2026-11-16, D-164 (2), (3), draft)

FOUNDING §10 says the ratchet holds committed pass lists, coverage and benchmark baselines, and a
merge may only keep or raise them. ADR-0017 makes the ratchet check ticket-gate step 5 and the
regeneration spec-gate step 4, and leaves the format, the commands and the floors to #14. The
oracles whose results it records are ADR-0018.

## Files

Every file is line-based, sorted text, so that a diff reads at a glance and two specs merge
without conflicts on unrelated lines.

| File | One line per | Columns |
| --- | --- | --- |
| `ratchet/oracle/corpus.txt` | passing golden-corpus case (all metamorphic variants) | case id |
| `ratchet/oracle/differential.txt` | case where our runtime agrees with Saxon-HE | case id |
| `ratchet/oracle/w3c-<suite>.txt` | passing W3C case (later milestones) | `<suite>:<set>/<case>` |
| `ratchet/coverage.tsv` | engine module, service, and `ui/src/model`, `ui/src/editor/store` | `<module>\t<lines %>\t<branches %>` |
| `ratchet/mutation.tsv` | class-C module | `<module>\t<surviving mutants>\t<generated>` (since #87) |
| `ratchet/bench.tsv` | benchmark scenario | `<scenario>\t<machine label>\t<score>\t<unit>` |

- Percentages are whole numbers, **rounded down**. A refactor that moves coverage by a fraction of
  a point does not fail unless it crosses a whole percent.
- `bench.tsv` holds the baseline the spec gate compares with. ADR-0021 sets it:
  - scenarios are `<mapping>/<size>/<metric>`, `compile/<mapping>` and `ui/tree-20128/scroll`;
  - the score is the median of three JMH forks;
  - the direction comes from the unit: `us/op`, `ms/op`, `B/op`, `ms` lower is better, `MB/s`
    higher is better, and an unknown unit fails;
  - the tolerance is 20 % for time and throughput and 10 % for B/op. A regression is measured
    twice before the gate fails;
  - `regen` moves a baseline only on a gain beyond the tolerance, and lowering one takes a
    finding.
  Plain `check` (ticket gate) ignores `bench.tsv`; `check --baseline` holds only that no row
  of it is gone. Benchmarks run only in spec-gate step 5.
  `bench/RESULTS.md` stays the log of every measurement.

## Commands

`scripts/ratchet.java` is a single-file Java program with no dependencies, run by the JDK 25
source launcher. It reads:
- `engine/oracle/target/surefire-reports/TEST-*.xml`, where a test case's name is its case id
  (ADR-0018);
- `target/site/jacoco/jacoco.csv` of every module that was built;
- `target/pit-reports/mutations.xml` of every module PIT ran on;
- `ui/coverage/coverage-summary.json`, summed per directory, because Vitest has no
  per-directory total.

- **Check**, ticket-gate step 5: `java scripts/ratchet.java check > .logs/<t>-ratchet.log`. It
  exits 1 when:
  - a listed case is failing, skipped or missing;
  - a module's percentage is below its line.

  A module or report that the gate did not build prints `NOT_RUN` and does not fail. A rise
  prints and does not fail. The check compares the build's reports with the `ratchet/` rows of
  the commit it runs on - once the integration branch's copy, which did not move during a spec.
  For a push that is not enough: "On GitHub" below.
- **Check against a baseline** (MAP-12a, the repository's D-164 (2); `draft — review at
  hand-over`): `java scripts/ratchet.java check --baseline <dir>`, where `<dir>` is a copy of
  an earlier commit's `ratchet/`. Plain `check` reads the rows of the commit it judges, so a
  commit that lowers a row together with its code, removes a row or drops a listed case
  passes it. The mode does what `check` does and then holds the commit's rows against the
  baseline's. It exits 1 also when:
  - a case of any `oracle/*.txt` of the baseline is not in the commit's list;
  - a coverage row of the baseline is gone or a percentage of it is lower; the keys of both
    `coverage.tsv` files are read through the map of moved directories;
  - a mutation row of the baseline is gone or its count of surviving mutants is higher;
  - a `bench.tsv` row of the baseline (scenario and machine) is gone. Presence only: the
    scores stay the spec gate's;
  - a report the commit must have is missing: the JaCoCo csv of a module that has a coverage
    row or production code, the figures for a row, the surefire reports of an oracle list
    that lists a case (`corpus.txt`, `differential.txt`). A `ui/` row and a mutation row with
    no report still print `NOT_RUN` and do not fail; an empty oracle list asks for no report.

  It prints `MEASURED coverage <module> lines <covered>/<total> branches <covered>/<total>`
  for every module whose csv has figures, after `check`'s coverage lines. The baseline's
  lines come after `check`'s last line, before the verdict. It exits 2, with its message on
  stderr and nothing on stdout, on a wrong command line, a `<dir>` that lacks one of its four
  names or a two-column mutation row in either tree. It writes nothing. There is no
  tolerance and no way to excuse a decrease: `regen` refuses every drop and nobody edits a
  row of `ratchet/` by hand, so a lowered or removed row can only come from breaking a rule.
  Which commit is the baseline and where the mode runs: "On GitHub" below.

  What still passes it while a figure falls:
  - a fall inside one whole percent (the rounding above);
  - UI coverage between its 90 % floor and its row: the job that runs the mode builds no UI
    report;
  - a count of surviving mutants that rises on a push: a push runs no mutation testing, so
    the row prints `NOT_RUN` (the nightly comparison and the verifier's run hold it, below);
  - a benchmark score: only the row's presence is held;
  - a PIT exclusion that removes the classes holding survivors: the count falls with them;
  - a gain made and lost between two baselines: the baseline is an earlier commit that
    passed, not the previous commit (D-164 (2)).
- **Regeneration**: `java scripts/ratchet.java regen --all`. It requires every coverage and
  oracle report (a module's JaCoCo csv file is its report also when it holds no figures: regen
  then writes no row for it, MAP-12a; a mutation row without a fresh PIT report stays as it is
  and prints `NOT_RUN`), refuses any drop exactly as `check` does, writes the files and
  prints the diff. The verifier runs it at every task commit that touches `mapping/` and the
  reviewer reads the diff (the repository's D-164 (2)); it was spec-gate step 4, committed to
  the integration branch. Nobody else writes a row of `ratchet/`.

"A merge may only keep or raise" now reads: a push to `main` may only keep or raise. (Before
MAP-12b a gain made by one ticket could be lost by a later ticket of the same spec, and only a
drop below `main` failed.)

## On GitHub

MAP-12b, the repository's D-164 (2) and (3); `draft — review at hand-over`.

- **Every push and pull request.** The last step of the job `mapping · backend` runs
  `scripts/ratchet-ci.sh push`: the lookup `scripts/ratchet-baseline.sh`, then `check --baseline`
  against the `ratchet/` tree the lookup exported.
- **The baseline** is the closest strict ancestor of the commit whose push run of `mapping-ci`
  on `main` ended with the job `mapping · backend` green - the last commit the check passed on -
  read through GitHub's API with the run's own token. The ratchet step is that job's last, so
  from the date below a green job means the check passed: a red push's rows never become a
  baseline, and a decrease stays red until it is restored. A pull request takes its base commit
  and asks nothing. When no ancestor has a green job (a fork's first run, a renamed job) the
  baseline is the parent commit, and the line says so. A lookup that fails - GitHub does not
  answer, an answer is not the data expected, the checkout is shallow - prints `LOOKUP FAILED`
  under its own annotation, and plain `check` still runs.
- **The reporting week.** Until the date in `scripts/ratchet-enforced-from` (one ISO line, UTC;
  the scripts read it nowhere else, and `scripts/test/ratchet-ci-wiring-test.sh` pins the same
  day, so a commit that moves the date edits a test too) a failing check and a failed lookup
  are warnings and cannot fail the job; from that date they fail it. What cannot be decided
  fails the job on any day - among them a missing or malformed date file, a check that wrote
  no verdict, a missing `ratchet/mutation.tsv` at night (the whole list is the header of
  `scripts/ratchet-ci.sh`). The week
  answers one question: do GitHub's machines measure the figures the rows hold. A figure that
  differs by machine gets a finding and a fix that makes it the same everywhere - never a
  tolerance, never a lowered row. In the week a commit whose check would fail keeps a green job
  and so becomes a baseline: the week reports, it holds nothing, and every `would fail` warning
  of the week is answered in MAP-12c's record before the date. The date moves only by a commit
  that says why; MAP-12c records the week and removes the date.
- **Every night** (`mapping-nightly.yml`). After the PIT step, `scripts/ratchet-ci.sh nightly`
  holds each module's fresh count of surviving mutants against `ratchet/mutation.tsv` through
  `scripts/pit-summary.sh`: a count above its row, and a row without a report, are a drop, under
  the same date. It compares a module's count, not which mutants survive: a new survivor can hide
  behind a newly killed one of the same module, never of another. `pit-summary.sh` also prints a
  module's generated count when it is below its row's (a PIT exclusion shows there) and how
  many of a module's killed mutants timed out. A timed-out mutant counts as killed and whether it
  times out depends on the machine (F-16), so a drop that comes from timed-out mutants alone
  turns the job red like any other, and whether it is a fault is the architect's to judge.
- **The verifier's two duties** (the repository's `docs/WORKFLOW.md` §1): it regenerates the
  ratchet files at every task commit that touches `mapping/`; and before a commit that touches a
  mutation-tested module it runs that module's mutation testing and `scripts/pit-summary.sh`
  against the committed count. A push runs no mutation testing: these two and the nightly job
  are where "no new surviving mutant" is held. `regen --all` reads what the mapping's build
  (`-Pbench clean verify`) wrote and `ui/coverage/coverage-summary.json`, which `npm test` in
  `ui/` writes: the verifier runs the build always, and `npm test` when the task touches `ui/`
  or the summary is absent.
- `mapping-ci` is not a required check (the repository's D-164 (6)): a red run blocks nothing by
  itself, and the architect reads the run after every push.
- A pull request runs the scripts and the workflow as its own head writes them: a pull request
  that changes them is held by its review, not by the check. The step has the run's own token
  with read access to the code and to the workflow's runs, and no secret.

## Floors

The floors are fixed minimums in the build, independent of the ratchet. The ratchet keeps what
was reached above them.

| Modules | Coverage floor (fails `verify`) | Mutation floor |
| --- | --- | --- |
| `xdm`, `ir`, `runtime`, `serializer`, `mapping`, `xslt-export`, `api`, `udf-api`, `udf` | JaCoCo 0.8.16 `check`, BUNDLE: lines ≥ 90 %, branches ≥ 80 % | PIT ≥ 80 % mutation score |
| `bytecode` (after milestone 1, ADR-0021) | as above, on the generator; generated hidden classes are not instrumented, the fifth metamorphic variant covers them | PIT ≥ 80 % |
| `structure`, `structure-import`, `service` | lines ≥ 80 % | — |
| `engine/oracle` | none, it has no main code | — |
| `ui/src/model/**`, `ui/src/editor/store/**` | Vitest v8 per-glob `thresholds`, lines ≥ 90 % | none in milestone 1 (ADR-0013) |

- JaCoCo runs in every `verify`. Surefire's `argLine` must start with `@{argLine}`.
- In `ui/`, `npm run test` is `vitest run --coverage`. The floor uses per-glob thresholds, because
  a global threshold lets a weak directory hide behind a strong one (observed).
- PIT (pitest-maven 1.30.1 with pitest-junit5-plugin 1.2.3) is not bound to `verify`. It runs:
  - in `mapping-nightly.yml` on `main`, over all nine class-C modules, failing below the floor,
    and then compared with the committed survivor counts ("On GitHub");
  - in **spec-gate step 1b**, over the class-C modules the spec touched, which feeds
    `mutation.tsv`.

  Amended by #87 (2026-11-05):
  - The mutation ratchet stores, per class-C module, the number of surviving mutants (generated
    minus killed, NON_VIABLE excluded) and the number generated. The generated count is
    informative only.
  - A module's survivor count may never rise above its row, so one new surviving mutant is a
    drop. That holds in `scripts/pit-summary.sh`, in `ratchet.java check` and in both gates.
  - A whole-percent score hid one: in spec 3, `mapping` went from 444/454 to 443/454 and still
    printed `97% floor 97% ok`.
  - `regen --all` keeps the smaller count and refuses a rise. A row in the earlier whole-percent
    format is compared as a percent until `regen --all` rewrites it.
  - The 80 % PIT threshold in the build is unchanged.
  - The gate log holds one PIT section per module, each preceded by `=== PIT <module> ===`.

  If step 1b takes more than 10 minutes, it narrows to the modules that class-C tickets touched.

## Ticket-gate budget

Ticket-gate step 1 now always builds `engine/oracle`, and with it the whole engine (ADR-0018).
The budget in ADR-0017 was measured on one module, so it becomes: an engine ticket gate under
30 s with three in parallel, and one that builds `service` under 60 s. The skeleton ticket
measures it into `bench/RESULTS.md`. If the first spec exceeds it, splitting the oracle run off
the ticket gate is a new decision with a finding, not an automatic fallback.

## Considered options

- **Regenerating after every merge** (the earlier WORKFLOW §4 step 6): rejected. It costs a full
  build of the integration branch per ticket, and three tickets in flight would check against a
  moving target. Reversed by the repository's D-164 (2): one task at a time commits to `main`,
  nothing is in flight beside it, and the verifier regenerates at every task commit (MAP-12b).
- **The previous tip of `main` as the baseline** (`github.event.before`): rejected. It is no
  checked commit - a run can be cancelled or skipped by the path filter - and a red would last
  one push: the next push would take the lowered rows as its baseline.
- **A ref or tag the job moves to the last green commit**: rejected. It gives a check write
  access, two runs can race for it, and whoever can push can move it.
- **A flag in the workflow instead of a date**: rejected. A forgotten flip leaves the check off;
  a date switches by itself, and postponing it takes a commit with a reason.
- **Ratios with three decimals**: rejected. Removing covered code lowers the ratio, so refactors
  would fail at random.
- **JSON ratchet files**: rejected. Line-based text diffs and merges better.
- **A shell + `jq` tool**: rejected. The JDK is everywhere the build is, and a Java program reads
  the XML reports without a second parser.
- **The ratchet value as the JaCoCo minimum**: rejected. The POM would change with every
  regeneration.
- **Vitest `thresholds.autoUpdate`**: rejected. It rewrites `vitest.config.ts`, so writers'
  branches would conflict, and it bypasses the verifier.
- **PIT in every ticket gate**: rejected. It costs minutes per module and is not needed to merge.
- **PIT nightly only**: rejected. Nightly does not commit, so the mutation ratchet would never move.
- **A global Vitest threshold**: rejected (see Floors).

## Consequences

- Observed on 2026-10-30 in scratch projects on the VM, not committed:
  - JaCoCo 0.8.16 with surefire 3.6.1 on the module path. A drop below the floor fails `verify`
    with `Rule violated for bundle demo: lines covered ratio is 0.79, but expected minimum is
    0.90`. JaCoCo adds about 2 s to a small reactor's `clean verify` (12.5 s vs 10.5 s).
  - PIT 1.30.1 + pitest-junit5-plugin 1.2.3 run on JUnit 6.1.3, adding
    `junit-platform-launcher` 6.1.3 automatically. A score below `mutationThreshold` fails:
    `Mutation score of 54 is below threshold of 80`. 5 s of PIT on a small module.
  - Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5 with per-glob thresholds:
    `Coverage for lines (63.63%) does not meet "src/model/**" threshold (90%)`, exit 1. The new
    packages are MIT, plus one MPL-2.0 optional binary.
  - A prototype `ratchet.java`:
    - `check` passed (exit 0, about 1 s);
    - it failed on a failing case and on a deleted case;
    - it failed on a coverage drop from 1.000 to 0.905, which the 0.90 JaCoCo floor let through.
- ADR-0017's ticket-gate budget, CI `backend`'s PIT scope and WORKFLOW §4 step 6 change with this
  ADR.
