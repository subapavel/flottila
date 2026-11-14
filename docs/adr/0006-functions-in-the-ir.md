# Functions in the IR: lifted, called by name, specialised away

Status: accepted (2026-11-10, owner, issue #6 "What shape does the IR have, and which analyses run on it?"); amended by ADR-0009 (issue #7); amended by F-31 (the natives sentence covers jq-defined builtins only); amended for spec 2a by ADR-0040 (F-41: inlined by the resolver, #70); `to_entries` a replacing native by ADR-0044 (#85)

Every jq `def` is lifted into the program's function list. Captured variables and enclosing
closure parameters become trailing parameters. A jq closure is a node of the caller's scope, run
with the callee's input wherever the callee calls it. Passing a parameter straight on never wraps
it again. A recursive definition is a function in a call-graph strongly connected component, its
recursive group.

- **Passes**: resolve → inline and specialise → fold → freeze → analyses.
- **Inlining**: every call to a non-recursive function becomes the function's body, with closures
  substituted hygienically.
- **Specialisation**: a closure parameter that every call in its recursive group passes on
  unchanged gets one copy of the group per distinct outside argument (equal arguments share a
  copy), under a node cap set in the spec. Real closures remain only where a recursive call changes
  the closure, or above the cap. This is what turns `recurse(f)`, `while`, `until` and `walk` into
  loops. Spike A's call-per-result shape, specialised, completes 10⁶ levels under tail jumps where
  the unspecialised one overflows, and it runs at about the tail-call shape's speed. Under the
  hybrid at 10⁶, the adviser observed medians of 64–82 ms specialised, 87–123 ms unspecialised and
  52–63 ms for the tail shape (scratch run; to be recorded by "Interpreter or bytecode first, and
  what speed must milestone 1 show?").
- **Call kinds**: `PLAIN`, `RECURSIVE`, `TAIL_SELF` or `TAIL_GROUP`. A call is a tail call only when
  all four of these hold:
  - the callee is in the caller's recursive group;
  - the call is in tail position (right of `Pipe`, `Comma` or `Alt`, a branch of `If`, the body of
    `Bind`; never inside `Try`, `Label`, `Reduce`, `Foreach`, `Collect`, `PathOf` or `Update`);
  - every left context on the way yields at most one result;
  - no closure argument captures a slot of the current activation.

  Tail calls become jumps, and recursive calls count toward the hybrid's K (amended Spike A).
- **Builtins** stay jq's own prelude definitions; the only natives are `range/2`, `inputs`, `last/1` (ADR-0009) and
  `flatten`. jq's results, error order and path behaviour come with the definitions:
  `path(limit(2; .[]))`, `path(skip(1; .[]))` and `path(..)` all work in jq. A builtin becomes
  native later only on a measured win, with its own path variant.

## Considered options

- **A real closure object for every closure argument of a recursive function** (founding §9 as
  written): simpler, but those loops stay in the slower shape and overflow without the trampoline.
- **Native loops for the recursive builtins now** (founding §3/§8): fastest per element, but each
  one needs a hand-written path variant and is one more place to differ from jq.
- **CPS or continuation nodes in the IR**: the recursion strategy belongs to the execution
  protocol; the IR only shows call kinds.

## Amendment (ADR-0009, issue #7)

`last/1` is a native without a path variant. jq 1.8.2 bytecodes it (`gen_last_1` in `builtin.c`),
so `[last(empty)]` is `[]` and `path(last(.[]))` raises (F-9).

Recursive calls count stack units against U (ADR-0011), not calls against K.

## Amendment (F-31)

"The only natives" counts jq's jq-defined builtins: of those only `inputs`, `flatten` and
`to_entries` (ADR-0042 as amended by ADR-0044, #85) are natives. Every builtin jq writes in C or bytecode (`empty`, `not`, `path/1`, `length`, `type`, …)
is a native in jqj.
