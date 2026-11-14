# IR: one sealed node family in query-core; formats reach it as Operation data

Status: accepted (2026-11-10, owner, issue #6 "What shape does the IR have, and which analyses run on it?"); amended by ADR-0008 and completed by ADR-0009 (issue #7); amended by ADR-0022 (issue #8: `keepsArgs`, in-place operations); clarified by ADR-0032 (the AST is not a second IR); completed by ADR-0034 (Comma, Bind and Var, atomic Intrinsic arguments, #63); amended by ADR-0037 (If, Alt, Truth, #66); AltBind's fields and catch rule completed by ADR-0038 (#67); Try, Label and Break fields by ADR-0039 (#68); Reduce, Foreach, Advance by ADR-0041 (#69); `keepsArgs` declared on the binder's natives until spec 3 by F-47 (#72); node list corrected by F-52 (`Empty` is an Operation; `Advance`, `Binds`); `Call`, `ParamCall` and `Program` by ADR-0045 (spec 2b); `PathOf` and `Update` (kinds 21 and 22), `Update.Kind`, `Facts` and `Tracking` by ADR-0048 and F-60 (spec 3); `reads` means the input stream by F-79 (spec 5 round 2, grilling-adviser for the owner under the delegation of 2026-11-14)

The IR is one `sealed interface Node` in `query-core` with 22 record kinds (21 as first written;
F-52 corrects the count), each carrying its source position. Both backends and every analysis switch
over it exhaustively. A format such as jq adds nothing to the family: its navigation steps and
native builtins reach the IR as `Operation` records called through an `Intrinsic` node. The reason
is founding §14 (`query-core` never mentions JSON or jq) together with exhaustive backends. It is
**not** javac: a `non-sealed` arm for `jq-core` records compiles on javac 25.0.5 (checked by the
grilling adviser on #6). We reject that arm because each jq node would then carry its own
interpretation and bytecode emission.

- **Nodes**:
  - Flow: `Identity`, `Const` (a built value, never a view), `Pipe`, `Comma`, `If`, `Alt` (`//`).
    `empty` is the Operation `Intrinsic(empty)`, not a node (F-52).
  - Abrupt: `Try`, `Label`, `Break`.
  - State: `Var`, `Bind`, `Binds` (ADR-0043), `Reduce`, `Foreach`, `Advance` (ADR-0041), `Collect`.
  - Functions: `Call(src, fn, closures, values)`, `ParamCall(src, param)`; the functions they name
    live in `Program(main, List<Function>)`, which is not a node kind (ADR-0045).
  - Paths: `PathOf(src, body, paths)` and `Update(src, kind, lhs, rhs, paths)` with
    `Kind {MODIFY, ASSIGN}` (`|=` and `=`; ADR-0048).
  - Destructuring alternatives: `AltBind` (`?//`). It catches errors (not breaks, ADR-0008) from the
    patterns, the body and everything downstream while a later alternative remains (ADR-0008, ADR-0038). On
    `[[1]]`, `(.[] as [$a] ?// $a | $a) | if . == 1 then error("x") else . end` gives `[1]`
    (observed); a `Try` in that place lets the error through.
  - Format: `Intrinsic(Operation, args)`.
  - Built at the end of 2a: 18 kinds; at the end of 2b: 20, with `Call` and `ParamCall` (ADR-0045).
    `PathOf` and `Update` arrive in spec 3a as kinds 21 and 22 (F-52, ADR-0048).
- **Atomic arguments**: `Intrinsic` arguments and `Call` value arguments are `Identity`, `Var` or
  `Const`. Operators, object construction, string interpolation, `.[e]`, `$x` parameters and
  destructuring lower to nested `Bind`s, so the cartesian order is explicit in the tree. jq's order
  differs by construct, and each construct gets an oracle case. Observed on jq-ref 1.8.2:
  - `(1,2)+(10,20)` → `11,12,21,22`;
  - in an object the first key is the outer loop;
  - in string interpolation the last part is the outer loop.
- **Assignments** `=`, `op=` and `//=` lower to a `Bind` of the right side around an `Update`:
  ASSIGN for `=`, MODIFY for the others (ADR-0048, F-60).
  `.a?` lowers to a `Try` that folds into the operation's quiet variant in the value variant only
  (ADR-0009, F-11).
- **Labels** are a per-activation token, captured like a variable. On jq-ref,
  `def f(g): label $l | (g, 10, (if . < 1 then (. + 1 | f(break $l)), 20 else 30 end)); [0 | f(empty)]`
  → `[10]`.
- **`Operation`** fixes only what passes read: name, cardinality, path rule (none / step / same),
  navigation step (key, index, all, dynamic; one `PathRule` in `Facts` since ADR-0048), `pure`,
  `reads` (the input stream), `keepsArgs`, and whether a
  quiet (non-throwing) variant exists. How an operation is called is left to "What is the
  execution protocol?", whose ADR completes this one.
- **Verifier**: an `IrVerifier` runs after every pass. The IR must be a tree with no node object
  used twice; slots and labels must be bound; `ParamCall` may appear only inside a function;
  arities must match; arguments must be atomic; no `Const` may hold a view.

## Considered options

- **A `non-sealed` arm for jq nodes in `jq-core`**: it compiles, but it moves backend code into the
  format, against §14.2.
- **A jq-level node family lowered into the core nodes**: a second IR and a second verifier.
- **Lowering `?//` to `Try`**: one node fewer, but errors downstream would propagate, a deviation
  from jq with no reason behind it.
- **Navigation as node kinds** (founding §7's list): replaced by `Intrinsic` plus navigation-step
  operations, to keep §14.

## Amendment (ADR-0008, issue #7)

`AltBind` catches errors and not breaks. A break passes `try`, `?` and `?//` and is listed in
`DEVIATIONS.md`. ADR-0009 gives the call forms of an `Operation`. In path mode `.a?` is the step's
quiet variant, which still raises path-validity errors, and not a `Try` (F-11).

## Amendment (ADR-0022, issue #8)

- `keepsArgs` means "the result may share a container or string with an argument or with the
  input, at any depth" (`sort` and `tostring` on a string keep).
- An `Operation` may carry an in-place variant, `InPlace.apply(ws, owner, target, a, b)`, used only
  at a licensed site (ADR-0022).
