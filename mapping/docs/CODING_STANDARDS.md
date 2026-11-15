# Coding standards

Read by `reviewer-standards` on every review, and by writers when a hand-back comes back with a
FAIL. Mechanical rules belong in a check (ArchUnit, ESLint, a CI job), not here; this file holds
judgement calls. A rule that a check now enforces is deleted from this file.

Rules marked _(Phase 0)_ wait for the decision named and may change with it.

## Tests

- **Would this test fail if the code were wrong?** Every test, every review. A test that kills no
  mutant of the code it covers is not a test.
- Tests assert observable behaviour at the seams the spec names, never internals, never
  "no exception thrown".
- Expected values come from an independent source: an oracle, a worked example, a literal from
  the spec. Never recomputed the way the code computes them.
- Mocks only at a port (I/O, clock, id generation), never of the class under test.
- No `@Disabled`, no skipped cases, no swallowed exceptions, no `assertTrue(true)`, no sleeps; a
  fake clock drives time.
- The test name states the behaviour it protects, in glossary terms.
- A negative case ("never reads", "not emitted") has a witness that cannot be vacuous.
- Property tests for queue operations and anything with an algebra (context functions compose,
  block invariants hold, `Lift` equals a reference implementation): JUnit 6 parameterised or
  dynamic tests over a seeded `RandomGenerator` in the module's test sources, the seed in the
  case name; a counterexample once found becomes an explicit case. No jqwik: from 1.10.1 it tells
  AI agents not to use it (ADR-0018).
- A corpus case states its provenance honestly: an expected output typed in from our own reading
  is `assumed` (`decided` if an owner decision or our rule), never `documented` or `observed`. A case is never deleted or renamed; a corrected
  expectation cites the observation or ADR that corrected it (ADR-0018).
- A mapping-level behaviour is tested as a corpus case in `engine/oracle`, not as a hand-built
  IR test, unless the ticket names an IR seam.

## Java (25)

- Data is `record`s and sealed interfaces; behaviour switches with exhaustive pattern `switch`.
  A `default` branch on a sealed type needs a reason in a comment.
- No mutable static state; clock, ids and randomness are injected.
- An interface exists where a second implementation exists on day 1 (a port and its fake, two
  runtime representations). Nowhere else.
- Hot paths allocate deliberately: no boxing, no streams, no lambdas capturing per-item state in
  the runtime's inner loops; JMH decides, not taste.
- Hot paths (ADR-0021):
  - the closure compiler builds a mapping's node tree once. Per-item code does no lookup, no
    name, no map and no boxing, and its dynamic context is frame slots;
  - traced and untraced are two compiles. An untraced node holds no hook and tests no
    `tracer != null`;
  - every loop over slots or contexts checks for interruption. This is not optional;
  - a performance claim in a ticket or PR counts only with a JMH number in `bench/RESULTS.md`.
- The logical XDM contract and the physical runtime representations are separate types; code
  generated for a hot path uses the concrete representation, generic code uses the contract
  _(Phase 0: XDM and representation ADRs)_.
- Sizes and counts are `long` where they can exceed 2³¹; every input has a finite limit, never an
  unlimited default.
- Errors carry the mapping location (target field, function, source path) a person can act on.
- UDFs (ADR-0020): only `engine/udf` compiles, scans, instruments and loads UDF classes, and only
  the runtime's adapter calls their handles. No other code reflects on a UDF class. A cache of UDF
  libraries or handles is bounded and never sits in a static field, so a dropped library unloads.
  A test of the guard or the allow-list has a UDF that would otherwise loop or escape as its witness.

## Module boundaries

- Dependency direction is enforced by JPMS between engine modules, by ArchUnit for the service
  and by the enforcer for artefacts (ADR-0016). Changing a rule is an architecture change:
  finding first.
- No central registry file that every ticket edits (function lists, routing tables, i18n bundles).
  Families register themselves (one file per family, ServiceLoader or generated index), so three
  parallel tickets never conflict on one file.

## Frontend (`ui/`, React + PatternFly)

- TypeScript strict; no `any` without a comment saying why.
- PatternFly components and tokens first; a custom component needs a reason in the PR body.
- Server state through one data layer _(Phase 0: UI architecture ADR)_, never `fetch` in a
  component.
- The editor's model types are generated from the mapping-model schema, never hand-written twice.
- Accessibility: every interactive element reachable by keyboard and labelled.

## Smells

`/code-review` adds Fowler's smell baseline as judgement calls. Two get extra weight here:
**Speculative Generality** (no hook, parameter or abstraction the spec does not need) and
**Shotgun Surgery** (one logical change spread across modules usually means a missing seam).
