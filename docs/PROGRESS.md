
## 2026-11-04 — CORE-4c2 body store on journal references: intake, DLQ, reads, claim, ledger (D-105) — done, review PASS
context compacted (twice); re-verified with git status / git log before changing anything.
Test first: JournalBlobStoreIT, PgIntakeJournalIT, JournalReadIT, PgDlqJournalIT, PgBodyRefsIT, WorkClaimIT, SchemaIT,
ReferenceEnqueueIT (test-author; red = test-compile 95 errors + semantic reds, agent/work/CORE-4c2-red*.log).
Change: TieredBlobStore on the journal (forced → keepIn(tx)/committed/rolledBack, LocationIndex, RemoteBodyException), SpoolSink
gone, PgBodies read-only, PgIntake on refs with openSaves(), PgDlq/PgWorkQueue on refs (claim by body_node), Retention on body_refs,
core Intake.save keepInput, LateBlobStore; exists() asks Postgres only, admin GET checks it first (r1 decision on the index).
Checks: reactor verify -Pit exit 0 (2711 + 422; run 1 hit the JournalKill9IT test race → FIX-120); coverage no decrease; mutants killed.
Review: FAIL r1 (6: keepInput unpinned, BEGIN-before-force unpinned, failed spill persist set, kept CTE locks, retired twins
missing, exists() from the index), FAIL r2 (admin open() catch removed → 500), PASS r3. Stale CORE-2 worktree removed.
D-054 data: class C (test-author ×1 resumed ×4, implementer ×1 resumed ×4, reviewer ×1 resumed ×2); rounds 3.
Next: CORE-4c3 [C] packet (pinned-cut refusal, ACCEPT_LOSS, channel cache, start carries).

