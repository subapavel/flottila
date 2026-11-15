# BRAND-1 — scout's inventory (2026-11-15, tree at a6b1201/663a78e, old paths)

Read-only; tracked files outside agent/ docs/ issues/ prompts/ PROGRESS.md HANDOVER.md; target/
ignored. Paths are the OLD ones (`flottila-<module>/...`); after commit A they are under `platform/`.
The architect's rulings for commit C are at the end.

## 1. WIRE / PERSISTED (src/main + deploy/config) — commit C, never commit B

Hash / KDF inputs:
- flottila-runtime/.../journal/RecordFormat.java:43 "flottila-journal-v1" — HKDF info, journal record keys
- flottila-runtime/.../blob/SpoolCipher.java:49 "flottila-spool-v1" — HKDF info, spool keys
- flottila-runtime/.../journal/peer/PeerHandshake.java:54 "flottila-peer-v1" — INFO; :154 HKDF info for session keys
- PeerHandshake.java:55 SUBKEY = INFO+" subkey"; :58 PROOF_LABEL = INFO+" proof"; :59 FINGERPRINT_LABEL = INFO+" fingerprint" (built by concatenation)
- flottila-schemas/.../schemas/Package.java:524 "flottila-package-content 1\n" — content digest input (lock files)
- flottila-runtime/.../deploy/FlowHash.java:66 "flottila-flow-hash 2" (+ newline via line())
- flottila-runtime/.../store/Retention.java:58 LOCK_KEY = 0x666c6f7474696c61L (ASCII "flottila"), PG advisory lock; no text match
- flottila-runtime/.../journal/SegmentFormat.java:25-27 MAGIC 0x464A5331 "FJS1", END_MAGIC "FJSE", NODE_MAGIC "FJN1" — on-disk magic (FJ abbreviation)

Text written to disk:
- flottila-cli/.../cli/Lock.java:67 "# written by flottila lock (D-087)..." — header of package.lock, written at :51
- flottila-runtime/.../journal/JournalStart.java:177 "flottila journal loss v1\n" — loss file text
- flottila-schemas/src/main/resources/schemas/lock.schema.json:5 description containing 'flottila-package-content 1'

Schema identity:
- schemas/{folder,test,flow,package,lock,endpoint}.schema.json:3 "$id": "urn:flottila:schema:<kind>:1" (6 files); :4 titles "Flottila ..." (6 files)
- flottila-schemas/.../schemas/Schemas.java:42 setBaseUri("urn:flottila:schema:")

DB, wire, Valkey, JFR:
- flottila-runtime/.../queue/PgWorkSignals.java:42 "flottila_work" — NOTIFY channel
- flottila-runtime/.../store/PgIntake.java:125 pg_logical_emit_message(true, 'flottila', '') — WAL message prefix
- flottila-runtime/.../lease/ValkeyPollerLeases.java:21 "flottila:poller:" — Valkey key prefix
- flottila-runtime/.../node/Node.java:430 "flottila-"+role+"@"+nodeId — PG application_name
- flottila-runtime/.../admin/AdminServer.java:178 "X-Flottila-Actor" — HTTP header, stored as audit actor (V16)
- flottila-bench/.../DbSampler.java:224 "flottila-bench-sampler" — application_name (bench only)
- flottila-runtime/.../node/NodeConfig.java:682 default PG database "flottila" and default user "flottila"
- NodeConfig.java:1017 default S3 key prefix "flottila"; NodeConfig.java:693 default data dir "/var/lib/flottila"
- flottila-core/.../record/StepEvent.java:36 "io.flottila.Step" — JFR event name; :28 @Label "Flottila Step", :29 @Category "Flottila"
- flottila-runtime/.../node/CpuSampler.java:85 "io.flottila.CpuSamplerDrain" — JFR event name; :95-96 label and category; :165 recording.setName("flottila-cpu-sampler"); :277 createTempFile("flottila-cpu-", ".jfr")
- flottila-cli/.../cli/FlowTests.java:93 "flottila-test" and Validate.java:44 "flottila-validate" — node ids (where a node id is persisted was not traced)

