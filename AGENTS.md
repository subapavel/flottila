# Rail8 — rules for agents

Rules for every agent in this repository (Claude Code, Codex, any other). Rail8 is an open-source
integration platform where monitoring is the product: every message, step and payload is captured,
searchable and replayable. Java 25, Vert.x 5.1, PostgreSQL, ClickHouse, Valkey, S3; Apache 2.0.
`docs/ARCHITECTURE.md` is the binding design: read the sections your change touches, never the
whole file. Everything in the repository is in English.

## How to work

- Think first. State your assumptions; when a requirement is unclear, ask before you code.
- Simplicity: the least code that solves the task. No speculative features, options or layers.
- Surgical changes: touch only what the task needs; leave unrelated code, comments and style alone.
- Verifiable goals: turn the task into a test that fails first, then make it pass.
- Comments and docs are for people: what the code does, its inputs, its outputs. Each one stands on
  its own, with no task ids, decision ids or pointers to other files.
- UI: PatternFly components and design tokens only (skill `patternfly`, PatternFly MCP).
- Contracts first: for an adapter, a step, the API for custom code or plugin loading, write and
  agree the contract before the implementation (skill `design-it-twice`).
- Unsure? Ask the owner-proxy: open a GitHub issue labelled `owner-question` (agent `owner-proxy`).
- Library APIs: Context7 first, then `javap` on the resolved jar. Never an API from memory.

## One task

1. One task = one GitHub issue = one fresh session, branched from `main`.
2. The session writes the test and the code, and runs the tests of the modules it touched.
3. It opens a pull request. Every commit is signed off (`git commit -s`, checked by DCO).
4. One review by a different agent (`reviewer`): correct behaviour, needless complexity, readable
   comments. Wording in docs is never a reason to send a change back. One round of fixes.
5. Green checks, then the session merges the pull request with the rebase method.

Design first only for hard things: contracts, public API, storage format, security. The
owner-proxy judges the design and a second model argues against it. No records beyond the issue,
the pull request and the commits.

Blocking on every change: the build with unit and integration tests on one Postgres version, and
the sign-off check. Coverage, mutation tests, a second Postgres, Windows, performance and the
platform started in Docker run nightly as a report.

## Technical rules

- `Envelope` is an immutable record; every step returns a new one.
- `Body` is one final class over a `BlobStore`: `open()` gives a fresh stream every time. Never a
  consumable stream or a byte array as the domain type.
- `Step` is `Envelope apply(Envelope)`, `Stage` is `Envelope apply(Envelope, Pipeline rest)`. Both
  run on the message's virtual thread and may block through Vert.x clients and `Future.await()`.
  Verticles own sockets and timers and never run a pipeline.
- Dependencies point `api` ← `core` ← `runtime`, enforced by ArchUnit. Components and steps depend
  on `api` only; only `runtime` talks to Postgres, S3, Valkey or ClickHouse.
- All contact with the outside world goes through the Component SPI. Every component ships with a
  mock that passes the same contract suite as the real one.
- State whose loss loses messages lives in Postgres, never in a cache.
- Clock, timers and ids are injected. Tests use a fake clock, never sleep.
- Sizes are `long`. Every limit is finite.
- Dependency licences: Apache 2.0, MIT, BSD, MPL 2.0, EPL only. SAP JCo is `provided` scope.
- Never weaken or skip a test to go green; never put a secret in the repository.
