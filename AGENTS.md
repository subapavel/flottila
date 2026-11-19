# Rail8 — rules for agents

Rules for every agent in this repository (Claude Code, Codex, any other). Rail8 is an open-source
integration platform whose goal is "find anything, fast": what it stores about messages is set for
each flow, and it has no replay. Java 25, Vert.x 5.1, PostgreSQL, ClickHouse, Valkey, S3; Apache 2.0.
`docs/ARCHITECTURE.md` describes what the code does today and what is planned: read the parts your
change touches. Everything in the repository is in English.

## How to work

- Think first. State your assumptions; when a requirement is unclear, ask before you code.
- Simplicity: the least code that solves the task. No speculative features, options or layers.
- Surgical changes: touch only what the task needs; leave unrelated code, comments and style alone.
- Verifiable goals: turn the task into a test that fails first, then make it pass.
- Comments and docs are for people: what the code does, its inputs, its outputs. Each one stands on
  its own, with no task ids, decision ids or pointers to other files.
- UI: PatternFly components and design tokens only (skill `patternfly`, PatternFly MCP).
- Contracts first: a new contract (an adapter, a step, the API for custom code, plugin loading) is
  `class:C` work, designed and agreed before the implementation (skill `design-it-twice`).
- Unsure? Ask the owner-proxy: open a GitHub issue labelled `owner-question` (agent `owner-proxy`).
- Library APIs: Context7 first, then `javap` on the resolved jar. Never an API from memory.

## One task

1. One task = one GitHub issue = one fresh session, branched from `main`.
2. The session writes the test and the code. Before it merges, it runs the build with the tests of the
   modules it touched, and `python3 ci/change_guard.py --base origin/main`.
3. It opens a pull request. Every commit is signed off (`git commit -s`, checked by DCO).
4. One review by a different agent (`reviewer`), launched at the effort the class sets: correct behaviour, needless complexity, readable
   comments. Wording in docs is never a reason to send a change back. One round of fixes.
5. Its own build green and the sign-off check green, then the session merges the pull request with the
   rebase method. It does not wait for other checks on GitHub.

The dispatcher (`.claude/routines/dispatcher.md`) picks the tasks and starts their sessions; a
session removes the label `in-progress` from its issue once its pull request is merged.

Design first only for `class:C` work (skill `design-it-twice`). The owner-proxy judges the design
and a second model argues against it. No records beyond the issue, the pull request and the commits.

## Task class

Every task issue carries one class label (an `owner-question` issue carries none). It sets the model and effort of the session and the review:

| Label | Work | Session | Review | Design first |
| --- | --- | --- | --- | --- |
| `class:A` mechanical | docs, CI scripts, fixtures, mock behaviour, compose files | Sonnet 5.5, medium | Opus 5.5, medium | no |
| `class:B` standard | a component with its mock or a step behind an agreed contract, a fix in existing code, the admin API, the CLI | Opus 5.5, high | Opus 5.5, high | no |
| `class:C` hard | a new contract, public types in `rail8-api`, JSON Schemas, storage format, security, batcher, admission, `BlobStore`, concurrency, leases | Opus 5.5, xhigh | Opus 5.5, xhigh | yes, `design-it-twice` |

Whoever opens the issue sets the class; when it is unclear, the owner-proxy decides. When a task
turns out harder than its class, raise the label and say why in the issue. Never lower it mid-task.

Blocking on every change: the sign-off check on GitHub, and the session's own build with unit and
integration tests on one Postgres version. The build runs Error Prone (its errors fail);
`ci/change_guard.py` rejects task or decision references in comments and hard-coded colours on the
changed lines. Once a day, before the owner's morning overview, GitHub builds `main`, runs the tests
and starts the platform in Docker; when that is red, its fix is the first task of the day. Coverage,
mutation tests, a second Postgres, Windows and performance are switched off for now.

## Technical rules

- `Envelope` is an immutable record; every step returns a new one.
- `Body` is one final class over a `BlobStore`: `open()` gives a fresh stream every time. Never a
  consumable stream or a byte array as the domain type.
- `Step` is `Envelope apply(Envelope)`, `Stage` is `Envelope apply(Envelope, Pipeline rest)`. Both
  run on the message's virtual thread and may block through Vert.x clients and `Future.await()`.
  Verticles own sockets and timers and never run a pipeline.
- Dependencies point `api` ← `core` ← `runtime`, enforced by ArchUnit. Components and steps depend
  on `api` only; only `runtime` talks to Postgres, S3, Valkey or ClickHouse.
- All contact with the systems a flow talks to goes through the Component SPI; the platform's own
  stores (Postgres, S3, Valkey, ClickHouse) are not part of that. Every component ships with a
  mock that passes the same contract suite as the real one.
- State whose loss loses messages lives in Postgres, never in a cache.
- Clock, timers and ids are injected. Tests use a fake clock, never sleep.
- Sizes are `long`. Every limit is finite.
- Dependency licences: Apache 2.0, MIT, BSD, MPL 2.0, EPL only. SAP JCo is `provided` scope.
- Never weaken or skip a test to go green; never put a secret in the repository.
