# API slice shapes: unlimited budgets, output options, the Stub mark and bench ids

Status: accepted (2026-11-11, architect, on the designer's draft for #24 "S1.02 API slice"); amends ADR-0016 (null and booleans are enums; the exception shapes), ADR-0019 (scenario ids) and ADR-0020 (the Stub verdict); records F-20; bench ids of G2, G3 and G4 added by #74; the `OutputOptions` withers by spec 5 round 1 (ADR-0016)

These shapes bind from the first publish (ADR-0016). They were left open by the ADRs and fixed by
spec 1's API slice, `docs/wayfinder/spec-1-grilling.md` round 1 item 1.

- **Unlimited.** A budget with no limit holds its largest value: `Limits.UNLIMITED`
  (`Long.MAX_VALUE`) and `Limits.UNLIMITED_TIME` (`Duration.ofNanos(Long.MAX_VALUE)`), so no budget
  check needs a branch for it. `Limits` is a record of ADR-0017's eight components. It rejects
  negatives, null and longer durations. `Limits.cli()` holds `UNLIMITED` for the budgets it drops.
  The jqtest runner uses `embedded()` with `UNLIMITED_TIME` as the deadline.
- **`OutputOptions`** is a final class built by factories: `compact()` and `pretty()` (indent 2).
  A later ADR-0015 option arrives as a factory or a wither, never as a constructor parameter.
- **Kinds.** `JqNull` and `JqBool` are public enums (ADR-0003). The four kinds with views
  (`JqNumber`, `JqString`, `JqArray`, `JqObject`) are sealed interfaces whose permits are in
  `.internal.value`. `JqException` is an abstract sealed class with ADR-0016's four subclasses, so a
  host's switch needs no default. `Cause` and `Budget` nest in `JqTermination`, whose `value` is
  `JqNull.NULL` when there is none.
- **The Stub mark.** A Stub throws `JqCompileException` whose message starts with `jqj stub: `.
  This is even at run, read and write sites, because no jq `try`, `?` or `//` can catch a compile
  exception. The malformed-input Stub is a `JqSyntaxException` with the same prefix inside jq's
  frame. Every oracle runner scores FAIL a case whose jqj exception or stderr carries the prefix,
  **before any other verdict rule**. Without that, a `%%FAIL` case that checks only that compiling
  fails, or a differential case recorded as a syntax error, would pass against a Stub (F-20).
- **Bench ids.** `bench.tsv` scenarios are `<gate>[.<mode>].<size>`, with ADR-0019's cell size in
  lower case:
  - `G1.1k`, `G1.100k`, `G1.10m`;
  - `G6.compact.10m`, `G6.pretty.10m`;
  - `G7.engine.1k` (build the engine, compile `.a`, run on 1 KB) and `G7.process.1k` (`jqj -c .a`).
  - (#74) `G2.1k`, `G2.100k`, `G2.10m`; `G3.100k`, `G3.10m`; `G4.1e4`, whose size is the element
    count 10⁴ of ADR-0019's G4 input, not a byte size (`1k` already means 1 KiB).

  Their differential cases are `bench.G1.1k`, `bench.G6.compact.1k`, `bench.G6.pretty.1k` and
  `bench.G7.1k`. In the case format (`<family>/<name>`, ADR-0020) they are written
  `bench/G1.1k` and so on, in the family `diff/bench.cases` (#34).

## Considered options

- No limit spelled as 0 (a legal budget: the backend oracle reruns with half the steps), −1 (a
  branch in every check), null or `Optional` (boxing or an NPE on the refuel path), or withers on
  `Limits` now (one caller, eight more frozen members).
- `OutputOptions` as a record of every ADR-0015 option (each addition breaks the canonical
  constructor; the writer cannot honour them yet) or an enum (cannot grow to `--indent n`).
- A Stub as a `JqFilterException` (a `try` swallows it, so a case can pass), a `JqTermination` (no
  Cause fits) or a fifth subclass (breaks ADR-0016).
- Flags in scenario ids (`G6-c`).

## Amendment (spec 5 round 1)

The later ADR-0015 options arrive as the withers of ADR-0016's spec-5 amendment: `indent(int)`,
`tab()`, `sortKeys()`, `ascii()`, `raw(RawMode)`, `seq()`, `color()` and `color(String)`.
