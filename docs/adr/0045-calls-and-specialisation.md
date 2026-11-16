# Recursive and over-cap calls survive as a Program; specialisation is a query-core pass with its own cap; each activation has its own slot cells

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 2b round 1); amends ADR-0005 (`Call`, `ParamCall`, `Program`; 20 kinds built), ADR-0006 (lifting, passes, the specialisation cap), ADR-0007 (fact keys) and ADR-0040 (recursion; query-core's first user); over-cap calls are F-54's; parameters, captures and frames amended for #107; label captures by the grilling-adviser for the owner (2026-11-14); freeze and call groups in query-core by ticket #112

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
  largest specialised node count over the jqtest and differential filters and the prelude). A (group, argument tuple)
  is specialised whole or not at all, in the order of its first outside call: by its Source (a lifted
  body's being its placing call's), then the Program's pre-order (#117); one that does not
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
  closures, and which calls keep closures would depend on the walk rather than the source. The
  Program's pre-order only breaks ties between equal Sources (#117).
- **A fact map by value**: two equal subtrees at different sites would share their facts.
- **Fact fields in the nodes**: ADR-0007 rejected them.
- **One slot array per run, as in 2a**: nested invocations of one closure, as in the required case,
  would share its cells.

## Amendment (ticket #107)

Approved 2026-11-14 by the architect under `/run-spec` #106 from the designer's draft. The label
capture (C4) was proposed by the grilling-adviser for the owner under the delegation of 2026-11-14.

- **Parameters**: in `Function(source, closures, values, body)`, slots 0..values−1 are its value
  parameters, bound by no node (ADR-0038), and `ParamCall(source, j)` runs closure parameter j.
  - Its own parameters come first: a filter parameter as a closure, and a `$name` parameter as a
    value, plus a closure only where the body calls `name`.
  - Its captures follow, in Binder order: the declarations outside it that its text or its callees
    reach. A variable is a value, a filter parameter is a closure, and a label is a value, its token
    (ADR-0005: "captured like a variable"). A Break in the body names the token's parameter slot.
  - The caller binds `$name` arguments around the Call, the first outermost (ADR-0040), and passes a
    ParamCall argument on without wrapping it. The kind of a Call (PLAIN, RECURSIVE) is not in the IR.
- **Frames**: a call activation's frame is its slots followed by its closure cells, under one count.
  - A closure argument that binds slots runs in a frame of those slots plus a link in cell 0 to the
    frame it was written in. One that binds no slot runs in that frame.
  - Each Call and each real-closure ParamCall ticks, enters the recursion-depth count and leaves it
    however it ends.
  - `RunState.begin` takes the recursion depth (amends ADR-0030). Only `Executable.run` turns a
    `StackOverflowError` into `Termination(STACK)`. That is a backstop until S2b.4 and S2b.5.
- **Numbering** of each function's nodes moves to the first ticket that reads it (S2b.9, S2b.14).
- **Considered**:
  - a frame copy per closure call (O(frame));
  - restoring a closure's cells after each call (LIFO only);
  - capturing the whole scope (a copy per call);
  - `$name` bound in the callee (a closure call per value);
  - a label passed as a Break closure (a `break` would tick by whether its function was lifted,
    against ADR-0039's cost);
  - an untick rule for a Break closure (a type test on every ParamCall).

## Amendment (ticket #112)

- Freeze (`Numbering`) and the recursive groups (`CallGraph`) live in query-core's
  `query.analysis`. `explain()` prints their ids and groups and keeps none of its own (#108's
  printer copy goes). Facts travel with the `Executable`.

## Amendment (ticket #117)

Accepted by the architect under `/run-spec` #106. It comes from the designer's draft and the
grilling-adviser's rulings S1–S5, made for the owner under the delegation of 2026-11-14.

**What is copied**
- Specialisation copies a lifted function's group, PLAIN ones included (S1), once per (group,
  argument tuple).
