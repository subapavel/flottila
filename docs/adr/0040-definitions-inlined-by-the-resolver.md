# Definitions are inlined by the resolver in spec 2a; names bind per jq compile level; one inline cap per compile

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #70; designer draft; finding F-41); amends ADR-0006 (passes and lifting, for spec 2a only) and ADR-0038 (which binding errors are reported); Binder order amended by ADR-0042 (native table before the prelude, #71); inline cap amended by F-44 (IR depth, #70); recursion and query-core's first user by ADR-0045, the inline cap, the nested expansion and IR depth by F-54 (spec 2b)

Spec 2a compiles no recursion, so every call that compiles is inlined, as ADR-0006 requires. jq 1.8.2 drops unreferenced definitions (`block_bind_referenced`). It checks an inner compile level only when the outer level had no error (`compile`: `if (bc->nsubfunctions && !errors)`). The jq facts were read from jq-1.8.2 source; #70's red records them on jq-ref, and jq-ref wins.

## Decision

- **Binder (pass 1, jq-core)**:
  - It binds each reference once per lexical occurrence. A call binds by (name, arity), innermost first: nested defs, then params (a later param of the same name wins), then the def itself, then outer scopes, then the prelude (#71), then natives.
  - `true`, `false` and `null` with no arguments stay parse constants.
  - Its function table is the lifted list of ADR-0006 in 2a.
  - It takes over the binding-error collection of #67 and #68, so there is one list. Scoped constructs (#69) register with it.
- **Live definitions**: a def is checked and lowered only if the main filter reaches it, closure arguments included. A self-reference does not keep a def alive.
- **Errors**:
  - The binding errors (`f/1 is not defined`, `$x is not defined`, `$*label-l is not defined`) form one list of blocks, thrown before any lowering. The caret covers the name only, except for `break $l`, where it covers the whole term (jq-ref).
  - A compile level (main, a live def's body, a closure argument of any jq-defined function, user or builtin) reports its own references in source order.
  - It reports its inner levels only when it had no error itself.
  - A C native's arguments stay in the caller's level. A bytecoded native's arguments (`path/1`, `last/1`, `range/2`) are inner levels, like a jq-defined function's (jq-ref, #71 red: `zz1, path(zz2)` reports zz1 only, while `zz1, getpath(zz2)` reports both).
- **Lowering (pass 2)**: a call to a live non-recursive def lowers the def's body in its defining environment plus its params, with fresh slots for each copy.
  - A filter param is the caller's closure, lowered on the callee's input for each use. A param passed straight on is not wrapped again.
  - `$a` is `Bind(a, s_a, …)`, with the first param outermost, and `a/0` stays callable.
  - Captured variables, params and labels resolve to the defining scope. A `break` in a closure targets the caller's label slot.
- **Recursion**: recursive groups are the call graph's cyclic SCCs. A param call is not an edge.
  Lowering a call into a recursive group gives a `Call` to the group's lifted function (ADR-0045). A
  live recursive def that is never expanded compiles.
- **Natives**: `empty`, `error/0` and `not` move into one native table, looked up after every def, so a user def can shadow them. `error/1` is jq-defined (`def error(msg): msg|error;`), so its argument is an inner compile level (jq-ref: `zz1, error(zz2)` reports zz1 only).
- **Inline cap**: `Resolver.INLINE_CAP = 65 536`, an internal constant. Each node lowered inside an
  expansion spends one; past the cap, or at an expansion nested 512 deep, a call is a PLAIN `Call`
  to its function, lowered once from a worklist with real closures, where nesting and depth restart
  at 0 (F-54). The red measures the census's S2a.20 maximum, and the cap is kept if that maximum is
  at most 10⁴.
  - **IR depth** (F-44): the cap also bounds depth. Past `Resolver.MAX_IR_DEPTH = 512` levels of
    built IR, a call is a PLAIN `Call` (F-54) and any other construct makes the filter a Stub,
    because node count alone lets nested expansions stack depth (about 30 000 levels under both
    caps). 512 is the parse depth (#81); a cold 512 KiB thread ran 625 levels and overflowed at 650
    (#70 probe). The red shows 512 compiling and running on the 512 KiB stack of ADR-0017 and 513 a
    Stub, and no ratcheted case becomes a Stub.
  - **Chains** (F-44): every step kind of a postfix chain with constant keys and bounds lowers without linear IR depth, not only field reads, and ticks as the nested form does. Computed-key steps count toward the IR-depth bound.
  - **What counts** (F-44): the depth of the built IR wherever the Resolver builds a node, `expand`'s value-parameter Binds and chain Binds included.
- **Cost**: an inlined call, closure or value parameter ticks nothing and builds nothing; a PLAIN
  `Call` ticks one step (F-54).
- **query-core gains nothing** in 2a. `Call` and the IR function list arrive with their first user
  (2b), which re-checks ADR-0006's pass order: ADR-0045.

## Considered options

- **`Call` nodes and an IR inliner now**: no Call survives 2a, so the backend arms would be dead code.
- **Checking names while inlining**: misses errors in closures that never run, and reports one error per copy.
- **Detecting recursion from the expansion stack**: wrongly flags `f(f(.))`.
- **A Stub for every live recursive def**: stubs `k(r)`, which jq runs.
- **The cap as a `Limits` field**: public API with no user yet.

## Amendment (ticket #114)

Accepted by the architect under `/run-spec` #106, from the designer's draft and the grilling-adviser's
rulings K1–K4, Q1–Q4, D1 and D2, made for the owner under the delegation of 2026-11-14. It amends
"Inline cap", "IR depth" and "Cost" (F-54). "Past the cap" means "would pass": the choice is made
before the call is lowered.

- Before it lowers a call of a live non-recursive definition, the Resolver reads the call's
  **expansion measure**: the nodes the expansion would spend and the levels it would reach, closures
  and nested expansions included. It inlines the call only if `inlined + nodes ≤ INLINE_CAP` and
  `levels + reach ≤ MAX_IR_DEPTH`. A call whose expansion would pass either bound is a PLAIN `Call`,
  and so is the 512th nested expansion.
- A PLAIN call's definition is lifted once and lowered from the worklist. Every later call of it is a
  Call, even one whose expansion would fit.
- No call is expanded twice. A compile lowers at most `INLINE_CAP` nodes inside expansions, plus each
  text once.
- The measure is computed once per compile, callee-first, by a loop rather than by recursion. It is a
  linear form of nodes and a max-plus form of levels over the filter parameters in scope. At each
  call site it takes the larger of the inlined form and the lifted form: a Call plus each closure
  argument lowered once, because `invoke` lowers a closure for every filter parameter, used or not.
  For nodes, the sum of the two forms stands for the larger. A fold counts as unfolded.
- The choice is greedy and top-down: the outermost call that does not fit is lifted (Q1). It may
  lift more functions than needed. Tests pin results and the node bound, not the number of lifted
  functions.
- `spend()` keeps its Stub as a backstop (F-90). Any construct other than a call that goes past
  `MAX_IR_DEPTH` stays a Stub. A PLAIN call ticks one step.
- Considered and rejected:
  - An exhaustion switch. Expansions already in progress and closure chains lower past the cap:
    2^k nodes for `def fi(g): f(i-1)(g|g)`.
  - Retrying after TOO_DEEP. It expands a call twice.
  - Depth that ignores closures. `g600` stays a Stub.
  - Inlining a lifted definition again where it fits. Each definition would have two shapes, and the
    only gain is speed.
  - Lifting as late as possible (the innermost call that overflows). It lifts fewer functions but
    needs a second measure and a second property. Deferred to spec 6.