Deploy and config:
- deploy/quickstart/Dockerfile:7 useradd ... --home-dir /var/lib/flottila flottila (uid 10001); :9 WORKDIR /opt/flottila; :10-13 flottila-runtime jar names; :15 USER flottila; :16 ENV FLOTTILA_DATA_DIR=/var/lib/flottila; :19 ENTRYPOINT /opt/flottila/flottila-runtime.jar
- deploy/compose/docker-compose.yml:10-12 POSTGRES_DB/USER/PASSWORD flottila; :17 pg_isready -U flottila -d flottila; :37-38 S3 keys (default "flottila"); :43 SeaweedFS identity name "flottila" (JSON); :122,:133,:201,:220 service name flottila; :137 image flottila-stack:local; :153 bucket "flottila"; :171 mount flottila-data:/var/lib/flottila; :237 volume flottila-data; :166 java -jar /opt/flottila/flottila-runtime.jar; :218 cli jar glob; :220 --admin http://flottila:8081
- docker-compose.yml:10-12 db/user/password flottila; :17 flottila-pg:/var/lib/postgresql; :41 service; :46 image flottila-quickstart:local; :72 flottila-data:/var/lib/flottila; :96-97 volume names
- .github/workflows/ci.yml:175-176 CREATE ROLE/DATABASE flottila (CI only); :61 -Dflottila.it.postgres; :73,:76 bin/flottila
- bench/run.sh:49 PGUSER/PGPASSWORD/BENCH_DB defaults flottila, flottila_bench; :80-81 glob flottila-bench-*.jar; :106 clickhouse container flottila-bench-clickhouse, password "flottila-bench"
- ci/coverage-baseline.json:2-50 module keys (13 entries, = module dir names) — commit A
- deploy/postgres V1 (line 2), V13 (1), V14 (2), V16 (2), V18 (1): comments only — never touched

Test-only names (consistency only): "flottila-archives", "/srv/flottila", "/etc/flottila/...", "/run/secrets/flottila-master".

## 2. GOLDEN VALUES (test expectations derived from list-1 literals)

Hard-coded constants — FAIL if the literal is renamed alone; recompute independently:
- PeerKnownAnswerTest.java:53 SUB_K1, :55 SUB_K2 — hex from "flottila-peer-v1 subkey"
- PeerKnownAnswerTest.java:60 FP_K1 "921381e52ce9d750", :62 FP_K2 — from the fingerprint label
- PeerKnownAnswerTest.java:67, :69 MASTER_FP_K1/K2 — dependence unverified (comment :198 describes an unlabelled fingerprint)
- PeerKnownAnswerTest.java:125-128, :135-138 frame hex concatenating FP_K1/FP_K2 — transitive
- PeerKnownAnswerTest.java:213-220 DIALER_PROOF, LISTENER_PROOF, MASTER_*_PROOF — HMAC proofs from "flottila-peer-v1 proof" and INFO-derived keys
- PeerKnownAnswerTest.java:92-94 computed in test from its own literal at :92 — coupled to :53/:55
- NOT dependent: PeerKnownAnswerTest.java:157, :168 (RFC 5869 A.1), :234 (empty info); ReplFramesTest.java:45-50 and LargeBodyTest.java:86 hold "FJS1"/"FJSE"; StreamHeapMainTest.java:24
- NodeIT.java:445 FEED_LEASE "flottila:poller:shop/feed/poll://drop" — from ValkeyPollerLeases PREFIX

Computed in the test from the same literal — pass only if prod and test are renamed together:
- ScriptedPeer.java:147, :152, :158, :171 (test helper)
- FlowHashTest.java:78, :190 (text literal), :103, :225 (sha256 of the same literal, in-test)
- JournalRecordFormatTest.java:96, :136 (HKDF info in-test from "flottila-journal-v1"; no hex constant)
- PackageDependenciesTest.java:388, DepsFixture.java:106, PackageDraft.java:192 ("flottila-package-content 1\n")
- SchemasTest.java:23 ("urn:flottila:schema:"+kind+":1" against the JSON $id)
- NodeConnectOptionsTest.java:83, :102 ("flottila-"+role+"@node-7")
- NodeIT.java:861, :868; FlowBuilderStepEventTest.java:75; CpuSamplerIT.java:741 ("io.flottila.Step")
- PgWorkSignalsIT.java:120, :236; TwoNodeWorkEventsIT.java:215, :438; NodePoolWaitIT.java:339 ("flottila_work")
- LostWorkAdminIT.java:196; AdminServerDiscardTest.java:83 ("X-Flottila-Actor")
- PostgresImageTest.java:18 ("flottila.it.postgres" property name — this one is commit B)
- ComposeStackTest.java:29, :143, :445, :448, :449 (service "flottila", volume "flottila-data", mount "/var/lib/flottila")
- NodeConfigTest.java:63, :232 (default "/var/lib/flottila")
- No package.lock fixture with recorded digests exists; the invalid lock fixtures use aaaa placeholders.
- ci/test_coverage_check.py uses module names "flottila-api" etc. as fixtures (commit A or B, with the guard).

