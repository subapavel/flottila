
## 2026-11-11 — FIX-141 lost work listed with what it blocks, discard with an audit row (review F7.2, D-126 draft) — done, review PASS (r2)
Test first: PgWorkQueueLostIT (8), LostWorkAdminIT (5), MigrationsIT V16 (audit columns not null, work_lost index) (red FIX-141-red*.log)
Changed: V16 `audit` (append-only, all not null) + partial index work_lost; PgWorkQueue lost listing and `blocks` on
held/lost; DISCARD one statement (delete while lost_at is not null, key advisory lock, audit insert), next-of-key node read
in the same tx; GET /admin/work/lost, POST /admin/work/:seq/discard (actor = X-Flottila-Actor ≤ 128, else unknown; remote);
wake + announce shared with release. No restore (bytes gone, D-105). Stale expectations fixed: V16 counts, held `blocks`
Checks: verify -Pit (no bench) 3599 tests, only FIX-155/FIX-156 flakes (pass alone) · coverage no decrease · mutants M1–M10 +
r2 A–C killed · generic plan: Index Scan using work_lost (200k rows, 5 lost)
Found: FIX-167 (a held+lost row is listed twice and its release answers 200 though it can never run; blocks count uncapped)
Review: r1 FAIL (not-null unchecked, next-of-key predicates and audit time unpinned, no index), r2 PASS

## 2026-11-11 — FIX-143 an ordered flow states onFailure; block refused until a durable barrier (review F7.4, D-125) — done, review PASS (r2)
Deviation: F-32 — ARCHITECTURE §4 said an ordered queue blocks by default; it parks and continues (both park sites complete)
Test first: FlowDefinitionTest (O2 missing key, O3 block, O4 without partitionBy, enum, park readable), FlowWorkerTest
.aKeyedItemParkedPastMaxAttemptsLetsTheNextItemOfItsKeyRun, SchemasTest, DemoFlowsTest, invalid fixtures (red FIX-143-red*.log)
Changed: FlowDefinition.onFailure (park | block) with load refusals; flow.schema.json enum + dependentRequired both ways;
demo flows invoice-async, invoice-sync, sftp-invoice state park; FlowWorker unchanged. Test fixtures with partitionBy gained
`onFailure: park` (O6, a new required key — reason in the commit body)
Checks: verify -Pit (no bench) 3577 tests; recount found 3 stale mutant-run failures, unmutated reruns core 631/0, schemas
293/0 · coverage no decrease · PIT FlowDefinition 59/60 (survivor unchanged :190) · mutants M1–M5, S1, H1, H2 killed
Review: r1 FAIL (F-32 evidence false, one park site unnamed, D-055 marker, mutant and count evidence missing), r2 PASS

