# Ratchet

What passes, per oracle and per module. Decided in ADR-0019; the oracles are ADR-0018.
Regenerated and committed only by the verifier, at every task commit that touches `mapping/`
(ADR-0019, "On GitHub"; once spec-gate step 4 on an integration branch, `docs/WORKFLOW.md` §6).
Never edited by hand. A drop fails the push's check on GitHub - from the date in
`scripts/ratchet-enforced-from`; until then it is reported - and the old gate.

## Files

All files are line-based, sorted, UTF-8, LF. Columns are separated by one tab.

| File | One line per | Columns |
| --- | --- | --- |
| `oracle/corpus.txt` | golden-corpus case passing in all metamorphic variants | case id (`fn/removeContexts/001`) |
| `oracle/differential.txt` | case where our runtime agrees with Saxon-HE 12.5 on the exported XSLT | case id |
| `oracle/w3c-<suite>.txt` | passing W3C case (later milestones) | `<suite>:<test-set>/<test-case>` |
| `coverage.tsv` | engine module, `service`, `ui/src/model`, `ui/src/studio/store` | `module`, `lines %`, `branches %` |
| `mutation.tsv` | class-C module | `module`, `surviving mutants`, `generated mutants` |
| `bench.tsv` | benchmark scenario (ADR-0021) | `scenario`, `machine label`, `score`, `unit` |

`bench.tsv` (ADR-0021): scenario `<mapping>/<size>/<metric>` (`orders/small/latency`),
`compile/<mapping>` or `ui/tree-20128/scroll`; machine label `claude-cloud-4vcpu`, the only one
judged (another label fails); score = median of the per-fork means of JMH `rawData`, four
significant digits. Direction by unit:
`us/op`, `ms/op`, `B/op`, `ms` lower is better, `MB/s` higher is better, any other unit fails.
Tolerance 20 % (time, throughput) and 10 % (B/op); a worse score or a missed target is run twice more
and judged on the median of the three runs (ADR-0021, Notes of 2026-11-11). `regen` moves a baseline only on a gain beyond the tolerance; lowering one takes a finding.
A baseline comes only from the spec gate's step 4 run; every other run is informative only and never moves or judges a baseline (F-36, ADR-0021). A row with no result fails; a result with no row is a new row. Plain `check` ignores `bench.tsv`; `check --baseline` fails on a removed row and compares no score.

Scenario names come from one rule in `scripts/ratchet.java` (`SCENARIOS`): the JMH benchmark
`dev.rail8.mapping.bench.<Mapping>Bench.<method>` with `<mapping>` = `<Mapping>` with a lower-case
first letter, and `<method>`:

| Method | Primary metric | `gc.alloc.rate.norm` (`-prof gc`) |
| --- | --- | --- |
| `smallLatency` | `<mapping>/small/latency` (`us/op`) | `<mapping>/small/alloc` (`B/op`) |
| `largeThroughput` | `<mapping>/large/throughput`, `MB/s` = `inputBytes` / 2^20 / seconds per op (`avgt` in `us/op` or `ms/op`) | `<mapping>/large/alloc` (`B/op`) |
| `largeHeap` | `<mapping>/large/heap`: the `-Xmx128m` fork completes, no row | none |
| `compile` | `compile/<mapping>` (`ms/op`) | none |

`largeThroughput` carries the JMH `@Param` `inputBytes`, the exact byte length of its input. Any
other benchmark name fails. Targets (GATE check 7): `*/small/latency` ≤ 500 `us/op`,
`*/large/throughput` ≥ 20 `MB/s`, `compile/orders` ≤ 5 `ms/op`, `ui/tree-20128/scroll` < 33.3 `ms`;
a result in another unit than its target's fails. The tree result is a JSON object
`{"scenario": "ui/tree-20128/scroll", "score": <median p95>, "unit": "ms"}`.

Percentages are whole numbers, rounded down. `mutation.tsv` holds counts: surviving = generated minus
killed, NON_VIABLE excluded; one more survivor than the row is a drop, and `regen` keeps the smaller
count (`generated` is informative). A row of the old format (two columns, a whole-percent score) is
still read, compared as a percent, and rewritten by `regen --all`. Module names are directories (`engine/xdm`).

## Commands

- `java scripts/ratchet.java check` (ticket-gate step 5): exit 1 if a listed case fails, is
  skipped or is missing, or a percentage is below its line. A module the gate did not build
  prints `NOT_RUN`. A rise only prints.
- `java scripts/ratchet.java check --baseline <dir>` (MAP-12a): `check`, then the commit's rows held
  against a copy of an earlier commit's `ratchet/` in `<dir>`. Exit 1 also if a row or a listed case
  of the baseline is gone, a percentage is lower, a survivor count is higher, or a report the commit
  must have is missing (a module with a row, except a `ui/` row, or with production code; an oracle
  list that lists a case). Prints `MEASURED coverage <module> lines c/t branches c/t`; exit 2 on a
  wrong command line, an incomplete `<dir>` or a two-column mutation row; writes nothing. ADR-0019
  "Commands".
- `scripts/ratchet-ci.sh push` (GitHub, the backend job's last step, MAP-12b): finds the baseline with
  `scripts/ratchet-baseline.sh` - the closest earlier commit of `main` whose push run had the backend job
  green - and runs `check --baseline` against its `ratchet/`. `scripts/ratchet-ci.sh nightly` (the nightly
  job, after PIT): each module's surviving mutants against `mutation.tsv`, through `scripts/pit-summary.sh`.
  Both report until the date in `scripts/ratchet-enforced-from` and fail their job from it. ADR-0019
  "On GitHub".
- `java scripts/ratchet.java regen --all [<bench result>...]` (the verifier, at every task commit that
  touches `mapping/`; once spec-gate step 4): requires every coverage and oracle
  report (a mutation row without a fresh PIT report stays, `NOT_RUN`), refuses any drop, writes the files,
  prints the diff. With bench results it also judges
  them like `bench` and writes `bench.tsv`; without them (off the VM) `bench.tsv` is untouched.
- `java scripts/ratchet.java bench <jmh.json> [<tree.json>] [<jmh-2.json> <jmh-3.json>]` (spec-gate step 5):
  judges the results against `bench.tsv` and the targets, exit 1 on a regression, a missed target,
  a row with no result or an unknown unit; writes nothing. A JMH file is a top-level array, the
  tree result an object. A scenario in three or more files is judged on their median; in one or two
  a later file's scenario replaces an earlier one's. The benchmarks that regressed or missed a
  target print as one JMH include regex, `RERUN ^(?:…)$`.

Inputs: `engine/oracle/target/surefire-reports/TEST-*.xml` (test name = case id),
`*/target/site/jacoco/jacoco.csv`, `*/target/pit-reports/mutations.xml` (the gate log `.logs/<t>-pit.log` has one section per module, each preceded by `=== PIT <module> ===`),
`ui/coverage/coverage-summary.json` (summed per directory), and for `bench` and `regen` the spec
gate's `.logs/<spec>-jmh.json`, `.logs/<spec>-jmh-2.json` and `-jmh-3.json` (the re-runs) and the tree-scenario result.

Floors below the ratchet are fixed in the build (ADR-0019): JaCoCo, PIT, Vitest thresholds.
