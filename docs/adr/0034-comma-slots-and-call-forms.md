# Comma, slots and atomic arguments in the IR; call forms as a sealed CallForm

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #63; designer draft); completes ADR-0005 and ADR-0009; amends ADR-0008 (`run` takes the slots); amended for #64 (`FnN`, `Collect`), see the end; slot rule amended by ADR-0038 (group and parameter slots, #67); state slots by ADR-0041 (#69); Many loop slots and the `to_entries` rate by ADR-0044 (#85); a frame per call activation by ADR-0045 (spec 2b); spec 4's tick table (spec 4 grilling round 1, question 12 and M1); `delpaths` ticks by spec 3 grilling round 1 (question 10)

#63 is the first ticket to lower constructs that #62's parser builds: comma, indexing, slicing and iteration. They need a sequencing node, a way to hold a computed key while its target runs, and the call forms ADR-0009 names. This ADR fixes those IR and protocol shapes; later tickets extend them with their first user.

## Decision

- **`Comma(left, right)`** is binary. It runs left, charges one step on entering the right branch (F-32), then runs right. A stop on the left returns without the tick.
- **`Bind(value, slot, body)` and `Var(slot)`**: each result of `value` sets the slot, and `body` runs on the Bind's own input. A tree has one Bind per slot, and a Var occurs only inside its Bind's body; the verifier rejects anything else. In 2a the slots live per run (`Op.run(RunState, Object[] slots, Object in, Sink)`); recursion (spec 2b) decides its own frames.
- **`Intrinsic(op, args)`** takes atomic arguments (Identity, Var or Const), as many as its form's arity. `Operation(name, CallForm)`, with `sealed CallForm permits Fn0, Fn1, Fn2, Many`; `CallForm.NONE` means no result, and `Many.END` is −1. A Many loop charges one tick per element before its emit.
- **jq's lowering order** (jq 1.8.2 `parser.y`, `gen_index` and `gen_slice_index`): `t[e]` binds `e` outside `t`, so the key is the outer loop; `t[a:b]` binds `a` outside `b` outside `t`. Constant keys and bounds are passed as Const arguments.
- **A navigation `?`** (`.a?`, `.[e]?`, `.[]?`, `.[a:b]?`) selects the step's quiet variant when it is lowered, not a Try (ADR-0008, ADR-0009, F-11). The quiet variant drops only the step's own error; errors from its target or key propagate.
- **Tape walks** charge `tick(entries stepped)` once per walk: `.[n]`, a length on a view, and a field lookup's key scan (ADR-0008).
- `Many.key`, an Operation's cardinality, path rule and quiet flag, and the other ADR-0005/0009 facts join with their first user, not before.

## Considered options

- **An n-ary `Comma(List)`**: ADR-0005 and every later analysis read a binary node.
- **Non-atomic arguments or an `Index(t, k)` node**: both hide the key-outer order. Distributing `t[a,b]` into `t[a], t[b]` works only for constant keys, and `.foo[.baz]` still needs a Bind.
- **Slots in RunState**: RunState is an exported type, the values would stay pinned between runs, and spec 2b needs a frame per activation.
- **A slice view (a third array permit) or a shared backing array**: the first puts ranges into every accessor, and the second needs ADR-0004's twice-size detach. A copy costs one tick per element.
- **`.a?` as `Try(.a)`**: ADR-0008 and ADR-0009 make it the quiet variant.
- **All of ADR-0009's call-form facts now**: nothing uses them before spec 2b or 3, and their code would sit untested under the PIT floors.

## Amendment (2026-11-12, ticket #64)

