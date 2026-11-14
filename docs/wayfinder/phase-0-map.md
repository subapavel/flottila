# Phase 0 map — draft for `/wayfinder`

Draft prepared 2026-11-10 from the founding conversation. `/wayfinder` (chart mode) reviews it
with the owner, then publishes the **Map** section as the `wayfinder:map` issue and the tickets as
its child issues, wiring the blocking edges in a second pass. After publishing, the issues are the
source of truth and this file is not updated again.

---

## Map: Phase 0 — decisions before milestone 1

### Destination

Milestone 1 (`docs/GATE.md`, "jq filters run on JSON bytes, end to end") is ready to build: every
decision it stands on is an ADR or a resolution comment, `GLOSSARY.md` has no _(open)_ term the
milestone needs, `docs/GATE.md` is final with its numbers, and the milestone is split into specs
in build order.

### Notes

Agreed in the founding conversation (`docs/FOUNDING-ARCHITECTURE.md`; owner's statements, to be
confirmed or recorded as ADRs by the tickets that touch them — not decisions yet unless an ADR
exists):

- Java 25 minimum, final APIs only; Class-File API, hidden classes, `MemorySegment` / `Arena`,
  sealed types and records, pattern `switch`.
- Input stays UTF-8 bytes plus a tape; values are views or built values behind one sealed type.
- Push-style execution (`emit` returns "keep going"); two backends over one IR.
- Ownership markers replace jq's reference count for in-place updates.
- Explicit `Workspace`, hard budgets, automatic streaming, unloadable filters.
- `query-core` (format-neutral) under `jq-core`; `jq-cli` on top; the Rail8 (R8) adapter is not here.
- Speed is a priority; the target is the same range as the original jq, not first place.
- Owner's decisions already made (founding architecture §2): standalone project, working name
  `jqj`, Apache 2.0, joni for regex, `import` through a host resolver.
- Way of working: ADR-0001; the environment is Claude Code Cloud (4 vCPU).

Skills every session uses here: `grilling` and `domain-modeling` for HITL tickets, `research` for
AFK tickets, `prototype` for the two spikes; the `designer` agent drafts class-C-like shapes
before a grilling session so the owner reacts to a concrete proposal. The owner asked for plain
explanations with concrete impact: every grilling question states what each answer would cost or
change, in a sentence.

### Decisions so far

(empty)

### Not yet specified

- The R8 adapter (`r8-step-jq`): step SPI, envelope variables, shared `JsonDoc` on the
  envelope, monitoring hooks. It is built in the R8 repo.
- An XML front end (XPath / XQuery) on `query-core` (founding architecture §14).
- A GraalVM native image of the CLI; the JDK ahead-of-time cache for start-up.
- A Vector API scanner behind a flag.
- Publishing to Maven Central; the final project name.

### Out of scope

- Branding and the final product name.
- Writing production code: Phase 0 produces decisions, measurements on throwaway branches, and
  the build skeleton only if a task ticket needs it to decide something.

---

## Tickets

Type label in brackets. `Blocked by` names other tickets of this map.

### 1. What exactly must we match? [research]

Pin the reference jq version (the newest 1.8.x release) and say why. From jq's repository and
manual: the language constructs, the full builtin inventory with which are defined in jq and which
in C, the CLI flags, the test files (names, format, case counts) and the licence of each thing we
might copy (function library in jq, test files, manual). Output: a cited note
`docs/research/jq-reference.md` with open ambiguities listed. If the pinned version differs from
`JQ_REF_VERSION` in `deploy/claude-cloud/setup.sh`, say so. Blocked by: none.

### 2. How do other implementations execute jq? [research]

jq's own VM (backtracking, tail calls, path tracking, reference counts), gojq, jaq and jackson-jq:
execution model, how each handles paths and updates, recursion depth, numbers, and what each
reports as its known differences from jq. Output: a cited note `docs/research/jq-implementations.md`
with the lessons that bear on tickets 6–9. Blocked by: none.

### 3. Spike A — does a self-calling jq function survive a big input? [prototype]

A throwaway push-style evaluator, just enough to run four hand-picked recursive filters (a self
tail call, a self call inside "for each result", two functions calling each other, non-tail
recursion) on inputs of 1 000 to 1 000 000 items. Measure where each fails and what the three
candidate fixes cost: self tail call rewritten to a loop, a trampoline, a thread with a large
stack. Compare with the reference jq binary on the same filters. Output: a results section in
`bench/RESULTS.md` and a one-page note saying which cases work, which give a clean error, and at
what speed cost. AFK; the owner reads the note. Blocked by: none.

### 4. Spike B — is reading one field from a big message fast enough? [prototype]

