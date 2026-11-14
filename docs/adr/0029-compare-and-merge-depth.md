# Equality, ordering and object merge stop at jq's depth limits

Status: accepted (2026-11-12, owner, spec 2 grilling round 1, Q8); completed by ADR-0037 for ordering (#66); extended to `contains` by spec 4 grilling round 1 (question 11); extended to path depth by spec 3 grilling round 1 (question 8)

jq 1.8.2 limits three recursive operations to a fixed depth: `jv_equal` (`MAX_EQUAL_DEPTH`, 10 000,
`src/jv.c`), `jv_cmp` (`MAX_CMP_DEPTH`, 10 000, `src/jv_aux.c`) and the deep object merge `*`
(`MAX_OBJECT_MERGE_DEPTH`, 10 000, `src/jv.c`). Past the limit jq raises the catchable errors
"Equality check too deep", "Comparison too deep" and "Object merge too deep"; jq.test checks them.
No earlier ADR covered them.

## Decision

- `==`, `!=`, the ordering operators and everything that orders (`sort`, `group_by`, `unique`,
  `min`, `max`, object key order) and object `*` follow jq: the same three constants, the same
  messages, catchable jq errors, not budgets and not terminations.
- They are not `Limits.nestingDepth` (the parse depth) and are not configurable: they are jq's
  semantics, not a resource limit.
- They are implemented without Java-stack recursion (an explicit stack), so a 10 000-deep value
  never reaches the stack backstop (ADR-0011).
- Where exactly each limit bites is jq-ref's: at 10 001 levels jq-ref returns `true` for `==` on
  nested arrays and merges nested objects with `*`, but raises "Comparison too deep" for `<` on
  nested objects. Differential cases pin each boundary on both sides.

## Considered options

- **`Limits.nestingDepth` for all three:** one knob, but a host that raises the parse depth would
  change what `==` returns, and jq's check-1 cases would depend on a setting.
- **Recursive Java code:** simplest, but on the 512 KiB default stack a deep compare ends as a STACK
  termination where jq returns a value or a catchable error.

## Correction (2026-11-13, tickets #65 and #66)

The level counts above were not each recorded. The #65 and #66 reds recorded them on jq-ref 1.8.2, and those cases bind:
- `==` and `<` on nested arrays: 10 000 levels compare, and 10 001 raise.
- On objects, the key list sits one level deeper (`jvp_cmp`), so an empty object at depth 10 000 already raises.
- `*` merges 10 000 levels around `{}` and raises at 10 001; around a non-object innermost value it merges 10 001 and raises at 10 002.
jq-ref parses only 5 000 nested objects from a document, so the object boundaries are pinned by JUnit over built values and by #69's `reduce` cases.

## Amendment (spec 4 grilling round 1, question 11)

`contains` follows jq 1.8.2's `jv_contains` (`src/jv.c`), translated under jq's notice. Past
`MAX_CONTAINS_DEPTH`, 10 000, `f_contains` (`src/builtin.c`) raises the catchable "Containment
check too deep". Like the three limits above, it is jq's semantics, not configurable, and runs on
an explicit stack. A: 10 000 arrays around `0` → `true`, 10 001 → "Containment check too deep";
`{a:…}` 10 000 times around `{}` → `true`, 10 001 → the error. Each side is a witness on a 512 KiB
stack.

## Amendment (spec 3 grilling round 1, question 8)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

On jq-ref, with n the path length, every limit raises "Path too deep" (architect's probes,
2026-11-14):
- `|=`, `op=` and `//=` raise "Path too deep" at n ≥ 10 001 before the right side runs, and at
  n = 10 000 after a right-side result (spec 3's ticket approval, grilling-adviser for the owner
  under the delegation of 2026-11-14).
- `=`, `|= empty`, `del`, `getpath`, `setpath` and `delpaths` raise at n ≥ 10 001.
- `path()` and `paths` never raise: `last(paths)|length` → `10001` on a 10 001-deep value.

Like the limits above they are catchable, not configurable, and use no Java-stack recursion. They
live in jq-core's `Paths`, and `Update.Kind` carries the difference of one (F-60). 3a's getpath
ticket pins the natives' boundary and its TRACKED Update ticket the assignments', both on both
sides.
