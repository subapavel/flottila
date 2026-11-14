# Reduce and Foreach bind a state slot that one Advance takes and refills; range is a Many

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #69; designer draft); amends ADR-0005 (Reduce, Foreach, Advance), ADR-0008 (none of the three ticks), ADR-0034 and ADR-0038 (slot rule); amended by F-43 (#69: the `_state` read, atomic range bounds, the Many args contract); amended by F-85 (#194: the shared empty args array, wording)

jq 1.8.2's `gen_reduce` and `gen_foreach` keep the loop state in a fresh variable. The pattern's matchers wrap `LOADVN state; update; STOREV state` (LOADVN loads the value and then nulls the variable). Patterns lower to Binds around a body (ADR-0038), so the update must be a node beneath them. The jq facts were read from jq-1.8.2 source; #69's red records them on jq-ref, and jq-ref wins.

## Decision

- **Lowering**:
  - `reduce SRC as P (I; U)` lowers to `Reduce(I, s, bindP(SRC, Advance(s, U)))`.
  - `foreach SRC as P (I; U; E)` lowers to `Foreach(I, s, bindP(SRC, Pipe(Advance(s, U), E)))`; the two-argument form has no Pipe.
  - `bindP` is ADR-0038's Bind or AltBind, so `?//` reuses AltBind and Alternative unchanged (ADR-0038 O5).
  - SRC and I see the outer scope; U and E see P's.
- **Run**:
  - For each result r of I: the slot is set to r, then `each` runs on the input.
  - Reduce then emits the slot's value and nulls the slot. Foreach emits each of `each`'s results.
  - Advance takes the value v and leaves null. It runs U on v, and stores and emits each result u.
  - Every U result therefore starts from v, the last one wins, and no result leaves null.
  - Nothing is rolled back when an alternative retries.
  - A false (a break) passes at once, and Reduce emits nothing after one (ADR-0039).
- **State slot**: it is bound by one Reduce or Foreach, set by that node and by its one Advance inside `each`, and read by no Var. The IrVerifier checks all three. Java stack depth follows IR depth, never the iteration count.
- **Cost**:
  - None of the three nodes ticks or charges.
  - ADR-0008's loop back-edge is SRC's own producer: Many per element, Comma per right branch, AltBind per later alternative. That producer has already ticked, so `reduce range(n)` costs n ticks. A filter still cannot run long without a tick.
  - Containers are charged where they are built (ADR-0035).
- **`range/2`** is a jq-core Many:
  - `range(A;B)` lowers to `Bind(A, s_a, Bind(B, s_b, Intrinsic(RANGE, [Var s_a, Var s_b])))`, so the start is the outer loop (builtin.c's bytecode).
  - Bounds that are not numbers raise "Range bounds must be numeric" before any result.
  - It stops when v ≥ end on doubles and adds 1 at each step, as jq does.
  - One tick per value; nothing is charged.
  - The first value keeps its literal form (jq-ref: `[range(1.10;3)]` gives `[1.10,2.1]`, recorded by #69's red), so `Many.value` gains its arguments: `Many.value(RunState, Object in, Object[] args, long cursor)`, amending ADR-0034.
- **`range/2`** joins ADR-0040's native table, so a user `def range(a;b)` shadows it. **`range/1`** is builtin.jq's `def range($x): range(0;$x);` and comes from #71's prelude by inlining, with no resolver special case. #69's cases that need `range/1`, the recorded `reduce-*` depth cases among them, are required at #71's gate.
- **Binder (ADR-0040)**: SRC and I bind in the enclosing scope; P's names are in scope for U and E only. The state slot is no name: pass 2 takes it fresh for each inlined copy.

## Amendment (F-43, #69)

- **The state read**:
  - jq-core reads the state through a `_state` operation. It maps the slot's Java null, the state after an empty update, to `null`.
  - The built lowerings are `Pipe(Reduce(I, s, bindP(SRC, Advance(s, Pipe(_state, U)))), _state)` and `Foreach(I, s, bindP(SRC, Pipe(Advance(s, Pipe(_state, U)), E)))`. No `_state` follows a Foreach: each of its results comes from an update.
  - On jq-ref, `reduce (1,2) as $x (0; empty)` gives `null`, and foreach's next update after an empty one sees `null` (recorded: `reduce/foreach-update-empty-then-next` gives `[[null,3]]`).
  - query-core's null stays no jq value (§14).
- **Bounds**: a range bound that is already atomic (Identity, Const or Var) is passed as an argument with no Bind, following ADR-0034's convention. The order is unchanged: start outer (recorded: `reduce/range2-generators`, where `[range(0,1;3,4)]` gives `[0,1,2,0,1,2,3,1,2,1,2,3]`).
- **The Many args contract**:
  - The call site allocates a new args array for each loop. IntrinsicManyOp does so on every run, except for a call with no arguments and no loop slots (ADR-0044; tested as `width != 0`, F-85), which shares one empty array that has nothing to replace. A future bytecode backend or trampoline (ADR-0011) must do the same.
  - `first` may replace an argument in that array with a form the later calls read more cheaply. Range stores its end's double once, because a NumberView recomputes it on every read (ADR-0012).
  - A Const is never written: only the array's element is replaced.
- **The verifier**: it accepts at most one Advance per loop node. The jq lowering always places exactly one; a hand-built IR loop with none is valid.

## Considered options


- **Pattern Binds inside the update**: a retried alternative would rerun U on the old state.
- **A Destructure node or patterns as node data**: rejected by ADR-0038.
- **State in a Java holder per activation**: Advance under Binds could not reach it without a new protocol parameter.
- **A tick per iteration at Advance**: `reduce range(n)` would pay 2n for what its producer already paid.
- **A range cursor computing start + i**: it rounds once where jq rounds at each step.
- **range as builtin.jq's recursive form**: it needs recursion (2b), and founding §8 makes range a native loop.