- `CallForm` also permits **`FnN(RunState, Object in, Object[] args)`**, ADR-0009's form, with #64's object construction as its first user. The args array is new for each call and the operation may keep it (a shape site keeps it as its values array).
- **`Collect(source, body, collector)`** runs `body` and hands each result to the collector; `[e]` lowers to it. It charges no tick of its own: every result after the first has already paid one (Comma's right branch, or a Many per element).
- **Object construction** lowers key-outer, as `t[e]` does: each key binds outside its own value, each pair outside the next; constant keys and Identity or Const values are passed as arguments without a Bind.

## Amendment (2026-11-13, ticket #65)

- **A binary arithmetic operator** `l op r` (`+ - * / %`) lowers to `Bind(r, s_r, Bind(l, s_l, Intrinsic(op, [Var s_l, Var s_r])))`: the right operand is the outer loop, as jq 1.8.2 runs a C function's arguments last-first (`expand_call_arglist`). An Identity or Const operand is passed as an argument without a Bind. `-t` lowers to `Pipe(t, Intrinsic(NEGATE, []))`. The order is pinned by a differential case (`[(1,2)+(10,20)]`) recorded on jq-ref.
- **Ticks (ADR-0008)**: an operator on two scalars ticks nothing. A string concatenation ticks once per started KiB of its result; array difference once per pair compared, stopping at the first match; `split` once per piece; object `+` once per right-hand entry; object merge `*` once per right-hand entry at every level. Also (#65 standards review), each a loop whose length depends on the data (ADR-0008):
  - array `+` ticks once per element it copies;
  - array difference ticks once per left element even against `[]`;
  - `split` ticks once per started KiB of the input times the started KiB of the separator (at least one), as well as once per piece;
  - object merge `*` and object `+` tick once per left entry they copy;
  - Equality ticks once per key it counts when it compares object sizes, once per member pair, and once per started KiB of the shorter string when it compares two strings (as ADR-0037's Order);
  - string repeat ticks once per started KiB of its result;
  - (#72, F-48) `keys` ticks once per member and once per compare of its key sort (at least 1, one
    per started KiB of the shorter key); `tostring` and `tojson` once per started KiB of output
    (`NativeStepsTest`);
  - (#73, F-48) `sort`, `unique`, `min`, `max`, `_sort_by_impl`, `_group_by_impl`, `_unique_by_impl`,
    `_min_by_impl` and `_max_by_impl` tick once per member of the input (with its key, for the
    `_impl` forms) and add the ADR-0037 ticks of each compare and equality check, never one of
    their own per compare (`SortStepsTest`).
  - (#85, ADR-0044) `to_entries` ticks once per entry, on top of a read object's key proof (`NavigationBudgetTest`).
Each rate is pinned by two adjacent counts (`ArithmeticStepsTest`, red at 65b717b, completed by the implementer for the rates added here).
- Considered: the left operand outer (not jq's order); a binary IR node (hides the binds, as `Index` would).

## Amendment (ADR-0044, ticket #85)

- **`Many.loopSlots()`** (default 0, Iterate 1): the slots after a Many's arguments that `first` sets for the later calls of its loop; `IntrinsicManyOp` allocates `args.length + loopSlots` per loop, so `.[]` holds a read object's walk there.

## Amendment (spec 4 grilling round 1, question 12 and M1)

One tick table for spec 4's natives (ADR-0008). A ticket corrects a row only by a finding, and each
rate is pinned by two adjacent counts.

- Byte loops (the trim family, `startswith`, `endswith`, `utf8bytelength`, the formats, `fromjson`,
  `strftime`, `strptime`): one per started KiB of max(input, output).
- `explode`, `implode` and `builtins`: one per element, as every other array copy, not per KiB.
- `_strindices` and string `contains`: `split`'s rate plus one per index.
- Array and object `contains`: one per pair plus Equality's. `bsearch`: one per probe plus the
  compare's.
- Regex (ADR-0053): one per search call, one per started KiB from the search start to the match end
  (or to the input's end when nothing matches, the range `f_match` passes), one per started KiB of
  the pattern at each lookup, compiled or cached (so a run's steps never depend on the cache).
- Scalar math: none, except `jn` and `yn`, once per 1 024 steps of any loop that grows with the
  order (ADR-0051, F-69).

## Amendment (spec 3 grilling round 1, question 10)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

`getpath` and `setpath` tick once per path component; `setpath` also once per element it copies;
`delpaths` once per path plus the ADR-0037 ticks of each compare of its path sort (as `sort`), once
per path component it walks and once per element it copies; it compacts each container once. A
copied level charges CONTAINER plus its slots. PathOf ticks once per component of each path value
it builds. Update ticks nothing of its own.

Amended at spec 3's ticket approval, grilling-adviser for the owner under the delegation of
2026-11-14.
