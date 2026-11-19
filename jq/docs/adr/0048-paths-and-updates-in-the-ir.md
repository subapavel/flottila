# Paths and updates in the IR: `PathOf`, `Update` and its Kind, operation `Facts` with one path rule, and `Tracking` on binds

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 3 rounds 1–2); amends ADR-0005 (kinds 21 and 22, `Update.Kind`, `Facts` on `Operation`), ADR-0009 (`getpath` is KEY_LIST), ADR-0010 (who passes the path on), ADR-0038, ADR-0041 and ADR-0043 (`Tracking` on pattern, loop and `Binds` values) and ADR-0042 (`_modify/2`, `_assign/2`); records F-59 and F-60; amended by the path-mode contract (2026-11-19, owner-proxy, #153)

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

## Amendment (2026-11-19, the path-mode contract)

Chosen by the owner-proxy after a design in three drafts (minimal interface, maximal flexibility,
the common caller's case) and the objections of a second model; Codex could not be run. It fixes
the open parts of this ADR and of ADR-0010: the interface jq-core implements, how an op exposes its
path variant, how a navigation step extends a path, and how a TRACKED Update interleaves. Nothing
in `run` names a type of `ir`.

- **Paths** (`run`): `record Path(Path parent, Object key, int length)` with `static final Path ROOT`
  (non-null and empty) and `Path child(Object key)`; `interface PathSink { boolean emit(Object v,
  Path p); }`, answering as `Sink` does, a null `p` untracked. Paths are immutable and may be shared;
  they are not charged.
- **The format** (`run`, the `paths` field of `PathOf` and `Update`, one instance per program,
  which the IrVerifier checks):
  ```java
  public interface PathFormat {
    Object value(RunState s, Path p);                    // PathOf: p as a value; PathOf ticked p.length() first
    FilterException invalid(RunState s, Object result);  // an untracked result used as a path
    Edit modify(RunState s, Object root);                // one TRACKED MODIFY Update activation
    Edit assign(RunState s, Object root);                // one ASSIGN Update activation
  }
  public interface Edit {                                // single use; its root starts as the Update's input
    Object get(RunState s, Path p);                      // MODIFY: getpath on the current root, before the rhs
    void set(RunState s, Path p, Object v);              // setpath on the current root
    void delete(RunState s, Path p);                     // MODIFY: p joins the delete list
    Object end(RunState s);                              // the deletes, then the root; once, after the lhs ran out
  }
  ```
  The Edit holds the root, the delete list and its charges, both depth limits (MODIFY's after an
  rhs result included) and every message. MODIFY calls, per lhs result in lhs order, `get`, then
  the rhs until its first result, then `set` with it or `delete` when there is none; ASSIGN calls
  `set` only, with the rhs read once on the Update's input. A break or an error abandons the Edit:
  `end` is never called and nothing is restored. The Edit lives in a local of the push form and in
  a field of the Update's heap form, never in a frame slot. `value` and `invalid` arrive with
  PathOf, `modify` and `assign` with the TRACKED Update.
- **Steps** (`run`): the call form of every operation whose rule is KEY, INDEX, DYNAMIC, SLICE,
  KEY_LIST or ALL also implements
  ```java
  public interface PathStep {
    FilterException untracked(RunState s, Object in, Object key, Object end);   // never asked for KEY_LIST
    default Path extend(RunState s, Path at, Object in, Object key, Object end) { return at.child(key); }
  }
  ```
  An Fn0 passes `(null, null)`, an Fn1 `(k, null)`, an Fn2 `(k, end)`, and a `Many` the key of its
  cursor, which `Many` gains as `default Object key(RunState s, Object in, Object[] args, long
  cursor)` (it throws unless overridden). A step's key lives with its form: Field keeps its name,
  SLICE builds its slice key, KEY_LIST (getpath) appends each key of its list, DYNAMIC appends the
  run-time key, whose kind only the format reads. A fused chain (`StepRun`) implements PathStep
  over its steps, each one a PathStep: `untracked` is its first step's, `extend` calls each step's
  own in order, so in path mode each step still extends the path by its own rule. Its Operation
  declares its first step's rule, which ops read only as "a step". The IrVerifier refuses a step
  rule whose form is not a PathStep of Fn0, Fn1 or Fn2, and ALL whose form is not a PathStep Many.
- **Ops** (`internal.interpret`): `Op` gains `boolean runPath(RunState s, Object[] slots, Object
  in, Path at, PathSink out)` and its heap form `void startPath(Trampoline t, HeapForm under,
  Object[] slots, Object in, Path at, HeapPathSink out)`, with `interface HeapPathSink { void
  accept(Trampoline t, HeapForm under, Object v, Path p); }`. Both default to the untracked
  variant: the op's value or push form, each result emitted with a null path. A path variant has
  the results, order, ticks, charges and slot writes of `run` on the same node; only the paths
  differ. A parent calls `eval` on a value-form child and `run` on a push one, in path mode too.
  The Trampoline gains the actions START_PATH and EMIT_PATH and the starter `static boolean
  runPath(RunState, Op body, Object[] frame, Object in, Path at, PathSink out)`. A test over
  `Op`'s permitted subclasses classifies every kind as passing paths on or untracked, and fails on
  an unclassified kind or on one of the two methods overridden without the other.
- **Navigation steps**: NONE emits untracked and SAME passes `at`; KEY, INDEX, DYNAMIC and SLICE
  raise `untracked` on a null `at` before the call (so `.a?` raises too), then emit the call's
  result with `extend`; ALL raises before `first`, then extends by each cursor's key; KEY_LIST
  emits untracked on a null `at` without raising.
- **Update**, push form: the lhs's path variant runs from `ROOT` on the Update's input, and the
  rhs runs inside its emit, so `[1,2]|(.[]|debug("L")) |= (debug("R")|.+1)` prints L, R, L, R.
  An rhs in value form is evaluated, one in push form runs into a reused first-result holder. An
  untracked lhs result raises `invalid` there, lazily, so `(1|empty) |= 1` gives its input. The
  Update emits once, `end`'s result, after the lhs ran out; a break passes on with no result. The
  heap form starts the rhs above the lhs producer, as Pipe's right side, so an rhs error passes
  back through the lhs's Try (wrapped) and AltBind (the next alternative) as in jq.
- **Hidden cells** (counted in the frame's one size, ADR-0045): each ITEM bind has one for its
  Path; a lifted function has one for its input Path, written at entry and before each tail jump,
  in programs that hold a PathOf or an Update, and none otherwise, so a program without paths
  keeps its frames.

Rejected: a parallel tree of path ops (a second build of functions and closures, twin heap
forms); a six-parameter format `step` that dispatches on the call form (a step's key would live in
two places); stateless format methods with the root and delete list in query-core (a copy of the
root per leaf, and the delete list's charges in query-core); Field as an Fn1 taking its key (a
lost precomputed name); `run` naming `ir.Update.Kind` (a package cycle); an Object array of
arguments per extension (an allocation per step); abstract path methods on every op (more code for
a guarantee the classification test gives).
