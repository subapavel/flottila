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
