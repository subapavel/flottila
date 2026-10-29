
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
