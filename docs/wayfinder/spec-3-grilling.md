# Spec 3 "Paths and updates" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` (Fable) answers for the owner. Drafts:
designer (round 1). jq behaviour: jq-ref 1.8.2 probes, observed by the architect (P1–P6) and the
adviser.

To show the owner on Monday (adviser's list): these change owner-accepted text — ADR-0010's Bind
rule (A1), GATE's "debug in specs 5 and 7" (A3), ADR-0023's spec 3 row (Q1).

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **Split 3a / 3b (option B).** G5 enters `bench.tsv` at 3a's FIXED/WALK ticket: it needs no
   licence (ADR-0022) and question 6 puts the own owner in 3a. G4 at 10⁵/10⁶ stays with in-place
   (3b). ADR-0023 allows the split without reopening GATE.
2. **`PathOf` and `Update` IR nodes, plus `Update.Kind {MODIFY, ASSIGN}`.** `Paths` follows the
   Collector precedent. `=` never reads the old leaf and its depth limit is one higher (question 8);
   ASSIGN takes an atomic right side and never deletes. The depth limits live in jq-core's `Paths`.
3. **[2b] Facts on Operation.** If 2b puts a facts record on Operation, spec 3 adds `path` and
   `keepsArgs`; if Operation stays `(name, form)`, spec 3's first ticket creates
   `Facts(path, pure, reads, keepsArgs)`. One path enum (NONE, SAME, KEY, INDEX, ALL, DYNAMIC, SLICE,
   KEY_LIST) replaces PathRule plus Step (STEP with Step.NONE is an illegal pair). `InPlace` is a call
   form, not a fact, joining Operation in 3b with its first user (ADR-0034). The shared TextFormat
   operation takes `keepsArgs` true, no split.
4. **Loop sources in path mode.** In a path variant the Bind directly under a Reduce's or Foreach's
   `each` runs its source in path mode; only a Foreach `$name` item keeps its Path, in a hidden slot.
   Destructured items stay untracked and raise (A1). jq-ref:
   `path(reduce ([1]|.[]) as $x (0;1)|empty)` raises "…near attempt to iterate through [1]" (foreach
   alike); `path(([1]|.[]) as $x|.)` gives `[]` (a plain `as` source stays in value mode).
5. **[2b] Frames.** If 2b's frame is a slot array sized by a per-function count from the fact table,
   2b adds nothing for spec 3; it promises only that the count is the frame's sole size source and
   that closures and heap frames copy the whole frame. Spec 3 raises the count and keeps the owner,
   item paths, walk state and a suspended call's input Path in hidden slots; it builds the
   Call/ParamCall path variants (push-only), the path-mode resume, the Path on a pending jump and
   F-30's re-run. Fixed-shape frame objects in 2b reopen this in round 2.
6. **The own owner is its own ticket, right after TRACKED Update**, with Owner, copy-only,
   `copyOnlyAgrees`, `keptResultsNeverChange` and `sharedInputsNeverChange` (they guard the first
   in-place write). Red: linear bytes on `[range(1e5)] | .[] |= .+1`.
7. **`_modify/2` and `_assign/2` are replacing natives lowering to Update**, so a user's def wins by
   ADR-0042's lookup (P1). A call by name lowers the same way: `{} | _modify(.a;1), _assign(.b;2)` →
   `{"a":1}`, `{"b":2}`.
8. **jq's exact depth limits** (n = path length; getpath forms and literal `.a` chains of 9 999,
   10 000, 10 001 agree): `|=`, `op=`, `//=` with a right-side result raise "Path too deep" at
   n ≥ 10 000, after the right side ran (at 10 000, `|= (debug|error("rhs"))` printed the debug line
   and raised "rhs"); `=`, `|= empty`, `del` and the three natives raise at n ≥ 10 001; `path()` and
   `paths` never raise (`last(paths)|length` → 10001 on a 10 001-deep value). The shape analysis
   keeps chains of 10 000 steps or more TRACKED.
