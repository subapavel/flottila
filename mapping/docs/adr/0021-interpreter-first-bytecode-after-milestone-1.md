# Milestone 1 runs a closure-compiled interpreter; the bytecode tier follows as the first engine spec after it; JMH targets and a noise-tolerant bench ratchet

Status: accepted (2026-10-31, grilling #17); order after milestone 1 refined by grilling #19 (JSON output first, then this tier); refined by ADR-0025 (the binding is in runtime, the algebra in xdm); amended 2026-11-10 (tree target p95 < 33.3 ms, F-26; orders row and heap forks, see "Notes of 2026-11-10"); notes of 2026-11-11 (spec 6, owner-approved)

FOUNDING §5 plans an interpreter (tier 0) and a Class-File API bytecode tier (tier 1), and §6
left open whether milestone 1 ships bytecode. §7 asks for JMH proof early: latency on small
messages, throughput and memory on large ones. ADR-0006 binds both tiers to one IR, ADR-0018
reserves a fifth metamorphic variant, ADR-0019 leaves `bench.tsv` to this ticket, and ADR-0020
leaves UDF calls from generated code to it. A scratch trial on the VM decided the questions
(numbers in `bench/RESULTS.md`, "Interpreter and bytecode trial").

## Milestone 1: one back end, the closure-compiled interpreter

- Milestone 1 ships **only the interpreter**. It is closure-compiled: compiling a mapping turns
  the IR once into a tree of executable nodes over frames with slots. Per-item code never looks
  anything up by name. A tree-walking `switch` over `Expr` was 20–50 % slower in map-only time
  and allocated more than twice as much.
- The interpreter and its closure compiler live in `engine/runtime`, next to the queue-operation
  library and the UDF adapter. There is no new module and no back-end interface, because no
  second back end exists in milestone 1.
- **Traced and untraced are two compiles.** The editor's test run compiles unoptimised IR with
  `Tracer.value` hooks in its nodes (ADR-0006). The untraced tree holds no hook and no
  `tracer != null` test.
- The interruption check sits on every loop over slots and contexts, always. It cost nothing
  measurable. A test-run timeout stays cooperative (ADR-0014, ADR-0020).
- UDFs are called through the `MethodHandle` the back end links from `FunctionRef`, via the
  runtime adapter (ADR-0020).
- **No tiering in milestone 1.** A deploy (use through `engine/api` outside the editor)
  compiles eagerly: optimised IR, untraced. Invocation counting and thresholds (FOUNDING §5)
  come with the bytecode tier or the XSLT front end.
- **No compiled-mapping cache in the service.** Compiling per request from the working copy
  (ADR-0015) is cheap: a closure compile took 0.05 ms warm. UDF compiles keep their LRU cache
  (ADR-0020). The `compile/orders` scenario guards this; crossing its target is a finding that
  may open a cache.
- **No Leyden AOT cache and no CDS in milestone 1.** It cut start to first output by about 30 %
  without preview flags, but no host or gate needs it yet. It goes into the map's fog with
  GraalVM native image. Commands and quirks are in `agent/NOTES.md`.

## The bytecode tier after milestone 1

The first engine spec after milestone 1 builds it, before the XSLT front end, and to this shape.
Grilling #19 placed it right after the JSON output spec (ADR-0008), so the generator emits the
JSON constructor records from the start (`docs/GATE.md`, "After milestone 1"):

- module `engine/bytecode`, JPMS `io.flottila.mapping.bytecode`, requires `ir`, `runtime` and
  `xdm`. `engine/api` selects it at deploy. Class C: JaCoCo lines ≥ 90 % and branches ≥ 80 %,
  and PIT ≥ 80 % on the generator. The generated hidden classes cannot be instrumented, so they
  are outside JaCoCo. The fifth metamorphic variant holds their correctness;
- one **hidden class per mapping** (`Lookup.defineHiddenClassWithClassData`, without
  `ClassOption.STRONG`), unloaded with the mapping. Each `TargetSlots` and `Let` is a private
  static method, and `Lift` loops are inlined. `AuthoringId`s, literals and UDF handles are
  `ConstantDynamic` constants over class data (`ConstantDescs.BSM_CLASS_DATA_AT`). Queue
  operations call the same `runtime` library as the interpreter, so their semantics live once;
- UDFs are called with `invokeExact` on the class-data constant and are never inlined by the
  generator, because the JIT inlines them;
- tracer hooks are emitted only in a traced compile (ADR-0006). The editor keeps using the
  interpreter;
- there is an interruption check on every backward branch, always;
- the **fifth metamorphic variant** (interpreter ≡ bytecode, ADR-0018) arrives with the module.
  It runs in the ticket gate over the whole corpus. If it breaks the 30 s budget (ADR-0019),
  the spec narrows it with a finding;
- hidden frames are missing from stack traces, so runtime errors carry the `AuthoringId` as
  always. Debug with `-XX:+ShowHiddenFrames`.

## Benchmarks (GATE check 7)

Three synthetic corpus mappings in `corpus/bench/`, each with a deterministic, seeded input
generator. Large inputs are generated, never committed.

| Mapping | Shapes it covers |
| --- | --- |
| `orders` | 1:1 fields, `concat` through `Lift`, `removeContexts`, `splitByValue`, an unbounded target, one single-value UDF |
| `wide` | a flat IDoc-like source of about 300 fields; the mapping reads about 10 %, mostly 1:1 (projection, shape specialisation) |
| `contexts` | `useOneAsMany`, nested `splitByValue`, `sortByKey`, `collapseContexts` (the FOUNDING §7 copying risk) |

Seven JMH benchmarks in `bench/jmh`, end to end from input bytes to output bytes through
`engine/api`:

| Scenario | Metric | Target on `claude-cloud-4vcpu` |
| --- | --- | --- |
| `<mapping>/small/latency` (message 8–10 kB) | `avgt` µs/op | ≤ 500 µs/op |
| `<mapping>/small/alloc` | `gc.alloc.rate.norm` B/op (`-prof gc`) | none, ratchet only |
| `<mapping>/large/throughput` (message ≥ 10 MB) | MB/s | ≥ 20 MB/s |
| `<mapping>/large/alloc` | B/op | none, ratchet only |
| `<mapping>/large/heap` | the large benchmark in a fork with `-Xmx128m` | completes |
| `compile/orders` | `avgt` ms/op, warm compile of the model to an executable mapping | ≤ 5 ms/op |

The targets defend "match or beat SAP PO" without PO on the VM. PO typically spends a few ms in
the mapping step of a small message (FOUNDING §7). 500 µs on 4 vCPU leaves room, and is 4–5×
the trial's toy mapping, to cover the real catalogue, typing and SUPPRESS. 20 MB/s maps 10 MB in
half a second. An **optional observation request** asks the owner to run the same synthetic
`orders` mapping on a local PO and report only the mapping-step times from message monitoring
(median of 10, PO release), for a small and a large message. The numbers go to
`bench/RESULTS.md` under the label `po-owner-local`. They are informative, never a gate
condition, and nothing from the owner's system enters the repo.

The UDF compile (javac) is not a JMH scenario. It is JDK code, and its rough numbers stay in
`bench/RESULTS.md`.

## The bench ratchet

- `ratchet/bench.tsv`: `<scenario>\t<machine label>\t<score>\t<unit>`. Scenario names are
  `<mapping>/<size>/<metric>`, `compile/<mapping>` and `ui/tree-20128/scroll`.
- Score: the **median of three forks** (`-f 3 -wi 3 -i 5`, 1 s iterations). The trial saw
  8–16 % spread between runs and a JMH error of up to ±100 % within one fork.
- The direction comes from the unit, through a fixed table in `scripts/ratchet.java`:
  - `us/op`, `ms/op`, `B/op` and `ms`: lower is better;
  - `MB/s`: higher is better;
  - an unknown unit fails.
- Tolerance: 20 % for time and throughput, 10 % for B/op.
- **Regression**: a score worse than the baseline by more than its tolerance. The verifier
  measures that scenario once more, and the gate fails if the second run is also worse. Missing
  an absolute target fails as well.
- **Baseline movement**: `regen` writes a new score only when it improves by more than the
  tolerance, so the baseline cannot slide down 20 % per spec. Lowering a baseline takes a
  finding (WORKFLOW §7).
- The 20,128-field tree scenario (ADR-0013) is measured by Playwright on the VM as the p95
  frame time while scrolling, in `ms`. The target is p95 < 33.3 ms (no dropped frame at p95; amended
  2026-11-10, F-26), with a 20 % tolerance.
- Benchmarks run only in spec-gate step 5, never in a ticket gate or on CI. The full run takes
  about 5–6 minutes.

## Notes of 2026-11-10 (spec 6 grilling, #181, owner)

- **Tree target.** The scroll target reads p95 < 33.3 ms, no dropped frame at p95, instead of the one-vsync
  target (F-26, #128). The 20 % tolerance stays.
- **`orders` row.** "One single-value UDF" cannot be built: ADR-0030 removes UDFs from milestone 1
  (F-35). The mapping uses `FormatNum` on the amounts and `DateTrans` on a date, through `Lift`,
  in its place.
- **Heap.** Large throughput is measured in forks with a 1 GB heap. `<mapping>/large/heap` is a
  separate fork with `-Xmx128m`; it may need a second JMH invocation in spec-gate step 5.

## Notes of 2026-11-11 (spec 6, #181, #189, #193, owner)

The #189 measurement judged one commit (5a2515f) three times on `claude-cloud-4vcpu` hosts:
`orders/large/throughput` was 34.69, 22.34 and 18.79 MB/s, so one run missed the target and two met it.

1. **Median of three runs.** A target and a regression are judged on the median of three runs, not
   on one run. A first run that regresses or misses a target is re-run twice, and the gate judges
   the median of the three. A clean first run stays one run. This replaces the single re-run of
   "The bench ratchet". `<mapping>/large/heap` only has to complete, so a fork that fails in any
   run still fails the gate.
2. **Large-message target as time.** The target is a 10 MB message in at most 500 ms, the same
   requirement as 20 MB/s. The ratchet keeps `MB/s` as the unit.
3. **No comparison with SAP PO.** The large-message target guards against regressions with
   headroom. The PO editor freezes on a message of about 10 MB, so the PO comparison is
   runtime-only and optional (#191). The observation request carries a second label,
   `is-owner-local`, beside `po-owner-local`; both stay informative, never a gate condition.
4. **Baselines from the spec gate only** (F-36, owner, 2026-11-11). Runs on one label differ by
   36–71 % between hosts. A `ratchet/bench.tsv` baseline comes only from the spec gate's step 4
   run. Every other run (ticket measurements, remote sessions, local copies) is informative only
   and never moves or judges a baseline. A regression that the median of three still shows goes
   to the owner as a finding, because it can come from the host.

## Considered options

- **Bytecode for the whole IR in milestone 1**: rejected. In the trial it was 2.3–2.7× faster in
  map-only time, but only 15–20 % faster end to end on a small message, where parsing takes
  about 85 %. It would cost a class-C module and the fifth variant, and the AOT cache does not
  store its hidden class.
- **Bytecode for hot paths only, delegating the rest to the interpreter**: rejected. Two tiers
  in one run make the byte-identical proof and tracing harder.
- **A tree-walking interpreter**: rejected. It was slower than closure compilation and allocated
  more than twice as much.
- **A single JMH run per spec gate, or a moving baseline**: rejected. VM noise would fail gates
  at random or let regressions accumulate.
- **PO numbers as a gate condition**: rejected. PO runs only on the owner's machine.

## Consequences

- FOUNDING §5, §6 and §7 are refined: "run compiled" in milestone 1 means compiled IR in the
  closure-compiled interpreter, and the §7 proof runs on the interpreter.
- New rows and rules in other documents:
  - ADR-0016 gains the `engine/bytecode` row;
  - ADR-0019 gains the floor row for it and the `bench.tsv` rules;
  - ADR-0018 times the fifth variant;
  - WORKFLOW §6 step 5 gains the commands;
  - GATE check 7 gains the targets;
  - CODING_STANDARDS gains the hot-path rules.
- The milestone-1 skeleton or the first spec adds `corpus/bench/`, the generators, the seven
  benchmarks and the bench rules of `scripts/ratchet.java`. #19 places it.
- Observed on 2026-10-31 in a scratch reactor on the VM, not committed:
  - five back-end variants (tree-walking, closure, bytecode, bytecode with a no-op tracer,
    bytecode without interruption checks) gave byte-identical output on small and 10.5 MB
    inputs. The test took 2–4 s;
  - a cold bytecode compile took 32 ms, a warm one 2.3 ms;
  - the hidden class was unloaded after one `System.gc()` in 100 of 100 runs;
  - a UDF call cost 0.7–0.8 ns from generated code and 3.1 ns from the interpreter (trivial
    body);
  - the AOT cache cut start to first output from 98–136 ms to 69–85 ms. It held lambdas and
    the `java.lang.classfile` classes, but not the generated hidden class.
