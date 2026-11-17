# Analysis contracts

Status: accepted (2026-11-10, owner, issue #6 "What shape does the IR have, and which analyses run on it?"); amended by ADR-0008 (issue #7); amended by ADR-0022 (issue #8); fact keys by ADR-0045 and cardinality's promise by F-56 (spec 2b); holds by F-77 and root use by ADR-0054 (spec 5); path shape's 10 000-step rule, the path-rule and P6 validity rows and bound values by spec 3 grilling (rounds 1–2; ADR-0048, F-59); may read input, hold sites, a hold's detach and path shape's read rule by spec 5 round 2 (F-79, F-80); the cardinality lattice, rules and fixpoint by ticket #112; may-break-out by ticket #115

Analyses run once on a frozen program and write an immutable side table of facts, keyed by a dense
node id: freeze numbers each function's nodes in pre-order into an identity map, and each fact is an
array by id (ADR-0045). Nothing rewrites a frozen program. An analysis reads only node kinds and
`Operation` fields. **A missed fact costs speed or raises an error, never a wrong value.**

- **Cardinality**: none, exactly one, at most one or many, for every node and function, computed as
  a least fixpoint per recursive group. An error or a break is not a result. "Exactly one" and "at
  most one" also promise that nothing runs after the result; a node that may run on after it is
  "many" (F-56).
  - **The guarantee is two-sided.** "Exactly one" promises one result unless an error or break
    ends the node, so a backend reads it without testing. A property test checks both bounds
    against the interpreter.
- **Path shape**, for every `Update` and `PathOf`:
  - FIXED: a constant key or index chain;
  - WALK: field, index and iterate steps, with guards that yield at most one result;
  - TRACKED: everything else.

  WALK is used only where it equals jq's `reduce path(lhs)` form, and a backend may always run WALK
  as TRACKED. The conditions come from jq-ref:
  - deletes apply after the walk: `(.[0], .[1]) |= empty` → `[3]`;
  - null-creating steps: `null | .a[1].b |= 5` → `{"a":[null,{"b":5}]}`;
  - quiet steps skip: `3 | .a? |= 5` → `3`;
  - an error leaves no partial result.
- **Path validity is an explicit rule.** A path result comes from the tracked position through
  navigation steps and input-preserving control flow, plus the `foreach` item variable that
  `limit`, `skip` and `nth` need. Anything else raises "Invalid path expression", including cases
  jq accepts: `path(. as [$a] | $a)` (jq `[0]`), `path(.a | . as $v | $v)` (jq `["a"]`) and
  `{"a":true} | path(.a | true)` (jq `["a"]`; on `{"a":1}` jq raises too, F-10). These are listed
  in `DEVIATIONS.md`.
- **Root use** reports only whether a filter is *eligible* to stream (`<fixed path> | .[] | rest`,
  where `rest` uses no outer slot and does not read input). Streaming is off unless the host turns
  it on. By default jq-ref prints nothing when a document has a late syntax error, and takes the
  last of duplicate keys.
- **Holds and retention** (amends ADR-0004): `input` documents get their own Workspace tape, so the
  main document stays valid for the whole evaluation (`. as $o | input | [$o, .]` → `[1,2]` on
  jq-ref).
  - A value that may view an input document is detached only when it enters a hold that spans a
    later read: a variable, an accumulator, an array being built, an error payload, or a closure
    or group that reads.
  - An accumulator detaches only its new part, so `reduce inputs as $x ([]; . + [$x])` stays linear.
  - A missed hold raises the stale-view error.
- **Last read**: a slot whose value is read exactly once per binding is marked, so "When may a
  value be updated in place?" may treat that binding as a move, not an escape.
- **Touched fields** is a contract only. Its consumer would be a reader that leaves untouched
  subtrees off the tape, which ADR-0002 does not allow yet. It is built only when "How do the JSON
  reader and writer behave?" adopts that.
- **Constant folding** (the parse-time fold; there is no IR fold, F-57) never folds an impure
  operation or one that reads input. An operation that
  throws while folding stays in place: `1/0` is a run-time error in jq.

## Considered options

- **Facts as fields in the nodes**: every pass would rebuild all nodes.
- **Upper-bound-only cardinality**: "exactly one" would then equal "at most one", and every read
  would need a test.
- **An identity test at each variable read for path validity**: it reproduces jq on `$v` but not on
  destructuring or literals, and built numbers have no identity (ADR-0003).
- **Automatic streaming** (founding §3/§6.7): whether a malformed document gives partial results or
  none would depend on whether the analysis happens to succeed.
- **One shared tape for `input`**: wrong for `. as $o | input | [$o, .]`.

## Amendment (ADR-0008, issue #7)

- **May break out** is one more fact computed per recursive group, next to cardinality. A subtree
  that holds a `Break` to an outer label, any `ParamCall`, or a call to a function that does either
  runs in the push form, never the value form.
- The `reduce` and `foreach` state stays untracked in path mode (ADR-0010).
- The path-validity row for `path(.a | true)` holds only on `{"a":true}` (F-10).

## Amendment (ADR-0022, issue #8)

- **Last read** means read at most once per binding activation, with no loop body (a reduce or
  foreach update or extract, an update's right side, a recursive body) and no context with more
  than one result between the binding and the read.
- **WALK key steps:** a dynamic step (ADR-0005) whose key has at most one result and does not read
  `.` is a WALK step; an object key at run time is a slice (ADR-0022).
- **The in-place licence** is a new fact. Like cardinality, an over-claim gives a wrong value.

## Amendment (spec 5 round 1; F-77, ADR-0054)

- **A hold** is every place a value stays live across a node that may read input, that node's own
  `.` included; this replaces the list of holds above. So a pipe's right side holds its input.
  Required cases: `input | [., input]` → `[1,2]`; on stdin `1 2 3`,
  `def f(g): [g, g]; input | f(., input)` → `[1,2,1,3]`.
- **May read input** is a per-group fact, computed as may-break-out is (the ADR-0008 amendment
  above), from the `reads` fact spec 3 puts on Operation. Holds add no IR kind. A kind that proves
  needed ships its heap form (ADR-0046) and its path variant.
- **Spec 5a detaches every `input` document at its read**, in both compiles. Spec 5b adds the hold
  analysis to the optimised compile; the conservative compile keeps detaching at each read
  (ADR-0018).
- **Root use** is ADR-0054's syntactic test; a filter the test does not prove eligible has
  `needsWholeDocument()` true.

## Amendment (spec 3 grilling, rounds 1–2)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

- **Path shape**: an Update or PathOf whose chain has 10 000 steps or more is TRACKED, a fused chain
  (F-44) counted by its steps. So jq's path depth limits (ADR-0029 as amended) run in TRACKED only.
- **Path validity**: an operation passes a path only by its `PathRule` (ADR-0048). `debug` and
  `stderr` are SAME, and every other native that is not a step is NONE. jq-ref keeps the path
  through `jv_identical` in `path_intact` for `tostring` and `@text` on a string, `tonumber` on a
  number, `delpaths([])`, `setpath([];.)`, `last(.)` and `not|not` on true; each is a
  `DEVIATIONS.md` row. `trim`, `ltrim`, `rtrim` and `tojson|fromjson` get theirs with spec 4's
  natives, and `ltrimstr`, `rtrimstr` and `trimstr` need none (F-70).
- **Bound values** run by their `Tracking` (F-59).

## Amendment (spec 5 round 2)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

- **May read input** uses may-break-out's group fixpoint with one difference: a `ParamCall` may
  read only if some call site, tail jumps included, passes a closure that may read for its
  parameter (one bit per function and closure parameter). This replaces 'computed as may-break-out
  is'. `reads` on an Operation means it reads the input stream; only `input` sets it (F-79).
- **An emit is a read when its consumer may read.** The consumer fact is joined over a function's
  call sites and a closure's `ParamCall` sites; its safe value is true.
- **Hold sites.** Entry: a node detaches its `.` on entry when it may read `.` after a node that
  may read has run. Store: a keeper detaches a value as it keeps it when the keeper lives across a
  node that may read. Keepers are bind, pattern, item and state slots, a Collect's elements, a
  call's value cells, a Try payload, an Update's leaf, and a DYNAMIC key entering a Path or walk
  state. No IR kind or hidden slot is added.
- **A hold's detach** copies the input-document views it reaches and leaves main-document views. A
  state an in-place site wrote is not detached again at its state hold (F-80).
- **Path shape**: an Update whose left side may read is TRACKED, and a read run from a PathOf body
  or an Update's left side detaches at its read.
- **WALK key steps** (the ADR-0022 amendment above): a key that may read input is no WALK step,
  because its Update is TRACKED.
- **Root use**: "uses no outer slot" in the bullet above is replaced by ADR-0054's "Amendment
  (spec 5 round 2)": `rest` may use a parameter slot, and the fact has two values, ELEMENTS and
  WHOLE.

## Amendment (ticket #112)

Accepted 2026-11-15, architect under `/run-spec` #106; designer draft.

- The cardinality lattice is NEVER < NONE, ONE < AT_MOST_ONE < MANY. NEVER means no result and no
  normal end (a Break, or a body that only recurses). It is the bottom the fixpoint starts from, and
  a stored fact keeps it as NONE.
- Recursive groups are analysed callee-first. Each starts from NEVER and runs to its fixpoint, and
  main runs last. A body is read in descending freeze id, with no recursion.
- The rules backends may rely on: "each result of a runs b" is the core rule; a Comma after a left
  result is MANY; a Try with a MANY body is MANY; a Label joins NONE; an AltBind is MANY (a
  downstream error re-enters it, ADR-0038); an Intrinsic follows its call form; a Call takes its
  callee's value; a ParamCall is MANY.
- A ONE or AT_MOST_ONE op, once its result's emit returns, returns with no emit, raise or tick of
  its own (F-56).
- ParamCall's MANY differs on purpose from may-read-input's join over parameters: no 2b consumer
  needs more, and may-break-out keeps every ParamCall off the value form.
- Considered: ParamCall as the join over call sites (callers would feed callees, so there is no
  callee-first order, e.g. `def k: g(k)`); NONE as the start (loses ONE in recursion); a summary
  per closure tuple (up to 5^k per function, and no 2b consumer reads it).

## Amendment (ticket #115)

Accepted 2026-11-15, architect under `/run-spec` #106; designer draft.

- May break out is true for a node when a run of it may end by a Break to a Label activation the run
  did not start. A function's value is its body root's. CONSERVATIVE stores true. The value form
  needs it false.
- Each node has a lowest target:
  - a Break's is the freeze id of the Label that binds its slot in the body, or −1 when no Label
    there binds it (a token passed in as a value);
  - a ParamCall's is −1;
  - a Call's is −1 when its callee may break out, and otherwise the lowest of its closures' and
    values';
  - Identity, Const and Var have none;
  - every other kind takes the lowest of its parts, since no catch set holds a break (ADR-0008).

  A node may break out when its lowest target is below its own freeze id. This is ADR-0008's rule:
  the IrVerifier makes a Break's Label an ancestor in the same body and the only node that binds its
  slot.
- The fixpoint runs callee-first over the recursive groups, main last, each group from false, with a
  body read in descending freeze id. A group shares one value, so it ends after its second round.
- Considered: a set of label slots per node (same answer, allocates); a ParamCall joined over its
  call sites (no callee-first order, as for cardinality); a Call that breaks only to the labels whose
  tokens it passes (nothing reads that precision before S2b.11); fusing it into the cardinality walk
  (ties two tested rule sets together to save one walk).
