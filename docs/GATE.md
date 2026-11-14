# Milestone 1 gate — "jq filters run on JSON bytes, end to end"

**Final, 2026-11-11** (issue #17, ADR-0023). The gate-reviewer (`/gate`) verifies every check on one
commit of `main`; each check names the command and the evidence it needs, and a change to a check
is a finding (`docs/WORKFLOW.md` §7). Checks that need a run (the spec gate, JMH, the nightly PIT)
are taken on a tree that differs from the gate commit's only in what the run writes (`ratchet/`,
`bench/RESULTS.md`) and in `agent/`: the spec gate on the last spec's integration branch, the
nightly PIT dispatched on the gate commit (`nightly.yml` has a manual trigger).

## Checks

1. **Language.** The jq 1.8 language and builtins run on the interpreter: all 879 cases of jq's own
   test files (copied under ADR-0021; ADR-0020's runner) pass and are listed in
   `ratchet/oracle/jqtest.txt`. No group is left out. A case is excused from this check only by a
   finding and a `DEVIATIONS.md` row that names it; a line already in the list never drops
   (ADR-0020, ADR-0021, ADR-0023).
2. **Agreement with jq.** On the differential cases (hand-written, translated from jq's `shtest`
   groups, and generated) stdout, stderr and the exit code equal the reference jq binary's, byte for
   byte, syntax-error text excepted (ADR-0020); every case id is in
   `ratchet/oracle/differential.txt`.
   - **Generated cases:** at least 1000 cases in `differential.txt`, counted after ADR-0020's
     drops, each for `values-v1`, `core-v1` and `ownership-v1`, and 200 for each builtin family
     (`builtins-<family>-v1`: collections, strings, regex, dates, math, formats). The host-facing
     builtins (`debug`, `stderr`, `env`, `halt`, `halt_error`, `input_filename`,
     `input_line_number`, `get_search_list`, `now`) belong to no family and arrive in specs 5 and 7
     with hand-written cases. A minimum may be raised freely and
     lowered only by a finding. A seed that hits a listed deviation stays as a deviation case
     (ADR-0023).
   - **Builtin coverage:** each of jq 1.8.2's 226 public builtins is named, by name and arity, in
     the filter of at least one passing jq-test or differential case (`scripts/builtin-coverage.sh`,
     reporting from spec 4; its list of uncovered builtins is empty at the gate).
   - **Deviations:** every `DEVIATIONS.md` row names a passing differential case or `test:` method
     that checks both columns.
   - **Compile modes:** the optimised, the conservative and the copy-only run agree on the
     compile-mode cases (`ratchet/oracle/compile.txt`, one line per mode; ADR-0018, ADR-0022).
     `ratchet/oracle/backend.txt` stays empty, reserved for milestone 2.
   - **Ownership:** `keptResultsNeverChange`, `sharedInputsNeverChange`, `keepsArgsIsSound` and
     `licenceFacts` pass (ADR-0022).
   (ADR-0018, ADR-0020, ADR-0022, ADR-0023.)
3. **JSON.** The reader accepts and rejects what the reference jq does on all 318 JSONTestSuite
   files (`ratchet/oracle/json.txt`, jq-ref verdicts, ADR-0015), `n_multidigit_number_then_00` as
   its deviation case. A malformed document gives jq's message, line and column on every route (the
   command line, `fromjson`, `input`, the API); the API's `JqSyntaxException` also carries the byte
   offset. Untouched strings come out byte-identical; literal numbers come out as jq prints them:
   copied byte for byte when the token is canonical, otherwise in jq's canonical form (ADR-0012,
   F-1). Every input and output mode of ADR-0015 has a differential case, including the `--seq`
   resynchronisation and resuming after a caught `input` error. (ADR-0012, ADR-0015.)
4. **Memory.** Each budget (recursion depth, bytes built, bytes written, steps, deadline) ends a
   filter as an uncatchable termination with a jq error message (ADR-0008, F-8), while the parse
   depth limit and the regex match time raise jq's catchable errors (F-13, ADR-0017), shown by a
   test per budget whose witness would otherwise exhaust the limit; the stack backstop ends as a
   termination (`stackBackstopIsTermination`, ADR-0011). `inPlaceKeepsBytesBuiltLinear` passes
   (ADR-0022). On the JMH scenarios (ADR-0019): G1 allocates at most 1 KiB per operation more on
   10 MB than on 1 KB; G4 grows at most 225× from 10⁴ to 10⁶ elements in time and allocation; G1,
   G2 and G6 `-c` on 10 MB complete in a 64 MiB fork. (ADR-0008, ADR-0011, ADR-0017, ADR-0019,
   ADR-0022.)
