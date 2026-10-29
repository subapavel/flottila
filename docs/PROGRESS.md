
## 2026-10-29 — P2-08b durable ACK measurement after one transaction — done, review PASS — Deviation: F-18
Context compacted (once, mid-review); re-verified with git status / git log -3.
Test first: bench NodeBenchAckCommitsIT, BenchRunScriptTest, NodeBenchMainIT settings case (red: target/P2-08b-red.log); implementer-authored BenchRunScriptCommitDelayTest, NodeBenchAckCountersIT approved after the fact.
Change: NodeBenchMain records six pg_settings, poolSize, commitsPerMessage, walSyncsPerMessage; run.sh ack loop c=1/8/64, BENCH_COMMIT_DELAY via ALTER SYSTEM + EXIT-trap reset. bench/RESULTS.md "P2-08 — durable ACK, one transaction".
Result: c=1 p99 10.58 → 8.80 ms; FLOOR (pg_wal on tmpfs, not durable) 8.04 ms → 5 ms target missed, storage not the limit; c=64 msg/s 833 → 733 (−12 %); commit_delay < 10 % on all runs, not in compose. No ISSUE-3.
Docs: F-18 (major), ARCHITECTURE §7 Sizing restated + changelog; FIX-95 [C] (recover load throughput), CONSIDER-6 (jqwik alternative, owner).
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS exit 0 (target/P2-08b-verify.log); BenchRunScript* 6/6 after the comment fix (target/P2-08b-r2-script.log); cleanup state target/P2-08b-cleanup.log; pg_test_fsync target/P2-08b-fsync2.log; coverage no decrease.
Review: FAIL r1 (−18 % approximation, FLOOR not labelled, overclaim, c=64 overlap, commit_delay rule, cleanup unevidenced, pg_test_fsync path unlogged), PASS r2.
D-054 data: class B (designer ×1, test-author ×1, implementer ×1 + 1 fix, verifier ×2, reviewer ×2); review rounds 2.
Next: P2-02L [C].

## 2026-10-29 — P2-02L poller lease held by the node (D-083) — done, review PASS
Test first: runtime PollerLeasesContract (in-memory + ValkeyPollerLeasesIT on valkey:8), InMemoryPollerLeasesPropertyTest, LeasedConsumerTest, DeploymentsPollerLeaseTest, TwoNodeSftpLeaseTest, NodeIT P2-02L (Valkey / in-memory + WARNING), NodeConfigTest, api ComponentTest, SftpComponentTest (red: target/P2-02L-red*.log).
Change: api `Component.polls` (default false, sftp true); runtime lease.PollerLeases + InMemory/Valkey (Lua EVAL, PX); deploy.LeasedConsumer (hold per tick on a virtual thread, validity from t0, clock re-read after start, fresh consumer on regain, stop → release); Deployments wraps polling consumers; NodeConfig Leases (FLOTTILA_VALKEY_URI redacted to scheme://host[:port][/db], TTL/RENEW refusals); Node wires Valkey or in-memory. vertx-redis-client 5.1.10 (Apache-2.0/EPL).
Docs: D-083, §4 pollers sentence, §7 Poller leases row, changelog; BACKLOG Done-when restated (≤ ttl + renew); FIX-96 (standby visibility), FIX-97 (rediss TLS).
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS 2380/0/0/0 with report dirs cleared (target/P2-02L-verify3.log), +1 adopted NodeConfig case after it (NodeConfigTest 70/0, target/P2-02L-r3-adopt.log); coverage no decrease; hand mutants 43 + 44 + 11 (target/P2-02L-mutants*.log), PIT api Component 1/1; lease classes 20/20 ×3.
Review: FAIL r1 (`?password=` printed; delay from a clock read before the start; failed start not stopped; docs overlap), FAIL r2 (hostless URI leaked the password), PASS r3.
D-054 data: class C (designer ×1, test-author ×1 + 6 follow-ups, implementer ×1 + 2 fixes, verifier ×2, reviewer ×3); review rounds 3.
Next: P2-02D [C].

## 2026-10-29 — P2-02D ingress dedupe by a consumer-supplied key (D-084) — done, review PASS
Test first: PgIntakeIT (12 dedupe cases incl. two lock-held races, own scope/key, row gone), DeliveryTest (duplicate answers M1, prop as arrived, absent/empty, wrongly typed → Nack), SequentialIntakeTest.refusesADedupeKey, SftpComponentTest, DeploymentsDedupeTest, SftpIngressDedupeIT (file delivered twice = one message, one item), MigrationsIT V8, PollingConsumerContract dedupe case (red: target/P2-02D-red*.log).
Change: api `Component.dedupeBy` (sftp `sftp.fileKey`), Ack javadoc; core Intake.save(…, dedupeKey) → Future<String>, Delivery reads the prop guarded and answers Accepted(saved id); V8 processed_ids(scope, sha256 key_hash); PgIntake claims the key first in the tx, a duplicate rolls back, evicts its local body, answers the first id; sftp move suffix from the accepted id.
Docs: D-084, §4 two sentences, §7 Component block (`polls`, `dedupeBy`), changelog; P2-07 retention note; FIX-98.
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS 2415/0/0/0, report dirs cleared (target/P2-02D-verify2.log); coverage no decrease; hand mutants 20/21 + 5/5 (target/P2-02D-mutants*.log; X4 later killed).
Review: FAIL r1 (FIRST_ID's WHERE unpinned; typed-prop read unguarded), PASS r2.
D-054 data: class C (designer ×1 (resumed at the turn limit), test-author ×1 (resumed) + 1 fix, implementer ×1 + 1 fix, reviewer ×2); review rounds 2.
Next: next ready row (FIX-95 [C] or a FIX row).

## 2026-10-29 — P2-05 gate compose stack (D-085) — done, review PASS
Test first: runtime ComposeStackTest (9 cases; red: target/P2-05-red.log, secret case target/P2-05-red2.log).
Change: deploy/compose/docker-compose.yml — postgres:17.11, valkey 8.1.11 (persistence off), SeaweedFS 4.50 (S3 identity from env), apache/kafka 3.9.1 (not wired), atmoz/sftp (digest), node (dev, S3 + Valkey, token `:?` from env), echo, one-shot `flottila deploy`, clickhouse under `history`; README; CI `stack` job; D-085 with image licences.
Observed on this VM (target/P2-05-stack2.log): `up -d` 13.49 s, /health green 13.56 s, deploy exit 0, demo listed, POST :8080/in 200 from the echo, clickhouse healthy, `down -v` clean.
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS (target/P2-05-verify.log); ComposeStackTest 9/0 (target/P2-05-test3.log); coverage no decrease.
Review: PASS r1. Raised FIX-99 (Kafka not-wired assertion, CI up seconds, HANDOVER env note).
D-054 data: class A (scout ×1, test-author ×1 + 1, implementer ×1 + 1 fix, verifier ×1, reviewer ×1); review rounds 1.
Next: P2-06 [A].