## 3. TRAPS FOR A BLIND REPLACE

- URLs: pom.xml:14 https://github.com/subapavel/flottila; deploy/claude-cloud/README.md:9, :13, :60 — stay.
- Migrations: V1 names io.flottila.runtime.db.Migrations, V13 FLOTTILA_JOURNAL_ACCEPT_LOSS, V14 FLOTTILA_NODE_LEASE_RENEW, V16 X-Flottila-Actor, V18 FLOTTILA_PEER_HOST — all comments. runtime pom.xml:23-26 copies deploy/postgres to db/migration on the classpath (a path for commit A).
- Hex/ASCII name: Retention.java:58 — a text replace misses it.
- Concatenated keys a full-key grep misses: Secrets.java:28 VARIABLE = "FLOTTILA_SECRET_" + name (tests SecretsTest.java:40-43, DeploymentsSecretsTest.java:412); PeerHandshake.java:55, :58, :59; Node.java:430; thread-name prefixes (Delivery.java:205, FlowWorker.java:548, RequestScope.java:84, Holder.java:118/:228, LeasedConsumer.java:113, PeerServer.java:281); CpuSampler.java:277 temp prefix.
- Class-name strings: Node.java:136 MOCK_PACKAGES = "io.flottila.components.mock", "io.flottila.testkit" (mock refusal by package); archtest Rules.java:29 "io.flottila.steps.."; ModuleBoundaryTest.java:63, :66; NoKafkaClientTest (api/core/runtime) Class.forName strings; MainProcessIT.java:239 and NodeTakeoverLedgerIT.java:321 pass "io.flottila.runtime.Main" as a JVM argument. No ServiceLoader in src.
- Substring identifiers: flottilaEnv (NodeIT.java:183, DeploymentsTest.java:122), binFlottilaValidatesTheDemo (LauncherIT.java:73), theHolderAnswersAsFlottilaOnTheNetwork (ComposeStackTest.java:366), flottilaPackagesAreFreeOfCycles (ModuleBoundaryTest.java:47).
- Test paths: PostgresImageSourcesTest.java:43 (a path with io/flottila/testkit), :99; BuildHygieneTest.java:27 "flottila-m1"; LauncherIT.java:92; BenchRunScript*Test create flottila-bench/target/flottila-bench-0.1.0.jar.
- bench/RESULTS.md has about 50 hits — history, excluded.
- Maven property names flottila.coverage.line/branch, flottila.mutation.threshold (pom.xml:67-70; api/core/testkit poms) are read by pom.xml:323, :328, :353.

## 4. COUNTS

- Java classes with "Flottila" in the simple name: 0.
- FLOTTILA_* keys: 469 occurrences in 48 files; central reader NodeConfig.java:669-1076; 119 distinct keys (architect's count over the whole tree).
- flottila.* property keys: 4 — flottila.coverage.line, flottila.coverage.branch, flottila.mutation.threshold, flottila.it.postgres.

Not searched: target/, git history, untracked files, the documents, Flyway checksum behaviour, JFR file content, whether MOCK_PACKAGES is tested.

## The architect's rulings for commit C

- Every literal of list 1 is renamed `flottila` -> `rail8`, `Flottila` -> `Rail8`, version digits kept.
- `Retention.LOCK_KEY` becomes the ASCII bytes of "rail8" in the high five bytes, zero below:
  `0x7261696C38000000L`, with the comment saying so.
- `SegmentFormat` magic: "FJS1"/"FJSE"/"FJN1" become "RJS1"/"RJSE"/"RJN1" (0x524A5331, 0x524A5345,
  0x524A4E31); the two tests that hold the bytes are updated with the reason.
- The applied migrations are not touched; their five comment lines go on the allow-list.
- PeerKnownAnswerTest: every constant is recomputed with an independent implementation (Python
  `hmac`/`hashlib`, HKDF per RFC 5869 written out) from the inputs the test documents. If
  MASTER_FP_K1/K2 do not depend on a label, they must come out unchanged — show that.
- Test-only names ("flottila-archives", "/srv/flottila", …) are renamed for consistency in C.
