# MAP-12 - the scout's map of today's ratchet script, its files and its tests (2026-11-16, at 89311471)

Read-only pass (haiku), written down by the architect from the hand-back. Paths under `mapping/` unless stated.

## 1a. Commands (`scripts/ratchet.java`:76-89)

- `check` (exactly 1 arg) runs check; verdict line `RATCHET CHECK OK`.
- `regen --all [<bench result>...]` (args[1]=="--all"); verdict `RATCHET REGEN OK`.
- `bench <result>...` (>=2 args); verdict `RATCHET BENCH OK`. Judges only, writes nothing.
- Anything else (:85-89): stderr
  `usage: java scripts/ratchet.java check | regen --all [<bench result>...] | bench <bench result>...`, exit 2.
  No per-argument validation: `check extra` and bare `regen` get the usage line; extra args after `--all` become
  bench results.
- Tree: ROOT = `Path.of("").toAbsolutePath()` (:53), i.e. the cwd. No argument or env var (scripts/ has no getenv,
  no TIMED_OUT string).
- Ends: failures>0 prints `RATCHET FAIL: <n> problem(s)`, exit 1 (:91-93); otherwise prints the verdict, exit 0.

## 1b. `check` (:107-205), in order

- Oracles: `ratchet/oracle/corpus.txt`, then `differential.txt` (:115-125), against
  `engine/oracle/target/surefire-reports/TEST-*.xml` (:210-260).
- Coverage: `ratchet/coverage.tsv` (:130) against `jacoco.csv` and `ui/coverage/coverage-summary.json`
  (measureCoverage, :307-357).
- Mutation: `ratchet/mutation.tsv` (:163) against `*/target/pit-reports/mutations.xml` (:378-401).
- `bench.tsv` is not read by check (only judgeBench, :583; judged only if bench results are given, :195).
- Output formats (quoted from the source):
  - `FAIL oracle/<file>: listed case <id> is failing, skipped or missing` (:289);
    `RISE oracle/<file>: <n> new passing case(s)` (:301); `NOT_RUN oracle/<file> (<n> cases)` (:281, no surefire
    reports).
  - `NOT_RUN coverage <module>` (:142); `NOT_RUN mutation <module>` (:168, printed even by regen).
  - `FAIL coverage <m> lines: <now> % is below the ratchet's <listed> %` (:755), same with `branches`;
    `RISE coverage <m> lines: <a> % -> <b> %` (:757).
  - `NEW  coverage <m> <lines>/<branches>` (:153, two spaces); `MOVED coverage <old> -> <new>` (:135).
  - `FAIL mutation <m>: <s> surviving mutants, the ratchet's <listed>` (:179);
    `RISE mutation <m>: <listed> -> <s> surviving mutants` (:181).
  - `NEW  mutation <m> <survivors>/<generated>` (:190). Old two-column rows:
    `mutation <m>: <now> % is below the ratchet's <listed> %` (:755).

## 1c. The two maps

- MOVED_COVERAGE_MODULES (:58-59): `Map.of("ui/src/editor/store", "ui/src/studio/store")`, comment
  `#161 moved the store`; used only at :133.
- MUTATION_MODULES (:60-70): engine/xdm, engine/ir, engine/runtime, engine/serializer, engine/mapping,
  engine/xslt-export, engine/api, engine/udf-api, engine/udf (nine; no bytecode); used only at :380.

## 1d. Coverage measurement

- Modules: `engine/*` subdirectories plus `service`, kept only if `target/site/jacoco/jacoco.csv` is a file
  (:360-368). Sums all rows over LINE_/BRANCH_ columns by header name.
- A csv with fewer than 2 lines is skipped silently (:312-313); such a module gets no coverage entry.
- UI: `ui/coverage/coverage-summary.json` summed per key prefix `<abs root>/ui/src/model/` and
  `.../ui/src/studio/store/` (:333-355).
- Rounding: `percent` = covered*100/(covered+missed), integer division, so floor; total 0 gives 100 (:370-373).
- Mutation: survivors = (non-NON_VIABLE mutants) minus those with `detected='true'`; the script never names
  TIMED_OUT (:378-401).
- Production-code rule (regen only, :415-443): `engine/*` except `engine/oracle`, and `service`, with any `.java`
  under `src/main/java` other than `module-info.java`. Such a module without a jacoco report:
  `FAIL regen: no JaCoCo report for <module> (it has production code)` (:425).
- Coverage row with no report (:761-766): check prints `NOT_RUN coverage <m>`; regen prints
  `FAIL regen: no report for coverage <m>`.
