# Ordering is jq's jv_cmp on an explicit stack; If and Alt join the IR

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #66; designer draft); completes ADR-0029 for ordering; amends ADR-0005 (If, Alt, Truth), ADR-0008 (neither ticks) and ADR-0034 (comparison lowering); Alt and Collect after a break amended by ADR-0039 (#68); sort compare ticks amended by F-48 (#73); the uncharged key-list sort is open, F-53 (#104)

#66 is the first ticket that orders values or branches on truth. S2a.23's orderers reuse its primitive, and #68 lowers `if` onto its If. Equality (`jv_equal`) is #65's (ADR-0034 amendment for #65); #66 builds `==` and `!=` on it.

## Decision

- **`Order.compare(RunState, a, b)`** follows jq 1.8.2's `jvp_cmp` step for step: the depth check (> 10 000 raises "Comparison too deep") first on every call, kinds null < false < true < number < string < array < object, a NaN side read as null at the same depth, strings by unsigned UTF-8 bytes then length, arrays element by element then length, objects by their sorted key lists (depth + 1, each key at + 2) and then their values in sorted-key order. Unlike equality it has no identity check. It runs on an explicit stack, never Java recursion. Every orderer calls it.
- **One number pair rule**: a three-way compare of two numbers (ADR-0012; jq's `jvp_number_cmp`) lives once and is shared by Order and #65's Equality. Its short path uses primitive comparison, so `-0` equals `0`; two Infinity literals of one sign are equal.
- **Ticks**: the member walks' own (#63, ADR-0044), one per key collected per side, one per member pair compared, one per started KiB of the shorter string or of each literal on the BigDecimal path, summed over both sides (#66 review). A sort's comparisons tick like any other; the count is that of jqj's own merge sort (F-48). A comparison builds no value and charges no bytes built; its scratch key orders are not charged.
- **Lowering**: `l OP r` for `== != < <= > >=` binds its right operand outside its left, like the arithmetic operators (ADR-0034); an Identity or Const operand is passed as the argument.
- **`If(source, cond, then, otherwise, truth)` and `Alt(source, left, right, truth)`** take a query-core `Truth` predicate, passed as data like `Collector`; jq-core's is "neither false nor null". `a and b` is `If(a, If(b, T, F), F)`, `a or b` is `If(a, T, If(b, T, F))`, `not` is `If(., F, T)`: the left side is the outer loop. `Alt` emits each truthy left result and runs the right side only if there was none; it catches nothing (ADR-0008, observed). Neither node ticks.
- **Folding**: `ConstantKey` folds a comparison of two constants as jq's `constant_fold` does (any kind); `and`, `or` and `//` are not folded. A fold that raises folds to no constant.

## Considered options

- **Java recursion**: ends as ADR-0029's stack termination.
- **A second number rule inside Order**: the two could drift.
- **`String.compareTo` or `ByteBuffer.compareTo`**: UTF-16 order, and signed bytes.
- **Ticking the sort's comparisons**: the count would depend on the algorithm.
- **An identity check in compare**: `jvp_cmp` has none.
- **And and Or IR nodes**: If covers both, as in jq.
- **`//` as a Try**: it catches nothing.
