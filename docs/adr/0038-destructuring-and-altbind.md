# Destructuring lowers to Binds; `?//` is an AltBind over group slots; host variables take the leading slots

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #67; designer draft); completes ADR-0005 (AltBind's fields); amends ADR-0034 (the slot rule); AltBind's rethrow amended by ADR-0039 (#68); which binding errors are reported amended by ADR-0040 (#70); state slots by ADR-0041 (#69)

jq 1.8.2 shares one variable per name across a `?//` chain (`bind_alternation_matchers`). ADR-0034's rule of one Bind per slot cannot express that without copying the body. Every behaviour below that was read from jq's source is recorded on jq-ref by #67's red. Where jq-ref differs, jq-ref wins and only the resolver changes.

## Decision

- **`E as $x | body`** is `Bind(E, s_x, body)`, with lexical scope and the inner name shadowing the outer one. An unbound `$n` is jq's compile error over its token; all such errors are collected, in jq's order (ADR-0036). A Var operand is passed as an argument without a Bind, like Const and Identity.
- **Patterns** lower to Binds of non-quiet steps in jq's matcher order:
  - array elements last first;
  - object entries in source order, each key run on the value at its own level;
  - an absent key or index, or a null, gives null; a non-container raises #63's message.
  - Without `?//`, key expressions see only the scope outside the pattern, and the body sees each name's first store.
- **`AltBind(source, value, slot, alternatives, slots, body)`**, with `Alternative(match, from)`:
  - `slots` holds one group slot per name across all alternatives.
  - At each result of alternative k, each group slot is set from that alternative's own slot, or null.
  - A FilterException from the match, the body or downstream starts alternative k + 1 while a later one remains. The last alternative's error propagates.
  - A stop, a break or a Termination returns at once. An error from E itself propagates.
  - One tick per later alternative entered (F-32); nothing is charged.
  - Inside an alternative, a group name means its latest store so far, or null.
- **Slots**: a slot is bound by one Bind, by one AltBind (its value slot and its group slots), or is a parameter. A group slot is read only in its AltBind's body. Each from-slot is in scope at its match's leaf. The IrVerifier checks all three.
- **Rulings on the red (90e4ac7)**:
  - An Alternative's `from` entry of −1 means null. jq-core never emits it: it binds one Const null slot around the chain instead (#67).
  - Inside an alternative, a group name read before its store resolves to a Const null, and after its store to that alternative's own slot, so a group slot is still read only in the body.
  - A parse-time error (ADR-0036) suppresses the binding errors, as jq-ref shows for `{(1): $a} | $b`.
- **Host variables**: the declared names, sorted, take parameter slots 0..k−1 in the root scope. Through `Compiler.compile(Node, int parameters, CompileMode)` and `Executable.run(RunState, Object[] parameters, Object in, Sink)`, the slots are in scope everywhere and bound by no node. A declared name left unbound gives IllegalArgumentException (ADR-0016). A name in the Bindings that was never declared is ignored.
- **`$__loc__`** is a Const `{"file":"<top-level>","line":N}` (jq's `gen_loc_object`; the line counts LF only). It is a constant for ADR-0036's key fold.
- **String interpolation** is `Intrinsic(Interpolate, parts)`, with the last part outermost. It charges STRING plus the total length before allocating, ticks once per started KiB (ADR-0034 concatenation), and never folds (#67).

## Considered options

- **Copying the body into each alternative**: n times the IR per `?//`, and 2^k when nested.
- **Several Binds per group slot**: breaks the scope check.
- **A Try per alternative**: misses downstream errors.
- **A Destructure node**: hides the matcher order and its errors.
- **Quiet steps**: jq emits INDEX, not INDEX_OPT.
- **Interpolation as a `+` chain**: n − 1 intermediate strings.
- **Host values as compile-time constants**: a compile per binding, against ADR-0016.
- **Slots in RunState**: rejected by ADR-0034.