A throwaway reader in two variants: "index everything (tape), then read one field" and "read
forward and stop at the field". Documents of 1 KB, 100 KB and 10 MB, the field near the start,
in the middle and at the end. JMH, time and bytes allocated per read, with the reference jq
binary for scale. Output: a results section in `bench/RESULTS.md` and a note recommending one
reading mode or two. AFK; the owner reads the note. Blocked by: none.

### 5. What are the value model and the tape layout? [grilling]

The sealed value types, views against built values, the tape entry (one long or two, tag values
reserved for other formats), strings as byte slices, number representation, shapes for built
objects, `detach()`. Whether a light forward reading mode exists beside the tape (ticket 4).
Designer draft first. Blocked by: 4.

### 6. What shape does the IR have, and which analyses run on it? [grilling]

Sealed IR node families; which nodes are format-neutral (`query-core`) and which are jq's
navigation; the analyses (cardinality, inlining, path shape, root use, touched fields, constant
folding) and what each must guarantee; how a jq closure and a recursive definition appear in the
IR. Designer draft first. Blocked by: 1, 2.

### 7. What is the execution protocol? [grilling]

`emit` and the stop signal, `label` / `break`, the error type and the non-throwing variants,
budgets on back-edges, how recursion is run given spike A, how path mode is expressed (a second
variant or something else). Designer draft first. Blocked by: 3, 6.

### 8. When may a value be updated in place? [grilling]

The ownership rule: which operations own their accumulator, what counts as an escape, how the
marker is stored, and the property tests that prove an update is never visible through another
reference. Designer draft first. Blocked by: 5, 7.

### 9. How do numbers behave and print? [grilling]

Doubles for arithmetic, original text kept for untouched literals, comparison of large integers,
conversions (`tostring`, `tonumber`, `@text`), and the exact output format, each checked against
the reference binary. What goes into `DEVIATIONS.md`, if anything. Blocked by: 1.

### 10. Which libraries and JDK facilities do we rely on? [research]

joni (licence, maintenance, Java 25, its byte-array API and the Oniguruma syntax jq uses);
Class-File API and hidden-class facts we depend on (class data, constant dynamic, size limits);
`MemorySegment` reads and `Arena` lifetimes; JMH and PIT on JDK 25; a public JSON parsing
conformance suite and its licence. Output: a cited note. Blocked by: none.

### 11. How do the JSON reader and writer behave? [grilling]

Strictness (what is rejected, with which position in the error), limits (depth, document size,
string and number length), input modes, skipping of unread subtrees, the writer's raw-copy rule
and output modes. Blocked by: 5, 9.

### 12. Interpreter or bytecode first, and what speed must milestone 1 show? [grilling]

Which backend milestone 1 ships (r8-mapping chose interpreter first, bytecode after), the
JMH scenarios, the machine label, and the numbers for gate checks 4 and 5: how close to the
reference jq each scenario must be, and the memory targets. Blocked by: 4, 6, 7.

### 13. What are the public API, the host SPI and the sandbox? [grilling]

`JqEngine`, `JqFilter`, `JsonDoc`, sinks, bindings, `Workspace` and limits; the host SPI (clock,
environment, module loader for `import`, debug sink, extra inputs); the defaults for an embedding
host against the CLI; thread-safety and unloading guarantees. Written so the R8 adapter can
be built from it without asking. Designer draft first. Blocked by: 7.

### 14. How is the repo laid out and built? [grilling]

Maven modules (`query-core`, `jq-core`, `jq-cli`, the oracle module, the benchmark module) and
their dependency rules (JPMS, enforcer), JDK 25 on the cloud VM, CI jobs on GitHub Actions, the
scoped commands the ticket gate runs in seconds, which gate scripts are ported from
r8-mapping. Blocked by: 6, 12.

### 15. How do the oracles and the ratchet work? [grilling]

The runner for jq's own test files and its case ids; the differential runner against the
reference binary (case layout, generated cases, flags); the backend-against-backend runner; the
JSON conformance runner; ratchet file formats and the regeneration command; coverage and mutation
floors for class-C modules; `DEVIATIONS.md` format. Blocked by: 1, 10, 14.

### 16. Set up the GitHub repo and the cloud environment [task]

Owner: create the repo, push, run `scripts/bootstrap-github.sh`, protect `main`, create the Claude
Code Cloud environment (`deploy/claude-cloud/README.md`: JDK 25, Maven mirror, Context7, the
reference jq binary). The first session records in `agent/NOTES.md` what was observed (Java
version, `gh` REST forms, Context7, `jq-ref --version`). Blocked by: none.

### 17. Final gate and the milestone's specs [grilling]

Finalise `docs/GATE.md` (pass-list sizes, speed and memory targets, floors) and split milestone 1
into specs in build order, each ready for `/grill-with-docs` and `/to-spec`.
Blocked by: 8, 9, 11, 12, 13, 15.
