# Flottila

Open-source enterprise integration platform where **monitoring is the product**: every message,
every step and every payload is captured, searchable and replayable — and the runtime also
delivers messages reliably and fast. Java 25 · Vert.x 5.1 · PostgreSQL · Apache Kafka ·
ClickHouse · Valkey · S3. Apache 2.0.

**Status: under construction, not usable yet.** Tag `proto-m1` is the milestone-1 state (one
module, Vert.x 4.5, the flow `HTTP → validate → extractKey → route → XSLT → HTTP` with snapshots,
DLQ, replay and a Postgres work queue). The tree is being restructured in place to the v3.3
architecture by an autonomous engineering session; the first thing worth trying by hand arrives at
the hand-over gate described in `docs/GATE.md`.

To send a message through a local node now: `docs/QUICKSTART.md` (Docker Compose, the demo package, the admin API).

Where to look:

- `docs/ARCHITECTURE.md` — the binding design (amendments block at the top, then the v3.3 text)
- `docs/GATE.md` — what "you can test the first real iFlow" means
- `agent/HANDOFF.md`, `PROGRESS.md` — where the work is, with measurements
- `docs/WORKFLOW.md`, `CLAUDE.md`, `AGENTS.md` — how the work is done (TDD, review, roles)
- `docs/DECISIONS.md`, `docs/findings/`, `issues/` — what was decided, what deviated, what is open
- `deploy/claude-cloud/` — the cloud environment the autonomous session runs in

Build at the current state: JDK 25 and Maven 3.9, `mvn -B -ntp verify` for the unit tests and
`mvn -B -ntp verify -Pit` to include the Testcontainers tests (Docker required). Modules:
`flottila-api`, `-testkit`, `-core`, `-runtime`, `-components/*`, `-steps/*`, `-schemas`, `-cli`,
`-archtest`. The milestone-1 prototype survives as tag `proto-m1` and in the history of the files
that moved; the demo flow it ran lives in `packages/demo/` until P0-12 converts it.

"Flottila" is a working name. Contributions are accepted under the Developer Certificate of
Origin: sign your commits (`git commit -s`).
