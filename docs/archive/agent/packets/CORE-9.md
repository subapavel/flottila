# CORE-9 — Kafka out of the core (class B)

Row: agent/BACKLOG.md CORE-9. docs/CORE-REWORK.md:34 O-1. Done-when: docs/GATE.md:91-94 CORE-GATE condition 6.

Facts (scout, after 8d40424):
- src/main has no Kafka client anywhere (no org.apache.kafka / io.vertx.kafka import). Only `queue: kafka`, refused at deploy,
  Deployments.java:1138-1139 ("not built yet (Phase 2, later)"), pinned by DeploymentsQueueTest:98-102.
- Poms: only flottila-archtest/pom.xml:76-78 vertx-kafka-client scope test (the violation fixture StepUsesAKafkaClient).
- deploy/compose/docker-compose.yml:54-61 `kafka` service has no `profiles:` — it starts on a plain `up`; nothing depends_on it;
  no FLOTTILA_*KAFKA* key. ClickHouse already sits under `profiles: ["history"]` (:225). ComposeStackTest:29/:95 list kafka.
- CI stack job (.github/workflows ci :130) runs `docker compose up -d --build`, tears down with `--profile history down -v` (:155).
- No IT starts a Kafka container; `verify -Pit` needs none.

## Decision
P1 Compose: `kafka` gets `profiles: ["kafka"]`; header comment says Kafka is the optional event-broker add-on (O-1), off by
  default. CI stack teardown adds `--profile kafka`.
P2 Classpath guard (condition 6, "dependency test"): a test in each of flottila-runtime, flottila-components, flottila-steps
  asserting no Kafka client class is loadable on that module's test classpath (e.g. `org.apache.kafka.clients.producer.Producer`,
  `io.vertx.kafka.client.producer.KafkaProducer` → ClassNotFoundException via the module's class loader). Archtest is excluded
  (its test-scoped fixture is deliberate; the ArchUnit rule stays unchanged).
P3 Deploy refusal text: `queue: kafka` names the add-on: "...sets delivery.queue kafka: Kafka is an optional add-on, not in
  this build; omit it or use postgres". Schema flow.schema.json:31 description likewise (add-on, refused at deploy).
P4 Docs (architect): GATE.md:9 check 1 (Kafka under profile `kafka`, not started); ARCHITECTURE §3 table Kafka row "required"
  → no (add-on, O-1) and §2 :139 one clause; changelog row; D-134. The full §2/§3/§7/§8 rewrite stays CORE-12.

## Acceptance (red first, test-author)
A1 ComposeStackTest: `kafka` has profiles exactly ["kafka"]; no service's depends_on names kafka; no service environment key
   contains KAFKA; the services with no profile (the default `up`) exclude kafka and clickhouse. Existing image checks kept.
A2 Classpath guard per P2 in the three modules (green today — pins; a hand mutant adding vertx-kafka-client to runtime's pom
   at test scope must turn it red, log it).
A3 DeploymentsQueueTest:98-102 expectation changed to the P3 text (test-author's own test change, reason: O-1 wording).
A4 Evidence of condition 6's e2e half: the verifier lists the sync, async, replay and DLQ-retry ITs that pass under
   `verify -Pit` with no Kafka (names + fresh-XML counts), agent/work/CORE-9-xml.txt.

## Red approved (architect, 2026-11-12)
ComposeStackTest :131 kafka profile (red), :138 default-up set exact (red), :148 no depends_on kafka, :162 no KAFKA env key
outside the broker itself (pins; the broker's own KAFKA_* excluded — accepted), :101 clickhouse case narrowed (accepted, the exact
set at :138 carries it). NoKafkaClientTest:28 in runtime and the five leaf modules (components http/mock/sftp, steps xslt/validate),
green on HEAD, red under the pom hand mutant (CORE-9-red-mutants.log). DeploymentsQueueTest:98 P3 text (red). Log CORE-9-red-head.log.
Implementer scope: compose (profile + header), ci.yml teardown `--profile kafka`, Deployments:1139 text, flow.schema.json:31 description.

## Review r1 FAIL → fix round (architect)
R1 guards added in flottila-api and flottila-core (test-author; core red under the pom mutant M2, CORE-9-fix-guards.log); D-134 names api/core.
R2 D-134 draft — review at hand-over (compose topology, precedent D-090), D-085 amended, HANDOVER drafts list.
R3 A4: NodeIT e2e cases named in CORE-9-xml.txt from the run2 XML. R4 changelog 3.13 moved below 3.12, Finding cell added.
Also: §3 :158 ingest clause no longer names Kafka; QUICKSTART:85.
