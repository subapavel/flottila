# Spec 2b "Core language: recursion, analyses, tail calls" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` (Fable) answers for the owner. Drafts:
designer (round 1). jq behaviour: jq-ref 1.8.2 probes, observed by the architect and the adviser.

To show the owner on Monday (adviser's list): GATE check 2's mode list (M3), the dropped IR fold
(Q10, reversing 2a round 1's "folding" in 2b), the embedded recursion-depth default (open below), the
pending jump carrying closures (records round, 3) and a public `stackBytes` 0 (records round, 4).

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **Functions in the IR: the Resolver keeps 2a's inlining; specialisation is a query-core pass.**
   Recursive groups and over-cap calls are lifted into `Program(main, List<Function>)`, with
   `Call(src, fn, closures, values)` (atomic values) and `ParamCall(src, param)`. Pass order:
   resolve + inline → verify → specialise (skipped under CONSERVATIVE, ADR-0018) → verify/freeze →
   analyses. The IrVerifier runs after every pass (ADR-0005).
2. **Over the inline cap or past `MAX_IR_DEPTH`: a PLAIN `Call` to a function lowered once with real
   closures**, weighing stack units like RECURSIVE. Conditions: a PLAIN call ticks one step
   (`def f1: .; def f2: f1|f1; …` would otherwise run 2^59 untimed calls; jq-ref runs the chain to
   `f22`, 2^21 calls, in 0.13 s, `null`); functions are lowered from a worklist, never inside their
   caller's lowering; the inline-or-call choice never expands a call twice, so compile work stays
   O(source + cap). DEVIATIONS "IR depth": the three call rows go; the three chain rows (`.[.a:]`,
   `.[.a]`, alternating operators) stay Stubs.
3. **Specialisation has its own cap**, `SPECIALISE_CAP` in query-core, 16 384 unless the designer's
   measurement (largest specialised node count over the census and the prelude) says otherwise. A
   (group, argument tuple) is specialised whole or not at all, in source order of its first outside
   call. Reason: with one shared budget an over-cap filter would leave `until`, `while`,
   `recurse(.+1)` unspecialised and hit the depth budget (jq-ref:
   `last(limit(3000000; 0|recurse(.+1)))` → `2999999`).
4. **The form-B trampoline runs every kind** that can sit in a function or closure body (20 after
   2b), each with a resumable heap form. jq-ref on a 9 999-deep array, about 10 ms each:
   `def d: [.[]?|d]; d|length` → `1`; `def r: reduce .[] as $x (0; .+($x|r)+1); r` → `9998`;
   `def l: first(.[]?|l)//7; l` → `7`; `walk(.)|length` → `1`. The spine-only option would end these
   near 200 levels, against ADR-0011's "depth bounded by memory".
5. **Tail calls in the push interpreter: a pending-jump field** in the run state (fn, input, value
   arguments, closure arguments: records round, 3); a tail-position op returns at once and the function's entry loop re-enters. Bound by
   M1.
6. **U per filter.** Release constants: bytes per unit `b` and a guard reserve, taken at the worst of
   `-Xint` (the deterministic cold column), C1-only and warm, in fresh agent-free JVMs, on 512 KiB,
   1 MiB and a virtual thread. `D_f` (deepest call-free nesting) is stored per compile;
   `U_f = (stackBytes − reserve) / b − D_f` is derived at run start, since `stackBytes` can be set
   per run. In-suite tests run on a thread of at least 4× the declared `stackBytes`; the 512 KiB edges
   run only in fresh child JVMs (F-44, F-49).
7. **The recursion-depth budget counts** one per live PLAIN or RECURSIVE `Call` and per real-closure
   `ParamCall`, stack and trampoline together; a tail jump replaces its activation; an inlined or
   specialised call counts nothing. A mode difference ending in a termination is no disagreement
   (ADR-0020). Required: `0|until(.>=10000000; .+1)` → `10000000` (jq-ref, 3.4 s).
8. **Fact keys: freeze numbers each function's nodes in pre-order into an identity map**
   (deterministic order); facts are arrays by id. Sound because ADR-0005 forbids a node used twice;
   records compare by value, so the map is by identity.
9. **Value form in OPTIMISED** where cardinality ≤ 1 (M1) and may-break-out is false. CONSERVATIVE
   never calls `eval` and keeps the Holder path, so the compile-mode oracle stays an independent
   second opinion. The tail emitter stays in both modes as the kind-only floor (ADR-0043's "may
   subsume" closed).
10. **No IR fold pass.** jq folds only while parsing (`--debug-dump-disasm`: `1+2` is `LOADK 3`,
    `def f: 1; f+2` keeps `CALL_JQ f:0`), which 2a built. Spec 6 may add one on a measured win.
11. **`explain()` in 2b**, right after the Call ticket; each analysis ticket adds its column and its
    red at the API seam. Tests match fact fields, never whole text.
12. **#97 and #104 are 2b's first class-C tickets, #101 its class-A ticket**, in parallel at the
    start, blocking nothing.

Confirmed: `walk/1` (`map_values`, `.[] |= f`) and `tostream/0` (`path()`, `getpath`) stay red
until spec 3; 2b lifts only their recursion Stub. Deep cases use user recursion (question 4).

### Decided beyond the draft

- **M1. Cardinality "one" and "at most one" also promise that nothing runs after the result**;
  otherwise "many". jq-ref: `[limit(1; (1, error("x")) | .+1)]` → `[2]`;
  `first((1, (repeat(1)|empty)) as $x | $x)` → `1` and terminates;
  `def f: if .>3 then . else ((.+1), error("x")) | f end; 0|f` → `4`, then error `x`, rc 5. These
  three are required differential cases of the cardinality, value-form and tail-call tickets.
- **M2. Frames.** Each Call and each real-closure invocation gets its own slot cells; 2a's one array
  per run ends. Required, recorded with `oracle-record.sh`:
  `def r(f): if .>40 then . else f|r(f|.) end; [0|r(. as $i|(1,2) as $x|(10,20) as $y|$i+$x+$y)]`
  (76 values on jq-ref, starting `[44,54,45,55,43,…`).
- **M3. A third oracle run, mode `trampoline`:** the conservative compile with `stackBytes` 0, so
  `U_f ≤ 0` and main and every body run on the driver. Ratcheted in `compile.txt`; runs wherever the
  conservative run does. Amends ADR-0020 and GATE check 2's mode list.
- **M4. Form B routes downstream events back into the driver.** Required cases beyond U (shapes
  observed on jq-ref at depth 3):
  `def f: if .==0 then ([[1]]|.[] as [$a] ?// $a|$a) else (.-1|f)|. end; [3|f|if .==1 then error("x") else . end]`
  → `[[1]]`; `[label $out | def f: if .==0 then (1,2) else (.-1|f)|. end; 3|f|., break $out]` → `[1]`.
- **M5. `stackBackstopIsTermination` belongs to the Call ticket**, the first where a filter can
  overflow the stack.

### Ticket shape (approved by the adviser for the owner): 16

13 class C:
1. #97 (F-50)
2. #104 (F-53)
3. Call, `Program`, frames, real closures, the PLAIN tick, the depth budget, the backstop
4. Heap forms of all 20 kinds, with the `trampoline` oracle run as its red
5. Units, weights, the switch at `U_f`, the form-B starters (after 4)
6. U calibration: the full matrix at the spec gate and nightly, the `-Xint` 512 KiB column in the
   ticket gate
7. PLAIN calls over the cap and past the depth (after 5)
8. The recursive prelude and `..`
9. Cardinality with M1
10. May-break-out
11. Value form
12. Tail calls (after 9, 11, 13)
13. Specialisation

2 class B: `explain()` (after 3, before 9); diagnostics. 1 class A: #101.

### Records to write

- New ADR: calls and specialisation (questions 1, 3, 8, M2); amends ADR-0005, 0006, 0007, 0040.
- Finding: over-cap PLAIN calls (question 2), against ADR-0006's "every call … becomes its body";
  amends ADR-0040, F-44's ruling, ADR-0008 (tick site), ADR-0011 (units), DEVIATIONS "IR depth".
- New ADR: heap forms and U per filter (questions 4, 6, 7, M4), with a finding against ADR-0011's
  "U is a constant per release"; amends ADR-0017's wording of what the budget counts.
- Finding: cardinality ends at its result (M1); amends ADR-0007, ADR-0008, ADR-0006's tail
  condition.
- New ADR: tail jumps and the value form (questions 5, 9); amends ADR-0011, closes ADR-0043's "may
  subsume".
- Finding: no IR fold (question 10); amends ADR-0006's pass order.
- Finding: the `trampoline` oracle mode (M3); amends ADR-0020 and GATE check 2.

### Records round (grilling-adviser for the owner, 2026-11-14)

Points the designer raised while drafting ADR-0045 to 0047 and F-54 to F-58, ruled by the adviser:

1. The 512th nested expansion is a PLAIN `Call` too, like the inline cap and `MAX_IR_DEPTH`;
   nesting and depth restart at 0 in a worklist function. jq-ref: `def f1: .+1; def f2: f1; …;
   f600` on null → `1`; `def g1(f): f; def gi(f): g(i-1)(f|.+1); …; 0|g600(.)` → `599`.
2. A call counts nothing only when no node is left at run time (inlined, or a `ParamCall`
   specialisation replaced); every surviving `Call` counts while live.
3. The pending jump carries closures: (fn, input, value arguments, closure arguments); ADR-0006's
   condition 4 stays. Corrects Q5. jq-ref, both past 4 194 304:
   `def r(f): if .>=5000000 then f else (.+1)|r(.+1) end; 0|r(0)` → `5000001`;
   `def r(f;g): if .>=5000000 then [f,g] else (.+1)|r(g;f) end; 0|r(1;2)` → `[1,2]`.
4. `stackBytes` 0 is a public `Limits` value: main and every body run on the driver.
5. `b` and the reserve are maxima over the calibrated cells; a re-run adds cells, so `U_f` only
   falls.
6. DEVIATIONS "IR depth": the 30 001-level row leaves in the over-cap ticket's merge on
   VERIFIED_PASS (jq-ref: 20 022 bytes, 10 001 `[`, `<skipped: too deep>`, 10 001 `]`, LF).
7. Terms: "activation" qualified (call, owning, label); "frame" is a call activation's slot cells,
   the JVM's is "Java frame" (GLOSSARY).
8. ADR-0030 and ADR-0034 status lines name ADR-0045 to 0047.

### Open

- **Embedded recursion-depth default** (ADR-0017, 4 194 304): after the calibration ticket, which
  records the heap bytes per live activation. Spike A's trampoline showed 290–600 MB RSS at 10^6
  levels, so the default may exhaust a heap before it fires, and `OutOfMemoryError` is no
  termination. Until decided, the depth witness runs with a small `recursionDepth`. Under the
  delegation the adviser decides it when the numbers exist.
- Missing facts: `b` and the reserve (calibration ticket); `SPECIALISE_CAP` (designer measures);
  whether the reserve covers natives and the sink at the deepest emit on a 10 000-deep value
  (`tojson`, sort, Detach unchecked; the Printer has its own stack); the added time of the
  `trampoline` run in gate step 1.
- Round 2: not needed before `/to-spec`.