- Module with a report but no row: `NEW  coverage ...` line; check writes nothing.

## 1e, 1f

- readLines (:774-777): a missing file returns an empty list (no error). Blank lines are dropped (:780).
- No other command. Only `regen --all` writes (write(), :464-486; prints `--- ratchet/<file>`, then `- ` and `+ `
  lines). Regen refuses (no write, exit 1) on any FAIL (:201). Without bench results regen leaves `bench.tsv`
  unchanged (:195-197).

## 2. Committed files (no header line in any)

- `ratchet/coverage.tsv`: `module TAB lines% TAB branches%`; 12 rows. e.g. `engine/api	99	100`,
  `ui/src/studio/store	100	100`.
- `ratchet/mutation.tsv`: `module TAB surviving TAB generated`; 7 rows (xdm, ir, mapping, runtime, serializer,
  xslt-export, api). e.g. `engine/mapping	11	525`, `engine/api	0	109`.
- `ratchet/bench.tsv`: `scenario TAB machine TAB score TAB unit`; 14 rows. e.g.
  `orders/small/latency	claude-cloud-4vcpu	166.6	us/op`, `ui/tree-20128/scroll	claude-cloud-4vcpu	16.7	ms`.
- `ratchet/oracle/corpus.txt`: 360 rows, e.g. `bench/contexts/001`, `bench/orders/001`. `oracle/differential.txt`:
  323 rows, same first two examples.
- `ratchet/README.md` :1-30 and :50-56 (summary): files are sorted line-based; percentages rounded down; bench
  tolerance 20 % (time, throughput), 10 % (B/op); check exits 1 on failing/missing listed cases or a percent below
  its line; a rise only prints; regen refuses any drop.

## 3. Script test harnesses

All: `here=$(cd "$(dirname "$0")" && pwd)`, `java_src=$here/../ratchet.java`, mktemp, trap rm; `set -uo pipefail`, no
`-e`; a `check` helper prints `ok   <name>` or `FAIL <name>: expected '<x>' got '<y>'`; exit `$fails`; the script is
run as `(cd "$tmp" && java "$java_src" "$@" 2>&1)`.

- `ratchet-moved-test.sh`: 9 cases. Fabricates `ratchet/{oracle/*.txt,mutation.tsv,coverage.tsv}`, `engine/`,
  `ui/src/studio/store/a.ts`, `ui/coverage/coverage-summary.json`.
- `ratchet-mutation-test.sh`: 11 cases. `report <module> <killed> <survived> <non-viable>` writes
  `<module>/target/pit-reports/mutations.xml`; `mutation.tsv` rows in both formats.
- `ratchet-bench-test.sh`: 64 cases. Helpers bm/jmh/tree/rows/lines (`grep -cF`); fabricates JMH JSON arrays, tree
  JSON, `ratchet/bench.tsv`; canned machine `claude-cloud-4vcpu`.
- `pit-summary-test.sh`: 17 cases (79 lines). `report` helper as above; PIT_ROOT=$tmp; fake mvn via PIT_MVN for
  `--run`.

## 4. `scripts/pit-summary.sh` (71 lines)

- Args: `<mutation.tsv> <module>...` (usage exit 2 if <2 args); `--run <log> <module>...` (exit 2 if <3 args). Root:
  PIT_ROOT or the script's parent dir.
- `--run`: writes `=== PIT <module> ===` then runs
  `${PIT_MVN:-../mvnw} -B -pl <m> -am test-compile org.pitest:pitest-maven:mutationCoverage` (:28); exit 1 if any
  failed.
- Output lines (:43-68): `<m> no PIT report DROP` (exit 1); `<m> <killed>/<gen> survivors <S> floor none ok` (no
  floor row); `<m> <killed>/<gen> <score>% floor <N>% ok|DROP` (old format, :59/:62);
  `<m> <killed>/<gen> survivors <S> floor <N> ok|DROP` (:65/:68).
- Exit 0 ok, 1 if any DROP or missing report, 2 usage.
- DROP (:42-66): new format, survivors > floor; old format, score < floor; or no report.
- TIMED_OUT (:49): not named in the script. killed = count of `detected='true'` (or "true"); its PIT status is not
  read.

## 5. `.github/workflows/mapping-nightly.yml` (58 lines; repository root)