- An invariant parameter is a closure parameter that every call in the group passes on as a bare
  ParamCall of the matching parameter, one per function.
- The tuple is the outside call's argument for each invariant parameter. A bare ParamCall argument
  stays a real closure.
- Copies are not specialised again, and originals that no call reaches stay in the Program.

**When two tuples are equal (S3)**
- Two tuples are equal when they have the same shape, with Source ignored. Slots a tuple binds
  itself are renamed, free references are numbered by first occurrence, and constants and
  Operations are compared by identity.
- So arguments with literals get a copy each: `[limit(2;0|recurse(.+1))],[limit(2;0|recurse(.+1))]`
  makes two copies. Reference jq gives `[0,1]` twice.
- A hook on jq equality would be wrong, because jq-ref prints `[1.0,1,1.000]` as written. The hook
  waits for spec 6.

**Free references (S4)**
- A free slot becomes a trailing value of the copy, and so does a free Break's token. A free
  ParamCall becomes a trailing closure. This is how captures already work (#107).
- A copy that holds a free Break may break out.

**Order and cap (S2, S5)**
- Outside calls are taken by Source, then by the Program's pre-order. Copies are appended in that
  order.
- A key is built if `spent + nodes ≤ SPECIALISE_CAP`. Otherwise it is refused at all its calls.
- The cap is 16 384. If the census shows a refusal, the cap becomes the next power of two at or
  above the largest demand, up to `INLINE_CAP`, except for a filter at the cap by design (below). A refusal above that is a finding; its ruling changes
  the pass or names the filter here.
- Refused by design (F-94, F-96): `over-cap/param-600`, `over-cap/param-unused-600`,
  `over-cap/closure-doubling-20`, `specialise/order-param-chain-then-recurse`. Their demand grows with the chain by construction, and #114
  requires them with real closures. The census asserts that the filters refused under the cap are
  exactly these, that each is also refused under `INLINE_CAP`, and it computes S5's number over the
  other filters only. An entry needs a finding and a line here; a writer never edits the list.
- At the cap by design (F-112, owner 2026-11-16): `bench/cap-specialise-over.1k`. It sits one key past
  the cap so that Table B shows a refused specialisation beside its pair `bench/cap-specialise.1k`.
  The census asserts that each entry is refused under `SPECIALISE_CAP`, refuses nothing under
  `INLINE_CAP`, and that its pair refuses nothing; S5's number is computed over filters in neither
  list. When the cap moves, the entry fails and gets a `-v2` id. The same rules as for the list above
  apply to edits.
- A filter whose earlier keys exhaust the cap leaves each later key that does not fit refused, a
  prelude loop's included. That key runs with real closures, which the depth budget counts (F-96).

**What explain() shows**
- The Executable carries a `Frozen` record: the specialised program, its Numbering, CallGraph and
  origins, and the specialisation counts.
- `explain()` prints from that record. A copy's line reads `copyOf=fn<o>`, and the closing line reads
  `specialised=<spent> refused=<keys>`.

**Re-pins after #116 (grilling-adviser V1–V5, W1–W4, 2026-11-15)**
- #116's PUSH shapes that called a non-recursive function with a closed closure now get a copy, so
  they run VALUE. The shapes became recursions with a non-invariant argument, and the old programs
  moved to `SpecialiserTest`, which pins the copy as VALUE. `everyTailEmitterIsValueForm` reads the
  frozen program, copies included.
- The over-cap run pin uses a comma ladder. The pipe ladder runs wholly on the driver at `U_f ≤ 0`
  and holds its PLAIN activations (F-100, #198).
- Cross-mode step equality holds at cap 0. With copies, steps are at most CONSERVATIVE's.
- These re-pin behaviour this amendment changes, and no assertion was dropped; they are not a
  weakening.

**Considered**
- Closed arguments only: `range/3` would keep real closures, against ADR-0006.
- Refusing a key with a free Break: an extra rule, where #107 already passes tokens.
- Pruning unreached originals: deferred.
- Copying copies.