9. **paths-v1 is recorded before the TRACKED Update ticket**, with the own-owner hazards (repeated
   paths, ancestor and descendant in both orders, overlapping slices, right sides returning their
   input or a part of it) and fractional indexes (P5). It avoids the deviation shapes by
   construction; `copyOnlyAgrees` runs over it. Frozen on a dry run with bounds written in the
   ticket beforehand (as #58).
10. **Delete-list charges:** a path entering the list charges CONTAINER plus ELEMENT per component
    (what jq's array holds); `delpaths` ticks per element copied and compacts each container once.
11. **Walk state in the Update's hidden frame slots**: nothing for chains without `.[]`, one cursor
    per `.[]` level entered, never one per element. A RunState stack would need restoring at every
    Try, Label, AltBind and trampoline abandon (rejected for TRACKED by ADR-0010) and pins keys
    between runs (ADR-0034). ADR-0030 is not amended.

### P6: DEVIATIONS, not conformance

`{"k":V}|[path(.k|F)]` on jq-ref keeps the path for `tostring`/`@text` on a string, `tonumber` on a
number, `ltrimstr`/`rtrimstr`/`trimstr`/`ltrim`/`trim` leaving a string unchanged, `delpaths([])`,
`setpath([];.)`, `last(.)`, `debug`, `stderr`, and scalar coincidences (`not|not` on true,
`tojson|fromjson` on true and null). Cause: `path_intact` calls `jv_identical` (execute.c). jqj:
`debug` and `stderr` are SAME; every other is NONE and raises "Invalid path expression with result
…". New DEVIATIONS rows (ADR-0007: identity is no rule); jqj raises where jq accepts, never the
reverse. Also `{"k":"xy"}|path(.k|ltrimstr("x"))` → `["k",{"start":1,"end":null}]` on jq-ref.

### Decided beyond the draft

- **A1. A destructuring pattern in path position raises.**
  `{"a":["b"],"b":0,"c":0} | (.a as [$x] ?// $x | if ($x|type)=="string" then .b else .c end) |= 1`
  → `{"a":["b"],"b":0,"c":1}`; `(.a as [$x] | .b) |= 5` raises "…near attempt to access element 0
  of [1]". The pattern's first matcher step raises jq's message for each source result; the IR
  marks pattern Binds (designer picks the mark). DEVIATIONS rows stay where jq continues by a
  quirk: P3's `[[0,0],[1,0]]`, the existing `. as [$a] | $a` row, `foreach .[] as $x (0;$x;.)` →
  `[[0],[1]]`, and the element named for multi-step patterns on a tracked value.
- **A2. [2b] Update and PathOf are resumable wherever 2b makes other kinds resumable.** jq-ref runs
  `walk(.)` on a 20 000-deep value and `[paths]` on a 10 000-deep one; both are reds of the TRACKED
  ticket (conservative) and the FIXED/WALK ticket (optimised).
- **A3. `debug/0`, `stderr/0` and ADR-0017's DebugSink move into 3a.** CE4, the retire rule,
  ownership-v1 and the SAME rule need them; no DebugSink exists yet.

### Ticket shape (approved by the adviser for the owner): 14 (11 class C, 3 class B)

3a (10): 1. Facts with `keepsArgsIsSound`; 2. getpath/setpath/delpaths with the depth limit;
3. debug/stderr (B); 4. paths-v1 (B); 5. path mode: PathOf, loop sources, item paths, pattern
raise, the P6 rows; 6. path mode through calls, with `paths`/`..` and the U re-run; 7. TRACKED
Update: Kind, delete list, limits; 8. the Update's own owner; 9. path-shape analysis; 10. FIXED/WALK
run, with G5.

3b (4): ownership-v1 (B), the licence, in-place with G4, nested reduce.

### Records to write

- Amend ADR-0005 (PathOf, Update, Kind, Facts, pattern mark), 0007 (validity rows, the 10 000
  rule), 0009 (KEY_LIST), 0010 (loop sources, hidden item slot, patterns, frame-held walk state),
  0022 (own owner and the never-change tests in 3a), 0023 with GATE's wording (split, G5, debug),
  0029 or a new ADR (Path too deep), 0034 and 0035 (rates, charges), 0041 (loop Bind shape), 0042
  (two replacing natives), 0020 (paths-v1).
- Findings: patterns in path mode; `=` against `|=`; debug needed before spec 5; the designer's two
  ADR-0010 conflicts.
- DEVIATIONS: the P6 and A1 rows.

### Open

- Round 2 needed: reconcile questions 3, 5, 11 and A2 with 2b's record; the designer confirms the
  pattern mark; may spec 4 start after 3a, beside 3b?
- Missing facts: whether any of spec 3's 110 census cases has a builtin or pattern in path position
  (the adviser's grep found none; ticket 5's red settles it); the cost of Path records (ADR-0010).

## Round 2 (grilling-adviser for the owner, 2026-11-14)

1. **Question 3: ticket 1 creates `Facts`.** 2b's records add nothing to Operation; its cardinality
   reads the call form. Ticket 1 declares operations so that 3b's `InPlace` changes no declaration.
2. **Question 5: confirmed, with one sentence added to ADR-0045 now** (2b carries it). `D_f` and the
   unit weights also cover the path variants and the FIXED/WALK run; ticket 6's re-run calibrates.
3. **Question 11, A2: `PathOf` and `Update` are kinds 21 and 22, and a ticket that builds a kind or
   a path variant builds its heap form** (red: its cases in the `trampoline` run). No Stub can stand
   in: it is a compile error and `stackBytes` is per run. Paths cannot be collected first: on jq-ref
   `[1,2]|(.[]|debug("L")) |= (debug("R")|.+1)` prints L, R, L, R. Hidden-slot walk state survives a
   suspension: a heap form works on its activation's cells and a recursive right side has its own
   frame; an Update sets its slots at entry and clears them at its end, and an abandoned run
   restores nothing. FIXED and WALK keep heap forms and the licence on the driver; the `trampoline`
   run is conservative, so ticket 10 adds `stackZeroAgrees` (paths-v1, optimised, `stackBytes` 0).
4. **The pattern mark is `Tracking {VALUE, PATH, ITEM}`** on `Bind`, each value of a `Binds` and an
   `AltBind`'s value, read by path variants only. PATH: the value runs in path mode on the bind's
   input, its paths dropped. ITEM: also, the Path is kept in a hidden slot for the slot's `Var`. The
   Resolver sets PATH on a pattern's index steps and a `reduce`/`foreach` source, ITEM on the source
   of `foreach … as $name`; a step then raises by ADR-0010's untracked-step rule. It replaces
   question 4's "directly under `each`" (a `Binds` or an AltBind breaks it). Not derivable: on
   `{"a":[1],"b":0}` jq-ref gives `[["b"]]` for `.a as $v|[path(($v|.[0]) as $x|.b)]` and raises for
   `[path(.a as [$x]|.b)]`. New row: `[[5],[6]]|[path(foreach .[] as $x ?// [$x] (0;1;$x))]` gives
   `[[0],[1]]` on jq-ref, an error in jqj. A node kind would cost a heap form and a `Binds` twin.
5. **Spec 4 may start once 3a is on main, beside 3b** (ADR-0023's order: owner on Monday): its own
   integration branch, the same three-writer cap, no edit to `Operation`, licence or Owner code. 3b
   lands first; spec 4 merges main before its diagnostics ticket and gate (`sub` needs 3b's append).
6. **P6 after spec 4's M7:** `ltrimstr`, `rtrimstr`, `trimstr` leave it. Spec 3 keeps `tostring`,
   `@text`, `tonumber`, `delpaths([])`, `setpath([];.)`, `not|not`. Spec 4 writes, with the natives:
   `trim`, `ltrim`, `rtrim`, `tojson|fromjson`, and the `ltrimstr` cases (`startswith` is unbuilt).
7. **Ticket shape: 15 (12 class C, 3 class B), 3a 11.** Ticket 5 splits by kind: 5 keeps PathOf, the
   kinds without a loop or pattern and P6; 5b takes loop sources, item paths, `Tracking`, AltBind.
8. **Records, added:** ADR-0046, 0047 (the Path on a jump), 0005, 0010, 0038, 0020, 0023, glossary.
9. **Open:** no round 3 unless 2b's Call ticket cannot keep ADR-0045's sentence. Missing facts: path
   heap forms as twins or with one Path register (ticket 5 measures); `gsub`'s cost before 3b.

## Records round (architect, 2026-11-14)

The records designer drafted ADR-0048, F-59 to F-62 and the amendments; ADR-0049, ADR-0050, F-63
and F-64 stay unused. The architect settled:

1. **Fused chains.** F-44's fused chain fits no `PathRule`. In a path variant it runs its steps one
   by one, each by its own rule; fusion applies to value mode only (ADR-0048, ADR-0010).
2. **Probes** (jq-ref 1.8.2): a user's `_modify/2` also catches `op=` and `//=`, and a user's
   `_assign/2` catches `=` (each gives `42`). At n = 10 001, `=`, `del`, `getpath` and `setpath`
   raise "Path too deep"; `|=` raises it at n = 10 000.
3. **`paths-v1`'s minimum** is GATE check 2's 1000 after drops (ADR-0020's spec-3 amendment).
4. **The Workspace path stack** leaves the glossary: F-62 took its only stated user.

Open for the tickets: P3's filter and input, `[path(foreach .[] as $x (0;$x;.))]`'s input and the
multi-step-pattern probe, which the DEVIATIONS rows of F-59 need (each pinning case records its
row);
whether PathOf and Update tick on their own, and what getpath and setpath tick.

For the owner on 2026-11-16: ADR-0010's Bind rule gives way to Tracking (F-59); GATE check 2's
`debug` and `stderr` in 3a (F-61); ADR-0023's spec 3 row and GATE row 3 (the split, G5 and the own
owner in 3a); spec 4 beside 3b.

## Ticket approval (grilling-adviser for the owner, 2026-11-14)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, for the
owner's review on 2026-11-16. The 15 tickets (12 class C, 3 class B) are approved with changes;
the blocking graph is unchanged. The architect re-ran every jq value on jq-ref 1.8.2.
1. **Conflicts.** The 40-line cap binds hand-backs, not the drafts. A2's `[paths]` witness is
   S3a.7's red and a required case from S3a.8 on. The query-core `paths` interface is neither
   S3a.1's nor S3a.2's: S3a.2 builds only jq-core `Paths` behind the natives, S3a.5 adds what PathOf
   calls and S3a.8 what Update calls (ADR-0048, ADR-0034). S3a.3 stays class B (a host SPI method,
   ADR-0017, F-61). The F-59 cases are S3a.6's red. The four F-59 rows are written into
   `DEVIATIONS.md` now; S3a.6 adds only their cases. The `. + [input]` licence fact goes to spec
   5b's hold-run ticket (spec 5 round 2, question 10).
2. **Proposals.** `keepsArgs` is true for getpath, setpath, delpaths, `debug` and `stderr`;
   `reads` is false for all five (F-79; spec 5 round 2, grilling-adviser for the owner under the
   delegation of 2026-11-14). S3a.9's 64 MiB stands. The census has 110 cases, all 3a's: `debug`
   moves jq.test:2341 from spec 7, and none of the 110 needs in-place writes or has a pattern in
   path position. S3a.6's foreach-extract input is `[[5],[6]]`.
3. **Depth limits** (3a body, S3a.8, ADR-0029): `|=`, `op=` and `//=` raise "Path too deep" at
   n ≥ 10 001 before the right side runs, and at n = 10 000 after a right-side result; ASSIGN
   raises at n ≥ 10 001. At 10 001, `|= (debug|error("rhs"))` prints nothing and raises "Path too
   deep"; `= (debug|1)` prints the debug line, then raises.
4. **Tick rates** (S3a.2, S3a.5, S3a.8, ADR-0034): getpath and setpath once per path component,
   setpath also per copied element; delpaths per path, per compare of its path sort, per component
   walked and per element copied, compacting each container once. PathOf ticks per component of
   each path value it builds; Update ticks nothing of its own (ADR-0041's rule).
5. **S3a.2's red** adds the three tick tests and `delpaths`' sorted-order checks: `try
   delpaths([[range(10001)|0], 0]) catch .` → `"Path must be specified as array, not number"`;
   with `{}` first → `"Path too deep"`; `{"a":1,"b":[1]} | try delpaths([["b","x"],["a",0]])
   catch .` → `"Cannot delete fields from number"`. NOTICE names `jv_getpath`, `jv_setpath`,
   `jv_delpaths`, `delpaths_sorted` and `jv_dels` (`src/jv_aux.c`).
6. **S3a.3** builds `DebugSink`, `Host.builder()`, `Host.Builder.debug(DebugSink)` and
   `Host.Builder.build()`, the only new public signatures; any other raises it to class C by a
   comment. `debug`'s line follows `-a`, `-S` and `-C` and stays compact under `--tab`; `stderr`
   ignores all four. It also changes the census (`scripts/census.java`, `census write`).
7. **Generator bounds**: S3a.4 (`paths-v1`) and S3b.1 (`ownership-v1`) get 1 600 seeds, written
   document and filter bounds and freeze floors of 1000 kept seeds (GATE check 2), per-form and
   per-hazard floors, rc 5 in at most 40 % and no rc 3, timeout or two-run rejection.
8. **S3a.5 and S3a.6 reds**: `null | try path(getpath([range(10001)|0])) catch .` → `"Path too
   deep"`, 10 000 gives a path of length 10000. S3a.6 pins the four new rows and records the
   `[$a,$b]` and `{a:$x, b:$y}` cases where both raise alike, and `[[5],[6]] |
   [path(foreach .[] as $x (0;1;$x|.[0]))]` → `[[0,0],[1,0]]`.
9. **A2 witnesses**: S3a.7's `[paths]` on a 10 000-deep array → `10000` in all three runs with
   `bytesBuilt` 1 GiB, posting its peak heap; S3a.8's `walk(.)` on 20 000 nested objects →
   `60025` after `tojson | length`, the `[.]` form → `20021`.
10. **S3a.9** checks the optimised and the conservative compile under 64 MiB and gains the
    `copy-only` run (ADR-0020, F-18). **S3a.10** writes `(.[] | select(.a)) |= f`: unparenthesised,
    jq-ref updates inside each element.
11. **3b**: S3b.2 keeps only the licence analysis (K1) shown through `explain()`; `InPlace`, the
    Reduce and Foreach owners and retire move to S3b.3, where a write makes them observable.
    S3b.3's G4 growth is recorded and fails nothing before spec 6 (ADR-0023). S3b.4: 1000 and
    2000 rows each under `bytesBuilt` 64 MiB, their bytes built at most 1 MiB apart.
12. A case marked *guard* passes before its ticket and must stay green: S3a.9's CE3, CE6,
    `(.[] | (.[], .)) |= f`, error and break cases; S3b.2's three filters; S3b.4's retire case.

Not measured: the floors, the 1 MiB difference and the 1 GiB setting; a red that cannot meet one
is a finding, not a lowered bound. Records: `DEVIATIONS.md` (four rows), ADR-0048, ADR-0034,
ADR-0029, F-59, F-61; 5a's tickets (`spec-5-grilling.md`); the owner's list. Open: the cost of
Path records, the conservative G5 row that S3a.11 records; the heap-form measurement.
