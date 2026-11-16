# Every body kind has a heap form on the form-B driver; U is derived per filter and run; the depth budget counts calls

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); records F-55; amends ADR-0011 (the trampoline's kinds, U, the calibration, what the depth budget counts) and ADR-0017 (what the recursion-depth budget counts; U per run); amended by spec 3 grilling round 2 (kinds 21 and 22, path variants on the driver, `stackZeroAgrees`, `D_f`); heap-form contract, driver and hand-back by ticket #109; units, the switch and the starters by ticket #111

ADR-0011 runs recursion beyond U on a resumable form-B trampoline and makes U a constant per
release. Building it in 2b raises three questions. Which kinds must the driver run? What is U for a
filter whose own nesting and declared stack differ from the calibration's? What counts as one level
of the recursion-depth budget?

## Decision

- **Every kind in a body has a heap form.** The form-B trampoline runs every kind that can sit in a
  function or closure body, 20 after 2b and 22 after spec 3a, each with a resumable heap form.
  jq-ref on a 9 999-deep
  array, about 10 ms each: `def d: [.[]?|d]; d|length` → `1`;
  `def r: reduce .[] as $x (0; .+($x|r)+1); r` → `9998`; `def l: first(.[]?|l)//7; l` → `7`;
  `walk(.)|length` → `1`. A spine-only driver keeps only the calls on the heap and every other kind
  on the stack. It would end these near 200 levels, against ADR-0011, under which a memory-bounded
  recursion-depth budget limits the depth. `walk/1` stays red until spec 3; 2b's deep cases use
  user recursion.
- **Form B routes downstream events back into the driver** (M4). Two things go back into the driver,
  at the heap form that produced the result: an error raised downstream of a result the starter
  emits, and the emit's answer (a stop or a break). So a heap `AltBind` still catches the error
  (ADR-0038), and the heap forms stop. Required cases beyond U (shapes observed on jq-ref at
  depth 3):
  `def f: if .==0 then ([[1]]|.[] as [$a] ?// $a|$a) else (.-1|f)|. end; [3|f|if .==1 then error("x") else . end]`
  → `[[1]]`; `[label $out | def f: if .==0 then (1,2) else (.-1|f)|. end; 3|f|., break $out]` →
  `[1]`.
- **U per filter** (F-55). The release constants are the bytes per unit `b` and a guard reserve.
  Each is taken at the worst of `-Xint` (the deterministic cold column), C1 only and warm, in fresh
  agent-free JVMs, on 512 KiB, 1 MiB and a virtual thread. A compile stores `D_f`, the filter's
  deepest call-free nesting, per backend. A run derives `U_f = (stackBytes − reserve) / b − D_f` at
  its start, because `stackBytes` can be set per run (ADR-0017). `b` and the reserve are each the
  largest over every calibrated cell (shape × tier × stack size × thread kind × backend); a re-run
  that adds cells can only raise them, so `U_f` only falls. `stackBytes` at or below
  `reserve + b·D_f`, 0 included, is a public setting under which main and every body run on the
  driver; the engine still needs the reserve's stack for the driver and natives, so the reserve is
  published in `bench/RESULTS.md` and the `Limits` javadoc. `stackBytes` is not a budget, so 0
  means zero bytes, never unlimited.
- **Where the stack is tested**: in-suite tests run on a thread of at least 4× the declared
  `stackBytes`, except a backstop witness, which declares more `stackBytes` than its thread has so
  that the real stack runs out first (StackBackstopTest, #107). The 512 KiB edges run only in fresh child JVMs (F-44, F-49). The calibration's full
  matrix runs at the spec gate and nightly; its `-Xint` 512 KiB column runs in the ticket gate.
- **The recursion-depth budget counts** one per live PLAIN or RECURSIVE `Call` and one per
  real-closure `ParamCall` (a call activation), on the stack and on the driver together. A tail
  jump replaces its activation (ADR-0047). A call counts nothing only when no node is left at run
  time: a call the Resolver inlined, or a `ParamCall` that specialisation replaced by the
  argument's body; the outside call into a specialised copy and the copy's own non-tail calls
  count. A mode difference that ends
  in a termination is no disagreement (ADR-0020). Required: `0|until(.>=10000000; .+1)` →
  `10000000` (jq-ref, 3.4 s).
- **Not decided here**: the embedded default of 4 194 304 (ADR-0017) waits for the calibration
  ticket's heap bytes per live activation. Spike A's trampoline showed 290–600 MB RSS at 10⁶
  levels, so the default may exhaust a heap before it fires, and `OutOfMemoryError` is no
  termination. Until then the depth witness runs with a small `recursionDepth`. Also open: the
  values of `b` and the reserve, and whether the reserve covers natives and the sink at the deepest
  emit on a 10 000-deep value (`tojson`, sort and Detach are unchecked; the Printer has its own
  stack).

## Considered options

- **Spine-only heap forms**: they end near 200 levels on the shapes above.
- **A constant U per release** (ADR-0011 as written): see F-55.
- **U from the declared stack alone, without `D_f`**: a body nested deeper than the calibration
  shapes reaches the end of the stack before U. That is the failure ADR-0011 found for a fixed K.
- **Constants from the warm column only**: F-49 showed the 512 KiB edge moving with the JIT tier;
  `-Xint` is the column that does not move.
- **512 KiB edges in the suite**: an agent's frames move them (F-44).
- **Counting every call, inlined ones too**: an inlined call holds no frame and no cells, so the
  budget would count stack it does not use.

## Amendment (spec 3 grilling round 2, questions 2 and 3; A2)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

- `PathOf` and `Update` are kinds 21 and 22. A ticket that builds a kind or a path variant builds
  its heap form, and its red is its `trampoline`-run cases. No Stub can stand in: a Stub is a
  compile error, while `stackBytes` is set per run.
- Hidden-slot state survives suspension (ADR-0045). jq-ref runs `walk(.)` 20 000 deep and `[paths]`
  10 000 deep; these are reds of TRACKED Update (conservative) and FIXED/WALK (optimised).
- FIXED and WALK keep their heap forms, and in 3b their licence, on the driver. The `trampoline` run
  is conservative and so runs TRACKED only. FIXED/WALK therefore adds `stackZeroAgrees`: each
  `gen/paths-v1` case gives the same output and error, optimised, with `stackBytes` 0 as with the
  default.
- `D_f` and the unit weights cover path variants and the FIXED/WALK run; the re-run (F-30) can only
  lower `U_f`.

## Amendment (ticket #109)

Accepted 2026-11-15, architect under `/run-spec` #106; designer draft.

- Every interpreter op has a heap form, entered by `start(Trampoline, HeapForm under, slots, in,
  HeapSink out)`, which `Op` keeps abstract, so a kind with no heap form does not compile. The heap
  form does what the push form does, in the same order, with the same ticks, charges, slot writes,
  label tokens and depth counts. It differs only where the push form runs a child or emits: there
  it sets the driver's next action instead.
- The driver runs one action at a time (start, emit, answer, raise). Each action sets exactly one
  next action, so no sink calls a sink and no form calls a form, and the driver's Java depth does
  not grow with the filter. A consumer starts above its producer, so an error raised downstream
  passes back down through the forms that produced the result, as it passes Java frames in push
  form.
- The starter's base sink hands each result back with the form that produced it. The starter emits
  the result from its own Java frame, with no driver live. It then resumes the driver at that form
  with the emit's answer or with the error the emit raised (M4). A stop or a break runs every heap
  form out as push form does. A Termination ends the run. A tail emitter has no role on the driver.
- In #109 the only starter is the run entry, at `stackBytes` 0. S2b.5's calls at `U_f` use the same
  entry, and S2b.5 re-runs the M4 cases with a small forced `U_f` across a real starter.
- Considered: continuations with a failure continuation, as Spike A had (one closure per step, and
  a third continuation threaded through all 20 kinds); call-free subtrees in push form on the driver
  (their consumers would run on the Java stack, which is form A again); sinks calling sinks (Java
  depth grows with the recursion); dropping the forms on a stop (differs from push form at Label and
  leave, and saves nothing).

## Amendment (ticket #111)

Accepted 2026-11-15, architect under `/run-spec` #106; designer draft; grilling-adviser rulings
C1–C3 for the owner under the delegation of 2026-11-14; F-88.

- A stack unit is one Java frame of the push form, lambda and proxy frames included.
- `w(site)` is the sum of three terms:
  - its descent: the frames from its body's root to the site's own op;
  - its rise: the most frames its body nests above the site's results, up to the body's own emit;
  - the callee's leaf-emit depth: the frames the callee's body nests from its root to an emit that
    no call's result leads to. These stay live under the caller's rise. For a Call it is the
    function's; for a ParamCall it is the closure argument's, which the closure carries.
- On every path a call counts as a starter nests it: 1+T frames, or 2+T for a ParamCall. `D_f` is
  the most frames main or any body (a function or a closure argument) nests with each call counted
  that way. Every term is fixed from the IR when the tree is built, without recursion.
- A push-form call adds its units, then compares: at `units ≥ U_f` it starts the trampoline, which
  adds `T`, the starter units. Heap forms add no units.
- `U_f = floorDiv(stackBytes − reserve, b) − D_f`. At `U_f ≤ 0` the run entry is the starter.
- Invariant: every live push-form frame is charged to a live call's units or lies in the innermost
  body. A run's frames therefore stay below `U_f − 1 + D_f` units, plus natives, which the reserve
  covers.
- A tail jump keeps its activation's units. A call into a group with group jumps is charged the
  group's largest leaf-emit depth. The tail-call ticket owns that red.
- Provisional constants until #113:
  - `b` is the largest over `-Xint`, C1 only and warm, measured on #111's deep filters in the child
    harness, and recorded in `bench/RESULTS.md`.
  - The reserve is 128 KiB (96 KiB of guard pages and 32 KiB for natives), a source constant. Main
    code reads no JVM flags; a child-JVM test reads them and asserts that the constant covers the
    guard pages.
- Units are `long`, as `stackBytes` and the depth count are.
- `explain()` shows `units` on each Call line (its whole `w`) and each ParamCall line (descent and
  rise), `emitDepth` on each function line and each closure argument's root line, and a closing
  line with `callFreeDepth`, `starterUnits`, `bytesPerUnit` and `reserve`. The text is not stable
  (ADR-0016).
- In-suite oracle runners run every case on one fixed thread of `max(4 × Limits.cli().stackBytes(),
  2 MiB)` (F-88).
- Considered: descent alone (misses a rise repeated per level, F-55); descent and rise without the
  leaf-emit depth (fails the adviser's witness, a recursion whose rise calls a function that emits
  from under a hundred `try`s); comparing before adding (overshoots by w − 1); a frame counter in
  every op (hot-path cost); one unit per IR node (frame counts differ by kind); reading the guard
  flags in main code (needs `java.management`, and the reserve is a release constant).

## Amendment (ticket #116)

Accepted by the architect under `/run-spec` #106. It comes from the grilling-adviser's rulings C2 and
R1, made for the owner under the delegation of 2026-11-14.

**Activations and units of a value-form call (C2)**
- A value-form call's activation and units end when its `eval` returns. On the driver, the call
  stays live for as long as its consumer runs, as #109 says.
- So in OPTIMISED a filter can count fewer live activations on the stack than beyond `U_f`, and its
  depth verdict can depend on `stackBytes`. That is allowed: a frame that is not held is not
  counted.
- Hand-over to the bytecode spec: `D_f` is per backend, and the backend oracle compares the cause of
  a termination. It can therefore disagree on RECURSION_DEPTH cases, and must leave them out or name
  them.

**The invariant (R1)**
- The invariant of the #111 amendment reads "every live push-form or value-form frame".
- An op's `eval` reaches each child and each starter through no more Java frames than its `run`
  does.
- Two properties hold it:
  - #116's StackWalker property: value form ≤ push form.
  - #111's `framesStayUnderTheLimit`, run in OPTIMISED on value-eligible recursion.

**Calibration (R1)**
- Compile mode becomes a dimension of the calibration cells (#113).
- Whichever of #113 and #116 merges second re-runs the matrix.
- #116's fresh-JVM `-Xint` 512 KiB cases run OPTIMISED on value-form recursion.

## Amendment (ticket #113)

Accepted by the architect under `/run-spec` #106, from the designer's draft and the
grilling-adviser's rulings K1–K10 and corrections D1–D4, made for the owner under the delegation of
2026-11-14.

**Cells**
- A calibration cell is one shape × tier × thread × compile mode, run in a fresh child JVM. The
  child has no agent, no `-Xmx` and no ulimit (F-91).
- There are 12 shapes:
  - Spike A's T, E, M and N, on a 10 000-element array;
  - a wide body W and a closure chain C;
  - the nested drivers;
  - F-30's `[..]|length` on DEEP;
  - #111's collect, reduce, first-or and hold.
- There are three tiers: `-Xint`, C1 only, and warm.
- There are three threads: a 512 KiB and a 1 MiB platform thread, and a virtual thread whose carrier
  is sized by the child's `-Xss512k`. javap shows the carrier is a `ForkJoinWorkerThread` with stack
  size 0, so it takes `-Xss`.
- There are two modes: CONSERVATIVE and OPTIMISED.
- A re-run only adds cells.

**What a cell measures**
- A cell measures its unit edge `U*`: the largest `U_f` at which the shape gives reference jq's
  result. At `U* + 1` the cell ends STACK. A JIT cell's `U*` is the smallest over at least 3 fresh
  children, and the spread is recorded (D3).
- Each cell proves its thread's size. A cell whose size is not proven is INVALID, not a pass. glibc
  can hand a new thread the cached stack of a larger thread that has ended (observed, D2).
- `b_cell` is the slope between the 512 KiB and 1 MiB cells: the bytes per unit. A virtual cell uses
  its platform pair's slope.
- `b` is the largest `b_cell`, rounded up to 8 B.
- The reserve is the largest `S − b_cell·(U* + D_f)`, rounded up to 4 KiB, plus 32 KiB for the
  natives. It is never below the guard pages plus 32 KiB, and `reserveCoversTheGuardPages` keeps
  that floor. There is no `T` term, because line 131's bound has none (D1).
- There is no margin beyond rounding and the 32 KiB. Each cell's headroom is in the record.

**The record**
- The committed record is a tsv that the matrix run writes, never edited by hand, and that
  `bench/RESULTS.md` cites. The verifier rewrites it at the spec gate.
- `constantsAreTheMatrixMaxima` checks that the constants equal the maxima over the rows of the
  record, from the `-Xint` and C1 rows only (K1: warm is proven, not measured). It reads the record in-process.
- A fresh run asserts in one direction only: each cell completes, and fresh values are at most the
  constants (K7).

**Where the columns run**
- The ticket gate runs the `-Xint` 512 KiB column, both modes, once per shape at the committed
  constants, as a check.
- The spec gate runs the `calibration-matrix` tag and profile.
- Nightly runs the same tag as a check only. It uploads its tsv and commits nothing (ADR-0014).

**ADR-0020's test name**
- ADR-0020's `calibrationFitsHalfStack` is renamed `everyCellSwitchesBeforeTheBackstop`. "Half" was
  never defined (K1).

**The red**
- The red is c2, `everyCellSwitchesBeforeTheBackstop` at #111's constants over the full matrix,
  logged per cell. Then comes c3, `constantsAreTheMatrixMaxima`, which first fails on the missing
  record.
- If the maxima equal #111's constants, the record is the green. No constant is perturbed to get a
  red (K9).

**The 10 000-deep native check (D4)**
- It asserts reference jq's values, with each native's first use in the child at the deepest emit:
  - `tojson|length` → `19998`;
  - `[.,.]|sort|length` → `2`;
  - `def d: [.[]?|d]|sort; d|tojson|length` → `19998`.
- Detach, the Printer and MergeSort are iterative, so a STACK there raises the natives part of the
  reserve. It is not a finding (K5).

**PIT**
- The child-JVM test classes leave PIT's test set, under a finding with seconds and first-kill counts
  (F-84). This is not a PIT-scope change (K10).

**Considered**
- Measuring overflow depth in levels.
- One `b` for every size, which folds the fixed overhead into `b`.
- "Half" as a 2× margin, which would halve `U_f`.
- Fresh equality with the constants, which is flaky across JIT runs.
- Moving the ticket column to the spec gate.

**Rulings on the red (2026-11-15, grilling-adviser, delegation to 2026-11-16)**
- Shapes: 13. F-95's `collect-descent` (a recursion through 100 Collects with no rise) joins the 12;
  #114 adds its PLAIN-call filter as a 14th after it merges and re-runs the matrix.
- Cold linkage is a reserve term of its own. The first switch to the trampoline loads its classes
  (`ClassLoader.defineClass1` at depth), a fixed cost of about 17.6 KiB on T under `-Xint`, the same
  at 512 KiB and 1 MiB, so it is not a `b` term.
  - An `-Xint` cell's `U*` is the cold edge: each probe is a fresh child's first run, and one
    bisection suffices, because the edge is deterministic. JIT cells keep the smallest of 3.
  - A second column holds the linked edge, measured after two linking runs. The two slopes of a pair
    must agree.
  - The reserve is the largest linked intercept plus the largest cold term, rounded up to 4 KiB, plus
    32 KiB for the natives (owner-review O1).
- The ticket column runs one fresh child per shape and mode. `edgeIsExact` runs `U*` and `U* + 1`,
  each as the first run of its own fresh child.
- `b` and the reserve are the `max-b` and `reserve` rows of the full matrix, run once at #111's
  constants. Probes declare `stackBytesFor(U)`, so `U*` does not depend on the committed
  constants; a second run confirms them. No stand-in value is committed. `b` is published as
  measured, even near 200 (owner-review O2).
- Frames above units are a weight defect, not a `b`: W and collect-descent join
  `framesStayUnderTheLimit` as named cases first.
- Heap per activation is measured on held generators, read at the first emit with depths n and 2n
  (F with the leaf `(0, 0)`; the same with 16 `try` per level; the closure chain with leaf
  `(f, f)`), per held filter and mode. Runtime's used heap is acceptable; it equals MemoryMXBean's
  after two collections.
- The warm proof excludes the probe's `down` method from compilation, because an inlined recursion
  makes the warm proof unstable.
- The matrix cuts no cell. `-Xint` and C1 children run 4 in parallel (`-Xbatch` edges are the same
  alone and in parallel); warm children run one at a time. Each search is seeded from the committed
  record. The time is measured and recorded; above 45 minutes it is a finding (owner-review O4).
- C1 at 512 KiB is covered by the matrix's C1 cells. There is no C1 ticket column.
- The implementer commits no `b` or reserve other than the record's rows, never raises one to absorb
  the other's red, does not prelink or pre-warm classes to move the cold edge, changes no unit
  weight and does not touch the 4 194 304 default. Only CalibrationTest and DeepStackFreshJvmTest
  leave PIT's test set; CalibrationRecordTest stays in.
- If the warm cells give an intercept far above the linked `-Xint` one (warm W: slope 88, about
  248 KiB), the implementer stops and hands back.

**Rulings on the matrix (2026-11-15, grilling-adviser K1–K8, delegation to 2026-11-16)**

The full matrix ran on impl/113 e6f4cc0 in 92.5 minutes. Its record (234 cells) is the evidence.
The warm intercept was far above the linked one, so the implementer stopped.
- **Warm is proven, not measured (K1).** In warm children the search runs while the JIT is still
  compiling, and under C2 some shapes almost fit 1 MiB whole. So their edges are not edges, and their
  slopes are junk. A confirm-run does not fix this. `b` and the reserve are therefore *measured in
  `-Xint` and C1, and proven in warm*. This replaces "the worst of `-Xint`, C1 only and warm".
  - A warm cell runs its shape at the cell's own declared stack, 60 runs in a row from the first cold
    one, in 3 default-tier children.
  - Every run must give the reference result. One STACK is a red and a hand-back.
- **`b` = 192 (K2).** C1 W CONSERVATIVE gives 189, with spread 0 and STACK at both sizes. Every
  `-Xint` and C1 cell has spread 0.
- **Reserve = 286 720, 280 KiB (K3).** It is the linked intercept of `-Xint` virtual ND OPTIMISED
  (220 832) plus collect-descent's cold term (29 988), rounded up to 253 952, plus 32 768.
- **One `b`, no split (K4).**
  - `U_f` is 1237 − D_f at 512 KiB and 3968 − D_f at 1 MiB.
  - A run cannot know its tier. A split by mode would gain 14 % at the price of a second constant.
  - The tightest cell is C1 W CONSERVATIVE virtual, with `U*` 2131 against `U_f` 1136.
  - Two shapes under C1 set `b`: W (189) and collect-descent (167). The rest lie between 24 and 100
    (F-102).
- **A seeded re-run before the commit (K5).** The order is: the K1 harness, then the constants, then
  one full run seeded from the e6f4cc0 record.
  - That run's `max-b` and `reserve` rows must equal the constants.
  - Every `-Xint` and C1 `U*` must equal the e6f4cc0 record's.
  - Any difference is a hand-back. No value is adjusted.
- **Pins (K6).** The 128 KiB pins (StackUnitsTest, ExplainTest) read `StackUnits.reserve()`. The
  literal lives only in the class PIT mutates.
- **`[7], termination STACK` (K7)** at an `-Xint` first-or `U*+1` is a valid edge. The emit reached
  the sink before the overflow.
- **Open (not blocking):** whether ND OPTIMISED's intercept, twice every other cell's, is a fixed
  cost or an extrapolation artefact. If it is an artefact, the reserve would be 168 KiB. That would be
  a formula change for the owner, not the implementer's choice. The re-run probes it at 256 KiB and
  384 KiB.

**Rulings on the seeded re-run (2026-11-15, grilling-adviser L1–L4)**

The K5 run (impl/113 078dd0a, 48.7 min) gave the K2/K3 rows, and every warm cell held. Three C1 edges
moved up, by +1, +7 and +31. Those are exactly the gallop's points above the seed: the edges were
found while C1 was still compiling.
- **A JIT edge is a fixed point (L1).** A JIT cell searches again from its own result until two passes
  agree. After 4 passes without agreement it is INVALID. Upward moves are not accepted, and C1 is not
  run unseeded where the record has the cell's row. A cell new to the matrix is searched unseeded
  once, at its fixed point, in 3 children; the next full run, seeded from that one, must return the
  same `U*` (K5; amended by #199 ruling C1).
  - The order: first the three cells alone, seeded from e6f4cc0, must return 5818, 2131 and 5113.
    Then one full run, seeded the same way, under K5.
  - The committed record is that run's. The e6f4cc0 and K5 records stay as evidence, and
    `bench/RESULTS.md` cites all three. Later runs assert only as above (fresh ≤ constants, each cell
    switches).
- **The reserve stays 286 720 here (L2).** The side probe puts ND OPTIMISED's `-Xint` intercept at
  about 105 K at 256, 384 and 512 KiB, against 220 832 on the 512 KiB → 1 MiB chord. So today's
  intercept is an extrapolation artefact. The rule changes with the next owed re-run (#199):
  - a 256 KiB platform thread;
  - each intercept taken on its lowest pair;
  - `b_cell` the largest adjacent slope;
  - no cell excluded by name, and no fit.
- **An edge at `U*+1` (L3)** may end `, termination STACK` with a prefix of the reference results,
  e.g. `[7], termination STACK` for first-or. `[7], ran out`, any other termination, or a result that
  is not a prefix stays red.
- **UnitLimitTest's floorDiv witness (L4)** is re-pinned to −286 720/192 → −1494 (286 720 = 192·1493
  + 64). Superseded by #199: line 158 holds no constant.

## Amendment (ticket #199)

Accepted by the architect under `/run-spec` #106. It comes from the designer's draft and the
grilling-adviser's rulings on it (2026-11-15, C1–C8), for the owner under the delegation of
2026-11-14. It replaces #113's "Cells" and "What a cell measures" where they differ, as L2 ordered.

- **Shapes: 14.** #114's PLAIN-call filter joins as `plain-descent`: IrDepthTest's `nested(30_001)`,
  300 lifted functions over 30 000 levels of PLAIN calls past the IR bound, on `null`, giving
  `NESTED_30001` (reference jq's `over-cap/depth-30001`). It is NEVER_FLAT. CalibrationCell builds the
  text itself, so children load no test class.
- **Threads.** `-Xint` and C1 run on platform 256 KiB, 512 KiB and 1 MiB, and on virtual threads
  (`-Xss512k`); warm runs on the last three. Cells: 224 measured and 84 warm, 308 rows; the ticket
  column has 28. Only `scripts/calibration-cells.sh` holds the counts.
- **Slope and intercept.**
  - Per tier, mode and shape, each adjacent platform pair has the slope s = ceilDiv(ΔS, ΔU*linked).
  - `b_cell` is the largest s, and a virtual row takes the lowest pair's slope.
  - The intercept S − s_low·(U*linked + D_f) is taken on the lowest pair's rows only (256 KiB, 512 KiB,
    virtual); the 1 MiB row has none.
  - cold = `b_cell`·(U*linked − U*cold).
  - b = ceil8(max `b_cell`); reserve = ceil4K(max intercept + max cold) + 32 KiB, never below the
    guard + 32 KiB.
  - Nothing is fitted and no cell is excluded by name. This replaces the 512 KiB → 1 MiB chord.
  - The lowest pair is the two lowest platform threads a group has. A group with no 256 KiB row (a
    record older than #199, as in the K1 fixture) takes 512 KiB → 1 MiB, and both of its rows and its
    virtual row then give intercepts (architect, approving the red).
- **Proof.** Each platform cell proves against the next thread up; 1 MiB proves against 512 KiB, and a
  virtual cell against 1 MiB.
- **Order.**
  - Run A runs at #113's constants, seeded from the record where a row exists. Its max-b and
    reserve rows give the constants.
  - Run B runs at those constants, seeded from A. It is committed only if:
    - its rows equal the constants;
    - every `-Xint` and C1 `U*` equals A's;
    - A's old `U*` equal #113's record;
    - every warm cell holds.
  - Hand back before committing any constant, keeping A's record, if:
    - reserve > 262 144;
    - max-b > 192;
    - a new C1 cell is INVALID or has spread ≠ 0;
    - an old `U*` differs from the record;
    - a warm cell ends STACK (C2).
- **`edgeIsExact` is seeded** from the record's `U*cold`, as "each search is seeded from the record"
  says; the assertion is unchanged (open question 5(a)).
- **The record** gains `machine`, `cpu` and `kernel` rows. A result longer than 200 characters prints as
  `<length> chars, sha256 <hex>`; comparisons stay on the full text (C4).
- **Re-pins.** `warmRowsFeedNeitherBNorTheReserve` keeps its expected
  `Maxima(189, 220_832, 29_988, 192, 286_720)` and gains each row's partners from e6f4cc0's record. A
  move to new-rule numbers would be a weakening (C6). UnitLimitTest:158 holds no constant:
  `unitLimit(e, reserve() − bytesPerUnit() − 1) == −2 − D_f` (C8).
- **Warm 256 KiB cells** are not added: a slope-setting shape keeps the same headroom at the switch on
  every size (open question 2).
- **Time** (F-97): with seeding, the spec-gate step is about 57–65 min. Below 45 min needs the warm phase
  cut, which is the owner's question (2-wide warm children against "one at a time").
- **Considered:**
  - intercepts at each cell's own S with the largest slope (the mirror artefact);
  - a fit, or excluding ND by name (L2);
  - a 384 KiB thread, or a virtual 256 KiB one;
  - a persisted `b_low` column;
  - loading IrDepthTest in children;
  - a count row inside the tsv;
  - a literal at line 158.

**Owner's rulings on #199's C3 stops (2026-11-16, on the grilling-adviser's recommendation)**
- **`plain-descent` at 256 KiB.** Its compile overflows on a 256 KiB thread in all four probes
  (`-Xint` and C1, both modes), and under C1 at 384 KiB. Its four 256 KiB cells stay in the record
  as `compile STACK` rows with no edge. They are a pinned set of exactly those four cells, like
  NEVER_FLAT, with their own red test. The shape's lowest pair is then 512 KiB → 1 MiB, by the
  lowest-pair rule above. Compiling stays on the cell's thread (C3). The counts stay 308 and 28.
  Compiling on a helper thread is the fallback if this shape's chord intercept sets the reserve
  (F-109).
- **A new baseline.** #117 and #118 moved 20 of #113's 26 record edges (F-110), so run A is an
  unseeded baseline on the commit of the C3 edge check: every measured cell is searched under C1's
  rule for new cells. C2's "A's old `U*` equal #113's record" becomes "A's `-Xint` 512 KiB `U*cold`
  equal the 28 values of the C3 edge check (`199-c3-edge.log`)", and "an old `U*` differs from the
  record" becomes "differs from that log". The other C2 stops stay.

**Owner's rulings on #199's run A stop (2026-11-16, on the grilling-adviser's recommendation)**
- **Compile on a helper thread.** Run A (unseeded, 2 h 02 min) failed on six more cells: C1 at
  256 KiB for W, hold and collect-descent, both modes. After the C1 search's 30 warm-up runs the
  Parser is C1-compiled and its frames no longer fit 256 KiB, so `compile` overflows inside
  `ended()` (F-109). `b` and the reserve govern the run, not the compile (`stackBytes` enters only at
  `JqFilter.run`), and a pinned set that depends on JIT state can differ by JDK or machine. So the
  calibration child compiles every cell's filter on one long-lived helper thread with a large stack,
  started after the cell's thread and living as long as the child, and runs it on the cell's
  thread. This replaces "Compiling stays on the cell's thread" and the `compile STACK` set above:
  all ten edgeless 256 KiB cells (these six and plain-descent's four) are measured, and a
  `compile STACK` row anywhere fails the matrix, with its own red test. The counts stay 308 and 28.
  The 512 KiB → 1 MiB fallback is not accepted for `b`: the four largest slopes sat in groups
  without a lowest pair.
- **Before the long run.** A hand probe of the ten cells at `-Xss256k` with the helper compile (3
  children), which also observes whether plain-descent runs to `NESTED_30001` at `stackBytes` 0 on
  256 KiB; then the 28-cell edge check on the new commit.
- **The second run A is seeded** from run A's record. The ten cells are searched unseeded as new
  cells. It stops if the 28 `-Xint` 512 KiB `U*cold` differ from `199-c3-edge.log`, or if any other
  `U*` differs from run A. Then run B. A real 256 KiB row for W CONSERVATIVE C1 may give `b` 200
  (C2's stop "max b > 192") or a reserve of 184 320 (adviser's inference); either goes to the owner.
- **The compile overflow is F-109, widened.** It is not a new finding.

**Owner's rulings on #199's second run A stop (2026-11-16, on the grilling-adviser's recommendation)**
- **Spin on virtual cells.** The seeded run A (1 h 28 min) failed on four virtual 512 KiB cells
  (`-Xint` CONSERVATIVE T, C1 OPTIMISED hold, collect and first-or: no fixed point or a `ran out` in
  one child), and 41 virtual `U*` moved, all towards the platform value and none past it. Every
  platform cell outside the ten equals run A. The likely cause is that the cell's virtual thread
  parks on the helper's `join()` and is remounted with only its top frames thawed (UNVERIFIED on
  25.0.5). So on a virtual cell the cell's thread waits for the helper without parking: it spins on
  `isDone` with `Thread.onSpinWait`, and calls no park, `yield` or `sleep`. Platform cells keep the
  parked `join()`. The calibration measures a virtual thread that has never parked. If the fix fails
  the probe, the fallback is compiling on the cell's thread for virtual cells only.
- **The probe comes first.** Before any long run, a hand probe of seven virtual 512 KiB cells, 3
  children each: the four failed cells, C1 W CONSERVATIVE (the cell that sets the reserve), and `-Xint`
  and C1 N OPTIMISED. Each must give a fixed point, spread 0 and run A's `U*`. Stable values that
  differ from run A go to the owner. No fixed point means trying the fallback.
- **The reserve is 184 320**, accepted on condition that the third run reproduces it. It comes from
  the rule, not from noise. C1 virtual W CONSERVATIVE keeps `U*` 2131, but its slope is now taken
  from the measured 256 KiB pair (`s_low` 182, not 189): intercept 118 064, plus cold 30 996, rounded
  up to 4K is 151 552, plus 32 768. It holds while that cell's `U*` is at least 2118. `b` stays 192,
  because the slopes come from platform pairs.
- **The third run is the second run A, repeated on the fix commit.** It runs after the 28-cell edge
  check, seeded from run A's record, with the ten cells unseeded. It stops if:
  - the 28 edge cells differ from `199-c3-edge.log`;
  - the ten cells differ from the second run's values;
  - any other `U*` differs from run A, all 56 virtual cells included;
  - max `b` is not 192 or the reserve is not 184 320;
  - any other C2 stop fires.
  Then run B, at 192 and 184 320.

## Amendment (ticket #198, owner 2026-11-16)

Owner's rulings on the #198 grilling round, on the grilling-adviser's recommendations (designer
draft; F-100, #117 W3).

- **A value-form Call ends at its first result on the driver.** On the driver a value-form Call
  takes its body's first result, answers it with a stop, leaves its activation and its units, and
  then emits the value with `under` as producer, as a value-form starter does (ADR-0047 #116). No
  heap form calls `eval`. This replaces C2's first bullet ("on the driver, the call stays live for as
  long as its consumer runs") and its carve-out that an OPTIMISED depth verdict may depend on
  `stackBytes`. It also narrows "the same … depth counts" above: the driver now counts what the
  stack counts. Only Call changes; the other value-eligible forms keep their heap form (their
  ending frees heap only and changes no count). Units, `D_f` and stack frames are untouched, and the
  ticket's calibration column of 28 cells must show no edge moved.
- **Why it is sound.** On the stack a value-form call's `eval` has returned before its consumer
  runs, so a downstream error never reaches the callee; the driver now agrees. jq-ref confirms the
  routes: an AltBind upstream of the call catches (`[[1]]`), a `try` inside the callee does not
  (error, rc 5), and M4 gives `[[1]]` (grilling-adviser, 2026-11-16).
- **Red and guards.** `ValueFormTest`'s `def f: if . == 0 then 0 else (. - 1 | f) | (0 | f) end` at
  `stackBytes` 0 in OPTIMISED: depth n + 1 passes and n ends at the budget (today 2n + 1, by code
  reading). `SpecialiseExplainTest`'s pipe ladder is re-pinned from 110 000 to 40 000 for OPTIMISED,
  which is stricter (owner's consent, 2026-11-16; precedent ADR-0047 #116); CONSERVATIVE still ends
  at the budget there. The M4 and F-56 cases and the adviser's probes run as OPTIMISED cases at
  `stackBytes` 0, since the oracle's `trampoline` run is CONSERVATIVE only. A filter that pipes
  lifted helper calls before each recursive call, `def r: if .==0 then 0 else
  ((.-1)|until(true;.)|until(true;.)|until(true;.)|until(true;.)|r)+1 end; 1000000|r`, is run on
  jqj at `Limits.cli()` before and after; jq-ref gives `1000000`.
- **Order.** The ticket runs after #199's record commit and #119's JMH session, so both keep a
  quiet baseline.
- **A value-form `D_f` (design C) is deferred** to spec 6 (Speed), not the bytecode spec (milestone 1
  ships only the interpreter, ADR-0018). It is reopened only if the all-driver time from #119
  exceeds a factor the owner sets once that number exists. Until then a filter whose
  `callFreeDepth` is above `U_f` runs wholly on the driver.
- **Later round.** If the ticket holds, whether "the count of live call activations does not depend
  on `stackBytes`" becomes a stated invariant per compile mode, with a property test, and the
  bytecode hand-over above becomes "holds it or names its cases".
- **Considered.** Every value-eligible form ending at its result (20–22 forms, heap only, 20× the
  PIT surface); deferring all of it (keeps C2's carve-out and the 110 000 pin, and may break
  DEVIATIONS' "at least 10⁶ levels" if the adviser's prediction holds); a value-form `D_f` now (a
  value-form walk in `Nesting`, measured `eval` weights, an OPTIMISED matrix re-run of about 60 min;
  an undercount ends in `termination STACK`).
