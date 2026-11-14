# Run state: an evaluation's budgets in query-core, owned by the Workspace

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #59; designer draft);; stack units and call activations by ADR-0046, the pending jump by ADR-0047 (spec 2b)
amends ADR-0008 (`ws` in the protocol is the run state; a termination carries its budget),
ADR-0009 (every call form takes the run state first), ADR-0013 (`…query.run` holds it) and
ADR-0015 (when the reader's ticks reset); amended by ADR-0035 (bytes built, #64) and ADR-0044
(scratch, #85)

ADR-0008 and ADR-0009 write the protocol's state as `ws`, the Workspace. `query-core` cannot name
the Workspace (founding §14), and spec 2a needs the steps, deadline and bytes-written budgets
before any loop exists. This ADR fixes where that state lives and when it counts.

## Decision

- `RunState` in `io.github.subapavel.jqj.query.run`, exported by `query-core` only, is the first
  parameter of every `Op.run`, `Fn*` and `Many` call (the `ws` of ADR-0008/0009). It holds fuel,
  the step budget, the deadline, the bytes-written count and a phase (idle, primed, running).
  Labels, the break target, bytes built, stack units and activations join it with their first
  user, not before. No exported `jq-core` signature names it (ADR-0016).
- The Workspace owns one RunState. A main read primes it; `JqFilter.run` begins it (a run inside a
  running one is an `IllegalStateException`) and keeps the primed read's ticks, so the reader's
  tick per KiB counts against the run that consumes the document. Reads during a run (`input`,
  `inputs`) count too. The deadline counts from `begin`, so a host's pause between read and run is
  not charged. `end` leaves every budget unlimited until the next run. Writer ticks outside a run
  (a print between runs, charged through `tickWritten`) are not kept by the next `begin`; a prime
  starts the kept count afresh (#77).
- A tick is `if (--fuel < 0) refuel()`. Refuel, every 4096 ticks (ADR-0008, ADR-0017), checks in
  this order: the step budget, the deadline (`System.nanoTime()`, the only clock; refuel and `begin` are its only readers), the thread
  interrupt (`CANCELLED`, ADR-0017). A run under 4096 ticks reads the clock once, at `begin`, and never checks the deadline (F-36).
- `Termination(BUDGET, budget)` and `Termination(CANCELLED)` are `query-core` types, translated
  once in `JqFilter.run` into `JqTermination` with exit code 5. Since jq has no budgets, the
  messages are ours: "Step budget exceeded", "Deadline exceeded", "Bytes-written budget exceeded".
- Bytes written are charged per drain of the writer's buffer, before the write: a drain that would
  pass the limit is not written, so output never exceeds the budget.
- The run's limits are the engine's `Limits` until `RunOptions` (ADR-0016) exists; then a run's
  own `Limits` replace them in `begin`.
- `CompileMode {OPTIMISED, CONSERVATIVE}` sits in `…query.compile`, engine-internal (ADR-0018);
  both compile the same tree until spec 2b's analyses.

## Considered options

- **Reset at run start only:** the reader's witness could never fire, and a large document would
  be free (ADR-0015).
- **Enforce limits at read time:** the run's limits are not known yet, and it would be a
  termination outside any evaluation.
- **Deadline from the read:** a host's pause would be charged.
- **Fuel 0 at `begin`:** one clock read per small run, only for deadlines shorter than the
  refuel interval, which no ADR asks for.
- **The writer throws `JqTermination` itself (as a stale view does):** a second raise path and a
  second running flag.
- **Charge after the write:** overshoots the limit by up to 64 KiB.
- **Name it `RunContext`:** the glossary keeps "context" for jq's `.`.

## Amendment (ADR-0044, ticket #85)

- `RunState.ints(n)` is a reused `int[]` of at least `n`, format-neutral scratch for one native's tape positions within one call, never charged: the RunState keeps at most 2^20 ints (4 MiB) for as long as it lives, and a longer ask gets an array of its own for that call; the key proof asks its table's slots, at most 2^20, plus 3 n positions for n key entries (#85).
