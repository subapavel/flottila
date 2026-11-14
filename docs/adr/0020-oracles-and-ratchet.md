# Oracles and ratchet: copied jq tests, recorded jq-ref expectations, required cases, per-merge pass lists

Status: accepted (2026-11-11, owner, issue #15 "How do the oracles and the ratchet work?"); replaces `docs/WORKFLOW.md` §6's ratchet and floor text together with ADR-0014; amends ADR-0013 (the CLI entry), ADR-0014 (ticket gate and CI), ADR-0016 (compile error text) and ADR-0017 (CLI environment, locale); supersedes founding §1's "not goals: identical error message wording" (F-15); finding F-14; amended by ADR-0022 (issue #8: no in-place licence in the conservative compile, copy-only switch); amended by ADR-0023 (issue #17: deviation seeds, generator minimums, `compile.txt` modes, `values-v1`); amended by F-19 (who measures the fallback targets); amended by ADR-0024 (the Stub verdict); amended by ADR-0025 (`values-v1`: forms, seeds, generated files, drops); amended by F-27 (PIT and the compile-mode oracle leave the ticket gate); amended by F-28 (the spec-gate PIT target); amended by F-29 (the compile-mode oracle after every merge); amended by #60 (compile-mode oracle: switch by reflection, compile report, after-merge script); amended by ADR-0033 (`core-v1`: forms, break placement, freeze numbers); amended by F-46 (#89: the ticket gate runs the compile-mode oracle); amended by F-84 (two jq-core test classes leave PIT's test set)

Facts marked "jq-ref" were observed on 1.8.2 by the adviser (issue #15 round 1). Every runner lives in
`oracle` on the class path, in `…jqj.oracle.{jqtest,diff,compile,backend,json,gen,record}`.

## Sources

- **jq's test files** are copied byte for byte into `oracle/src/test/resources/jq/tests/`, together
  with jq's `COPYING`, a `SOURCES.sha256` and a `NOTICE` line. They are `jq.test`, `onig.test`,
  `base64.test`, `uri.test`, `optional.test`, `man.test`, `manonig.test` and `tests/modules/**`:
  879 cases (550 + 47 + 10 + 20 + 2 + 231 + 19, all passing on jq-ref). ADR-0021 records the
  licence. The `man.test` runner sets `PAGER=less`; without it two cases fail on jq-ref.
- **jq's `tests/shtest`** (MIT) is translated into `diff/shtest-<group>.cases`, with jq's notice in
  each header and in `NOTICE`. Its expected output comes from recordings. It drops
  `--debug-dump-disasm`, `LD_PRELOAD` injection, valgrind, `-b` and `--stream-errors` (ADR-0023),
  each with its reason.
- **JSONTestSuite** at `1ef36fa0` (318 `test_parsing` files and its LICENSE) is copied into
  `jsontestsuite/` (ADR-0015).
- **The reference profile** is the release binary jq-linux-amd64 1.8.2: static,
  `--with-oniguruma=builtin`, decNumber, `have_decnum` and `have_literal_numbers` true, 226
  builtins. A case `profile/builtins` runs `[builtins] | sort`.

## The jqtest oracle

- **The parser follows `run_jq_tests`:**
  - a skip line is blank, or `#` after spaces or tabs;
  - the markers are exactly `%%FAIL` and `%%FAIL IGNORE MSG`;
  - a FAIL case only compiles;
  - the line after the program is the input, taken raw;
  - expected lines run until a skip line or EOF;
  - jqj rejects a line of 4096 bytes or more (jq's `fgets` would split it at 4095).
- **The verdict follows `jq_test.c`:**
  - too few results, a mismatch or a superfluous result fails;
  - an error after the last expected result passes;
  - each expected value must survive jqj's writer and reader.
- **A Stub** (ADR-0024): a case whose jqj exception or stderr carries the `jqj stub: ` prefix is FAIL, before any rule below (F-20).
- **A FAIL case** fails if it compiles. Otherwise its expected lines are compared with the text of
  the **last** compile error block (jq's `test_err_cb` keeps only the last). The comparison is by
  prefix, skipping one LF each time, and leftover text fails. Under the error rule below, the five
  cases whose message is a Bison "syntax error, unexpected …" check only that compiling fails.
- **Equality** is `JvEqual` in `oracle`, independent of jqj's `==`, as `jv_equal`:
  - kinds must match;
  - two literals compare as exact decimals, NaN equal to nothing;
  - any other number pair compares as doubles;
  - strings compare by UTF-8 bytes, arrays by position, objects by key set.

  Number text is left to the differential oracle (ADR-0012).
- **The host** is `Host.sandboxed()` with UTC, a `ModuleLoader` over the copied `tests/modules`, and
  debug discarded; for `man.test` it also sets `environment({PAGER=less})` (`$ENV.PAGER` and
  `env.PAGER` cases).
- **Case id:** `<file>#<first 12 hex digits of SHA-256 over the case's lines>`, LF-joined with the
  marker included. Identical duplicates get `~2`, `~3` in file order. An id survives inserted,
  deleted or moved cases; a changed case gets a new id.

## The differential oracle

- **Case files:** `oracle/src/test/resources/diff/<family>.cases`, one per family.
  - Each case is a block from `case <family>/<name>` to `end`, one `key value` per line.
  - Values are escaped `\n \t \\ \xHH` and are otherwise UTF-8.
  - The keys are `arg` (repeatable), `filter`, `stdin`, `file <name> <bytes>`, `env K=V`,
    `deviation <slug>`, and `jqj-rc`/`jqj-stdout`/`jqj-stderr`, the last three only with
    `deviation`.
  - Ids are immutable.
- **Recordings:** `<family>.rec` is generated and never edited.
  - Its header is `# jq-1.8.2 sha256 <hex>`.
  - Per case it holds `def <12 hex of the case block>`, `rc`, `stdout` and `stderr`.
  - `scripts/oracle-record.sh <cases…>` writes it. The script runs jq-ref in a fresh directory per
    case, with that case's files and an environment of exactly `HOME=<dir> TZ=UTC LC_ALL=C` plus
    the case's `env`. It runs each case twice with a 10 s timeout and rejects a case whose two runs
    differ.
  - `--verify` re-runs every case and fails on any byte difference or a different jq-ref sha.
  - The writer records in its own ticket, because the recording is the ticket's red.
  - The ticket gate runs `--verify` on the `.rec` files the ticket changed. CI `build` and the spec
    gate run it on all.
  - The normal test run and PIT never start jq-ref.
- **Comparison** is the exit code, stdout and stderr, byte for byte, under the error rule below. A
  `def` that differs from the recording is STALE, which fails.
- **jqj runs in process** through `jq-cli`'s `int run(Invocation)`, where `record
  Invocation(List<String> args, Map<String,String> env, Path cwd, InputStream in, OutputStream
  out, OutputStream err)`. This replaces ADR-0013's `run(String[], …)`. The command-line host takes
  the environment, `TZ` and `HOME` from the `Invocation` (amends ADR-0017). `TZ` unset →
  `ZoneId.systemDefault()`; set to an IANA id → that zone; set to anything else → UTC, as jq-ref
  does for an unknown name. jq-ref honours POSIX strings such as `JST-9`; supporting them is a
  later case.
- **Generated cases** are named `gen/<generator>/<seed>`.
  - `oracle-record.sh --generate <gen> <from> <to>` commits the cases and their recordings. It
    drops seeds where jq-ref gives rc 3 or times out. A seed that hits a listed deviation stays as
    a deviation case: its ticket adds the row's slug and jqj's expected output, and review confirms
    the difference is only that deviation (ADR-0023). Minimums: 1000 seeds each for `values-v1`,
    `core-v1` and `ownership-v1`, 200 per builtin family.
  - `values-v1` (recorded whole in spec 1; more of it passes by spec 5, F-18) runs random documents with lenient tokens and the UTF-8
    classes under `-c .`, `.`, `-S`, `-a`, `tojson`, `[paths]` and `tostream`.
  - `core-v1` (spec 2) runs a typed core-language grammar to depth 4.
  - `builtins-<family>-v1` is added per family.
  - A changed generator gets a new name.
- **The json oracle:** each JSONTestSuite file is a recorded case `-c . <file>`. Its id is the file
  name without `.json`, and its label is the prefix. `n_multidigit_number_then_00` carries
  `deviation nul`.

## Error text

jqj matches jq's error text wherever a filter or a script can read it: caught messages, JSON parse
errors (ADR-0015), the runtime `jq: error (at <file>:<n>): …` line and exit codes. Compile errors
other than syntax errors match too (`$bar is not defined`, `Module metadata must be constant`), as
do the 13 jq.test `%%FAIL` cases that check them. **Syntax errors keep jqj's own text.** A
differential case whose recorded stderr has a line starting `jq: error: syntax error` compares rc
and stdout only, and
`DEVIATIONS.md` has one row for it. jq's Bison text ("expecting" lists that depend on the parser
state, several errors after recovery) would mean emulating Bison's LALR tables, while founding §3
chose a Pratt parser for its messages. `JqCompileException.getMessage()` (ADR-0016) is jq's whole
command-line text when no syntax error is involved: every error block, each with its source line
and carets, then `jq: N compile error(s)`. Bison recovery can add blocks after a syntax error
(jq.test `{1+2:3}` prints a syntax block, then "May need parentheses…", then `jq: 2 compile
errors`); such text is jqj's own. The jqtest runner reads the last block from a list of blocks.

## The compile-mode and backend oracles

- **In milestone 1** (ADR-0018), the compile-mode oracle runs every jqtest, differential and
  generated case twice: optimised and conservative, through an engine-internal switch. It
  compares output and error. Cases that end in a termination under either compile are left out (a
  tail-recursive case can finish optimised and hit the recursion budget conservative). A third run
  under the copy-only switch (ADR-0022) checks in-place updates the same way; cases that end in a
  termination under copy-only are left out. The oracle runs in the ticket
  gate unless spec 1's last ticket (F-19), or any later spec gate (ADR-0023), measures the oracle above
  ADR-0014's 60 s target, in which case
  it moves to the spec gate. **#40 measured step 1 above 60 s (F-27): the compile-mode oracle,
  when built, runs in the spec gate.**
- **The backend oracle** (interpreter against bytecode) starts with the bytecode spec. It compares
  output bytes, the error value or termination cause, rc and `Workspace.stepsUsed()`. It then runs
  each case again with half the steps it used, and requires the same output prefix and
  termination (ADR-0008).

## JUnit and PIT

- Each oracle is a `@TestFactory` of DynamicTests: one per required case and backend, sorted by id.
- **A required case** is either in the ratchet or named by the ticket at its gate
  (`-Doracle.require=<file>`, the `ORACLE_REQUIRE` file `gate-ticket.sh` is given; F-38, #84).
- **The oracle report** `oracle/target/oracle-reports/<oracle>-<backend>.tsv` (`id`, PASS, FAIL,
  STALE or CRASH, detail) holds every case: required cases write their verdict when asserted.
- **A Survey test** runs every case that is not required and adds it to the report. It fails only
  on CRASH, an exception that is not a `JqException`. PIT excludes it.
- **PIT 1.30.2 with pitest-junit5-plugin 1.2.3 and JUnit 6.1.3 on JDK 25.0.5 works this way**
  (VERIFIED_PASS, adviser probe): each dynamic test is a unit, ids are positional, an aborted test
  counts as passed, and every unit run rebuilds the factory (3.0 ms with 1 case, 5.6 ms with 5000).
- A surviving mutant covered by 5000 cases costs at least 28 s. If spec 1's last ticket (F-19), or any later
  spec gate (ADR-0023), measures the ticket gate's PIT step above 3 minutes, generated seeds are first grouped per unit or kept out of
  PIT; only if that is not enough does PIT leave the ticket gate by a finding (ADR-0014). **#40
  measured 535 s even without seeds: PIT left the ticket gate (F-27).** The spec gate's PIT has a
  45-min target (F-28); above it, the seeds are grouped per unit or kept out of PIT, by a finding.
- **The handed-over test list of ADR-0008 to ADR-0011** (issue #7) belongs to the tickets that build
  those parts, as unit tests or oracle cases. Examples: `emitFalseStopsProducer`,
  `tryFlagPerActivation`, one test per budget, `stackBackstopIsTermination`,
  `calibrationFitsHalfStack`, `walkEqualsTracked`, and the jq.test label/break cases.

## Ratchet

- **Files:**

  | File | Columns |
  | --- | --- |
  | `oracle/jqtest.txt` | `id`, `backend` |
  | `oracle/differential.txt` | `id`, `def`, `backend` |
  | `oracle/compile.txt` | `id`, `def`, `mode` (`conservative`, `copy-only`; ADR-0023) |
  | `oracle/backend.txt` | `id`, `def` |
  | `oracle/json.txt` | `id`, `label`, `def` |
  | `coverage.tsv`, `mutation.tsv` | as `ratchet/README.md` |
  | `bench.tsv` | `scenario`, `machine`, `score`, `unit`, `tolerance%` (ADR-0019: jqj's time) |

  Headers: `# source <path> sha256` for each copied file, and `# jq-ref <ver> sha256`. Oracle lines
  are kept per backend from the start, so the second backend's progress shows.
- **`ratchet.java check`** fails when a ratcheted id is FAIL, STALE or missing from the report, when
  its `def` changed, or when a percentage is below its line or its floor. A missing id is retired
  with a warning only if its source file's sha differs from the header.
- **Cadence:** the verifier runs `regen --oracles` after every merge into the integration branch.
  It takes the union of pass lists and refuses any drop. The input is a verify of the merge commit,
  or the gate's report when the merged tree equals the gated tree. `regen --all` (coverage,
  mutation, bench) runs once per spec, in the spec gate. This resolves F-14.

## Floors

`quality/floors.tsv` (`module`, `class`, `lines`, `branches`, `mutation`) is the class-C list and
the floors in one file. It is read by `gate-ticket.sh`, `gate-spec.sh` and `ratchet.java`, and it
changes only with an ADR.

| module | class | lines | branches | mutation |
| --- | --- | --- | --- | --- |
| `query-core` | C | 90 | 80 | 80 |
| `jq-core` | C | 90 | 80 | 80 |
| `jq-cli` | – | 80 | – | – |

The PIT score is killed mutants over all generated mutants, so an uncovered mutant counts as a
survivor. A class that cannot reach a floor needs a finding.

## Deviations

- Each `DEVIATIONS.md` section is `## Title (ADR, issue)`, prose, a table
  `| case | filter | input | jq 1.8.2 | jqj |`, and `Reason:`.
- `case` names a differential case carrying `deviation <slug>`. Its jq column is the recording, kept
  honest by `--verify`; its jqj column is the case's `jqj-*` keys, which the runner checks.
- Where no command-line case fits (host streaming, the document limit), `case` is
  `test:<Class#method>`.
- A recording equal to its jqj column fails as "deviation gone".

## Environment

- Recordings run with `TZ=UTC` and `LC_ALL=C`. Zone cases set an IANA `TZ`. The static jq-ref reads
  zoneinfo, accepts POSIX strings such as `JST-9`, and gives UTC for an unknown name; whether jqj
  accepts POSIX strings is a later case.
- jq-ref showed **no locale effect**: with a de_DE.UTF-8 locale, `%A %B %c %x %p` stayed English.
  ADR-0017's sentence on locale is corrected accordingly, and there is no locale row.
- `now` appears only in deterministic forms, which the twice-run rule enforces.

## Considered options

- **Ordinal or line ids:** they shift on every upstream insertion.
- **jq-ref inside the normal run or PIT:** a process per mutant, and a clean clone (GATE 9) could
  not test.
- **Hand-written expected outputs:** the oracle stops being independent.
- **JSON-lines case files read by our own reader:** the reader under test would parse its own
  fixtures.
- **jqj's `==` as jqtest equality:** a wrong `==` would hide itself.
- **Every case a JUnit test with failures ignored:** CI goes green on regressions, and PIT's
  baseline is red.
- **One JUnit test per file:** PIT would re-run every case for every mutant.
- **Running shtest as a script.**
- **Byte-exact syntax errors:** an emulation of Bison.
- **No compile text compared:** 13 cheap messages would go unchecked.
- **Pass lists once per spec:** an in-spec regression shows only at the spec gate.
- **PIT over covered mutants only:** uncovered code would be guarded by the line floor alone.
- **One ratchet line for all backends:** every line would drop when the second backend starts.

## Amendment (F-29, spec 2)

Beside the spec gate, the verifier runs the compile-mode oracle on the integration branch after
every merge, with `regen --oracles`; a disagreement stops the next merge until it is fixed. The
ticket gate stays as F-27 left it. (Superseded by F-46, #89: the ticket gate's step 1 now runs `-Pcompile-oracle`.)

## Amendment (#60, compile-mode oracle)

Accepted 2026-11-12, architect under `/run-spec` #56, on the designer's draft for #60.

- **Reaching the switch:** the oracle reaches the engine-internal switch (ADR-0030) by reflection on
  the package-private `JqEngine.Builder.compileMode(CompileMode)`, from one oracle test class and on
  the class path only. A reflective failure is an `IllegalStateException`, never a silent second
  optimised run. jq-cli's in-process entry gains `run(Invocation, UnaryOperator<JqEngine.Builder>)`
  beside `run(Invocation)` (ADR-0013). No module-info and no exported signature changes. Renaming
  the switch is a change to the oracle.
- **What is compared:** every jqtest, differential and generated case runs in both modes. Both
  runs use `Limits.embedded()` with no deadline and no regex match time, so a slower mode cannot
  disagree by timing. Exit code, stdout and stderr are compared byte for byte. A `halt` or
  `halt_error` is compared like any result. A run that ends as a budget, stack, cancel or
  stale-view termination is left out: the case fails as `left out:` and is not ratcheted.
- **Report and ratchet:** the report is `compile-<mode>.tsv`, and `compile.txt` holds the ids that
  pass under `conservative`. Plain `ratchet.java check` (CI) prints `NOT_RUN compile: no report` when no
  compile report exists; the ticket gate runs the oracle and passes `--require-compile`, so there a missing report fails (#89, F-46). `regen` refuses a
  `disagree:` row; that is F-29's stop. The compile-mode tests carry the JUnit tag `compile-mode`
  and run only under the Maven profile `compile-oracle`.
- **After every merge:** `scripts/after-merge.sh <ticket>` runs `./mvnw -B -Pbench,compile-oracle
  verify` and then `regen --oracles`. The spec gate's clean verify adds `-Pcompile-oracle`.
- **Considered:**
  - A qualified `exports … to` the oracle module. javac warns "module not found" (lint `module`),
    which fails the build under `-Werror`. `exported-signatures.txt` would change. On the class
    path the export has no effect.
  - A system property switch: product code would read a test hook.
  - A public builder method, against ADR-0018.
  - A new verdict kind: `left out:` already keeps such a case out of the pass list.
  - A Survey that fails on disagreement, against the glossary.