- Triggers: `schedule` cron `'17 2 * * *'` (:8), `workflow_dispatch` (:9). Permissions: `contents: read` (:11-12).
- Job `mutation`, name `mapping · mutation`, `runs-on: ubuntu-24.04`, working-directory `mapping` (:17-23).
- Steps: `actions/checkout@v7`; `actions/setup-java@v6` (temurin, 25, cache maven); `Maven mirror` (writes
  `~/.m2/settings.xml`, :32-45); `PIT` (:48-52); `actions/upload-artifact@v4` (pit-reports, if: always, :53-58).
- PIT step (:50-52) verbatim: `../mvnw -B` /
  `-pl engine/xdm,engine/ir,engine/runtime,engine/serializer,engine/mapping,engine/xslt-export,engine/api,`
  `engine/udf-api,engine/udf` (one argument, wrapped here)
  / `-am test-compile org.pitest:pitest-maven:mutationCoverage`.

## 6. `docs/findings/F-16.md` (lines 1-25)

- PIT counts TIMED_OUT mutants as killed, so the mutation score depends on machine load (no timeout configured).
  Non-blocking. No change made (:16).
- Decision (:19-20 verbatim): "Until then a ratchet drop that comes from timed-out mutants alone is judged by the
  architect," / "not treated as a ticket failure."
- :22 update 2026-11-10: the timeout and ratchet proposal moves to a process spec after milestone 1.

## 7. `docs/adr/0019-ratchet-and-floors.md` (quotes)

- :6-7 "ADR-0017 makes the ratchet check ticket-gate step 5 and the" / "regeneration spec-gate step 4, and leaves
  the format, the commands and the floors to #14."
- :19 "| `ratchet/oracle/w3c-<suite>.txt` | passing W3C case (later milestones) | `<suite>:<set>/<case>` |"
- :24-25 "Percentages are whole numbers, **rounded down**. A refactor that moves coverage by a fraction of" / "a
  point does not fail unless it crosses a whole percent."
- :35 "`check` (ticket gate) ignores `bench.tsv`. Benchmarks run only in spec-gate step 5."
- :49-63 summary: check exits 1 on a failing/skipped/missing listed case or a percent below its line; regen
  requires every report, refuses drops, writes, prints the diff.
- :81-91 PIT: nightly over nine class-C modules, failing below floor (:82); spec-gate step 1b on touched modules
  feeds `mutation.tsv` (:83-84); :90-91 "A module's survivor count may never rise above its row, so one new
  surviving mutant is a drop."
- :111-114 "Regenerating after every merge (the earlier WORKFLOW §4 step 6): rejected." Reason: full build per
  ticket, moving target.

## Discrepancies (reported, not resolved)

- ADR-0019's header (:3) says the coverage path was amended by the mapping's F-34 to `ui/src/studio/store`; the
  table rows at :20 and the floors at :76 still say `ui/src/editor/store` (MAP-11 corrects them).
- ADR-0019 :28 "the score is the median of three JMH forks"; `ratchet.java` :24-27 "the median of the per-fork
  means of rawData".
- ADR-0019 :72/:82 list bytecode (after milestone 1) among class-C modules; MUTATION_MODULES (:60-70) and the
  nightly yml have no bytecode.

## Observed on GitHub, 2026-11-16 (the architect, through the GitHub connector and Context7's copy of the REST docs)

- `mapping-ci` run 43928160498 (run number 3, event push, branch main, head 89311471): four jobs, all `success`;
  the jobs list carries `name` as the display name - `mapping · backend`, `mapping · ui`, `mapping · windows`,
  `mapping · stack` - with `status`, `conclusion`, `head_sha`, `run_attempt`. The backend job took 4 min 24 s; its
  checkout step 2 s (a shallow checkout). So a backend-green push run exists on `main` before MAP-12.
- REST docs (docs.github.com/en/rest/actions/workflow-runs, .../workflow-jobs, read through Context7 - the host
  itself is outside this session's network): `GET /repos/{owner}/{repo}/actions/workflows/{workflow_id}/runs` takes
  the workflow's file name as `workflow_id`; query parameters `branch`, `event`, `status`, `head_sha`, `per_page`
  (max 100, default 30), `page`, `created`, `exclude_pull_requests`; "This endpoint will return up to 1,000 results
  for each search when using the following parameters: actor, branch, check_suite_id, created, event, head_sha,
  status"; each run carries `id`, `head_sha`, `head_branch`, `event`, `status`, `conclusion`, `run_number`,
  `run_attempt`. "Anyone with read access to the repository can use this endpoint."
  `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs` takes `filter` (`latest`, the default: "jobs from the most
  recent execution of the workflow run"; `all`), `per_page`, `page`.
- Not stated on those pages, so unverified: the order of the runs list; that the running run is in the list.