## 2026-11-11 — FIX-146 Postgres TLS with CA and hostname verification (review F9.1, D-124 draft keys) — done, review PASS (r2)
Test first: NodeConfigPgTlsTest, PgTlsIT (ssl=on hostssl-only servers + a plaintext one, keytool certs at test time; right CA,
wrong CA, name mismatch, disable refused, verifying mode vs plaintext refused, start's migration verified via a forwarder),
ComposeStackTest explicit disable; NodeConfigTest/LedgerMainPartsTest/StreamHeapMainPartsTest set disable (red FIX-146-red*.log)
Changed: FLOTTILA_PG_SSL_MODE disable|verify-ca|verify-full (loopback → disable, else verify-full), FLOTTILA_PG_SSL_ROOT_CERT
(missing/unreadable/empty refused naming both keys); one PgConnectOptions for pool, membership, LISTEN; Flyway URL with
sslmode + encoded sslrootcert; both compose files set disable; implementer test NodeJdbcUrlTest
Checks: verify -Pit (no bench) 3566/0/0/0 fresh XML · bench 257/0 · coverage no decrease · mutants r1 16/17 + r2 5/5 killed;
survivors equivalent (VERIFY_CA keeping HTTPS; REQUIRE — one Vert.x branch with the given trust and algorithm)
Found: FIX-165 (IPv6 host unbracketed in JDBC URL), FIX-166 (bench's own PG links carry no TLS)
Review: r1 FAIL (migration call site and plaintext-server fallback unpinned, tautological URL test, javadoc, bench wording), r2 PASS

## 2026-11-11 — FIX-144 readiness and liveness endpoints (review F4.4, D-123) — done, review PASS (r1)
Test first: AdminServerTest liveAnswers200WithoutReadingAnyCheck, readyIsDownNamingEachFailedCheck, readyAnswersWithoutAwaiting,
liveAndReadyNeedNoTokenWhileAdminDoes; NodeIT fencedNodeIsNotReadyButRemainsLive, drainDropsReadinessBeforeListenersStop,
journalInOutageNodeIsNotReady, failedFlowNodeIsNotReady, liveDoesNotTouchPostgres (red: compile + 404 vs shim, FIX-144-red*.log)
Changed: AdminServer GET /live (constant) and GET /ready (lifecycle, fence, journal UP, deployments; in-memory, no await, no
token), Readiness record, 4-arg HealthProbe (2/3-arg = ALWAYS); Node ready flag set last in start, cleared first in close
Checks: verify -Pit (no bench) 3530 tests, 0 failures, 1 error = FIX-156 flake · coverage no decrease (runtime 99.4/99.6) ·
hand mutants M1, M3–M5 killed; M2 (ready set early) survives, accepted (visible only with FIX-164's leaked admin port)
Found: FIX-164 (start failure leaves a started admin server open). Docker healthchecks still use /health (unchanged)
Review: r1 PASS

## 2026-11-11 — FIX-160 test race: COW subList views read under concurrent append — done, review PASS (r2)
Test-only. NodeShutdownWaitsForWorkerSettlementIT:363 streamed a live CopyOnWriteArrayList subList the log handler appends to (CME)
Changed: snapshot (`List.copyOf`) before every subList; lazy failure messages via until(BooleanSupplier, Supplier<String>); same
fix in JournalKill9IT, OrderedDeliveryIT, ReplayRetentionIT, LostBodyAdminIT, PgWorkSignalsIT, DeploymentsUndeployTest
Checks: NodeShutdownWaitsForWorkerSettlementIT 20/20 runs exit 0, 4/0/0/0 each (agent/work/FIX-160-v.summary, v1..20.xml) ·
touched classes 33/0/0/0 (FIX-160-touched.log) · coverage baseline not rewritten (no production change; partial-run reports)
Review: r1 FAIL (per-run counts missing from -q logs), r2 PASS on the XML evidence

## 2026-11-11 — CORE-7 batched completion of unkeyed work items (D-122 draft, F-31) — done, review PASS (r2)
Context compacted mid task (re-verified git status/log). Deviation: ARCHITECTURE §4 fence (D-101 (2) amended), §7, changelog 3.7, F-31
Test first: FlowWorkerCompleteUnkeyedTest, PgWorkQueueBatchedCompletionIT (100 completions → 2 statements, red 101),
PgWorkQueueBatchedCompletionPropertyIT, WorkQueueContract unkeyed case, PgBatchedWorkQueueContractIT, JournalBlobStoreIT and
NodeShutdownWaitsForWorkerSettlementIT cases (red agent/work/CORE-7-red*.log); implementer added PgWorkQueueCompletionEdgesIT
Changed: core WorkQueue.completeUnkeyed, FlowWorker.settle routes unkeyed items to it; runtime PgWorkQueue batch over BatchingWriter
(256 / PT0S / 2 in flight / 8192 / PT5S, constants), one autocommit unnest DELETE fenced by attempts, held and keyed rows excluded,
absent = STALE, failed statement fails all, no retry; Node closes the queue after flows, before signals and pool
Checks: verify -Pit (no bench) 3520 tests, 0 failures, 1 error = FIX-156 flake (passes alone 2/2) · BenchMainIT 2/2 · coverage no
decrease · PIT FlowWorker/WorkQueue 195/204 (survivors on untouched lines) · hand mutants 13/13 killed; M14 (start-failure close)
survives, accepted (rows left to their lease) · generic plan one work_pkey probe per member (CORE-7-plan.txt)
Found: FIX-163 (close wait unbounded). F-31 (2): every row is still deleted; keyed rows keep one transaction per message
Review: r1 FAIL (delay-bound wording false, D-101 marker missing, start-failure close unpinned), r2 PASS

## 2026-11-11 — P2-16b work events between nodes, PT30S reconciliation (D-121 (b) draft, F-30) — done, review PASS (r2)
Container restart mid fix round (tree intact, no mutant left: cmp against .r3.bak). Deviation: ARCHITECTURE §4/§7, changelog 3.6
Test first: FlowWorkerEventsTest, PgWorkSignalsIT, TwoNodeWorkEventsIT, IdleNodeStatementsIT (red agent/work/P2-16b-red*.log)
Changed: core WorkSignals, WorkEvents (leading edge, one folded statement per 50 ms, cap 64 → `* *`), LocalWorkSignals; runtime
PgWorkSignals (dedicated LISTEN connection, backoff 100 ms → 5 s until close, wakeAll after every LISTEN); NOTIFY only after commit:
keyed completion (fast path and plain completeRow) naming the next row's node, admin release (also wakes locally), kept ref on
another node; WorkerPolicy DEFAULT PT30S, first reconciliation at P + floorMod(hash(flow), P); announce throws isolated and logged
Test edits (wrong expectations, named in the packet): ~17 tick tests (B5), PgWorkSignalsIT:365, PgIntakeHandoffIT.Claims forward
Checks: verify -Pit (no bench) 3495 tests, 0 failures, 1 error = FIX-160 test race · coverage no decrease · PIT 465/479 core ·
hand mutants M1–M24, R1–R8, V1–V6, N1/N5, O1–O3 killed; N2–N4 Node wiring survive → FIX-161
Review: r1 FAIL (reconnect cap, LISTEN-error close, failed next-row read unpinned), r2 PASS
Opened: FIX-160 (shutdown IT CME), FIX-161 (Node wiring IT), FIX-162 (spilled wake not isolated)
Next: CORE-7 (batched completion, class C: designer first)
## 2026-11-10 — P2-16a local hand-off: a saved item runs on its node without a claim (D-121 (a) draft, F-30) — done, review PASS (r3)
Context compacted once mid-task (re-verified with git status / log -3). Deviation: ARCHITECTURE §4/§7 + changelog 3.5 (no accept poll)
Test first: FlowWorkerHandoffTest, PgIntakeHandoffIT, HandoffKeyOrderProperties, WorkQueueContract (red agent/work/P2-16a-red.log);
after review: FlowWorkerHandoffEdgeTest, FlowWorkerHandoffRefusedRunTest, PgIntakeHandoffGroupIT/LogIT, +R1–R14 IT cases
Changed: core Workers + FlowWorker.Slot; WorkQueue.enqueueLeased; the intake writes a heading item leased (attempts 1) in its
transaction; per-key advisory lock on keyed inserts and completes (READ COMMITTED); V15 work(flow, partition_key, seq); accept poll
gone, lease-end wake instead; claim-room fix (`asking`, found by jqwik); slot calls after the answers, isolated, cleanup suppressed
Test edits (wrong expectations by design, named in the packet): 12 key-lock ITs, SegmentSweepIT, 17 lease-end core tests, V15 counts
Checks: verify -Pit (no bench) VERIFIED_PASS 3428 tests, 375 fresh XML · BenchMainIT 2/0 · coverage no decrease · PIT 199/206
(FlowWorker*/SequentialIntake) · hand mutants M01–M34, R01–R15, O01–O12, r4/r6 logs; survivors argued in the packet
Review: r1 FAIL (untested paths R1–R3, slot-call order R4), r2 FAIL (lease left renewing N1, primary lost N2), r3 PASS
Opened: FIX-156 (ledger ack flake), FIX-157 (M20 lost-lease wake), FIX-158 (claimed() loop leak), FIX-159 (open-save flake, base)
Next: P2-16b (events between nodes, PT30S reconciliation), then CORE-7
## 2026-11-10 — P2-15c kill -9 ledger and SIGSTOP acceptance (D-112, F-28) — done, review PASS (r2)
Test: NodeTakeoverLedgerIT (child JVM nodes, Testcontainers PG) — acceptance, green on today's code; red by hand mutants
Case 1 kill -9 A at 1000 ACKs: 2000 ACKed, partner distinct 2000, raw duplicates 8 = A in flight 8, Σdoubts 8 = reruns 8, none
before A's old lease_until, B never superseded (59 s). Case 2 SIGSTOP A with 4 held sends: reset after SIGCONT, nothing after the
fence, no queued item taken, A exits SUPERSEDED (9 s)
Mutants (agent/work/P2-15c-mut.log): a send ignores the fence — killed; c rejoin does not wait — killed; d claim ignores the fence —
killed; b TAKE without liveness — survives by construction (a node claims only its own body_node items until CORE-5)
Review: r1 FAIL (negatives behind three gates, mutant d not run), r2 PASS · No production change
Next: P2-16 (local hand-off, no claim), then CORE-7
## 2026-11-10 — FIX-145 a pg_xact_status failure pins only its own save (review F7.3, F-29) — done, review PASS (r1)
Test first: SegmentSweepIT .futureXidDoesNotBlockUnrelatedSegmentCollection, .aFailingStatusQueryLeavesEveryInDoubtSaveOpenAndThe
SweepRuns, .oneBadXidDoesNotHoldBackAResolvableOne + InDoubtSeam (red agent/work/FIX-145-red.log, FIX-145-red-scratch.log)
Changed: PgIntake.settle — batched lookup first, per-xid on its failure; a failed xid stays in doubt (never an outcome), one WARNING
per xid until it settles; openSaves never throws for a lookup; package-private inDoubt seam (also the production path)
Checks: runtime -am verify -Pit VERIFIED_PASS 3054 tests, 338 fresh XML · coverage no decrease · hand mutants M1–M3 killed
Review: r1 PASS · Opened: FIX-153 (xid reused after a failover, class C), FIX-154 (stop the fallback on a connection failure)
Next: P2-15c (kill -9 ledger, SIGSTOP)
## 2026-11-10 — FIX-142 node shutdown awaits bounded worker settlement (review F4.3, F-29) — done, review PASS (r2)
Test first: NodeShutdownWaitsForWorkerSettlementIT (3; red agent/work/FIX-142-red.log, redeploy case a green control); after the red,
FlowWorkerUnsettledTest (test-author) and DeploymentsProducersTest close case reversed (wrong expectation: close now waits)
Changed: Deployments.close stops flows, then drains every worker's close within the one stopDeadline (node timer); past it one
WARNING per flow names its unsettled items, rows stay claimed; redeploy/undeploy unchanged (D-063 amended); FlowWorker.unsettled()
Checks: verify -Pit (no bench) VERIFIED_PASS 3367 tests, 364 fresh XML · coverage no decrease · PIT FlowWorker 105/108 (survivors
pre-existing :318/:342/:361) · hand mutants M1–M3 killed, M4/M5b equivalent (agent/work/FIX-142-mut.log)
Review: r1 FAIL (D-114..D-120 not recorded; M5 run against a fixture without a non-worker flow), r2 PASS
Decisions: D-114..D-120 (owner answers to F-29) · Opened: FIX-152 (Node javadoc)
Next: FIX-145 (pg_xact_status failures isolated per xid)
## 2026-11-09 — FIX-139 durable creation of the journal, segments and key directories (review F7.5, F-29) — done, review PASS (r2)
context compacted (tree re-verified with git status/log)
Test first: JournalIoTest (4), JournalDurableDirTest (4) (red agent/work/FIX-139-red.log); implementer's JournalSegmentsEntryTest
.aFirstStartForcesTheJournalDirectoryOnceSegmentsIsThere approved with M6r1 as its red
Changed: JournalIo.createDirectoriesDurably — creates each missing level and forces its parent, forces the directory last; used for
the journal dir, segments/ and the master-key dir; spill keeps plain createDirectories (comment, loss changes nothing durable)
Checks: verify VERIFIED_PASS 3047 tests (FIX-139-verify.log) · coverage no decrease · hand mutants M1–M7 killed · PIT helper 5/5
Review: r1 FAIL (implementer's test not approved in the packet), r2 PASS · Opened: FIX-151 (javadoc on a not-retried force; `..`)
Next: FIX-142 (node shutdown awaits bounded worker settlement)
## 2026-11-09 — FIX-140 recovery pins agree with sweep pins (review F7.1, F-29) — done, review PASS (r3)
Test first: SegmentSweepIT.aNormallySweptKeptRefDoesNotRefuseRestart (red: ACCEPT_LOSS refusal), controls
.aRegisteredSegmentWhoseFileVanishedStillRefuses, .aDlqHeldRefStillPinsItsSegment (agent/work/FIX-140-red.log)
Changed: PgSegmentRows.PINS_BEYOND — a kept ref pins at recovery only while its segment row is registered; work rows always pin;
guard comment per branch (key share / active segment + open-save set / FIX-150 window); SegmentRows javadoc; CORE-3 item 9 amended
Checks: runtime -am verify -Pit VERIFIED_PASS 3035 tests (FIX-140-verify3.log) · coverage no decrease · hand mutants per predicate killed
Review: r1 FAIL (node predicate unpinned, guard comment, docs), r2 FAIL (comment missing the FIX-150 window), r3 PASS · Opened: FIX-150
Next: FIX-139 (journal parent-directory fsync)
## 2026-11-06 — P2-15b send bounded by the fence; x-message-id; split ids (D-113 amends D-100) — done, review PASS (r2) · Deviation: F-28
Test first: FlowBuilderSendDeadlineTest (7), SendFencePropertiesTest (jqwik), NodeLeaseTest.openNanos…, ProducerTest, ComponentContract
(3 cases, http + mock + recording), HttpComponentTest (2), SplitTest (jqwik); red agent/work/P2-15b-red.log
Changed: api Deadline, Producer.send(e, d), Envelope.H_MESSAGE_ID / outboundId(); core Fence, FencedWait abandon, TargetLimit permit
follows the raw future, Split ids <parent>.<i>; http Timer constructor, cut by reset, timeoutMs → retryable Nack; mock/recording deadline
Checks: reactor verify -Pit VERIFIED_PASS 3601 tests (P2-15b-reactor2.log; run 1 NodeIT:1425 freePort race → FIX-149) · coverage no
decrease · PIT api 188/189, core 613/622 · hand mutants 39/40 killed (C3 equivalent)
Review: r1 FAIL (null answer leaked the fence timer; NodeLease:165 boundary unpinned), r2 PASS
Decisions: D-113 (draft) · Opened: FIX-148, FIX-149 · owner: benchmarks paused until a dedicated machine; message-id model low priority
Next: core fixes from F-29 (FIX-140, 139, 142, 145), then P2-15c ledger, P2-16, CORE-7
## 2026-11-06 — P2-15a node lease and self-fencing (D-101 → D-112, F-28) — done, review PASS (r3) · Deviation: F-28
context compacted (twice, plus two container restarts; tree re-verified with git status/log before continuing)
Test first: NodeLeaseTest, NodeLeaseFencePropertiesTest (jqwik), MembershipContract + PgMembershipIT, PgWorkQueueIT/InMemoryWorkQueueTest
liveness/doubt/rejoin cases, FlowWorkerFenceTest, PgWorkQueueStatementsIT, SegmentSweepIT, NodeIT notices (red agent/work/P2-15a-red.log, r1 P2-15a-r1-red.log)
Changed: V14 (nodes, work.doubts); core node (Membership, InMemoryMembership, NodeLease, FencedException); PgMembership; claim/TAKE
liveness + doubts; fence on claim, send, park, settle; renew bounded at L/2 with reconnect; rejoin waits for the old lease end
Checks: reactor clean verify -Pit VERIFIED_PASS rc 0 (P2-15a-r2-reactor.log) · coverage no decrease VERIFIED_PASS · PIT core
node/queue/flow/Parking 633/642 VERIFIED_PASS · hand mutants M-A..M-K killed
Review: r1 FAIL (rejoin with another lease, unfenced park/claim, docs), r2 FAIL (F-28 "never" vs FIX-135), r3 PASS
Decisions: D-112 (draft) · Opened: FIX-133, FIX-134, FIX-135
Next: P2-15b (message-id header, split ids, send bounded by the fence, kill -9 ledger); r2000 rerun to show the lease costs nothing
## 2026-11-05 — FIX-127 sweeper's pinned check linear in work + candidates (F-27 I3) — done, review PASS
Test first: PgSegmentRowsSweepPlanIT (generic + custom, 49 920 work rows, 200 candidates; red agent/work/FIX-127-red.log);
implementer's PgSegmentRowsSweepRecheckIT (a pin committed between lock and delete keeps the segment).
Change: PgSegmentRows PINNED = one materialized CTE (live work segments ∪ fresh or DLQ-held kept refs), LOCK / DELETE anti-join
against it; no index, no migration; the later-snapshot re-check kept. EXPLAIN 74 k → 670/690 buffers, ~900 → 18–37 ms.
Checks: reactor verify -Pit exit 0 (3455, 392 fresh); coverage no decrease; M1, M3, M4 killed, M2 (`materialized`) equivalent.
Preview rerun (moved from FIX-130): RESULTS "FIX-130 + FIX-127 preview" — r2000 drains (≈ 1 890 completes/s, was ≈ 375), ceiling
≈ 1 800–1 900 msg/s, CPU-bound; ACK p50 79 ms at r2000.
Review: FAIL r1 (rerun not recorded), PASS after the run. Found: FIX-132 (jqwik log line).
Next: CORE-17 / D-111 for ACK latency, node CPU split; P2-15 → P2-16 → CORE-7 for commits per message.

## 2026-11-05 — FIX-130 CLAIM bounded by its room, node sessions without JIT (F-27 addendum) — done, review PASS
Test first: PgWorkQueueClaimPlanIT (generic + custom, 50 k rows), NodeConnectOptionsIT.theNodesPooledSessionsRunWithoutJit
(test-author; red agent/work/FIX-130-red.log); implementer's PgWorkQueueNextOfKeyPlanIT (red agent/work/FIX-130-M4-next-in-form.log).
Change: Node.connectOptions adds startup property jit=off; PgWorkQueue CLAIM and CLAIM_NEXT_OF_KEY take rows by
`w.seq = any(array(<eligible … limit … for update skip locked>))` — no seq scan of `work`; fence, ordering, SKIP LOCKED unchanged.
Checks: reactor verify -Pit exit 0 (3451, 390 fresh); coverage no decrease; mutants M1..M4 killed. EXPLAIN figures in the commit body.
Review: FAIL r1 (rerun clause unmet → moved to FIX-127; fast-path test unapproved → approved), PASS r2. Found: FIX-131 (keyed claims).
Next: FIX-127 sweeper, then the r2000 rerun.

## 2026-11-05 — CORE-15b the open-loop record measures the database (F-27 I1) — done, review PASS
Test first: OpenLoopSamplerTest, DbSamplerTest, DiskSamplerTest, CpuSourceClusterTest, NodeBenchMainSettleProbeTest,
NodeBenchMainOpenIoOptionsTest, BenchRunScriptOpenDbTest, NodeBenchOpenDbSeriesIT (test-author; red agent/work/CORE-15b-red.log);
implementer's DbSamplerCasesTest, CpuSourceClusterCasesTest, NodeBenchMainSamplersTest, DiskSamplerCasesTest, OpenLoopOffloadedTest, DbSamplerPgIT.
Change: per-second `db` series (commits, pg_stat_wal, `work` tuples/vacuum, top statements by window time) on the sampler's own
connection; `io` from /proc/diskstats; `pgCpu` over the whole cluster; samples in order; settle probe work-first, 1 s pause.
Checks: reactor verify -Pit exit 0 (3442, r0); bench verify -Pit r1 exit 0 (3135; earlier runs FIX-121 ×2, PgIntakeJournalIT:351 ×1 → FIX-128);
coverage no decrease; mutants M1..M9, M11 killed, M10 survives (memory only, accepted).
Review: FAIL r1 (sample order unpinned; RESULTS lag sentence wrong), PASS r2. Found: FIX-128, FIX-129. The PROGRESS entry was
missing from its commit aa4a717 (a script error) and is added with FIX-130's ledger fix.
Preview rerun (c4b197a, RESULTS "CORE-15b preview"): CLAIM 171 ms per call at r2000 → FIX-130.

## 2026-11-05 — FIX-124 open-loop record kept when the settle misses, recording level option — done, review PASS
Found by the owner's preview run (ack 5 000 msg/s, recording all: settle timed out, no JSON; ~470 k of ~3.15 M accepted, work 376 k left).
Test first: NodeBenchMainOpenSettleTest, NodeBenchMainRecordingLevelTest (test-author; red agent/work/FIX-124-red.log); implementer's
NodeBenchMainOpenSettleCasesTest, BenchRunScriptOpenLevelTest, NodeBenchOpenRecordingLevelIT, NodeBenchOpenSettleCountsIT.
Change: settleOpen writes settled/settleDeadline/settleTarget/messages/work/receipts, exit 4 when unsettled (wins over 3);
--recording-level none|all (none → flow level off; sync + none refused); run.sh BENCH_RECORDING_LEVEL, BENCH_MAX_IN_FLIGHT, level
in the run name, summary prints level and settled; RESULTS note updated.
Checks: reactor verify -Pit exit 0 (3387, run 2; run 1 FIX-121 flake); bench verify r2 exit 0 (199); coverage no decrease; M1..M11 killed.
Review: FAIL r1 (RESULTS note stale, all/none runs shared a file), PASS r2. Found: FIX-125.
Next: stepped open-loop run, recording none (owner 2026-11-05), then CORE-8 at-least-once half, P2-15 → P2-16 → CORE-7.

## 2026-11-05 — CORE-15 open-loop load harness (GATE §CORE-GATE condition 2, P2-14 (b)) — done, review PASS
Test first: OpenLoopScheduleTest, OpenLoopTest (FakeClock/FakeTimer), NodeBenchMainOpenOptionsTest, CpuSourceTest, NodeBenchOpenEchoIT,
NodeBenchOpenAckIT, BenchRunScriptOpenTest (test-author; red agent/work/CORE-15-red.log); implementer's OpenLoopAccountingTest,
NodeBenchMainOpenRunTest, BenchRunScriptOpenDefaultsTest, NodeBenchOpenEchoCpuIT.
Change: NodeBenchMain --rate/--ramp/--window/--max-outstanding/--pg-cpu-source, scenario echo; OpenLoop (fixed schedule, latency
from the scheduled send time, saturation invalidates, backlog = accepted − partner receipts per second); CpuSource (cgroup or pid,
null + reason when unreadable); LatencyStats rank ceil(p·n/100); run.sh scenario `open`; RESULTS method note.
Checks: reactor verify -Pit exit 0 (3368, 367 fresh reports); coverage no decrease (bench 97.8/97.2); mutants M1..M8 killed.
Review: PASS r1. Found: FIX-123 (sample budget, monotonic clock, clamp pin, timeout label, pg CPU scope).
D-054 data: class B (scout ×1, test-author ×1, implementer ×1, reviewer ×1); rounds 1.
Next: preview run at 5 000 msg/s (owner 2026-11-05), then CORE-9/12.

## 2026-11-05 — CORE-6b intake group commit (D-109, FIX-95a) — done, review PASS
context compacted; re-verified with git status / git log before changing anything.
Test first: PgIntakeGroupIT (19), PgIntakeGroupPropertyIT (jqwik), NodeConfigIntakeGroupTest, BatchingWriterTest +5, BatchingWriterPropertyTest +1,
PgWire holdNextConnection / fatalReplyTo (test-author; red agent/work/CORE-6b-red.log, -r1-red, -r2-red); implementer's PgIntakeGroupEdgeIT (8), BatchingWriterWeightTest.
Change: core BatchingWriter weight + batchBytes; NodeConfig.IntakeGroup + FLOTTILA_INTAKE_GROUP_* (draft until CORE-GATE); PgIntake gathers
saves into one transaction (unnest claims sorted by flow/key, ON CONFLICT DO NOTHING, failed group re-runs survivors one by one, ERROR
reply to COMMIT = known abort, FATAL/PANIC/lost = in doubt until pg_xact_status); groupStats(); ARCHITECTURE §4, D-082, D-084, D-071 amended.
Checks: reactor verify -Pit exit 1 (3317, 1 failure = FIX-121 NodeBenchMainWarmupIT:111 float flake, not intake); class rerun exit 0, 5/0/0
(agent/work/CORE-6b-r2-bench-rerun2.log); coverage no decrease; PIT core batch+flow 529/535; mutants r1 R1..R7, r2 S1..S3 killed.
Review: FAIL r1 (re-run order, keyed duplicate in a poisoned group, SINGLE by value, refused COMMIT in doubt), FAIL r2 (FATAL reply as
known abort could lose a committed body; R1 only a hang), PASS r3.
Found: FIX-122 (PANIC unpinned). D-054 data: class C (test-author ×1 resumed ×3, implementer ×1 resumed ×2, verifier ×1, reviewer ×1 resumed ×2); rounds 3.
Next: next ready CORE row (CORE-5 waits for P2-15).

## 2026-11-04 — CORE-6a intake round trips: prepared-statement cache, pipelined turns (D-109, F-26, FIX-95b) — done, review PASS
Deviation: F-26 (the node pool did not cache prepared statements as §4 Writer says; code brought to the document).
Test first: PgIntakeRoundTripIT (8), NodeConnectOptionsTest, AtLeastOnceIntakeIT.theNodesPoolParsesEachIntakeStatementOncePerConnection,
PgWire turn counting (test-author; red agent/work/CORE-6a-red.log); implementer's NodeConnectOptionsCacheLimitTest.
Change: Node.connectOptions (cache on, SQL limit 8192: KEEP is 2514 chars); PgIntake turns BEGIN | claim + first-id | rows | abandoned
check | COMMIT alone; PgBatchSink pipelined write(SqlClient, List). Warm connection: unkeyed 3 waits, keyed 4 (was 8+).
Checks: reactor verify -Pit exit 0 (3310 testcases); coverage no decrease; mutants M1..M12, V1..V3 killed, M8/M9 equivalent.
Review: FAIL r1 (claim turn's own cause and the sink's statement order unpinned), PASS r2.
D-054 data: class B (designer ×1 for CORE-6, test-author ×1 resumed ×1, implementer ×1, verifier ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-6b [C] packet (group commit, D-109).

## 2026-11-04 — CORE-4d segment sweeper, journal bound with 503, open-save outcomes, 4c carries (D-105) — done, review PASS
context compacted; re-verified with git status / git log before changing anything.
Test first: SegmentSweepIT (+r1/r2 cases), JournalBoundIT, IntakeAppendWaitIT, GiveUpRaceIT, LargeBodyCrashTest, JournalSpillBoundTest,
SegmentSweepUnlinkTest, NodeConfigJournalSweepTest, RetentionIT +1, NodeJournalIT +1 (test-author; red agent/work/CORE-4d-red*.log, -r1-red, -r2-red).
Change: SegmentSweeper/SweepConfig (interval, grace, keepMaxAge), JournalFullException; bound on segments + spill + reservations
(intake edge max − max/16, spill counted per chunk, 503 + Retry-After, 80 % WARNING); pg_xact_status settles in-doubt commits;
core Intake.save takes Future<?> abandoned; dir-force generations; giveUp from the cut `for update`; per-extent bytes, unlink
pending set, NoSuchFile = unlinked; stored() swept fallback keeps corrupt/key failures; Retention BODIES limited batch.
Checks: reactor verify -Pit exit 0 (3265, r2); coverage no decrease; reviewer and new-guard mutants killed (r1, r2 logs).
Review: FAIL r1 (spill unbounded while streaming, stored() race, sweeper wiring untested), FAIL r2 (stored():442 rethrow unpinned), PASS r3.
Found: FIX-121 (bench float flake); IntakeAppendWaitIT wait fixed by its author in r1.
D-054 data: class C (test-author ×1 resumed ×4, implementer ×1 resumed ×2, reviewer ×1 resumed ×2); rounds 3.
Next: CORE-5 per BACKLOG.

## 2026-11-04 — CORE-4c3 pinned-cut start refusal, ACCEPT_LOSS, read channels, start carries (D-105) — done, review PASS
Test first: api BodyLostExceptionTest, core ReplayTest (+2), JournalReadChannelsTest, StoreReadKeyCheckTest, JournalFreeSpaceTest,
JournalCloseAndDirForceTest, StoreAppendWaitTest, LostBodyReadIT, NodeJournalIT (+13), LostBodyAdminIT, NodeConfigJournalLimitsTest,
MigrationsIT V13 (test-author; red = test-compile + shimmed semantic reds, agent/work/CORE-4c3-red.log).
Change: V13 (work.lost_at, journal_losses); JournalStart (room, identity, pins, digest; refusal changes nothing), Recovery plan/apply,
ReadChannels (LRU), record key check, ACCEPT_LOSS give-up in one tx, lost reads (BodyLostException, 409 BODY_LOST, GET 410),
lost sources feed no reference/enqueue/park, MASTER tmp cleanup, generated-key notice, quiet close, dir-force re-check.
Checks: reactor verify -Pit exit 0 (3194); coverage no decrease; 41 + 10 hand mutants killed.
Review: FAIL r1 (append-wait wiring store-only → deferred to CORE-4d; lost sources fed new pins; digest sort unpinned), PASS r2.
D-054 data: class C (test-author ×1 resumed ×2, implementer ×1 resumed ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-4d [C] packet (sweeper, disk bound 503, pg_xact_status, carries in its BACKLOG row).

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
