# Tail calls are a pending jump in the run state; the value form runs only in the optimised compile; the tail emitter stays

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); amends ADR-0011 (tail calls in the push interpreter) and ADR-0043 (closes "may subsume"); its "at most one" is F-56's; the `Path` on a pending jump by spec 3 grilling round 1 (question 5)

ADR-0011 makes tail calls jumps and gives the bytecode backend's loops. In the push interpreter an op
runs inside its producer's emit, so a call cannot loop where it stands: the activation must be left
first. ADR-0008's value form arrives in 2b beside ADR-0043's tail emitter, which the value form "may
subsume".

## Decision

- **A pending jump.** The run state gains a pending-jump field: the callee `fn`, the input, the
  value arguments and the closure arguments. A tail-position op sets it and returns at once. The
  function's entry loop takes it, clearing it as it does, and re-enters at `fn`, so neither the
  Java stack nor the recursion-depth count grows (ADR-0046); `RunState.end` clears it too, so no
  value stays pinned between runs (ADR-0034). `TAIL_SELF` and `TAIL_GROUP` both use it. A call is
  a tail call only under ADR-0006's four conditions, with each left context at most one in F-56's
  sense (M1). Condition 4's "a slot of the current activation" is any cell of the caller's frame
  (bind slot, value parameter, closure parameter, label token); a parameter passed straight on and
  a closure that reads none of them pass, so `r(f|.)` is RECURSIVE (jq-ref runs it 10⁶ deep and
  gives `1000000`). A closure that passes is an immutable reference, as cheap to carry as a value,
  so group jumps and permuted closure parameters stay jumps. A `Call` inside a closure argument's
  body is never a tail call: its frame is not the function's (ADR-0045). A jump ticks one step, as
  ADR-0008 charges every tail jump. The field joins `RunState` with its first user (ADR-0030).
- **The value form only in OPTIMISED.** A node runs `eval` (ADR-0008) where its cardinality is at
  most one (F-56) and may-break-out is false. CONSERVATIVE never calls `eval` and keeps the Holder
  path, so the compile-mode oracle stays an independent second opinion (ADR-0018).
- **The tail emitter stays** in both modes as the kind-only floor (ADR-0043). It reads no fact, so
  it keeps wide constructs flat where the analyses prove less, and everywhere in CONSERVATIVE.
  ADR-0043's "may subsume" is closed: the value form adds to the tail emitter and replaces nothing.
- **Required**: F-56's three jq-ref cases are differential cases of the value-form and tail-call
  tickets. ADR-0046's `0|until(.>=10000000; .+1)` → `10000000` (jq-ref, 3.4 s) is the witness that
  a jump replaces its activation. Two more cases, both past the default depth budget, are the
  tail-call ticket's: `def r(f): if .>=5000000 then f else (.+1)|r(.+1) end; 0|r(0)` → `5000001`
  and `def r(f;g): if .>=5000000 then [f,g] else (.+1)|r(g;f) end; 0|r(1;2)` → `[1,2]` (jq-ref).

## Considered options

- **A thrown tail signal**: against founding §8, as ADR-0008 found for break and stop.
- **A jump that carries no closures** (only the caller's own passed on): group jumps, permuted
  closure parameters and fresh closed closures would become RECURSIVE and stop at the depth budget
  where jq-ref answers (the two cases above).
- **A jump sentinel returned by `run`**: one compare on every return in hot code, as ADR-0008 found
  for a BREAK sentinel. The field is read only by a function's entry loop.
- **The value form in both modes**: CONSERVATIVE would run the optimised compile's paths, and the
  compile-mode oracle would stop being a second opinion.
- **The value form subsuming the tail emitter**: CONSERVATIVE has no facts, so its wide objects,
  interpolations and operator runs would nest per part again (ADR-0043).

## Amendment (spec 3 grilling round 1, question 5)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

In a path variant the pending jump also holds the input's `Path` (null when untracked), and the
entry loop re-enters the callee's path variant. `RunState.end` clears it. Path variants never run
the value form.
