# A read object is walked only through its key proof

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #85; designer draft; the owner chose option D on #82); amends ADR-0002 (duplicate keys), ADR-0008 (the proof's ticks), ADR-0030 (RunState scratch), ADR-0034 (Many loop slots), ADR-0037 (line 11's walk citation) and ADR-0042 (`to_entries` native); closes F-40; amended by #95 (Equality's key index)

A walk over a read object's members costs n² key comparisons (F-40): 39.9 s for `-c .` on 78 841
keys, against 0.06 s on jq-ref. Proving the keys distinct in the reader made every read pay (#82,
over its cost bar). The owner chose to prove them on the walk side instead.

## Decision

- **`ObjectView` is no `Members`.** Every walk of a read object begins with
  `KeyProof.walk(RunState, ObjectView, boolean written)`, which returns an
  `ObjectWalk(Document doc, int index, int size, int[] plan)` that implements `Members` and `Keyed`.
  Its cursor is the key's tape index when `plan` is null, or a position in `plan` (pairs of a key
  index and its last value's index) when a key repeats. A walker that skips the proof fails
  loudly, not slowly. `ObjectView` keeps `Keyed.get`; a field read scans as before and pays
  nothing.
  The Printer is the one walker that enters through `KeyProof.plan(RunState, Document, int, boolean)`
  instead (#85): the same proof and ticks, without an `ObjectWalk` per printed object. It walks the
  tape when `plan` is null and the plan's pairs otherwise; no other walker may call `plan`.
- **Walkers**: `.[]`, Equality's left side (a read right side is no walk: its key index, amendment
  below), Order's key
  lists, ObjectMerge (both sides), the Printer, Detach (replaces its TreeMap), `keys`,
  `keys_unsorted`, `length`, `flatten`, `to_entries` (now a native, replacing the prelude definition), and the
  host accessors `size()`/`forEach`.
- **`.[]` holds its walk in a loop slot**: `Many.loopSlots()` (default 0, Iterate 1);
  `IntrinsicManyOp` allocates `args.length + loopSlots` per loop.
- **The proof**: pairwise compares up to 8 keys; otherwise open addressing on decoded key bytes in
  `RunState.ints(n)`, a reused `int[]` (query-core, format-neutral), a power of two ≥ 4·n slots
  capped at 2^20, with a probe budget of 4·n for the whole proof; a probe that compares two keys
  of one length weighs max(1, KiB of the key) against that budget (#85), so long colliding keys
  reach the sort, not 4·n full-length compares. Over the budget (crafted
  collisions) it sorts the key ordinals by unsigned decoded bytes with jqj's stable merge sort
  (ADR-0012, F-48); equal neighbours are repeats. Worst case O(key bytes + n log n), never n². The
  first repeat starts the plan: first place, last value, as jq.
- **The knowledge lives as long as its walk.** A second walk proves again (at most twice a linear
  walk). Not per view (views are made anew), not per document (an Owned document is shared across
  threads; the tape stays read-only and tag 10 stays reserved), not per run (it would grow with the
  input and make step counts depend on history).
- **Ticks** (ADR-0008): one per key entry, max(1, started KiB) for a long key, and one per
  fallback compare, max(1, KiB of the shorter key); the Printer charges through `tickWritten`. Inside a run every caller that prints a read object
  (`tojson`, `tostring`, `@text`, interpolation, and an error's dump of a value) charges the run's state, not a throwaway one (#85).
  Host accessors (`size()`, `forEach`) run outside any run, like Detach (ADR-0016), and charge none.
- **Hot-path rule** (`docs/CODING_STANDARDS.md`): a native's position scratch within one call, and
  the plan of a repeated-key object held by `.[]` while it emits, are not collections between
  pipeline stages: they hold tape positions, never values.
- **Out of scope**: BuiltObject's #83 index on `String.hashCode`.

## Amendment (ticket #95)

- When an object pair's right side is read, Equality takes it through
  `KeyProof.index(RunState, ObjectView, Members)`: the right side's proof, the same code and ticks,
  then, within that call, a lookup of each left key in walk order among the proof's pairs, table or
  sorted distinct keys, until one is missing or as many are found as the right side has distinct
  keys (the sizes then decide). It gives `KeyIndex(doc, size, values)`: the
  right side's distinct count and, per found left member, the tape index of its last value. The
  right side is proved once; its `size` comes from the index. The left side may be read or built.
- Lookups among the pairs or in the table charge nothing: a found key retraces its own insertion
  probes, so they stay within the proof's budget; a missing key's probes end at an empty slot or
  after every slot once, at most once per comparison, within one cluster of at most `size` slots.
  The search's shape is the implementation's: KeyProofTest pins the one in use, WideObjectTest
  accepts either a midpoint or a lower-bound search. A sorted lookup is a binary search, one tick per
  compare, max(1, KiB of the shorter key), all taken before the first member pair. One tick per
  member pair, as before. The identity check comes first, so `. == .` keeps its count.
- `values` is the comparison's own array of tape positions: a nested pair proves again in the same
  `RunState.ints` scratch, so nothing may stay there across a pair. It is position scratch within
  one call under the hot-path rule.
- Rejected: an ObjectWalk mode (every walk would carry a table), a lazy lookup in the scratch
  (nested pairs overwrite it), an owned copy of the table, a scratch arena (changes ADR-0030's API),
  sorting both key lists (changes jq's compare order), checking sizes first (jq compares in left
  key order and raises a depth error before the count).

## Considered options

- **Proof in the reader (tag 10)**: #82, +12 % at 100 KB, +26 % at 10 MB, 2.59× on a flat object;
  over its cost bar.
- **A memo per view, per document or per run**: see the decision.
- **A scan for repeated keys**: leaves a crafted n² on `.[]`.
- **Sort only**: n log n on every wide walk.
- **A seeded hash**: step counts would vary between runs.
- **A trie or radix sort**: linear worst case, but much more code to save a log factor.
- **Lowering Iterate with an input argument**: touches the IR #87 changes, and a root `.[]` would
  read as a whole-input read.
- **Long cursors in `Members`**: every walker changes, and the plan still has nowhere to live.