5. **Speed.** The 16 gate-scenario cells of ADR-0019 (G1–G6 at their sizes) and G7 run in spec-gate
   step 3 on `claude-cloud-4vcpu`, recorded in `bench/RESULTS.md` with machine, date and commit,
   beside the reference jq binary's time per document measured in the same session
   (`bench/jq-ref.sh`). Target: every G1–G6 cell at most 1.0× the reference time per document; G7 is
   recorded with no factor. A cell over 1.0× fails this check; it is written up as a finding, and
   only the owner's change to ADR-0018 or to the factor lifts it (ADR-0019). No cell and neither G7
   measurement is worse than `ratchet/bench.tsv` beyond its per-cell tolerance (set in spec 6 by
   a finding; before spec 6 the bench ratchet records and fails nothing). Every diagnostic scenario,
   `ownershipGain` included, has a row in `bench/RESULTS.md`. (ADR-0019, ADR-0023.)
6. **Command line.** `jqj` runs a filter over files and standard input with every option of jq
   1.8.2's `--help` except `--stream-errors`, and with jq's exit codes (ADR-0017); shown by
   differential cases that go through the command line. `-h`, `-V` and `--build-configuration`
   print jqj's own text (`DEVIATIONS.md`); jq's rc-2 usage lines stay byte-identical. `--unbuffered`
   has a differential case for its output and a test for the flush after each result. ADR-0013's module-path cases pass on the interpreter. (ADR-0013, ADR-0015,
   ADR-0017, ADR-0020.)
7. **Embedding.** ADR-0016's API and ADR-0017's host SPI are in place: a test checks every exported
   signature; a compiled filter is immutable and safe to share between threads (a concurrency
   test), and unloads when dropped (a test that observes the filter and its interpreter tree
   collected, ADR-0018); a second use of a running Workspace throws `IllegalStateException`; a
   thread interrupt ends a run with `CANCELLED`; host `stream` has its cases. Under the embedding
   defaults a filter cannot read the environment or the system time zone, name a file, halt the
   JVM or import from disk (one test per rule, each with a witness filter); `import` resolves
   through the host. `Limits.embedded()` carries the step default and refuel interval set in spec 6.
   (ADR-0016, ADR-0017, ADR-0018.)
8. **Quality.** The CI run of the gate commit is green in every required job. The spec gate
   (`scripts/gate-spec.sh`) passes on the VM. Floors: JaCoCo lines ≥ 90 % and branches ≥ 80 % on
   `query-core` and `jq-core`, lines ≥ 80 % on `jq-cli`; PIT ≥ 80 % on the class-C modules, killed
   over all generated mutants (the nightly run; `quality/floors.tsv`, ADR-0020). The ratchet is
   green (`java scripts/ratchet.java check`). `query-core` has no reference to JSON or jq (the word
   check `scripts/check-core-neutral.sh`, run by the ticket gate and CI, ADR-0013). `NOTICE` lists
   every copied or translated file (jq's tests and `shtest`, the `jv_parse.c` translation,
   JSONTestSuite, the CC BY 3.0 attribution), and the `jq-core` jar carries jq's MIT text. Every spec
   of the milestone was merged through `/run-spec` with its review. (ADR-0013, ADR-0014, ADR-0015,
   ADR-0020, ADR-0021.)
