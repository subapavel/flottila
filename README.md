# Rail8

Open-source enterprise integration platform where **monitoring is the product**: every message,
every step and every payload is captured and searchable — and the runtime also
delivers messages reliably and fast. Java 25 · Vert.x 5.1 · PostgreSQL · ClickHouse · Valkey · S3
(Apache Kafka only as an optional add-on, D-134). Apache 2.0.

**Status: under construction, not usable yet.** Tag `proto-m1` is the milestone-1 state (one
module, Vert.x 4.5, the flow `HTTP → validate → extractKey → route → XSLT → HTTP` with snapshots,
DLQ and a Postgres work queue). The tree is being restructured in place to the v3.3
architecture by an autonomous engineering session; the first thing worth trying by hand arrives at
the hand-over gate described in `docs/GATE.md`.

To send a message through a local node now: `docs/QUICKSTART.md` (Docker Compose, the demo package, the admin API).

Where to look:

- `docs/ARCHITECTURE.md` — the binding design (amendments block at the top, then the v3.3 text)
- `docs/GATE.md` — what "you can test the first real iFlow" means
- `agent/BACKLOG.md` — the open work, until it moves to GitHub Issues
- `AGENTS.md` — how the work is done (one issue, one session, one pull request, one review)
- `docs/DECISIONS.md`, `docs/findings/`, `issues/` — what was decided, what deviated, what is open
- `agent/cloud/` — the cloud environment the autonomous session runs in

Build at the current state, JDK 25. The repository holds two areas, each built on its own
(`docs/ARCHITECTURE.md` §12):

- the platform, `platform/` and `bench/` — Maven 3.9: `mvn -B -ntp -f platform/pom.xml verify` for
  the unit tests, with `-Pit` to include the Testcontainers tests (Docker required). Modules:
  `rail8-api`, `-testkit`, `-core`, `-runtime`, `-components/*`, `-steps/*`, `-schemas`, `-cli`,
  `-sim`, `-archtest`, `-bench`.
- the mapping engine, `mapping/` — imported with its history, with its own parent pom, rules and
  decisions (`mapping/README.md`, `mapping/CLAUDE.md`, `mapping/docs/adr/`):
  `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify`.

The milestone-1 prototype survives as tag `proto-m1` and in the history of the files that moved;
the demo flow it ran lives in `examples/demo/` until P0-12 converts it.

Rail8 (R8 for short) is the product's name. Contributions are accepted under the Developer Certificate of
Origin: sign your commits (`git commit -s`).
