# Milestone 1: the final gate and the specs in build order

Status: accepted (2026-11-11, owner, issue #17 "Final gate and the milestone's specs"); makes `docs/GATE.md` final; amends ADR-0014 (when the fallbacks fire), ADR-0019 (when G4 enters the ratchet, the 1 KB differential cases) and ADR-0020 (deviation seeds, generator minimums, `compile.txt` modes, `values-v1`); records F-17 and F-18; amends `docs/WORKFLOW.md` §2 (Phase 0 ends with the first spec ready for `/grill-with-docs`); amended by F-19 (spec 1 grilling: the API slice and the last ticket's measurements); amended by F-27 (both fallbacks fired at spec 1); amended by F-33 (G3 in spec 2a)

Facts marked "jq-ref" were observed on 1.8.2 by the adviser (issue #17 round 1 and the ADR
review). Each rule says whether it is the owner's answer or the architect's choice.

The "skeleton ticket" of ADR-0014, ADR-0016, ADR-0019, ADR-0020 and `AGENTS.md` means spec 1's
tooling tickets.

## Specs in build order

1. **Walking skeleton.** The tooling tickets come first and block every class-C ticket except the
   API-slice ticket, which the runner and bench tickets need and which only the build ticket blocks
   (F-19). Then `.` and `.a` over one document through every layer, under the full gates; spec 1's
   last ticket takes the gate-step and PIT-per-seed measurements (F-19).
2. **Core language**, including computed numbers and their printing, the prelude loader and the
   natives its cases need, the ADR-0007 analyses its constructs need under the compile-mode oracle
   (path shapes arrive in spec 3, holds and root use in spec 5, touched fields stay out), recursion (ADR-0011) and the mechanics of all
   five budgets. It is the largest spec and may split into 2a and 2b.
3. **Paths and updates** (owner: before the builtins). Path mode, the path builtins and the
   assignments, with the natives the in-place red needs (`length`, `type`, `has`, `keys_unsorted`,
   `tostring`, `getpath`, `setpath`, `delpaths`). Its last two tickets are in-place updates and then
   nested-reduce inheritance (ADR-0022). Founding risks 2 and 3 land right after the core, G4 and G5
   are measured a spec earlier, and every builtin ticket is checked by `keepsArgsIsSound` from its
   first day. jq 1.8.2's `builtin.jq` defines `walk`, `map_values`, `sub`, `gsub`, `del`, `paths`,
   `pick`, `tostream` and `INDEX` through `|=`, `=`, `+=` or `path()`, so they would stay red until
   this spec anyway.
4. **Builtins** by family, with regex limits and builtin coverage.
5. **JSON in full** (ADR-0015 complete) and host streaming; the element hand-out and whether each
   element gets its own tape are decided at this spec's grilling (map #18).
6. **Speed:** checks 4 and 5 in full, the per-cell tolerance, the step default and refuel interval,
   the whole-container copy, the fast paths and the diagnostic records.
7. **Embedding and command line**, ending with `HANDOVER.md`.

The order is the owner's (question 1); the moves the ADRs force on the draft table, and a spec
splitting in two at its own grilling without reopening `docs/GATE.md`, are the owner's (question
2).

## Gate rules decided here

- **Check 1** (owner): all 879 cases, no group left out. A case is excused only by a finding and a
  `DEVIATIONS.md` row that names it; a line already in the pass list never drops. jq-ref passes all 879 (550 + 47 + 10 + 20 + 2 + 231 + 19), and
  `jq.test` and `man.test` pass whole under `TZ=Asia/Tokyo` and `America/New_York`.
- **Builtin coverage** (check 2, owner): each of the 226 public builtins is named, by name and
  arity, in the filter of at least one passing jq-test or differential case, checked by
  `scripts/builtin-coverage.sh`, which reports from spec 4 and is empty at the gate. 69 of the 196
  distinct names appear in none of jq's seven test files (mostly libm, among others `halt`,
  `halt_error`, `input_filename`, `input_line_number`, `localtime`, `stderr`, `get_search_list`,
  `todate`, `keys_unsorted`, `get_jq_origin`, `have_literal_numbers`, `finites`,
  `todateiso8601`); about 77 by name and arity (a rough scan) need a case.
- **A generated seed that hits a listed deviation** (owner) stays as a deviation case: the ticket that
  finds it adds the row's slug and jqj's expected output, and review confirms that the difference is
  only that deviation. Generators avoid known deviation shapes by construction. This closes
  ADR-0020's open point.
- **Generator minimums** (check 2, owner): 1000 cases in `differential.txt`, counted after
  ADR-0020's drops, each for `values-v1`, `core-v1` and `ownership-v1`; 200 for each of the six
  builtin families. The host-facing builtins arrive in specs 5 and 7 with hand-written cases. Raised freely; lowered only by a finding. The tooling tickets measure the
  recording and verify cost per 1000 seeds; spec 1's last ticket measures PIT per 1000 passing seeds,
  or on the seeds that pass if fewer (F-19).
- **Benchmarks before spec 6** (owner) fail nothing. Every spec gate records each existing cell's
  factor against same-session jq and runs the 64 MiB forks that exist, and a cell over 1.0× is reported to the owner at that
  spec's end. `bench.tsv` holds the numbers; its `tolerance%` is empty until spec 6's finding.
