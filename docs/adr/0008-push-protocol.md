# Push protocol: emit's false stops, a break is a Workspace token, two exception classes

Status: accepted (2026-11-10, owner, issue #7 "What is the execution protocol?"); amends ADR-0004, ADR-0005 and ADR-0007; amended by ADR-0015 (issue #11) and ADR-0017 (issue #13); amended by F-32 (two more tick sites); amended by ADR-0030 (run state, #59); amended by ADR-0034 (`run` takes the slots, #63); catch set and label tokens amended by ADR-0039 (#68); reduce and foreach tick at their producer, ADR-0041 (#69); the key proof's ticks, ADR-0044 (#85); Equality's key index, ADR-0044 (#95); a number token's re-scan is uncharged until #97, F-50

Results are pushed. `interface Sink { boolean emit(Object v); }` lives in `query-core`. If emit
returns `false`, that is a stop: the producer emits nothing more and returns `false` at once. The
interpreter has two forms, `boolean run(Frame, Object in, Sink)` and a value form
`Object eval(Frame, Object in)` for cardinality one or at most one, which returns `NONE` for no
result. The bytecode backend fuses `a | b`, and a `Sink` object exists only where results cross
into separately compiled code.

- **Label and break use no exceptions.** Each `Label` activation takes a fresh token, held in a
  slot and captured like a variable. A `Break` sets `ws.breakTo = token` and returns `false`. The
  `Label` whose token matches clears the field and returns `true`; any other `Label` passes
  `false` on. A host stop is `false` with no token.
- **A subtree that may break out runs in the push form.** A subtree may break out if it holds a
  `Break` to a label outside it, holds any `ParamCall`, or calls a function that does either. A
  fixpoint per recursive group computes this, next to cardinality (amends ADR-0007). Such a
  subtree never uses the value form. On jq-ref, `[label $l | def f: if . > 2 then break $l else
  (. + 1 | f) + 1 end; 5, (0 | f), 6]` gives `[5]`.
- **Two exception classes**, both subclasses of `sealed EvaluationException extends
  RuntimeException` and both created without a stack trace:
  - `FilterException(Object value)` is every jq error. `error(null)` counts: `try error(null)
    catch .` gives `null`.
  - `Termination(cause, exitCode, value)`, with cause `HALT`, `BUDGET`, `STACK` or `STALE_VIEW`,
    ends the evaluation. No `try`, `?` or `?//` catches it. On jq-ref, `try halt_error catch 1`
    prints nothing and exits with rc 5. A budget overrun carries a jq error message, but a filter
    cannot swallow its own budget.
- **Catch sets:**
  - `Try` catches a `FilterException` raised by its body only. Errors downstream or in the handler
    propagate. The mark is per activation: `def f: try (if . > 0 then . - 1 | f else . end) catch
    "caught"; 2 | f | error("down")` raises `down`, while an outer activation's `Try` still
    catches an error in its own body (`def f: try (if . > 0 then (. - 1 | f) | error("down") else
    . end) catch "caught"; 1 | f` → `"caught"`). After a catch the body is abandoned:
    `[try (1, error(2), 3) catch .]` gives `[1,2]`.
  - `AltBind` catches a `FilterException` from the patterns, the body and downstream while an
    alternative remains. It catches errors, not breaks (amends ADR-0005).
  - `.a?`, `.[e]?`, `.[]?` and slices with `?` are the step's quiet variant (ADR-0009). `term?` on
    any other term is a `Try` with an empty handler. The two differ in path mode.
  - `//` catches nothing (jq-ref: `[error("x") // 1]` → error, rc 5).
  - No catch set holds a break or a `Termination`. A break passing `try`, `?` or `?//` is a listed
    deviation.
- **Node rules:**
  - `Reduce` runs once per init result. An update with no result sets the state to `null`, and
    with several the last one wins.
  - `Foreach` also takes init as a generator. Every update result becomes the state and runs the
    extract. An update with no result sets the state to `null`:
    `[foreach range(4) as $x (5; if $x==1 then empty else .+1 end; .)]` gives `[6,1,2]`.
  - `Alt` runs its right side only when the left gave no truthy result.
  - A stop anywhere returns `false` at once.
- **Budgets:** a step budget and a wall-clock deadline, plus the nesting-depth, bytes-built,
  bytes-written and recursion-depth budgets (ADR-0011).
  - Steps are charged only at sites the IR defines: a loop back-edge, a tail jump, a `RECURSIVE`
    or real-closure call. So both backends stop at the same step. A trampoline bounce charges no
    step.
  - Every native loop whose length depends on the data charges ticks against the step budget, per
    element or per KiB. This
    includes the `input` reader, the writer and `detach`.
  - `ws.tick()` is `if (--fuel < 0) refuel()`. Refuel reads the host clock every 4096 ticks; the
    interval is measured in "Interpreter or bytecode first, and what speed must milestone 1 show?".
  - Ticks do not bound regex backtracking. That is left to the regex library's limit and "What are
    the public API, the host SPI and the sandbox?".
- **Streaming that a host enables** (amends ADR-0004): the engine ends an element's document when
  `rest` returns for that element. A streamed result view is valid only during its sink call.
- **Kind dispatch** uses pattern `switch` in cold code and exact-class `instanceof` in hot
  operations. There is no `int kind()`. "Interpreter or bytecode first, …" measures both.
- **The Workspace's protocol state** (label counter, `breakTo`, fuel, units) is reset when an
  evaluation starts.

## Considered options

- **Exceptions for break and stop** (jackson-jq, jaq): against founding §8, and `first` and `limit`
  would throw on every use.
- **jq's break as a catchable `{"__jq":n}` error**: `first` over a `?//` then gives two results
  (`[first(.[] as [$a] ?// $a | $a)]` on `[[1]]` → `[1,[1]]`), and `n` is a per-process counter
  that an embedded Workspace cannot reproduce.
- **One exception class with a fatal flag**: same behaviour, but every catch tests the flag.
- **Catchable budget errors**: `repeat(try … catch .)` runs past the deadline.
- **A BREAK sentinel returned by the value form**: one compare on every return, in hot code.
- **Deadline only** (GATE check 4 as first written): there is no reproducible cut-off, and budget
  tests would depend on the clock.
- **A timer thread setting a flag**: one thread per host, and no gain over reading the clock at
  refuel.

## Amendment (ADR-0015 and ADR-0017, issues #11 and #13)

- `Workspace.tick(int n)` charges n ticks at once; the reader and the writer charge one per KiB.
  The fuel reset comes before the main document is read.
- Refuel reads `System.nanoTime()`, not the host clock, and the thread's interrupt flag.
- A new cause `CANCELLED` ends a run whose thread was interrupted; the flag is set again.
- A regex match is bounded by its own `regexMatchTime` budget (a catchable jq error, as jq's retry
  limit) and by the remaining deadline (a termination).
- The nesting-depth budget is not a termination: the parse depth limit is an ordinary jq error
  (F-13), and built values have no depth limit.

## Amendment (F-32, spec 2)

Steps are also charged on entering `Comma`'s right branch and each `AltBind` alternative after
the first.

## Amendment (ADR-0044, ticket #85)

- A read object's key proof ticks once per key entry (one per started KiB of a longer key) and once per compare of its fallback sort (one per started KiB of the shorter key, at least one), the table's probes none; the writer's proof charges through `tickWritten`, and a host accessor's proof (`size()`, `forEach`, Detach) charges no run.
