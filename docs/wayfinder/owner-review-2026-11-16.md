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
  - #114 (over-cap calls): F-54 and the 2b record are amended. `f600` is the nesting trigger's
    witness, `g600` the depth trigger's, and depth counts closures (K1). ADR-0040's "past the cap" is
    read as "would pass", a choice made before lowering (K2). The greedy top-down lift is accepted,
    with lifting as late as possible kept as a spec-6 option (Q1). The expansion measure takes the
    larger of the inlined and lifted forms per call site (D1). Records: ADR-0040 "Amendment
    (ticket #114)", F-54.
  - #116 (value form): in OPTIMISED the value form comes before the tail emitter, so there the
    Holder path serves only path variants. This amends ADR-0047 bullet 3's "replaces nothing" (C1).
    A value-form call's activation and units end when its `eval` returns, so in OPTIMISED the
    recursion-depth verdict can depend on `stackBytes` (C2). Compile mode becomes a calibration
    dimension (R1). Records: ADR-0047 and ADR-0046 "Amendment (ticket #116)".
    - The never-weaken rule: `TailDepthTest`'s Label control expected an overflow in both modes.
      In OPTIMISED it now expects the run to fit, and a new control (a fence that breaks to an
      outer label) expects the overflow in both modes. The adviser reads this as a re-pin of
      behaviour the ADR changes, not a weakening; a "no" restores the old pin and reopens C1.
  - #117 (specialisation), with records to follow:
    - S1: the lifted functions of PLAIN calls get copies too (GLOSSARY, F-54).
    - S2: copies are ordered by the Source of the placing call, then by discovery (ADR-0045).
    - S3: in 2b, arguments that carry literals do not share a copy; a hook for Const equality is
      deferred to spec 6.
    - S4: free refs become trailing parameters of the copy.
    - S5: if the census shows refusals, the cap becomes the next power of two, up to `INLINE_CAP`.
  - #118 (tail calls), with records to follow:
    - T1: ADR-0018:29 is reworded; CONSERVATIVE stores no tail kind.
    - T2: ADR-0047 is amended; written closures are bound without a frame at jump sites.
    - The embedded recursion-depth default stays open.
  - #117 census (F-94), from the adviser's rulings R1–R4: three over-cap cases are refused by
    design and named in ADR-0045 #117; the census asserts that exact set. ADR-0020 #117: the census
    reaches the capped compile by reflection. The adviser reads the change as fixing a wrong red,
    not a weakening; a "no" makes the census red until a cap rule changes.
  - F-96 closes F-94's open point: a parameter chain can spend the cap before a later prelude
    loop; the order stays and the filter is the fourth refused by design. The order test changes
    from "recurse must be copied" to "the result is jq's at default limits". The adviser reads this
    as re-specifying its own earlier red before any pass exists; a "no" means reversing S2's order.
  - F-95: the provisional b = 96 does not cover a Collect-heavy descent; #113 owns b, and #114
    merges after #113.
  - #113 (calibration), from the adviser's rulings K1–K10 and corrections D1–D4 (ADR-0046 #113):
    - Red rulings (ADR-0046 #113, "Rulings on the red"); each "yes" accepts the adviser:
      - O1: the reserve is the largest linked intercept plus the largest cold-linkage term, rounded
        up to 4 KiB, plus 32 KiB (T alone gives about 152 KiB against #111's 128 KiB).
      - O2: `b` is published as measured, even near 200; `U_f` at 512 KiB then falls to about
        1 850 − D_f, so deep recursion switches earlier with the same results.
      - O3: heap per live activation is 88–887 B, so the 4 194 304 depth default costs 0.37–3.7 GB
        before RECURSION_DEPTH fires. The default stays the owner's decision.
      - O4: the matrix budget is its measured time, with a 45-minute target, re-run at #114 and
        #116.
      - O5: trampoline classes keep loading lazily, at the price of the O1 term (about 5 % of `U_f`
        at 512 KiB), instead of prelinking on every CLI start.
    - ADR-0020's `calibrationFitsHalfStack` is renamed `everyCellSwitchesBeforeTheBackstop`.
      There is no margin beyond rounding and the 32 KiB; each cell's headroom is in the tsv.
    - The reserve is the measured intercept plus 32 KiB, and never below the guard pages plus 32 KiB.
    - Gate step 1 already takes twice F-28's target. Its profile finding is F-92, and the
      calibration column stays in the ticket gate.
    - The child-JVM test classes leave PIT's test set under a finding. The adviser ruled this is not
      a PIT-scope change; a "no" reopens it.
    - The calibration has 12 shapes, F-30's `..` among them.
    - A second nightly job runs, as a check only (ADR-0014's nightly sentence).
    - The 4 194 304 depth default stays open. If the heap numbers arrive after Monday morning, it is
      the owner's to decide.
  - #119 (recursion diagnostics), from the adviser's rulings C1–C6 and O1–O4:
    - ADR-0020 amendment (#119): one bench class reaches the compile-mode switch by reflection,
      class path only, read back in `@Setup`.
    - ADR-0019 amendment (#119): `walk` and `[paths]` rows are owed by spec 3; jq-ref times stand
      beside the 2b diagnostic rows with no ratio.
    - The ticket stays class B. Its 13 `bench/<name>.1k` ids freeze with the filters at the measured
      caps.
    - For information: the conservative B/op rows at 10⁶ are heap-per-level evidence for the open
      4 194 304 depth default.

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


- **Sign-off identity on one ratchet commit (spec 2b run, told not asked).** The verifier's
  regen commit 3746c2d ("chore(ratchet): regenerate after #107") carries `Signed-off-by: Claude`
  instead of the owner's identity that earlier regens (e.g. a0ac318) carry. check-trailers accepts
  it. It is pushed to the integration branch; rewriting it is a history rewrite, so it stays unless
  the owner asks. Later regens are told to sign off with the GIT_AUTHOR identity.

- **#117 re-pins and #198 (spec 2b run, told not asked).** The grilling-adviser ruled V1–V5 and
  W1–W4 for the owner. Five #116 tests were re-pinned because specialisation removes the closures
  they were built on. The over-cap run pin moved from a pipe ladder to a comma ladder, because a
  filter with `U_f ≤ 0` runs wholly on the driver, which holds PLAIN activations (F-100). The
  withdrawn expectation is issue #198. It is not ready for agents and waits for grilling with you
  or the adviser after #113 and #119.

- **#113 matrix rulings K1–K8 (spec 2b run; ADR-0046 #113 "Rulings on the matrix").** These amend
  O2 and the measuring rule:
  - Warm cells are proven (60 runs at the declared stack), not measured, because their edges are
    junk (K1).
  - `b` = 192 (C1 W), and the reserve is 280 KiB (K2, K3).
  - `U_f` at 512 KiB is about 1237 − D_f, against O2's estimate of 1 850 (K4, F-102).
  - The full matrix took 92.5 min, against the 45-minute target (O4).
  - Open for you: whether ND OPTIMISED's large intercept is an artefact (reserve 168 KiB), bytes
    per unit by kind, or a larger 512 KiB default.
  - WORKFLOW §11's branch names disagree with practice (F-103), a WORKFLOW change for you.