9. **Hand-over.** `HANDOVER.md` covers: building and running from a clean clone with only JDK 25
   installed; running a filter from the command line and from Java; how to run the ticket gate,
   the spec gate and the benchmarks; what the Rail8 (R8) adapter needs: the API of check 7, the host
   rule (a result is valid during its sink call, or while the host holds the document it views;
   otherwise `detach()`), owned against Workspace documents, `Limits.embedded()` and `Limits.cli()`
   with their values, the sandbox defaults, `needsWholeDocument` and host streaming, `byte[]`
   documents of at most 2^31−3 bytes (F-12), the regex risk (joni's uncapped stack) and ADR-0022's
   accepted costs; open findings, ADRs to revisit and the next three specs. The gate-reviewer
   follows the start instructions on a fresh worktree and records the result. (ADR-0015, ADR-0016,
   ADR-0017, ADR-0022.)

## Specs in build order

ADR-0023 fixes this order. Milestone 1 is built as a **walking skeleton** first, then widened axis
by axis; each spec goes through `/grill-with-docs` → `/to-spec` → `/to-tickets` → `/run-spec` in
this order, and no spec issue exists before its `/to-spec`. A spec may split in two at its own
grilling without reopening this file. Check 8 holds after every spec. Every ticket that adds a
builtin or a construct also adds its oracle cases. Each spec adds the API and SPI pieces, budget
ticks and bench scenarios its features need. Every spec gate records each existing bench cell's
factor against same-session jq, and runs the 64 MiB forks of check 4 that exist; a cell over 1.0×
is reported to the owner at that spec's end.

| # | Spec | Scope | Proves |
| --- | --- | --- | --- |
| 1 | Walking skeleton | The tooling tickets first, blocking every class-C ticket except the API slice, which only the build ticket blocks; spec 1's last ticket measures every ticket-gate step alone and three at a time, and PIT per passing seed (F-19). The tooling: modules with `module-info`, the oracle module and runners, `scripts/ratchet.java` and the gate scripts, the record script, JaCoCo, PIT in a nightly workflow, `quality/floors.tsv`, the word check, the bench module and `bench/jq-ref.sh`, CI (the owner then makes its three jobs required, ADR-0014); copies of jq's tests and JSONTestSuite with `NOTICE`. Then the thinnest path through every layer: `.` and `.a` over one JSON document — value model, tape, reader, lexer, parser, IR, the interpreter, writer, a minimal API and the command line — with the first jq-test, differential and JSON cases in the ratchet and G1, G6 and G7 in `bench.tsv`. | 8; first slices of 1, 2, 3, 5, 6 |
| 2 | Core language | Paths, pipes, comma, literals, construction, operators, computed numbers and their printing, comparisons and sort order, variables and destructuring, `if`, `try`, alternative, `reduce`, `foreach`, `label`, definitions and closures, the prelude loader and the natives its cases need, the ADR-0007 analyses its constructs need with the compile-mode oracle (path shapes arrive in spec 3, holds and root use in spec 5, touched fields stay out), specialisation and tail calls, recursion with the trampoline and U calibration, the mechanics of all five budgets. G2 and G4 at 10⁴ enter `bench.tsv`; G4 is expected over 1.0× until in-place updates land. May split into 2a and 2b. | 1, 2, 4; first slice of 5 |
| 3 | Paths and updates | Path mode (ADR-0010), `path()`, `paths`, the `getpath` family, `del`, the assignment operators, the natives the in-place red needs; then in-place updates (ADR-0022, class C, with copy-only and the ownership tests) and, after it, nested-reduce inheritance. G4 at 10⁵ and 10⁶ and G5 enter `bench.tsv`. | 1, 2, 4 |
| 4 | Builtins | The remaining builtins by family (collections, strings, regex with joni and its limits, dates, math, formats `@…`), each with its oracle cases and generator; builtin coverage. G3 enters `bench.tsv`. | 1, 2, 4 |
| 5 | JSON in full | ADR-0015 complete: input modes, the stream across files, `input` and `inputs`, lenient tokens, limits, the `jv_parse.c` replay, `--seq`, every output option; the `shtest` groups translated and recorded (those needing spec 7's flags pass there); host streaming (ADR-0007, ADR-0016). | 1, 2, 3, 4, 7 |
| 6 | Speed | Every cell measured beside same-session jq; checks 4 and 5 in full; the per-cell tolerance; the step default and refuel interval (ADR-0017 amendment); the whole-container copy; the fast paths a missed cell calls for; the diagnostic records. | 4, 5 |
| 7 | Embedding and command line | ADR-0016's surface closed and ADR-0017's host SPI, sandbox, `import`/`include`/`modulemeta`, cancellation; the remaining flags and exit codes. Its last ticket writes `HANDOVER.md`. | 1, 2, 6, 7, 9 |

## Out of the gate

The R8 adapter, an XML front end, a native image of the command line, a Vector API scanner,
publishing to Maven Central; the bytecode backend and the backend oracle (milestone 2, ADR-0018);
the chunked reader and skipped subtrees with tape tag 7 and touched fields (ADR-0015);
`--stream-errors`; the options jq accepts outside `--help` (`-b`, `--run-tests`,
`--debug-dump-disasm`, `--debug-trace`, `--debug-trace=all`); POSIX `TZ` strings (ADR-0020); the API-diff check (ADR-0016, at the first
publish); ADR-0022's follow-ups (a foreach's downstream read-only analysis, a key reading `.` as a
borrow, re-stamping the spine at retire).
