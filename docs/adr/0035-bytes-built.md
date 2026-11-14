# Bytes built: charged by the builder, before allocation, in RunState

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #64; designer draft); amends ADR-0030 (bytes built join RunState) and ADR-0017 (how the fixed charges are measured, F-39)

#64 is the first ticket that builds arrays, objects and strings during a run, so it is the first user of ADR-0017's bytes-built budget. ADR-0030 deferred the counter to that user. This ADR fixes where the charge happens and how its fixed costs are set.

## Decision

- **RunState** holds the bytes-built count and its limit. `chargeBuilt(n)` adds `n` and, past the limit, raises `Termination(BUDGET, Budget.BYTES_BUILT)`; `JqFilter.run` translates it to exit 5 with the message "Bytes-built budget exceeded" (ours, as ADR-0030's other budget messages are). `end` leaves it unlimited, as for every budget.
- **The builder charges before it allocates**: a collector, an object constructor or a string builder calls `chargeBuilt` for what it is about to add, so a run never holds more than its limit plus one charge.
- **`Charges`** (in `query-core`) names the rates: `ELEMENT` 8 B per array element, `ENTRY` 16 B per object entry (ADR-0017), and `CONTAINER` and `STRING`, the fixed charges per built container and per built string. The ticket measures `CONTAINER` and `STRING` with JMH `-prof gc` (allocated bytes per operation) and records them in `bench/RESULTS.md`.
- **A collector charges per element collected**, not by the capacity it grows to; growth beyond the elements is the builder's own cost (amends ADR-0017's "growth charges the capacity it adds" for collectors only; copies and `detach()` keep ADR-0017's rule).
- **Ticks**: a collector's growth copies charge no tick; they average a constant per element and are bounded by the body's own ticks. A slice copy keeps ADR-0034's tick per element and also charges `CONTAINER + ELEMENT × length` before it allocates.
- Number boxes are never charged (ADR-0017).

## Considered options

- **Charge after building**: a single large construction overshoots the limit by its own size.
- **Charge by capacity in collectors**: the count then depends on the growth policy, so a change to it moves budget results.
- **JOL to measure the fixed charges**: GPL-2.0, not on the licence list (F-39).
- **A counter in the Workspace**: `query-core` cannot name it (founding §14), and the other budgets are already in RunState.

## Amendment (2026-11-13, ticket #65)

- **An operator charges its result by the value it builds**: a string STRING plus its UTF-8 length, an array CONTAINER plus ELEMENT per element kept, an object CONTAINER plus ENTRY per distinct key as it enters (at every merge level). An operand returned unchanged (`null + x`) charges nothing; error texts are not charged.
- **A builder that knows its length only after a scratch dump** (`@text`, later `tostring`) charges after the dump and before the value exists.
- **Folding at parse time** builds no value larger than 4 KiB: a string or container result past it is left unfolded, so the run builds and charges it. The output is the same, since a string key is valid either way.
- **Key-kind check past the cap** (#65 standards review):
  - When a capped fold gives nothing and the key is an array or object expression, it is folded again for the kind check only, so jq's compile error (rc 3) is kept, as in `{(("x"*5000)/"x"):1}`.
  - That second fold runs under its own bytes-built bound of 1 MiB, shared by the whole constant expression. Each cap, the 4 KiB and the 1 MiB, holds for the whole fold, not per operator, so a container of folds is bounded too. Past that bound the key is left to the run, which raises the same text with rc 5. DEVIATIONS row "Huge constant keys".
  - A string result is never folded again.
- **One fold budget per compile** (#65, third standards review):
  - Every parse-time fold of one compile, the key-kind check included, shares one RunState.
  - That RunState holds 1 MiB of bytes built and 2²⁰ steps; it never runs unlimited.
  - Each fold result is still capped at 4 KiB; the key-kind check is bounded by the compile budget itself.
  - Once the compile budget is spent, later folds are left to the run, so the output is the same. A key-kind error past it follows DEVIATIONS "Huge constant keys".
  - "Builds no value larger than 4 KiB" means no fold result; literals written in the filter text are not folds.
  - Each AST node is folded at most once per compile: folding is bottom-up and its result is kept. A literal a fold builds is charged like any other built value, so no fold work goes uncharged (#65, fourth standards review).
- Considered: a counting pre-pass (twice the work); upper-bound charges (they depend on the operands); folding any size, as jq does (an unbounded allocation at compile time).