- **Tests the ADRs already require are named in the gate** (owner): ADR-0022's ownership tests
  (check 2) and `inPlaceKeepsBytesBuiltLinear` (check 4), `stackBackstopIsTermination` (check 4),
  cancellation, Workspace re-entry and the exported-signature test (check 7), all 318 JSON files
  and the `--seq` resynchronisation (check 3), the module-path cases (check 6), `NOTICE` and jq's
  MIT text in the jar (check 8), and a passing case for every `DEVIATIONS.md` row (check 2).
- **The fallbacks fire at any spec gate** (owner; F-17): ADR-0014's 60 s oracle target and ADR-0014
  and ADR-0020's 3-minute PIT target are measured by spec 1's last ticket on the cases that pass by then (F-19).
  At each spec gate the verifier reports the slowest ticket-gate step 1 and step 2 of the spec's
  tickets from `.logs/`; one above its target applies the fallback ADR-0014 and ADR-0020 already
  name (the compile-mode oracle moves to the spec gate; for PIT, seeds are grouped first), by a
  finding, with no new owner round. Both fired at spec 1 (F-27): from then on the verifier reports
  step 1 only.
- **Command line** (check 6, architect): jq-ref 1.8.2's `--help` lists 30 option lines; all are in
  except `--stream-errors`, which ADR-0020's shtest drop list now names. `-b`, `--run-tests`,
  `--debug-dump-disasm`, `--debug-trace` and `--debug-trace=all` are accepted by jq-ref but not in
  `--help`, and stay out of the gate. `-h`, `-V` and `--build-configuration` print jqj's own
  identity, as `$JQ_BUILD_CONFIGURATION` does: a `DEVIATIONS.md` section now, its text left to spec
  7; shtest's cases for them become deviation cases. jq's rc-2 usage lines stay byte-identical.
  `--unbuffered` has a differential case for its output and a test for the flush after each
  result.

## Amendments

- **ADR-0019** (architect): G1, G6 and G7 enter `bench.tsv` in spec 1; G2 and G4 at 10⁴ in spec 2
  (G4 is expected over 1.0× there: jq-ref takes 5.7 ms, and without in-place updates jqj copies);
  G4 at 10⁵ and 10⁶ and G5 with spec 3's in-place ticket, since before that G4 would make about
  5×10⁹ slot copies at 10⁵ and end with BYTES_BUILT (ADR-0022); G3 in spec 4. A scenario with no
  1 KB cell (G3, G4, G6) gets its `bench.<name>.1k` case on a 1 KB input of the same generator. A
  `bench.tsv` line with an empty `tolerance%` is overwritten by `regen --all` in either direction
  and skipped by `check`.
- **ADR-0020** (architect, except the owner's seed rule and minimums): the deviation-seed rule and the minimums above; `compile.txt` gets a `mode` column
  (`conservative`, `copy-only`), one line per mode, so the copy-only run's arrival drops no line
  (F-18); `values-v1` is recorded whole in spec 1 and what passes is ratcheted, so "widened in spec
  5" means more of its cases pass, not a changed generator (F-18); the shtest drop list adds
  `--stream-errors`.
- **ADR-0014:** the fallback rule above.
- **WORKFLOW §2** (architect): Phase 0 ends with the first spec ready for `/grill-with-docs`, as issue #17 says.
- **Glossary:** "Automatic streaming" becomes **Host streaming** (F-4: the host switches it on);
  **Walking skeleton** is added.

## Left to the specs' own grilling

- Spec 1: the exact API slice and its stubs; how the skeleton splits into tickets; the required
  case ids; whether tag-6 decoding and canonical literals are in; whether any analysis runs (if
  not, `compile.txt` starts in spec 2); the stub text for malformed input until spec 5; which
  `Limits` the jqtest and compile-mode runners use; the `owner` field from day one. The 8-byte read measurement belongs to spec 6 (ADR-0015).
- Spec 2: the bytes counted per container slot (an ADR-0017 amendment: 8 B per array element or
  object value is the designer's proposal; whether an object entry is one slot or two; G4 at 10⁴
  stays under the 1 GiB default only if a slot counts below about 21 B); the
  conservative compile first, then each analysis under the compile-mode oracle; lazy prelude
  loading; `explain()` before `licenceFacts`.
- Spec 6: the per-cell tolerance, the step default and refuel interval, any missed cell. Spec 7:
  what `-h` and `-V` print.

## Considered options

- **Builtins before paths and updates** (the draft table): a broad pass list sooner, but the natives
  declare `keepsArgs` a whole spec before its test exists, and many prelude builtins stay red.
- **Leaving a jq-test group out of check 1:** spec 7 shrinks, but "the jq 1.8 language runs" stays
  partly unproven.
- **No builtin-coverage rule:** a wrong `gamma` or `localtime` passes the gate unless a generator
  happens to call it.
- **Dropping deviation seeds:** free, but coverage disappears silently. **Leaving them failing:**
  check 2 cannot be met.
- **Each spec sets its own seed count:** the gate would not be final.
- **A provisional bench tolerance before spec 6:** catches regressions, but can fail a spec gate on
  VM noise. **No factor before spec 6:** the milestone's biggest risk shows only one spec before the
  end.
- **The whole-container copy in spec 5:** the writer stays in one spec, but the largest class-C spec
  grows.
- **The fallbacks measured only by the skeleton:** they would rest on a suite of a few cases.
- **One `compile.txt` line that needs every run to agree:** the copy-only run's arrival would drop
  lines that terminate under it.

## Amendment (F-33, spec 2)

G3 enters `bench.tsv` in spec 2a, with `sort` and its kin, not in spec 4.
