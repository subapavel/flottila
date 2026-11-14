# In-place updates: a sole-use licence and a per-activation owner

Status: accepted (2026-11-11, owner, issue #8 "When may a value be updated in place?"); amends ADR-0003 (owner on built strings), ADR-0005 and ADR-0009 (`keepsArgs`, in-place operations), ADR-0007 (last read, key steps of WALK), ADR-0010 (WALK writes in place), ADR-0018 and ADR-0020 (conservative compile, copy-only switch) and ADR-0019 (the analyses-gain diagnostic); records F-16; supersedes founding §6.3's clear-on-escape (F-16); amended by spec 3 grilling round 1 (question 6: the own owner and the never-change tests in 3a); "Detach and budgets" amended by F-80 (spec 5 round 2, grilling-adviser for the owner under the delegation of 2026-11-14: a state hold's detach and main-document views); "Borrows" and "WALK key steps" amended by spec 5b's ticket approval (grilling-adviser for the owner under the delegation of 2026-11-14)

Facts marked "jq-ref" were observed on 1.8.2 by the adviser or the architect (issue #8 round 1).
Timings are single runs, not `bench/jq-ref.sh` measurements.

## The rule: two keys

A site writes a built value in place only when both keys hold. Otherwise it copies once and
stamps the copy with its owner.
- **K1, static, the in-place licence:** an IR fact that the site's target is the sole use of an
  owning activation's state (below).
- **K2, dynamic, the owner:** the target, and every container above it on this write's descent
  from the state, carries the activation's current `Owner`.

Two cases need K2 only: an Update's own rebuild (only its right side's old leaf can reach its
copies, and that retires) and a string append.

The licence catches the owner reading its own state twice, which founding §6.3's clear-on-escape
misses (F-16): `reduce (1,2) as $x ([]; (. + [$x]), (. + [0]))` gives `[0,0]` on jq-ref;
clear-on-escape alone gives `[0,2,0]`. The owner catches every value the activation did not make:
constants, views, host values, `Bindings`, the init value and earlier runs' results.

## Owners

- **`final class Owner {}`** in `query-core` has identity only. It lives in a hidden frame slot,
  which the trampoline's heap frames keep (ADR-0011). An `Owner` is never pooled or reused.
- **Owning activations:**
  - each `Reduce` run, and each `Foreach` run (one per init result, ADR-0008), whose body has a
    licensed site or a string `+`;
  - each `Update` activation, for its own rebuild, allocated at its first copy. The conservative
    compile keeps this owner too.
  - A licensed FIXED or WALK `Update`, or a licensed in-place operation, inside a body uses the
    enclosing activation's owner.
- **Nested reduce** (milestone 1, its own ticket after the first): an inner `Reduce` inherits the
  outer owner when its init is exactly `.` and it sits on the outer chain, its source does not
  read its input, and its body is licensed. It shares the outer activation's owner slot; a retire
  inside it replaces the outer owner. Otherwise, and for every inner `Foreach`, it takes a fresh
  owner. jq-ref stays flat on
  `reduce .rows[] as $r (state; reduce $r[] as $w (.; .[$w] += 1))` with a 10⁵-key state: 1000
  and 2000 rows both take 0.17 s.
- **Private builders** (`Collect`, object construction, an unlicensed `setpath` or `delpaths`)
  build a value nobody else can see. They need no marker and emit unowned values.
- **Retire:** when an owned value leaves the licensed chain while its activation keeps running, the
  activation replaces its `Owner` with a fresh one. Every value made so far is then foreign, at any
  depth, in O(1). The next write copies the path it touches once. Values leave the chain as:
  - a `Foreach` extract emitted downstream;
  - the input of an update's right side that has no licence;
  - a value passed to `debug` or `stderr`.

  A `Reduce` or own-owner `Update` that ends drops its owner. The result of a licensed Update or of
  an inheriting inner Reduce stays on the chain and does not retire. Retire fires only if the
  leaving value carries the current owner, or is a slice cut from an array that does. The shallow freeze of the
  round-1 draft is rejected: a copy of a frozen container still shares children that carry the
  live owner (counter-examples CE1–CE3, pinned below).
- **An error whose payload is on the chain** withdraws the licence for that body; a caught error's
  payload is then its only reference. **`input`** is no escape: jq-ref is linear on
  `reduce range(n) as $i ([]; . + [input])` (225 ms and 321 ms at 2×10⁵ and 4×10⁵).

## The in-place licence

Computed after inlining and specialisation, so the inlined prelude `add`, `join`, `INDEX`,
`from_entries`, `pick` and `fromstream` are covered.
- **The sole-use chain:** the state goes from the body's input to the target and on to the body's
  result along a whitelist of links, each in a context that runs at most once per body run:
  - Pipe, If branches, Try body, Label, a licensed Update, an in-place operand;
  - Alt left, only if Alt right reads no input at all;
  - Comma, only if exactly one branch reads the input and the other reads none;
  - a Bind body, when its source has at most one result and either reads the chain only as
    borrows, or binds the whole chain, which then moves by its last read;
  - `debug` and `stderr` (they retire).

  Anything else on the chain is an escape, including a Call or ParamCall input.
- **Borrows:** every other read of the chain or a part of it reaches only If conditions with at most
  one result and operations that are `pure` with `keepsArgs` false. An operation whose `reads`
  fact is set, with `keepsArgs` false, takes nothing from its `.` (only `input` sets `reads`, F-79;
  `onlyInputReads` pins it), so the chain arriving as its `.` is no read of the chain. This is the
  rule behind "`input` is no escape" above; a second operation that sets `reads` needs its own
  ruling.
- **Variables:** a variable bound to the chain or a part of it allows no licensed site in its Bind
  body, except the whole-chain move. Without this rule,
  `reduce (1,2) as $x ({a:[]}; .a as $y | .a += [$x] | .n = ($y|length))` would give `"n":2`;
  jq-ref gives `{"a":[1,2],"n":1}`.
- **Static escapes** withdraw the licence for that body (an extract emit, an unlicensed right
  side's input and `debug` retire instead):
  - a second read of the chain (the Comma, Alt, If and Bind cases above, a `?//` re-run);
  - a capture as a closure or call argument;
  - a store through Collect, an object, a `keepsArgs` operation or a slice (slices share storage);
  - a hold other than the activation's own state slot, such as a nested init or source;
  - PathOf over the chain;
  - `error` with the chain as payload.
- **Extracts:** a `Foreach` extract may read the state only as borrows, or as a navigation chain of
  field and index steps (`.`, `.a`, `.[0]`; no slice) that ends in an emit. That emit retires.
- **Right sides:** an update's right side is licensed on the old leaf when its own body is a
  sole-use chain (`.[$k] += [$x]`, `.[$k] |= (. // []) + [$x]`). jq-ref is quadratic here
  (`.a += [$x]`: 0.28, 1.08 and 5.5 s at 1, 2 and 4×10⁴, 40 s at 10⁵); jqj is not.
- **Never licensed:** TRACKED updates (the path generator reads the value being written) and
  path-mode variants (the state is untracked there, ADR-0010).
- **An over-claimed licence gives a wrong value,** as an over-claimed cardinality does (ADR-0007).
  The analysis is class C.

## In-place sites

The left operand is the state, or an owned level of it.
- `+` on an array appends. `null + x` returns `x` unchanged.
- `+` on a growable object overwrites or appends the key and keeps its index current; a shape object
  becomes growable in place.
- `setpath` and `delpaths` (deletes from the end).
- A FIXED or WALK update writes each owned level in place, iterates over the original length, and
  applies deletes after the walk. A walk checks a level's owner once, on entering it; a retire by
  its own right side does not copy that level again.
- **WALK key steps** (amends ADR-0007): a dynamic step (ADR-0005) whose key has at most one result
  and does not read `.` (constants and variables, as `.[$row|f|tostring]` in `INDEX`) is a WALK
  step. A key that may read input is none: its Update is TRACKED and has no licence, as
  `.[input] |= f` (ADR-0007, "Amendment (spec 5 round 2)"). jq-ref builds `INDEX` over 4×10⁵ keys
  in 0.86 s. At run time a string key is a field, a number an index and an object a slice
  (jq-ref: `[1,2,3] | {"start":0,"end":2} as $k | .[$k] |= [.]` → `[[1,2],3]`); any other key
  raises jq's error. A level reached through a slice key is rebuilt by copy, never written in
  place. On jq-ref the key is evaluated once per path, interleaved with the right side.
- **Strings:** a built string carries an owner (amends ADR-0003). An append writes after the
  string's last byte when the owner matches and capacity remains, then returns a new string header
  over the same bytes. The old header loses the right to grow, so every string stays immutable and
  needs no licence. A string leaving the chain never retires. Both compiles keep string appends;
  copy-only turns them off. One header per append: a `join` of 10⁵ elements takes 1.5–2.8 ms against
  1.1–3.2 ms for a mutable string (bare Java loop, adviser). Without an owner that `join` copies
  57.9 GB, and jq-ref's own `join` takes 0.93 s at 10⁵.

## Exceptions, breaks, stops and terminations

A licensed write touches only containers reachable through a live activation's chain. Abandoning
the body drops that chain (no result leaves the state null, ADR-0008), so a half-written value is
never reachable. Sinks receive only values that left the chain, and so carry a retired owner
(ADR-0016). An owned document is never written (ADR-0016).

## Detach and budgets

- In a state hold that spans a later read, an in-place write detaches the inserted value's
  input-document views, and the state hold does not detach again a state the site wrote. The
  state's view flag stays clear only when the inserted value holds no main-document view.
- Growth charges the capacity it adds as bytes built (at most twice the final size), and ticks per
  element. A copy charges its slots.
- G4 `reduce .[] as $x ([]; . + [$x])` becomes linear (gate check 4). G5 `.items[].price |= . *
  1.2` needs no licence: the update's own owner copies the items array once.

## Accepted for milestone 1

- **A `Foreach` that emits its state at every element copies it at every element.**
  `foreach range(10⁶) as $x ([]; . + [$x]) | length` takes 0.57 s on jq-ref; jqj makes about
  5×10¹¹ slot copies and ends with `JqTermination(BUDGET, BYTES_BUILT)` under `Limits.embedded()`.
  A later analysis may prove that the rest of the pipe only reads the value. Until then, a
  diagnostic scenario records it.
- **A key that reads `.` keeps its step TRACKED** (owner, after the ADR review), so
  `reduce … ([]; .[length] = $x)` copies its state per iteration. jq-ref is linear there (18, 30
  and 54 ms at 1, 2 and 4×10⁴); jqj makes about 5×10⁹ slot copies at 10⁵ and ends with
  BYTES_BUILT. A later round may treat such a key as a borrow. The diagnostic records it.
- **TRACKED paths that visit a descendant before its ancestor** (`(.[] | (.[], .)) |= f`) retire
  per element and copy the root each time (owner, after the ADR review). jq-ref is linear (15, 23
  and 43 ms at 2, 4 and 8×10³). A later round may re-stamp the current spine at retire.

## Conservative compile and the copy-only switch

- The conservative compile has no in-place licence anywhere. It keeps retire and the update's own
  owner, so its updates stay linear (an update over n paths would otherwise copy the root n times).
- **Copy-only** is an engine-internal switch for the ownership tests: no owner ever matches. It is
  the only baseline that shares no in-place code. Counter-example CE3 below is TRACKED, so it is
  equally wrong under both compiles; only copy-only sees it.

## Tests

- **Pinned differential cases** (`DEVIATIONS.md` has no row; these are plain jq behaviour):
  - F-16 `reduce (1,2) as $x ([]; (. + [$x]), (. + [0]))` → `[0,0]`;
  - CE1 `[foreach (1,2,3) as $x ({p:[],q:[]}; if $x==1 then .q += [0] | .p += [0] elif $x==2
    then .p += [2] else .q += [3] end; if $x==1 then . else empty end)]` →
    `[{"p":[0],"q":[0]}]`;
  - CE2 `reduce (1,2,3,4) as $i ({a:{x:[]}}; if $i==1 then .a.x += [1] elif $i==2 then .a |=
    [.x,.x,.x] elif $i==3 then .a[0] += [3] else .a[1] += [4] end)` →
    `{"a":[[1,3],[1,4],[1]]}`;
  - CE3 `{"a":[[[1]],[[1]],0]} | (.a[0][0], .a, .a[0][0], .a[1][0]) |= (if length == 3 then
    [.[0],.[0],.[0]] else . + [9] end)` → `{"a":[[[1,9,9]],[[1,9,9]],[[1,9]]]}`;
  - CE4 `reduce (1,2) as $i ({a:{x:[1]}}; if $i==1 then .a |= (debug | .x) else .a += [2] end)` →
    `{"a":[1,2]}`, stderr `["DEBUG:",{"x":[1]}]`;
  - CE6 `[[1],[2]] | (.[0][0], .[0:1], .[0][0]) |= (if type=="array" then . + . else . + 10
    end)` → `[[21],[11],[2]]`;
  - CE7 `[foreach ({"start":0,"end":1}, 0) as $k ([[1],[2]]; .[0] += [9]; .[$k])]` →
    `[[[1,9]],[1,9,9]]`;
  - CE8 `reduce (1,2) as $i ([[0],[1],[2]]; if $i==1 then (.[0] += [5] | {"start":0,"end":1} as
    $k | .[$k] |= . + .) else .[0] += [9] end)` → `[[0,5,9],[0,5],[1],[2]]`;
  - the variable case above → `{"a":[1,2],"n":1}`;
  - `[foreach (1,2) as $x ({a:[]}; .a += [$x]; .a)]` → `[[1],[1,2]]`;
  - `reduce (1,2) as $x ({a:[],b:null}; .b = .a | .a += [$x])` → `{"a":[1,2],"b":[1]}`;
  - a break and a caught error in the middle of a WALK, and a slice followed by `setpath`: their
    filters are written by the ticket that builds the licence, recorded against jq-ref.
- **The compile-mode oracle** (ADR-0020) compares licensed with unlicensed on every case.
- **`copyOnlyAgrees`:** every jqtest, differential and `gen/ownership-v1/*` case gives the same
  output and error with copy-only as without.
- **`gen/ownership-v1`:** a generator of reduce, foreach and update bodies over the hazards: Comma,
  Bind, Alt, multi-result If, Try, label/break, `?//`, slices, `debug`, `error`, `+`, `+=`,
  `setpath` and `delpaths` on arrays, objects and strings, conditional extract emits, an alias
  through an unlicensed right side, descendant-before-ancestor TRACKED paths, variable-held parts,
  on small random inputs. Recorded against jq-ref.
- **`keptResultsNeverChange`** (every case): a keeping `JqSink` and `DebugSink` snapshot
  `toJson()` and `detach()` at each call and serialise again after the run; the bytes are equal.
- **`sharedInputsNeverChange`:** after N runs, and with two threads on one owned document, the
  constants, the `Bindings` values and the document's bytes and tape hash are unchanged.
- **`keepsArgsIsSound`** (property): an operation with `keepsArgs` false returns no container or
  string that shares storage with an argument or its input, at any depth.
- **`licenceFacts`** through `explain()`: no licence for `(. + [$x]), (. + [0])`,
  `. as $s | . + [$x] | [$s,.]`, `(. + [$x]) // .`, `if (true,true) then . + [1] else . end`,
  `. + .`, TRACKED or path mode; one for G4, `add`, `join`, `INDEX`, `from_entries`, `pick` and
  `fromstream`; `INDEX`'s key step is WALK.
- **`inPlaceKeepsBytesBuiltLinear`** (deterministic, no timing): G4, `INDEX`, `from_entries`,
  `join`, `add` on strings, `.[$k] += [$x]` and `fromstream(tostream)` at 10⁵ complete with
  bytesBuilt = 64 MiB; the conservative compile ends with BYTES_BUILT on G4.
- **Diagnostic `ownershipGain`** (`bench/RESULTS.md`): `INDEX`, `from_entries` and `join`, licensed
  at 10⁴ to 10⁶ and conservative at 10⁴ only, and the per-element `Foreach` copy above. jq-ref's `join`
  is itself quadratic (28 ms, 0.93 s and over 25 s at 10⁴, 10⁵ and 10⁶), so it has no reference
  time at 10⁶.
- **The analyses-gain diagnostic** (ADR-0019) times G4 under the conservative compile at 10⁴ only;
  at 10⁶ it would make 5×10¹¹ slot copies.

## Considered options

- **Clear-on-escape alone** (founding §6.3): misses the owner's own second read (F-16), and needs a
  freeze in every native.
- **A shallow freeze at each escape:** wrong values (CE1–CE3).
- **Withdrawing the licence at every escape:** `fromstream` copies per event, and one `debug` in a
  rare branch makes the loop quadratic.
- **Mutable strings under the licence:** saves one header per append, but a licence mistake changes
  a string someone else holds.
- **No owner on strings:** `join` of 10⁵ elements copies 57.9 GB and ends at the 1 GiB default.
- **A per-evaluation owner:** nested activations would match each other's values.
- **`long` counters:** collide across Workspaces unless kept in a global atomic.
- **Nested reduce later:** jqj copies the outer state per outer row (2×10⁸ slots at 2000 rows).
- **No licence on right sides:** jq's quadratic growth, and the copies count as bytes built.
- **Analysing a foreach's downstream reads now:** more licence surface before any experience.
- **Licensing TRACKED:** its path generator reads the value being written.
- **Rewriting `reduce . + [$x]` into a builder:** covers G4 only.
- **An identity map of owned values:** a lookup per write.

## Amendment (spec 3 grilling round 1, question 6)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

- The Update's own owner is a 3a ticket of its own, right after TRACKED Update. It brings `Owner`,
  copy-only, `copyOnlyAgrees`, `keptResultsNeverChange` and `sharedInputsNeverChange`, because the
  own rebuild (K2 only) is the first in-place write. Red: bytes built stay linear on `[range(1e5)] |
  .[] |= .+1`.
- 3b keeps `ownership-v1`, the licence (`licenceFacts`), in-place with G4
  (`inPlaceKeepsBytesBuiltLinear`), and nested reduce.
