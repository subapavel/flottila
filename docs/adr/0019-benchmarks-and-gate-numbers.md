# Benchmarks: gate scenarios against same-session jq-ref, and the numbers of gate checks 4 and 5

Status: accepted (2026-11-11, owner, issue #12 "Interpreter or bytecode first, and what speed must milestone 1 show?"); fixes GATE checks 4 and 5; the bench module runs from spec 1 (ADR-0013, ADR-0014); amended by ADR-0022 (issue #8: the analyses-gain diagnostic times G4 at 10⁴ only); amended by ADR-0023 (issue #17: when each scenario enters the ratchet, G4 at 10⁴ in spec 2 and larger sizes with in-place updates; 1 KB cases); amended by ADR-0024 (scenario ids); amended by spec 4 grilling round 1 (M5: spec 4's diagnostic scenarios); amended by spec 5 round 1 (the reader's rows first, the 10 % bar, `inputs | .a`)

The reference numbers below were observed by the adviser on `claude-cloud-4vcpu` with jq 1.8.2
(issue #12 round 1). The logs and the D-list are on issue #12 and in `bench/RESULTS.md` "Reference
times and limits (issue #12)".

## Scenarios

- **One operation** is `ws.read(bytes)`, then `run`, then `JsonWriter.sink` into a counting
  discard channel, in compact output with `Limits.embedded()`. The Workspace is reused and warmed
  in `@Setup`.
- **Inputs** are synthetic and generated:
  - `orders`: `{"orders":[{id,total,k}],"items":[{sku,price}],"a":{"b":{"c":"x"}}}`;
  - `recs`: `[{"k":"k0".."k63","v":n}]`;
  - `ints`: `[0..n-1]`;
  - `wide`: a root object of 78 841 keys.

  Sizes are 1 KB, 100 KB and 10 MB.
- **Gate scenarios** (founding §12), each ratcheted; G1–G6 are bounded by check 5, G7 is not:

  | id | filter | input |
  | --- | --- | --- |
  | G1 | `.a.b.c` | orders 1K/100K/10M |
  | G2 | `.orders[] \| select(.total > 100) \| {id, total}` | orders 1K/100K/10M |
  | G3 | `group_by(.k) \| map({k: .[0].k, n: length})` | recs 100K/10M |
  | G4 | `reduce .[] as $x ([]; . + [$x])` | ints 10⁴/10⁵/10⁶ |
  | G5 | `.items[].price \|= . * 1.2` | orders 1K/100K/10M |
  | G6 | `-c .` and pretty `.` | orders 10M |
  | G7 | cold start: build the engine, compile `.a`, run on 1K; and the `jqj -c .a` process | 1K |

- **Diagnostic scenarios** are recorded in `bench/RESULTS.md` with no factor. Each one informs
  the decision named after it (D1–D15, listed on issue #12):
  - workspace after a large read (#5 shrink);
  - wide iterate (ADR-0002 first touch);
  - detach of inputs and of numbers (ADR-0004, ADR-0012);
  - `limit`, `..`, `walk` and `[paths]` (ADR-0006, ADR-0010);
  - recursion T/E/M/N beyond U (ADR-0011);
  - a tick loop for steps per second (ADR-0017);
  - the reader alone (ADR-0015);
  - computed against literal printing (the Ryu trigger, ADR-0012);
  - per-message cost (ADR-0016);
  - compile cap (ADR-0006);
  - the gain of the analyses, under the conservative compile (ADR-0018); G4 there at 10⁴ only,
    since at 10⁶ it makes about 5×10¹¹ slot copies (ADR-0022);
  - the gain of in-place updates, `ownershipGain` (ADR-0022).
- Every gate and diagnostic filter on its 1 KB input is also a differential case
  (`bench.<name>.1k`), so jqj is timed on the same work jq does; a scenario with no 1 KB cell (G3,
  G4, G6) gets its case on a 1 KB input of the same generator (ADR-0023).
- G1, G6 and G7 enter `bench.tsv` in spec 1, G2 and G4 at 10⁴ in spec 2, G4 at 10⁵ and 10⁶ and G5
  with spec 3's in-place ticket, G3 in spec 2a (F-33). Before spec 6 the bench ratchet records
  and fails nothing: a line with an empty `tolerance%` is overwritten by `regen --all` in either
  direction and skipped by `check`.

## JMH settings

- JMH 1.37 with `-bm avgt -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json` and a 2 GiB fixed heap,
  on one thread. These are the spikes' settings, so their numbers stay comparable.
- Small-heap forks use a 64 MiB fixed heap. G7 uses single-shot mode with 10 forks and no warm-up.
- The exact JMH flag spelling is checked by the skeleton ticket (UNVERIFIED).
- **Machine label:** `claude-cloud-4vcpu`. Each record adds the CPU model, the kernel,
  `java -version`, `jq-ref --version`, the commit and the date.

## Timing the reference

`bench/jq-ref.sh` (bash; the VM has no hyperfine and no `/usr/bin/time`) runs right after JMH, in
the same session, on the same files.
- **Per cell:** one discarded run, then the best of 5 wall-clock runs, with output to `/dev/null`.
- **Small documents** are timed as K concatenated copies: 10 000 at 1 KB, 100 at 100 KB, 1 at
  10 MB; G4 uses K = 100, 10 and 1 for 10⁴, 10⁵ and 10⁶ elements.
- **Reference time per document** = (best − best of 5 runs of the same filter on `/dev/null`) / K.
  A process on one 1 KB document took 2.08 ms against 2.07 ms of start-up, so timing one process
  per document would measure only start-up.

## Gate check 5

- **Every G1–G6 cell at every size:** jqj's JMH time per operation ≤ **1.0 ×** the reference time
  per document.
- **G7 has no factor.** A JVM hello-world starts in 22.9–24.0 ms, 11–12× the reference's 2.0 ms
  (adviser, issue #12 ADR review), which nothing short
  of a native image (out of the gate) could beat. G7 is recorded and ratcheted.
- **The ratchet holds jqj's JMH time per cell,** with a tolerance per cell that spec 6 sets from the
  JMH error, recorded by a finding (open until then). It does not hold the factor:
  the reference's best-of-5 moved by −29 % to +10 % between two sessions. The factor is computed
  only for the check-5 limit, from the same session.
- **Open:** the 1.0 factor rests on G1's read part only; interpreter times for G2–G5 do not exist
  yet. A cell that misses in spec 6 is a finding (GATE preamble), and the owner then revisits
  ADR-0018 or the factor.

## Gate check 4

The three numbers were confirmed by the owner after the ADR review (2026-11-11, issue #12).

- **Flat allocation:** G1 at 10 MB allocates at most **1 KiB per operation** more than G1 at 1 KB.
  JMH noise was 238 B against 0.015 B on Spike B's reused tape.
- **Small heap:** G1, G2 and G6 `-c` at 10 MB complete in a **64 MiB** fork. Reading alone passed at
  64 MiB under the G1, Serial and Parallel collectors; at 48 MiB only the G1 collector passed both
  documents. The writer, G2 and the JMH harness
  in such a fork are NOT_RUN.
- **Linear accumulate:** G4 time and allocation from 10⁴ to 10⁶ grow at most **225×** (a quadratic
  run gives about 10 000×). This is bounded over the whole range, because jq-ref's own growth per
  10× step ranged from 8.7× to 13.3×.

## Limits and refuel

- **`regexMatchTime`** stays at 1 s (ADR-0017). The rule is the smallest of {250 ms, 500 ms, 1 s,
  2 s} that is at least max(2 × warm, cold) joni time on `("a"*23+"b") | test("^(a+)+$")`. That
  gave 872 ms → 1 s; n = 24 (787–828 ms warm, single runs up to 843 ms) would then pass in jqj. Regex limit tests avoid n = 24–25.
- **The step default and the refuel interval** are set when the interpreter can count steps per
  second (diagnostic tick loop), as an ADR-0017 amendment. The refuel interval stays at 4096
  ticks if ticks at the slowest site come at most 16 ms apart and refuel costs at most 1 %.
- **Measured in their owning tickets**, not here: kind dispatch (pattern `switch` against
  exact-class `instanceof`, #7) in the value-model ticket; the number-flag cost in the reader (#9)
  and reading eight bytes at a time (#11) in the reader ticket.

## Considered options

- **Factor 2.0:** it would pass an engine half as fast as jq, with no evidence it is needed.
- **A per-size rule:** jq-ref has no cheap small-document path (17–21 µs per KB at 1 KB and 100 KB),
  so it has nothing to compensate.
- **Comparing whole processes:** at 1 KB it measures only start-up.
- **A factor or a millisecond ceiling for cold start:** unreachable on the JVM, or tied to one
  machine.
- **Ratcheting the factor:** the reference's own noise exceeds any useful tolerance.
- **Absolute millisecond targets; hyperfine; jaq, gojq or jackson-jq in the gate (founding §12:
  orientation only); JMH's default 5 forks × 10 s.**

## Amendment (spec 4 grilling round 1, M5)

No gate cell calls a spec-4 builtin. Spec 4's diagnostics ticket records the following on 100 KB
inputs, with no factor, beside same-session jq-ref ("Timing the reference"): `test` per element
with the pattern cache on and off (ADR-0053), `gsub`, `ascii_downcase`, `@csv`, `tojson|fromjson`
and `strftime`. Its rows in `bench/RESULTS.md` name spec 6's candidates for a native (ADR-0006: on
a measured win only).

## Amendment (spec 5 round 1)

- 5a's reader-diagnostics ticket measures the reader's diagnostic rows first, on the reader as it
  is before spec 5. A ticket that changes the fast loop measures against those rows in one
  session, as #85 did. It does not merge over the owner's ~10 % line (ADR-0002).
- Added row: `inputs | .a` over 10 000 documents of 1 KB, measured in 5a (detach at read) and again
  in 5b (the hold analysis). It is the "detach of inputs" row above.
- No host-streaming cell joins the gate scenarios.