## 2026-11-04 — CORE-4c1 V12, journal master and tenant keys, identity, segment rows, node wiring (D-106, D-107) — done, review PASS
Test first: MasterKeySourceTest, JournalLifecycleTest, JournalSegmentRowsTest, JournalKeysIT, PgSegmentRowsIT, NodeJournalIT,
NodeConfigJournalTest, MigrationsIT V12 (test-author; red = test-compile exit 1, agent/work/CORE-4c1-red.log). CORE-4c split in 4c1/4c2.
Change: deploy/postgres/V12__journal.sql; MasterKeySource (file/env/opt-in generate, hard-link publish), JournalKeys (GCM wrap),
PgSegmentRows, Journal (row before every file, injected id, identity refusal incl. fresh database, close waits, fail-stop for
spill/persist, pre-open insert retried, health); NodeConfig keys; Node loads the key first; compose data volume; api DEGRADED (D-107).
Checks: runtime verify exit 0 (2326); reactor verify -Pit exit 0 (3102); coverage no decrease; 74 + 29 hand mutants killed; PIT api NOT_RUN.
Review: FAIL r1 (fresh database erased the journal; compose without data volume; MASTER generation race; tier < 4096 unstartable;
one failed row insert fail-stopped the journal), PASS r2. Additions in CORE-3 "Added in CORE-4c1".
D-054 data: class C (scout ×1, test-author ×1, implementer ×1 resumed ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-4c2 [C] packet (store on refs, intake, DLQ, reads, claim, ACCEPT_LOSS, ledger; carries in CORE-3).

## 2026-11-04 — CORE-4b large bodies in journal format; S3 blob tier removed (D-105 amended) — done, review PASS — Deviation: format
Test first: LargeBodyTest (12), LargeBodyHeapTest (-Xmx48m), JournalStartTest.spillIsClearedAtStart (test-author; red =
test-compile exit 1, 70 errors, agent/work/CORE-4b-red.log); S3TierIT/S3HeapIT and S3 cases deleted with the tier.
Change: journal Spill (streamed 1 MiB AEAD chunks, hashed once, persist = trailer → force → SegmentRows.insert → ATOMIC_MOVE →
force dir), Journal.stream, JournalRef.length long, LargeRecord, SegmentRows; blob S3 tier, S3Sink, Sink, preferS3 gone;
runtime pom `it` profile (empty heap-bounded-it) removed. Format deviation: index length u64, kind-2 record (CORE-3 "Added in 4b").
Checks: runtime verify exit 0; reactor verify -Pit exit 0 (2988 tests); coverage no decrease (runtime 99.5/99.6); 64+18 hand mutants.
Review: FAIL r1 (int cut of a long ref length; no outside decrypt of kind 2; format changes unrecorded), PASS r2.
D-054 data: class C (scout ×1, test-author ×1 resumed ×1, implementer ×1 resumed ×2, reviewer ×1 resumed ×2); rounds 2.
Next: CORE-4c [C] packet (store over Postgres V12, D-106 key, the 4b/4a carries in CORE-3 "Added in" sections).

## 2026-11-04 — CORE-4a body journal core, no Postgres (D-105) — done, review PASS
Test first: JournalWriterTest, JournalSegmentTest, JournalRecoveryTest, JournalCipherTest, JournalRecordPropertiesTest,
JournalConfigTest, JournalKill9IT (test-author; red = test-compile exit 1, agent/work/CORE-4a-red.log). Container restart mid-task.
Change: flottila-runtime io.flottila.runtime.journal (17 main files: Journal, Recovery, Record/SegmentFormat, ChunkedAead, …);
blob/SpoolCipher delegates to ChunkedAead, behaviour unchanged; implementer's JournalStart/Read/Append/RecordFormatTest.
Checks: runtime verify exit 0 (2206), journal 84/0/0/0, JournalKill9IT exit 0, reactor verify -Pit (no bench) exit 0, coverage
runtime 99.5/99.6 (baseline written for runtime); 69 hand mutants (results.txt), 2 unkillable without a seam (commented).
Review: FAIL r1 (reserve() changed state before Timer.schedule → position hole and close hang; per-record key derivation unpinned),
PASS r2. Design additions in agent/packets/CORE-3.md "Added in CORE-4a".
D-054 data: class C (test-author ×1, implementer ×1 resumed ×2, reviewer ×1 resumed ×1); review rounds 2.
Next: CORE-4b [C] packet (large bodies, sealer index read paths per CORE-3); owner: CORE-2 inline bound.

## 2026-11-04 — CORE-2 ClickHouse payload compression and size bound — done, review PASS — Deviation: parallel worktree
Test first: InvoiceGeneratorTest (generator) and ClickHouseCompressionMainIT (harness on the pinned image); a measurement row.
Change: flottila-bench ClickHouseCompressionMain, ClickHouseHttp, InvoiceGenerator; bench/run.sh `BENCH_SCENARIOS=clickhouse`;
bench/RESULTS.md §CORE-2 (48 cells × 3 runs, 0 failures; proposal inline ≤ 10 MiB, ZSTD(1), merge memory decides; fan-in limit).
Run in a git worktree beside CORE-4a on the owner's order (one-writer rule of AGENTS.md set aside, disjoint paths). Container
restart mid-run: 5 cells re-run on the new VM (2.10 GHz, marked †); recheck with the committed harness reproduced ratio and merge peak.
Checks: verify -Pit exit 0 (2 572 tests), coverage bench 97.4/96.6 (baseline written for bench only), mutant 'MergeParts' red.
Review: FAIL r1 (harness edited after the run, IT did not pin merge/insert numbers, fan-in limit, restart wording), PASS r2.
D-054 data: class B (implementer ×1 resumed ×3, reviewer ×1 resumed ×1); review rounds 2.
Next: the owner decides the inline bound; CORE-4a review r2.

## 2026-11-03 — CORE-3 body journal design (D-105) — done, review PASS — Deviation: F-25
Test first: none (design packet); the red tests of CORE-4a..4d are named in agent/packets/CORE-3.md.
Change: agent/packets/CORE-3.md (segments, per-record AEAD, one group-fsync writer, fail-stop, refs in V12, retention by
reference, disk bound, ACCEPT_LOSS per digest); D-105; F-25; ISSUE-4 (journal key, blocks CORE-4c); CORE-4 split 4a..4d.
Checks: ledger-only change, no build run (NOT_RUN).
Review: FAIL r1 (12: write-order hole, fsyncgate, sweep races, unbounded wait, FIX-35 compare, start checks, tests), FAIL r2
(large-body grace, sealer hand-off), FAIL r3 (ACCEPT_LOSS), FAIL r4 (wording), PASS r5.
D-054 data: class C (designer ×1 resumed ×1, reviewer ×1 resumed ×4); review rounds 5.
Next: CORE-4a [C] — acceptance spec, test-author red tests.

## 2026-11-03 — CORE-0/CORE-1 core-rework brief approved, CORE-GATE conditions (D-103) — done, review PASS
Test first: none (docs only); acceptance = the owner's approval (CORE-0) and a measurable GATE.md §CORE-GATE (CORE-1).
Change: docs/CORE-REWORK.md (owner's brief, approved), GATE.md §CORE-GATE (VM, pass rule = median of 3, 7 conditions),
D-101 (4) amended, D-102, D-103; BACKLOG §Core rework CORE-0..14 + CORE-GATE, P2-14..16, CONSIDER-10/11; ARCHITECTURE status note.
Checks: ledger-only change, no build run (NOT_RUN).
Review: FAIL r1 (18 points: no pass rule, gameable throughput condition, brief contradictions), FAIL r2 (5), PASS r3.
D-054 data: class B (reviewer ×1, resumed ×2); review rounds 3.
Next: D-104 (no measurement of the old core); CORE-3 [C] designer, CORE-2 [B], CORE-9 [B], CORE-15 [B].
## 2026-11-02 — FIX-111 agent work files out of target/ (D-093) — done, review PASS
Test first: none (docs and workflow only); acceptance observed by the verifier (below).
Change: `agent/work/` (gitignored, `.gitkeep`) replaces `target/` for logs, diffs, harnesses, bisect worktrees and `.batch-in-review` in CLAUDE.md, AGENTS.md, WORKFLOW §1/§10, NOTES, HANDOFF, verifier/reviewer/implementer/test-author definitions, memories, diagnosing-bugs, the gated Stop hook; SessionStart install checks the new marker path and keeps the original's backup (owner-sanctioned §10 D exception).
Checks: root `mvn -B -ntp -q clean` exit 0 and `clean -pl flottila-runtime -am` exit 0 leave agent/work/ intact, target/ probe gone (verifier, agent/work/FIX-111-clean-am.log); done-when grep 0 hits; SessionStart install tested in a fake HOME (original → backup; old gated copy → replaced, backup kept; rerun idempotent; failing backup (dangling symlink) → exit 1, hook unchanged); check-ignore -v agent/work/x.diff → .gitignore:12, ls-files agent/work = .gitkeep.
Review: FAIL r1 (installer `;` could overwrite the original hook after a failed backup; ledger stale), PASS r2. WF-2 patch rebased (D-093 moved its context).
D-054 data: class A (verifier ×1, reviewer ×2); review rounds 2.
Next: owner decides; a new session picks up the Stop-hook marker path.

## 2026-11-02 — WF-1 workflow skills from mattpocock/skills (D-091) — done, review PASS — Deviation: hook dropped
Test first: none (docs and agent workflow only). Owner request ("aplikuj patch WF-1"); applied and committed by the architect, an owner-sanctioned §10 D exception. Context compacted.
Change: .claude/skills/ (retro, writing-for-agents, diagnosing-bugs, grill-handover, MIT licence); reviewer, test-author and implementer definitions (tdd anti-patterns, FIX-row pointer); memories; NOTICE.
Hook: the command guard (force push, history rewrite, discarded work, reactor-root `mvn clean`) failed review r1 (regex), r2 (blanking parser) and r3 (lexer: `#` comments, `mvn -e`, `-pl X -am`, dir/glob pathspecs, untested `or` arms); owner dropped it; kept unapplied in agent/patches/guard-commands-hook.patch.
Found: `clean` with `-am` cleans the root `target/` too; the root cause of the P1-08 r2 loss goes to FIX-111 [A] (agent files out of target/).
Checks: no production code; licence header matches upstream (curl raw LICENSE, observed); `git apply --check` of the hook patch clean.
Review: FAIL r1–r3 (hook), FAIL r4 (retro still named a PreToolUse hook; NOTICE missed the tracked patches), FAIL r5 (FIX-111 grep could not come back empty), PASS r6.
D-054 data: class B (raised from A after r2); reviewer ×6; review rounds 6.
Next: owner decides; then FIX-111 [A].

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
