# Milestone 1 ships the interpreter; the bytecode backend opens milestone 2

Status: accepted (2026-11-11, owner, issue #12 "Interpreter or bytecode first, and what speed must milestone 1 show?"); amends ADR-0011 (calibration), ADR-0013 (module-path cases, linkage test) and ADR-0016 (unloading); reads founding §4, §9 and §12 layers 1–2 for milestone 1; amended by ADR-0022 (issue #8: no in-place licence in the conservative compile, copy-only switch); amended by F-77 (spec 5: the conservative compile detaches every `input` document at its read)

- **Milestone 1 ships only the interpreter backend.** It starts in spec 1 (walking skeleton) and
  is complete by spec 5. The bytecode backend is the first spec of milestone 2.
  - The interpreter is needed whatever the order: ADR-0011 keeps the interpreter tree of every
    recursive group and closure, and runs recursion beyond U there.
  - Check 5 does not need bytecode on the evidence so far. On jq-ref 1.8.2, per document with
    start-up subtracted, G1 `.a.b.c` costs 16.5 µs at 1 KB, 1.67 ms at 100 KB and 376 ms at 10 MB.
    The milestone-1 reader (`m1NoDup`) costs 9–13 % of that at both ends. Interpreter time on
    G2–G5 does not exist yet (adviser, issue #12 round 1).
- **Recursion in milestone 1:** there is no hand-over. Past U the interpreter runs its own form-B
  trampoline (ADR-0011). The U calibration test arrives with recursion in spec 2 and runs on the
  interpreter only. The bytecode spec adds the hand-over entry, the weights of generated frames
  and scenario `recursionHandover`. It then runs the calibration again on both backends, and the
  release takes the smaller U.
- **No backend choice in the API.** `JqEngine.builder()` has no `backend(…)` in milestone 1
  (founding §4: callers never know). The bytecode spec decides how the engine chooses, and how
  founding §9's command-line size threshold is reached through the exported API (ADR-0016).
- **`explain()`** prints the IR and its facts (ADR-0016). A bytecode listing comes with that
  backend, from jqj's own printer over `ClassModel`: JDK 25.0.5's `java.base` exports no class
  printer (`jdk.internal.classfile.components` is exported to `jdk.jfr` only).
- **The second opinion in milestone 1 is the compile-mode oracle** (ADR-0020). It compares the
  optimised compile with a conservative compile of the same filter, on output and error. The
  conservative compile sets cardinality to many everywhere, makes every Update and PathOf
  TRACKED, detaches at every hold and caps specialisation at 0. It grants no in-place licence but
  keeps the update's own owner (ADR-0022); the copy-only switch, under which no owner matches, is
  the ownership tests' baseline. Because cardinality is many everywhere, it has no tail calls, so its deep-recursion cases run on the trampoline. "Backend
  oracle" and `ratchet/oracle/backend.txt` stay reserved for the interpreter against bytecode.
- **Module path:** ADR-0013's ten-or-so `jq-cli` cases run on the interpreter. The test that a
  generated class names only `java.*` and `query-core` types moves to the bytecode spec.
- **Unloading (GATE check 7):** in milestone 1 the test observes a dropped `JqFilter` and its tree
  being collected. The hidden-class rule of ADR-0016 applies once bytecode exists.

## Considered options

- **Both backends in milestone 1:** spec 6 would gain a class-C code generator (21 nodes in value
  and path variants, method splitting, the hand-over, a second calibration), and that size is
  unmeasured.
- **Bytecode first:** it still needs the whole interpreter beyond U, so it amounts to "both".
- **A mix of interpreted and compiled parts inside milestone 1:** a third execution shape for the
  oracle.
- **A public `backend(…)` now:** there is nothing to choose, and it goes against founding §4.
- **A tiered swap after N runs:** compile latency lands on a random message, and the engine starts
  no thread (ADR-0017).
- **Calling the compile-mode oracle "backend oracle":** contradicts the glossary.

## Amendment (F-77, spec 5)

The conservative compile detaches every `input` document at its read. It keeps doing so when spec
5b adds the hold analysis to the optimised compile. Under "detach at every hold" both compiles
would share one hold list, and the compile-mode oracle could not see a missed hold. This replaces
"detaches at every hold" above. The `trampoline` run (F-58) is a conservative compile and does the
same.
