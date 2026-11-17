# Coding standards

Read by `reviewer-standards` on every review, and by writers when a hand-back comes back with a
FAIL. Mechanical rules belong in a check (a build rule, the enforcer, a CI job), not here; this
file holds judgement calls. A rule that a check now enforces is deleted from this file.

Rules marked _(Phase 0)_ wait for the decision named and may change with it.

## Tests

- **Would this test fail if the code were wrong?** Every test, every review. A test that kills no
  mutant of the code it covers is not a test.
- Tests assert observable behaviour at the seams the spec names, never internals, never
  "no exception thrown".
- Expected values come from an independent source: the reference jq binary, a case of jq's own
  test files, a literal from the spec. Never recomputed the way the code computes them, and never
  a jq behaviour from memory.
- Mocks only at a port (the host SPI: clock, environment, module loader, sinks), never of the
  class under test.
- No `@Disabled`, no skipped cases, no swallowed exceptions, no `assertTrue(true)`, no sleeps; a
  fake clock drives time.
- The test name states the behaviour it protects, in glossary terms.
- A negative case ("never reads", "not emitted", "not copied") has a witness that cannot be
  vacuous.
- Property tests for anything with an algebra (ownership: an in-place update is never visible
  through another reference; reader and writer round trip; both backends agree): JUnit
  parameterised or dynamic tests over a seeded `RandomGenerator`, one case per seed with the seed
  in the case name; a counterexample once found becomes an explicit case. No jqwik: from 1.10.1 it
  tells AI agents not to use it.
- A filter-level behaviour is tested as an oracle case (filter, input, expected output), not as a
  hand-built IR test, unless the ticket names an IR seam.
- An oracle case is never deleted or renamed. A case we fail on purpose is listed in
  `DEVIATIONS.md` with its reason; it is not removed from the suite.

## Java (25)

- Data is `record`s and sealed interfaces; behaviour switches with exhaustive pattern `switch`.
  A `default` branch on a sealed type needs a reason in a comment.
- No mutable static state; clock, environment and randomness come through the host SPI.
- No `ThreadLocal`: per-evaluation state is the `Workspace`, passed as a parameter.
- An interface exists where a second implementation exists on day 1 (a port and its fake, the two
  backends). Nowhere else.
- Final APIs only. A preview or incubator API sits behind an opt-in flag and has a fallback.
- Hot paths (the reader, the writer, the runtime library the backends call, generated code):
  - no boxing, no streams, no lambdas capturing per-result state, no `String` built from input
    bytes unless the filter needs text; JMH decides, not taste;
  - no collection between two pipeline stages; an array exists only where the filter asks for one;
    a native's scratch of tape positions within one call, and the plan of a repeated-key object that `.[]` holds while it emits, are no such collection: they hold positions, never values (ADR-0044);
  - every loop back-edge checks the budget. This is not optional;
  - a value is updated in place only under the ownership rule _(Phase 0: ticket 8)_; when in
    doubt, copy;
  - a performance claim in a ticket or PR counts only with a JMH number in `bench/RESULTS.md`.
- Strings in the core are byte slices. UTF-8 byte order is the sort order; `String.compareTo` on
  jq strings is a bug.
- Sizes and counts are `long` where they can exceed 2³¹; every input has a finite limit, never an
  unlimited default.
- Errors carry the filter position (line, column) or the input byte position a person can act on.
- Generated classes: only the bytecode backend defines hidden classes; a cache of compiled
  filters is bounded and never sits in a static field, so a dropped filter unloads.

## Module boundaries

- Dependency direction is enforced by JPMS and the enforcer _(Phase 0: ticket 14)_. `query-core`
  never mentions JSON or jq. Changing a rule is an architecture change: finding first.
- No central registry file that every ticket edits (builtin tables, error-code lists). Builtin
  families register themselves (one file per family, ServiceLoader or a generated index), so three
  parallel tickets never conflict on one file.

## Smells

`/code-review` adds Fowler's smell baseline as judgement calls. Two get extra weight here:
**Speculative Generality** (no hook, parameter or abstraction the spec does not need — the XML
door of the founding architecture §14 is four named rules, not a licence to generalise) and
**Shotgun Surgery** (one logical change spread across modules usually means a missing seam).
