# The ratchet is line-based text checked per ticket and regenerated per spec; fixed floors sit under it

Status: accepted (2026-10-30, grilling #14); floor table extended by ADR-0020 (`udf-api`, `udf`) and ADR-0021 (`bytecode`); `bench.tsv` rules set by ADR-0021; mutation measure amended by #87 (2026-11-05): surviving mutants; paths amended by F-34 (2026-11-10, owner): the UI store moved to `ui/src/studio/store` (#161), so its coverage row and 90 % floor below read `ui/src/studio/store`; the decisions are unchanged

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
  `check` (ticket gate) ignores `bench.tsv`. Benchmarks run only in spec-gate step 5.
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
  prints and does not fail. The check compares with `ratchet/` on the integration branch, which
  does not move during a spec, so it is `main`'s ratchet.
- **Regeneration**, spec-gate step 4: `java scripts/ratchet.java regen --all`. It requires every
  report, refuses any drop exactly as `check` does, writes the files and prints the diff. The
  verifier commits it to the integration branch, and it reaches `main` with the spec's PR.
  Nobody else writes `ratchet/`.

A gain made by one ticket can be lost by a later ticket of the same spec. Only a drop below
`main` fails. "A merge may only keep or raise" applies to merges into `main`.

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
  - in `nightly.yml` on `main`, over all nine class-C modules, failing below the floor;
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
  moving target.
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
