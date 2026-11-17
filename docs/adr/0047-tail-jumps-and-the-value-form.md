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

## Amendment (ticket #116)

Accepted by the architect under `/run-spec` #106. It comes from the designer's draft and the
grilling-adviser's rulings C1–C4 and R1, made for the owner under the delegation of 2026-11-14.

**The `eval` contract**
- `Object eval(RunState, Object[] slots, Object in)` is abstract on `Op`. It returns the one result,
  or `CallForm.NONE` when there is none; Java `null` is a result.
- Up to the result, it ticks, charges and writes slots, label tokens and depth counts exactly as
  `run` does. Then it returns.
- Break, ParamCall, AltBind and a Many intrinsic are never eligible. Their `eval` throws
  `IllegalStateException`.

**Where the value form runs**
- **Eligibility.** An eligible node always runs by `eval`, wherever it sits. A node is eligible
  where its cardinality is at most AT_MOST_ONE and may-break-out is false.
- **Who emits the value.** A push parent emits the returned value itself: `v == NONE ||
  out.emit(v)`. So do a Call and the run entry.
- **Facts only.** Eligibility reads only the facts. CONSERVATIVE stores MANY and may-break-out
  true, so it never runs the value form, with no test of the mode.
- **Heap forms never call `eval`.** A value-form Call at `units ≥ U_f` starts the trampoline. It
  takes the first hand-back and resumes with a stop.

**Tail sites (C1, amends bullet 3)**
- In OPTIMISED, the value form comes before the tail emitter.
- #112 gives every tail-emitter kind at most AT_MOST_ONE, and #115 makes it non-breaking. So the
  Holder path is dead in OPTIMISED. Only path variants, which never run the value form, still reach
  the tail emitter there.
- Bullet 3's "replaces nothing" therefore holds only for CONSERVATIVE and path variants. In both of
  those the tail emitter stays as it is.
- `TailDepthTest`'s Label control (ADR-0039) holds in CONSERVATIVE only. In OPTIMISED the same run
  fits a 512 KiB thread, because a Label that no outer Break names is value-eligible. The OPTIMISED
  control is a fence that breaks to an outer label (`Label(k, If(no, Break(outer), cond))`), which
  may break out and so overflows in both modes. This re-pins behaviour this amendment changes, with
  a stricter OPTIMISED assertion; it is not a weakening (grilling-adviser, 2026-11-15).

**`RunForm` (C4)**
- `RunForm {PUSH, VALUE}` is a public enum in `query.compile`, read as
  `Executable.form(int function, int id)`, with `-1` for main.
- `explain()` prints `form=push|value`.
- The tail emitter is a fast path of the push form, not a form of its own (ADR-0043).

**Considered**
- A default `eval` through a Holder: its extra frame per op undercounts units.
- The tail emitter first: a Holder per run for nothing, and an exception in the uniform rule.
- `eval` only in value positions: the form would depend on the parent.
- Value-form unit weights now: unmeasured, and an undercount overflows the stack.
- The name `Form`: GLOSSARY "Form" is a generator's command line.

## Amendment (ticket #118)

Accepted by the architect under `/run-spec` #106. It comes from the designer's draft and the
grilling-adviser's rulings T1–T4, made for the owner under the delegation of 2026-11-14.

- **Tail positions.** Only a function body has them. From its root they pass to:
  - the right of a Pipe whose left side is at most one;
  - the right of a Comma or an Alt;
  - the branches of an If whose condition is at most one;
  - the body of a Bind or Binds whose every value is at most one.

  Required: `def f: if . > 3 then . else ((.+1),(.+10)) as $x | $x | f end; [0|f]` →
  `[4,13,12,11,10]` (jq-ref).
- **Call kind.**
  - It is a fact by freeze id in `query.analysis`: PLAIN, RECURSIVE, TAIL_SELF or TAIL_GROUP. It is
    read as `Executable.callKind(function, id)` and printed as `call=`.
  - A free slot, a free Break or a ParamCall in a closure argument makes the call fail condition 4.
    An exception: a bare ParamCall passed on, and #117's trailing values and closures, still pass.
  - CONSERVATIVE stores no tail kind (ADR-0018).
- **The jump.**
  - It builds the callee's frame, which holds the value and closure arguments, and ticks one step.
    It then returns true from `run`, NONE from `eval`, or an answer of true on the driver.
  - It enters no activation and adds no units.
  - The entry loops of `CallOp.run` and `CallOp.eval` take a pending jump in the stack forms, and
    `CallOp.Active.resume` takes it on the driver. A single `CallOp.enter` was the design. It was split
    in 180b412 because a helper frame per call level broke DeepStackFreshJvmTest.
  - **Where a jump is taken** (rulings J1–J5, 2026-11-15; F-104): the jump records only itself,
    its activation's frame and the input. The callee's frame is built at the take, not at the jump.
    Only the entry loop running in that same frame takes the jump and builds the frame; every other
    loop leaves it pending. The rule is the same on the stack and on the driver. A left-side call that
    may break out is at most one and still push form, so the frame check on the stack is live code
    (J3). In `eval` the check cannot see a foreign jump today, because eval has no sink and F-56 makes
    a left side that runs on after its result MANY; it stays as written (J5). The same entry checks
    `jumpFrame() == frame` in every form, so that later forms cannot take a jump by mistake.
- **Bullet 1 on passing closures now reads:** "A closure that passes is bound at the jump with no
  defining frame. So it is an immutable reference, as cheap to carry as a value, and a chain of jumps
  keeps no earlier frame alive. Group jumps and permuted closure parameters stay jumps."
  - Frame-free binding at every call, and frame reuse for TAIL_SELF, wait for spec 6 and a measured
    win.
- **Oracle.**
  - A differential case with the key `limit recursion-depth n` runs every compile mode under `n`.
  - Its conservative and trampoline runs pass only as `Termination(BUDGET, RECURSION_DEPTH)`, so the
    conservative kind is pinned.
  - Reference jq ignores the key.
- **Considered:**
  - separate argument arrays on the pending jump: a second copy per jump;
  - the caller's frame as the defining frame: O(n) heap along a chain of jumps;
  - tail kinds from CONSERVATIVE's many facts: `repeat` would jump there;
  - a CallOp flag instead of a JumpOp: a branch on every call.
