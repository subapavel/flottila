
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
