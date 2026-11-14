# Recursive and over-cap calls survive as a Program; specialisation is a query-core pass with its own cap; each activation has its own slot cells

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); amends ADR-0005 (`Call`, `ParamCall`, `Program`; 20 kinds built), ADR-0006 (lifting, passes, the specialisation cap), ADR-0007 (fact keys) and ADR-0040 (recursion; query-core's first user); over-cap calls are F-54's

Spec 2a inlined every call in the Resolver and made a call into a recursive group a Stub (ADR-0040).
So no `Call` reached query-core, and ADR-0006's pass order was left for 2b to re-check. 2b runs
recursion. Calls must survive into the IR, specialisation needs a home and a cap, the analyses need
node keys that value-equal records cannot give, and a recursive body needs slots of its own.

## Decision

- **The Resolver keeps 2a's inlining.** A call into a recursive group becomes a `Call`, and so does
  a call over the inline cap, past `MAX_IR_DEPTH` or at the 512th nested expansion (F-54). Every
  other call is inlined as ADR-0040
  says. The callees are lifted into `Program(main, List<Function>)` in query-core. A `Function`'s
  captured variables and enclosing closure parameters come after its own parameters (ADR-0006).
  `Program` and `Function` are not node kinds.
- **Nodes**: `Call(src, fn, closures, values)`. `fn` names the callee in the program's list
  (ADR-0011's function id), `closures` are nodes of the caller's scope, and `values` are atomic
  (`Identity`, `Var` or `Const`, ADR-0005). `ParamCall(src, param)` runs the enclosing function's
  closure parameter `param` on its input. 2b builds 20 of the 22 kinds; `PathOf` and `Update` come
  in spec 3 (F-52).
- **Pass order**: resolve + inline → verify → specialise (skipped under CONSERVATIVE, ADR-0018) →
  verify/freeze → analyses. The first pass is the Resolver's; the others are query-core's. The
  IrVerifier runs after every pass (ADR-0005). There is no fold pass (F-57).
- **Specialisation** is a query-core pass over the `Program`, as ADR-0006 defines it. It has its own
  cap, `SPECIALISE_CAP` in query-core: 16 384 nodes, unless a measurement says otherwise (the
  largest specialised node count over the case census and the prelude). A (group, argument tuple)
  is specialised whole or not at all, in source order of its first outside call; one that does not
  fit keeps its real closures. Reason: if specialisation shared one budget with the inline cap, a
  filter over that cap would leave `until`, `while` and `recurse(.+1)` unspecialised, and they
  would hit the depth budget (jq-ref: `last(limit(3000000; 0|recurse(.+1)))` → `2999999`).
- **Fact keys**: freeze numbers each function's nodes in pre-order into an identity map, so the
  order is deterministic. Each fact is an array by id. This is sound because ADR-0005 forbids using
  a node twice. The map is by identity because records compare by value.
  `Operation` stays `(name, form)` in 2b; cardinality reads an Intrinsic's call form (`Fn0`–`FnN`:
  at most one; `Many`: many). (Spec 3 round 2.)
- **Frames** (M2): each `Call` and each real-closure invocation gets its own slot cells, which ends
  2a's one slot array per run (ADR-0034). A closure's captured slots stay those of the scope it was
  written in (ADR-0006, ADR-0040). Required, recorded with `oracle-record.sh`:
  `def r(f): if .>40 then . else f|r(f|.) end; [0|r(. as $i|(1,2) as $x|(10,20) as $y|$i+$x+$y)]`
  (76 values on jq-ref, starting `[44,54,45,55,43,…`).
  A frame is sized by one stored count and nothing else: its body's IR slots plus the hidden slots a
  backend adds (ADR-0022's owner; spec 3's item paths and walk state). Whatever allocates, copies or
  keeps a frame takes every cell by that count, never a list of slot kinds. A heap form reads and
  writes its activation's own cells, so a hidden slot written before a suspension is read back after
  it. (Spec 3 round 2.)

## Considered options

- **Inlining as a query-core pass, every def lifted** (ADR-0006 as first written): 2a's inlining,
  compile levels, cap and depth bound are built and pinned in the Resolver (ADR-0040, F-44). Moving
  them would change every pin and add no behaviour.
- **One budget for inlining and specialisation**: see the reason above.
- **Specialising part of a group, or in traversal order**: a group would mix copies with real
  closures, and which calls keep closures would depend on the walk rather than the source.
- **A fact map by value**: two equal subtrees at different sites would share their facts.
- **Fact fields in the nodes**: ADR-0007 rejected them.
- **One slot array per run, as in 2a**: nested invocations of one closure, as in the required case,
  would share its cells.
