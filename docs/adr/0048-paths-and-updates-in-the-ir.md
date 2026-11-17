# Paths and updates in the IR: `PathOf`, `Update` and its Kind, operation `Facts` with one path rule, and `Tracking` on binds

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 3 rounds 1–2); amends ADR-0005 (kinds 21 and 22, `Update.Kind`, `Facts` on `Operation`), ADR-0009 (`getpath` is KEY_LIST), ADR-0010 (who passes the path on), ADR-0038, ADR-0041 and ADR-0043 (`Tracking` on pattern, loop and `Binds` values) and ADR-0042 (`_modify/2`, `_assign/2`); records F-59 and F-60

ADR-0005 names `PathOf` and `Update` (`|=`) and lets each `Operation` fact join with its first
user (ADR-0034); 2b keeps `Operation` as `(name, form)` (ADR-0045). Spec 3 builds path mode
(ADR-0010) and the assignments. So the IR must say how an operation moves a path, how `=` differs
from `|=`, and which bound values a path variant runs in path mode. Behaviour marked jq-ref was
observed on 1.8.2 by the architect (probes P1–P6) or the adviser
(`docs/wayfinder/spec-3-grilling.md`).

## Decision

- **Kinds 21 and 22** (spec 3a; F-52's count):
  - `PathOf(src, body, paths)` runs `body`'s path variant on its input from the root `Path` and
    emits each result's path as a value. An untracked result raises "Invalid path expression with
    result …" (ADR-0010).
  - `Update(src, kind, lhs, rhs, paths)`, `enum Kind {MODIFY, ASSIGN}`. MODIFY is `|=`: `rhs` runs
    on each leaf, its first result wins, and a path whose `rhs` gives none enters the delete list
    (ADR-0010). ASSIGN is `=`: `rhs` is atomic (`Identity`, `Var` or `Const`). It is read on the
    Update's input, never on the old leaf, and ASSIGN never deletes. The two kinds' depth limits
    differ by one (ADR-0029 as amended, F-60).
  - `paths` is a query-core interface that jq-core implements, as `Collect`'s collector is
    (ADR-0034). jq-core's `Paths` builds the path value, runs getpath, setpath and delpaths, and
    holds jq's depth limits and messages, so query-core names none of them (founding §14). Each
    method arrives with its first caller in query-core: PathOf's in 3a's PathOf ticket, Update's in
    its TRACKED Update ticket (ADR-0034; grilling-adviser for the owner under the delegation of
    2026-11-14, spec 3's ticket approval).
- **Lowering**: `|=` and `=` go through the names `_modify/2` and `_assign/2`. These are replacing
  natives (ADR-0042 as amended), so a user's definition of either wins (jq-ref, P1), for `op=` and
  `//=` too (`def _modify(p;u): 42; {} | .a += 1` → `42`, architect's probe). A call by name lowers
  the same way: `{} | _modify(.a;1), _assign(.b;2)` → `{"a":1}`, `{"b":2}` (jq-ref). `a = b` is
  `Bind(b, s, Update(ASSIGN, a, Var s))`, with no Bind when `b` is atomic (ADR-0034). `op=` and
  `//=` keep ADR-0005's Bind of the right side around a MODIFY Update.
- **`Facts`**: `record Facts(PathRule path, boolean pure, boolean reads, boolean keepsArgs)`, so
  `Operation(name, form, facts)`. 3a's first ticket creates it; cardinality still reads the call
  form (ADR-0045). `enum PathRule {NONE, SAME, KEY, INDEX, ALL, DYNAMIC, SLICE, KEY_LIST}`
  replaces ADR-0005's path rule plus navigation step, so the illegal pair "a step rule with no
  step" cannot be written:
  - NONE emits untracked. SAME passes the path on (`debug`, `stderr`). KEY, INDEX, ALL and SLICE
    extend it by their step. DYNAMIC extends it by its run-time key's kind (ADR-0022). KEY_LIST
    extends it by a key list without raising on an untracked input (`getpath`, ADR-0009).
  - `keepsArgs` moves from `Binder.Native` (F-47), and `keepsArgsIsSound` checks every operation
    from that ticket on. The shared `TextFormat` operation declares it true and is not split.
  - A fused chain (F-44) has no rule of its own: in a path variant it runs its steps one by one,
    each by its own rule, and fusion applies to value mode only.
  - `InPlace` is a call form, not a fact. It joins `Operation` in 3b with its first user
    (ADR-0034); the first ticket declares operations so that adding it changes no declaration.
- **`Tracking {VALUE, PATH, ITEM}`** sits on `Bind`, on each value of a `Binds` (a list beside its
  slots) and on an `AltBind`'s value. Only path variants read it (F-59):
  - VALUE: the value runs in value mode, as ADR-0010 wrote for every bound value.
  - PATH: the value runs in path mode on the bind's input and its paths are dropped, so a
    navigation step on an untracked value raises by ADR-0010's untracked-step rule.
  - ITEM: as PATH, and each result's `Path` waits in a hidden frame slot (ADR-0045) that the
    slot's `Var` passes on in a path variant.
  - The Resolver sets PATH on a pattern's index steps and on a `reduce` or `foreach` source. It
    sets ITEM on the source of `foreach … as $name`, and VALUE everywhere else. A pattern's first
    step then raises jq's message once per source result, and a later `?//` alternative runs on
    that error.
  - The IrVerifier accepts ITEM only on a Foreach's source `Bind`, and only an atomic ASSIGN `rhs`.
- **jq-ref evidence**:
  - On `{"a":[1],"b":0}`, `.a as $v|[path(($v|.[0]) as $x|.b)]` → `[["b"]]`, but
    `[path(.a as [$x]|.b)]` raises. Both lower to a Bind of a step on a `Var`, so the mark cannot
    be derived from the IR.
  - `path(reduce ([1]|.[]) as $x (0;1)|empty)` raises "…near attempt to iterate through [1]"
    (`foreach` alike); `path(([1]|.[]) as $x|.)` → `[]`.
  - `{"a":["b"],"b":0,"c":0} | (.a as [$x] ?// $x | if ($x|type)=="string" then .b else .c end)
    |= 1` → `{"a":["b"],"b":0,"c":1}`.
  - A destructured item stays untracked: `[[5],[6]]|[path(foreach .[] as $x ?// [$x] (0;1;$x))]`
    → `[[0],[1]]` on jq-ref, an error in jqj (`DEVIATIONS.md`).

## Considered options

- **`|=` only, with `=` as MODIFY on an atomic right side**: it reads the old leaf, and it raises
  at 10 000 steps where jq-ref's `=` gives a value (F-60).
- **An `Assign` node kind**: a 23rd kind with its own heap form, path variant and backend arms,
  all for one flag on the same run.
- **The parser lowering `|=` straight to `Update`**: a user's `_modify` would lose (P1).
- **A path rule plus a separate step field**: a step rule with no step could be written.
- **A `Tracking` node kind around the value**: one more heap form and a `Binds` twin.
- **Deriving the mark**: the shapes above lower alike. Round 1's "the Bind directly under a
  loop's `each`" breaks when the source is a `Binds` value or an `AltBind`'s value.
- **A tracked destructured item**: jq keeps one only by a quirk of its matcher. jqj raises where
  jq accepts, never the reverse, except the `?//` row of `DEVIATIONS.md`, which gives another path
  (grilling-adviser for the owner under the delegation of 2026-11-14, spec 3's ticket approval).
- **Splitting `TextFormat` by `keepsArgs`**: two operations for one body, and true is the safe
  value (ADR-0007: a missed fact costs speed).
