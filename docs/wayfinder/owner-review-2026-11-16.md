# Owner review, Monday 2026-11-16

Under the owner's delegation of 2026-11-14, the grilling-adviser answered and approved for the owner
in the grillings of specs 2b, 3, 4 and 5. This page lists what comes back to the owner. Each item
names its record; the reasons are there. A "no" on an item reopens that record, not the spec.

## Owner-only (not decided under the delegation)

1. **Licence, issue #105.** May code under the SunPro (fdlibm) notice, or CORE-MATH's once its licence
   is read, be translated for 11 math builtins (`erfc`, `gamma`, `lgamma`, `lgamma_r`, `tgamma`,
   `j0`, `j1`, `jn`, `y0`, `y1`, `yn`)? Until then the fallback is published formulas (DLMF), in
   one ticket (#130). A yes needs a licence-exception ADR.
2. **GATE check 8** names only jq's MIT text in the jar; Arm's MIT text must ship too once `erf`
   lands (ADR-0051). Changing a check is the owner's.
3. **Repository renames** `subapavel/flottila` and `flottila-mapping` on GitHub, and whether history
   that still holds the old name is rewritten. Nothing is rewritten without the owner's word.
4. **GATE check 6** ("jq's rc-2 usage lines stay byte-identical"): does it cover jq's short usage
   (`usage(2,1)`)? Spec 7's tickets assume no: only the `Unknown option` and `Use jq --help…`
   lines stay identical, and the short usage is jqj's own under the `-h` row. A yes takes the
   `deviation` lines from S7.12's deviation case (`docs/wayfinder/spec-7-grilling.md`).

## Spec 2a gate (#56, #190; `docs/findings/F-84.md` on `spec/56-core-language-2a`)

Proposed by the grilling-adviser for the owner under the delegation of 2026-11-14:
- The gate's PIT step was stopped by hand after 4 h 04 min. Its target is 45 min (F-28).
- Two jq-core test classes leave PIT's test set: `WideConstructStepsTest` and `OperatorChainTest`.
  - The ruling: this is not weakening a test. Both still run in step 1 of both gates, and leaving a
    test out of PIT's set can only lower the score.
- ADR-0014's fallback text changes: the seed fallback does not reach a module's own tests. From now
  on, slow test classes come out first, then `threads` 2.
- Not decided under the delegation: `floors.tsv`, the 45-min target and the PIT scope.
- Round 2, answered by the owner on 2026-11-14 (no review needed):
  - Yes: oracle's PIT run mutates only the mutants each module's own run left alive, through a mutation
    filter plugin (#192).
  - Yes: `excludedClasses` for oracle's and jq-cli's own classes is not a PIT-scope change (#191).

## Spec 2b (#106; `docs/wayfinder/spec-2b-grilling.md`)

- GATE check 2's compile-mode list gains the `trampoline` run (F-58).
- No IR fold pass, reversing 2a round 1's "folding in 2b" (F-57).
- The embedded recursion-depth default stays 4 194 304 until the calibration's heap numbers exist
  (ADR-0046).
- The pending jump carries closure arguments (ADR-0047).
- `stackBytes` 0 is a public setting: everything runs on the trampoline (ADR-0046).

- Run (2026-11-14), proposed by the grilling-adviser for the owner under the delegation of
  2026-11-14:
  - F-87: a termination on the command line prints `jq: error: <message>` and ends 5, ticket #197.
  - ADR-0045 (#107): a label that a lifted function captures is passed as its token, not as a Break
    closure. This is ADR-0005's "captured like a variable".

## Spec 3 (3a #152, 3b #164; `docs/wayfinder/spec-3-grilling.md`)

- ADR-0010's Bind rule ("bound values run in value mode") gives way to `Tracking` (F-59, ADR-0048).
- `debug` and `stderr` arrive in spec 3a, not in specs 5 and 7; GATE check 2 says so (F-61).
- ADR-0023's spec 3 row and GATE row 3: the 3a/3b split, G5 and the own owner in 3a.
- Spec 4 may start once 3a is on main, beside 3b (ADR-0023's order).
- Ticket approval (2026-11-14), proposed by the grilling-adviser for the owner under the
  delegation of 2026-11-14:
  - A `?//` pattern on the tracked input can give another path than jq, not an error
    (DEVIATIONS, F-59).
  - GATE check 2's generator list does not name `paths-v1`; its 1000 rests on ADR-0020's
    amendment, and adding it to the check is the owner's.
  - `DebugSink` and `Host.Builder` are public API built at class B.

## Spec 4 (#120; `docs/wayfinder/spec-4-grilling.md`)

- The last digits of 36 math builtins are jqj's own, the same on every platform, each within a
  measured ulp bound (ADR-0051, F-65). jq-ref's own digits change with the CPU.
- Arm's `erf` is ported under MIT (its dual licence's listed alternative); Arm enters NOTICE.
- ADR-0017: date names from fixed English tables, `strptime` and `strftime` read the host zone for
  `%s`, the regex limit per search call (ADR-0052, ADR-0053, F-66, F-67).
- GATE rows 2 and 4 (G3 in 2a, F-33) and check 2's host-facing list (ADR-0023), changed with no
  finding of their own.
- The designer's choices the adviser approved: LRU eviction and a run-scoped identity in the pattern
  cache, `fromjson`'s charge rule, the 25/18/6/12 math lists, the `jn`/`yn` order conversion, F-70's
  row in the strings ticket, far instants at the zone's standard offset after jq-ref's last daylight
  instant.

## Spec 5 (5a #136, 5b after round 2; `docs/wayfinder/spec-5-grilling.md`)

- ADR-0016 (F-71): `Workspace.read(JsonInputs)`, `-n` leaves `InputOptions`, the `OutputOptions`
  withers.
- ADR-0018, ADR-0007 and the glossary (F-77): a pipe's right side is a hold; the conservative
  compile detaches every `input` document at its read.
- ADR-0002 and ADR-0012 (F-73): NONFINITE is never written.
- ADR-0015: `-R` copies jq's per-block UTF-8 quirk, which loses a byte (F-75); `--seq`'s silent cut
  only after whitespace (F-74).
- ADR-0023: the 5a/5b split; jq.test 2315, 2319, 2324, 2498 and 2563 are spec 5's (F-76).
- ADR-0054: host streaming saves memory only on an array target, and uses one reused element tape
  where founding §6.7 says "each with its own small tape".
- Ticket approval (2026-11-14), proposed by the grilling-adviser for the owner under the
  delegation of 2026-11-14:
  - F-78: `malformed-v1` gets GATE check 2's minimum of 200 cases per form after drops (ADR-0023).
    This changes the text of GATE check 2; a change to a GATE check is the owner's to confirm.
  - 5b is held: its spec issue and tickets are not published before its round 2.
  - A missing input file ends the run with rc 2, replacing a parse error's 5 and `-e`'s code; a
    missing first file stops the main loop after one value (ADR-0015, correcting round 1's A5).
- Round 2 (5b, 2026-11-14), proposed by the grilling-adviser for the owner under the delegation of
  2026-11-14 (`docs/wayfinder/spec-5-grilling.md`, "Round 2"):
  - F-79 changes two published 3a tickets and spec 3's approval item 2.
  - ADR-0007's ParamCall sentence, the hold wording and path shape's new TRACKED condition.
  - GLOSSARY "Root use" loses "not at all".
  - 5b has four tickets (ADR-0023).
  - S3b.3 loses a bullet to 5b.
- Ticket approval, spec 5b (2026-11-14, grilling-adviser for the owner under the delegation of
  2026-11-14): R1, R2, R4 and R6 change owner-accepted ADR-0022 and a published 5a ticket.

## Spec 7 (#174; `docs/wayfinder/spec-7-grilling.md`)

Round 1 (2026-11-14), proposed by the grilling-adviser for the owner under the delegation of
2026-11-14:
- ADR-0017's module loader SPI and its command-line host: `module` and `data` take the importer
  and return `ModuleSource`, `home()`, `programOrigin()` and `jqOrigin()`, jq-cli's
  `FileModuleLoader` (F-81).
- `Invocation.origin`, the jq origin, beside the six-argument constructor (ADR-0013, ADR-0020).
- The `<case>` token in recordings and comparisons, and the jqtest host's loader (ADR-0020, F-82).
- The Bindings sentence: `$ARGS.named` in first-put order, `$ARGS` and `$ENV` never a declared
  variable (ADR-0016).
- Spec 7 is one spec and may start beside spec 6 once 5b is on main (ADR-0023).
- The `-V`, `-h` and build-string texts: `jqj-<version>`, fresh help text, `jqj <version> release 25
  interpreter` (DEVIATIONS).
- Four DEVIATIONS rows: `modulemeta` on a module that does not parse, `$ENV`'s sorted key order,
  the `-V`/`-h`/build-string texts, the jq origin.
- The adviser's 100-line format, where its role and `AGENTS.md` say 40.
- NOTICE (for information): a ticket that translates `main.c`'s option loop or usage text, or
  `linker.c`'s search code, adds a NOTICE line under CLAUDE.md's existing rule for jq's MIT files.
- Ticket approval (2026-11-14), proposed by the grilling-adviser for the owner under the
  delegation of 2026-11-14:
  - T4 has 9 running cases and jq.test 1981; T5 has jq.test 1969–1977.
  - T15 is also blocked by T5, and T10 by T6.
  - T12 is class B.
  - The serialising edges S7.12 → S7.4, S7.10 and S7.13, and S7.6 → S7.7.
  - R1–R8: ADR-0017 (import order, `\` in a path, `optional` and `raw`, `$HOME/jq/main.jq`,
    no `HOME`), ADR-0013 and ADR-0020 (the launcher passes `$(dirname "$0")` unchanged),
    ADR-0020 (`/` in a case's `file` name, the `<version>` token), DEVIATIONS (the `lib/body.jq`
    `modulemeta` row, one jq-origin row, `module not found` with `HOME` unset), GLOSSARY ("Case
    directory", "Home module").
  - GATE check 6's reading is owner-only (item 4 above).
  - Architect's ruling on NOTICE: each ticket that translates `main.c` or `linker.c` code or text
    adds jq's NOTICE line itself (CLAUDE.md allows it). The owner is told, not asked.

