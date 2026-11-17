# Ratchet

What passes, per oracle and per module, in the format of ADR-0020's Ratchet section as amended by
ADR-0023. `scripts/ratchet.java` reads and writes these files. Regenerated and committed only by the
verifier, on an integration branch: the oracle pass lists after every merge, everything in the spec
gate (`docs/WORKFLOW.md` §6). Never edited by hand. A drop fails the gate; the one exception is a
listed drop of `compile.txt` ids that the owner approved in a finding (`drop`, below).

## Files

All files are line-based, UTF-8, LF. Columns are separated by one tab.

| File | One line per | Columns |
| --- | --- | --- |
| `oracle/jqtest.txt` | passing case of jq's own test files, per backend | `id`, `backend` |
| `oracle/differential.txt` | case where our output equals the reference jq binary's, per backend | `id`, `def`, `backend` |
| `oracle/compile.txt` | case where the optimised compile and the compile of that mode agree, per mode | `id`, `def`, `mode` (`conservative`, `copy-only`) |
| `oracle/backend.txt` | case where both backends agree | `id`, `def` |
| `oracle/json.txt` | passing JSON conformance case | `id`, `label`, `def` |
| `coverage.tsv` | module | `module`, `lines %`, `branches %` |
| `mutation.tsv` | class-C module | `module`, `surviving mutants`, `generated mutants` |
| `bench.tsv` | benchmark scenario and machine | `scenario`, `machine`, `score`, `unit`, `tolerance%` |
| `drops/F-<n>.txt` | `compile.txt` id that an owner-approved drop removes | `id`, after one header line `# finding F-<n>` |

- **Oracle pass lists** have no column-name line. Their entries are sorted. A pass list may start
  with header lines: `# source <path> sha256 <hex>` for each copied source file and
  `# jq-ref <ver> sha256 <hex>`; `regen` refreshes the sha of a `# source` header. `compile.txt` and
  `backend.txt` are written once their oracles exist.
- **`coverage.tsv`, `mutation.tsv` and `bench.tsv`** start with one line of the column names above;
  the lines after it are sorted.
- **`bench.tsv`**: `score` is jqj's time as JMH reports it (ADR-0019), in `unit` (such as `us/op`),
  at most six decimals. `machine` is the label set by `RATCHET_MACHINE`. `tolerance%` is empty until
  spec 6's finding (ADR-0023).

Percentages are whole numbers, rounded down; the mutation score is computed from the two counts. A
benchmark baseline moves only on a gain beyond its tolerance; lowering one takes a finding.

## Commands

- `java scripts/ratchet.java check [--require-compile]` (ticket gate, CI build): exit 1 if a
  listed case fails, is skipped or is missing, or a percentage is below its line or its floor. A
  module the gate did not build prints `NOT_RUN`. The ticket gate passes `--require-compile`: its
  step 1 runs `-Pcompile-oracle`, so a listed `compile.txt` id without a report row fails (F-46,
  #89). CI's Ratchet step builds without that profile and does not pass it, so a missing compile
  report prints `NOT_RUN compile`.
- `java scripts/ratchet.java regen --oracles` (after every merge): the oracle pass lists only.
- `java scripts/ratchet.java regen --all` (spec gate): requires every report, refuses any drop,
  writes the files, prints the diff.
- `java scripts/ratchet.java floor-mutation <module>...`: checks only the PIT score of the modules
  against their floor. PIT left the ticket gate by F-27 and runs in the spec gate, whose
  `regen --all` checks the same floor.
- `java scripts/ratchet.java drop compile ratchet/drops/F-<n>.txt` (verifier, integration branch):
  removes the `compile.txt` entries of the listed ids and leaves every other line. It needs an owner
  decision recorded in the finding `docs/findings/F-<n>.md` that the drop file's header
  `# finding F-<n>` names; without that file it refuses. The drop file is generated from oracle
  reports, never typed by hand. It refuses the whole drop, writing nothing, if any listed id is not
  in `compile.txt` or if any of its entries' rows in the current compile report is not `FAIL` with
  a `stub:` detail (a row missing from the report counts as not). The report comes from a verify
  that does not stop on the oracle's own failures. It prints each dropped entry and the count. A
  dropped case comes back through the normal `regen` once it passes.

## Inputs of `scripts/ratchet.java`

`RATCHET_ROOT` (default: the current directory), `RATCHET_JMH` (default
`bench/jmh/target/jmh-result.json`), `RATCHET_MACHINE` (the `machine` of the `bench.tsv` lines).

- **Oracle reports** `oracle/target/oracle-reports/<oracle>-<suffix>.tsv`: `id`, verdict (`PASS`,
  `FAIL`, `STALE`, `CRASH`), detail, then the extra columns. The suffix fills the pass list's
  `backend` (jqtest, differential) or `mode` (compile) column; `backend` and `json` ignore it. The
  pass list's `def` is report column 4 (differential, compile, backend); its `label` is column 4
  and its `def` column 5 (json). A listed id whose verdict is not `PASS`, whose `def` or `label`
  changed, or that is missing, fails.
- **Coverage**: JaCoCo `jacoco.csv`; class-C modules from `oracle/target/site/jacoco-aggregate/` rows
  whose `GROUP` is the module, any other module from `<module>/target/site/jacoco/`. A module without
  a report prints `NOT_RUN`.
- **Mutation**: the mutants of `<module>/target/pit-reports/mutations.xml`, combined with oracle's
  crossModule report `oracle/target/pit-reports/mutations.xml` (ADR-0014). A mutant of the module's
  own report counts as killed if either report kills it; the two reports' mutants are matched by
  `mutatedClass`, `mutatedMethod`, `methodDescription`, `lineNumber`, `mutator` and `indexes`.
  Killed is `KILLED`, `TIMED_OUT` or `MEMORY_ERROR`; everything else counts as surviving. Without
  oracle's report the module's own report alone counts. A module without its own report prints
  `NOT_RUN` in `check` and `floor-mutation`, and is a `FAIL` of `regen --all`, which needs every
  report; oracle's report alone does not stand in for it.
- **Bench**: the JMH json result, one line per benchmark (`Class.method` without the class, each `_`
  of the method read as `.`, plus `/param=value`). A `bench.tsv` line with an empty `tolerance%` is
  skipped by `check` and overwritten by `regen --all`; otherwise the baseline moves only on a gain
  beyond the tolerance and a loss beyond it is refused. A score that is not a finite number (`NaN`,
  bare or as JMH's string, or `+INF`/`-INF`) fails `check` only for a scenario with a toleranced
  line of this machine (`check` skips the other lines), and fails `regen --all` for any scenario
  of the result. Both `check` and `regen --all` fail a toleranced line of this machine whose
  scenario the JMH result lacks; lines of other machines and lines with an empty `tolerance%` are
  not checked for that.
