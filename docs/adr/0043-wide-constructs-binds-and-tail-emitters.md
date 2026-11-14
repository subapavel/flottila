# Wide constructs bind through one Binds node; and/or runs are balanced; a tail emitter's result is taken first

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #87; designer draft); records F-45; amends ADR-0005 (22 node kinds), ADR-0034 (slot rule; operator and object lowering), ADR-0037 (and/or runs; If), ADR-0038 (patterns, interpolation) and ADR-0040 (value parameters); amended by F-85 (#194: a holder answers a stop)

A construct with n parts lowers to n nested Binds (objects, interpolation, patterns, value
parameters) or to Binds nested in value position (left-associative operators), and `and`/`or` nest
in an If's condition (F-45). A body under n Binds cannot be balanced: a slot is in scope only inside
its Bind's body (ADR-0034). `+` and `*` are not associative (rounding at each step; the right
operand's error first). And in push form a run nests per part whatever the IR shape, because the
rest runs inside each part's emit. So a flat run needs one n-ary node and a way to take a single
result before the rest runs.

## Decision

- **`record Binds(Source source, List<Node> values, List<Integer> slots, Node body)`**, n ≥ 2, means
  `Bind(v1, s1, … Bind(vn, sn, body))`, first outermost. Slot k is in scope in the later values and
  in the body; the verifier rejects anything else and a Binds of one value. It ticks and charges
  nothing of its own. The IR has 22 kinds; both backends and every pass switch over it.
- **The Resolver builds one Binds** past `BIND_RUN` (= `PIPE_CHAIN`, 64) nested Binds, for objects
  (key, value and the key check per pair, BuildObject as body), interpolation (last part first),
  patterns (`destructure`, `alternatives`; each alternative's match is `Binds(steps, Identity)`),
  value parameters (one Binds per expansion, one level), and runs of one left-associative
  arithmetic or comparison operator: the right operands from the top operator down, then the
  leftmost term, then each operator's result from the bottom up, the top operator as body. Each is
  the order the nested form runs. Below `BIND_RUN` nothing changes, so today's pins hold.
- **`and`/`or` runs** of more than `PIPE_CHAIN` terms of one operator are built balanced from
  ADR-0037's lowering, under which both are associative; depth is about 2·log₂ n. No And/Or node.
- **`//` runs** (#94) are read by a loop in the parser and the Binder, and past `PIPE_CHAIN` terms
  built as a balanced Alt: `//` catches nothing, and `a // (b // c)` equals `(a // b) // c` on
  jq-ref over error, empty, false, null and generator terms. 20 000 terms run where jq-ref's parser
  stack is exhausted (DEVIATIONS "Long operator runs").
- **A tail emitter** is decided by node kind alone: Identity, Const, Var, an Intrinsic of a
  one-result call form, or a Pipe, If, Bind or Binds made of tail emitters. It holds no Break and no
  ParamCall, so it never breaks out (ADR-0008). `BindsOp` runs a tail value into a holder, sets its
  slot and loops on; any other value runs the rest inside its emit, one recursion per generator
  part, as today. `IfOp` takes a tail condition's result before it runs the branch. The same in
  both compile modes; no tick or charge changes.
- **The tail emitter is not ADR-0008's value form.** It adds no `eval` form and reads no analysis
  fact (cardinality, may-break-out); it is a local fast path of the push form. The value form and
  its analyses stay in 2b (spec-2 grilling round 1, Q1), which may subsume it.
- **Still nested**: nested constructs (a construct at or under `BIND_RUN` keeps its nested Binds, so eight nested
  64-pair objects pass 512 levels; one past it is a Binds of a level or two, #93, #94), nested
  definitions and nested value parameters keep their F-44 Stub; a generator part recurses once.
- **Repeated object keys** (`Resolver.shaped`) are found through a map by bytes, not a linear scan.

## Considered options

- **An n-ary Bind in place of Bind**: the same behaviour, but it rewrites every Bind site and pin
  under ADR-0034, ADR-0038 and ADR-0041.
- **A balanced `Pipe(Bind(v, s, .), rest)`**: no new kind, but a Var outside its Bind's body breaks
  ADR-0034's slot rule.
- **A fused jq-core operation, like `StepRun`**: it cannot read slots or run IR, covers only `.a`
  parts and hides the order.
- **Passes that walk Bind bodies in a loop**: the IR depth stays n for every later pass and for the
  bytecode backend.
- **Buffering a generator part**: changes laziness, error order and ticks.
- **And/Or nodes**: ADR-0037 rejected them.
- **Binds for every construct of two or more Binds**: one path, but it moves every pin for no depth
  gain under `BIND_RUN`.

## Amendment (F-85, #194, mutation equivalents)

- A holder answers its one result with a stop.
- A tail emitter emits last and returns its sink's answer unchanged:
  - run into a holder, its run answers false exactly when its result came;
  - run into any other sink, it answers that sink's answer.
- The If or Binds that owns the holder takes that result and does not pass this false on (ADR-0039).
- A kind added to the tail set must keep this.
- No tick or charge changes.
- Proposed by the designer and approved by the grilling-adviser for the owner under the delegation of
  2026-11-14.
