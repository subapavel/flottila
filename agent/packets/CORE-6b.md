# CORE-6b — intake group commit (class C; D-109, FIX-95a)

Architect 2026-11-04. Base: 35835d4 (CORE-6a). Design: D-109 and the designer's CORE-6 draft (6b part, summarised here).
Code: core batch/BatchingWriter.java, runtime store/PgIntake.java (6a's turns), batch/PgBatchSink.java, node/NodeConfig.java,
journal/PgWire.java (turns), PgIntakeIT / PgIntakeContractIT / SftpIngressDedupeIT (green unchanged).

## Scope
1. **Core BatchingWriter**: new constructor `BatchingWriter(BatchSink<T>, Timer, Options, ToLongFunction<? super T> weight,
   long batchBytes)`; the old one = weight 1, batchBytes = batchSize, behaviour unchanged. A batch holds ≤ batchSize items and
   ≤ batchBytes bytes; an item above batchBytes goes alone; a batch full by count or bytes does not wait for the window; an idle
   writer sends at once; up to commitConcurrency batches in flight, as today.
2. **Runtime**: `record NodeConfig.IntakeGroup(int max, long bytes, Duration window, int concurrency)`, `IntakeGroup.SINGLE`
   (max 1, window 0); `PgIntake(Pool, TieredBlobStore, PgMessageStore, PgWorkQueue, Timer, IntakeGroup)`; the 4-arg constructor =
   SINGLE (one code path: a single save is a group of one). save = forced → writer.save(member) → the member's own answer. A full
   backlog (derived: max × concurrency × 16) fails Overloaded (retryable). A member's weight = the UTF-8 bytes it binds (ids, flow,
   keys, headers/props/keys JSON). The sink never blocks (the window fires on an event loop) and never fails its batch future.
3. **Group transaction** on one connection, 6a's turns: an unkeyed member whose answer is decided is dropped at take and again
   after getConnection (fails abandoned, rolledBack(), nothing sent); a keyed member still claims (PgIntakeIT:656). Turn 1 BEGIN
   (awaited); claim turn: CLAIMS and FIRSTS as unnest statements over the keyed members sorted by (flow, key) — one lock order, no
   deadlock between racing groups; rows turn: messages and message_keys as one unnest each, then work and KEEP per member in call
   order (same-partition seq in call order); abandoned check on every live member; COMMIT alone.
4. **Dedupe isolation**: ON CONFLICT DO NOTHING RETURNING, no savepoint; a duplicate writes nothing; one key twice in a group:
   the first in call order claims, the second answers the first's id after the commit; no live member left → ROLLBACK (today's
   duplicate/gone path, PgIntakeIT:780).
5. **Outcomes**: COMMIT acknowledged → each live member committed() and its own id; each duplicate rolledBack(), body evicted, the
   first id. Failure before COMMIT (a statement, a KEEP/RemoteBody/BlobNotFound check, a member abandoned at the check) → ROLLBACK;
   a group of one fails as today; in a larger group abandoned members fail and every other member re-runs alone in its own
   transaction (a poison member never fails its neighbours); nothing is answered from the failed transaction. COMMIT refused →
   every member fails, rolledBack(). COMMIT reply lost → every member fails; `inDoubt` becomes xid → members; settle applies the
   xid's pg_xact_status to each.
6. **Deadlines**: only Delivery decides an answer (saveTimeout, stop deadline) through `abandoned`; a member waits in the gather at
   most one window or until a slot frees; the checks drop a decided member, so none commits after its answer.
7. **Keys** (NodeConfig the only reader): FLOTTILA_INTAKE_GROUP_MAX 64 (1..1024; 1 = no grouping), _GROUP_BYTES 1 MiB
   (64 KiB..64 MiB), _GROUP_WINDOW PT0.001S (PT0S..PT0.05S, whole ms), _GROUP_CONCURRENCY 4 (1..64, below FLOTTILA_PG_POOL_SIZE —
   IAE naming both). Defaults draft until CORE-GATE (bench/RESULTS.md "CORE-6 — intake group sizing").
8. **Stats, no new meter names**: `PgIntake.groupStats()` → record (groups, members, bytes, reruns) totals, read by tests and the
   bench; the gate derives per-group figures from them.
9. **Docs**: ARCHITECTURE §4 durable-save paragraph and D-084 restated "a key already seen writes nothing of the arrival and
   answers the first message's id" with a changelog row; the core Intake javadoc and D-082 / P2-08a "one txid per save" amended
   to "per group; a re-run member commits in its own transaction". BACKLOG CORE-6b done-when: "a lost COMMIT reply keeps every
   member open until pg_xact_status is known" (no pipelined COMMIT, D-109).

## Red tests (test-author)
PgIntakeGroupIT: .concurrentSavesShareOneTransaction (concurrency 1, first COMMIT held, 8 queued: one BEGIN/COMMIT and one txid
for the 8, 4 + 2·8 statements) / .aDuplicateInAGroupAnswersTheFirstIdAndItsNeighboursCommit / .oneKeyTwiceInAGroupIsOneMessage /
.groupsClaimingTwoKeysInOppositeOrderBothSave (no 40P01) / .aPoisonMemberFailsAloneAndTheOthersSaveInTheirOwnTransactions /
.aRefusedGroupCommitFailsEveryMemberAndLeavesTheirBodiesLocal / .aLostGroupCommitKeepsEveryMemberOpenUntilItsXactStatusIsKnown /
.aMemberAbandonedWhileGatheredWritesNothingAndTheRestCommit / .aMemberAbandonedAtTheCheckRollsTheGroupBackAndTheRestSaveAlone /
.aKeyedMemberWithItsAnswerDecidedStillAnswersItsDuplicate / .sameKeyMembersKeepCallOrderInOneTransaction / .aFullBacklogIsOverloaded;
core BatchingWriterTest: .aBatchStopsAtItsByteLimit / .anItemAboveTheByteLimitGoesAlone / .aBatchFullByBytesSkipsTheWindow /
.theOldConstructorBatchesAsBefore; jqwik BatchingWriterPropertyTest.batchesKeepArrivalOrderWithinCountAndBytes; jqwik
PgIntakeGroupPropertyIT.noAckWithoutCommittedRowsNoneLostDuplicatesIsolatedOrderKept (~30 tries, ≤ 16 saves, random keys, repeats,
abandons, one poison member); NodeConfigIntakeGroupTest.defaultsAndBounds / .concurrencyNotBelowThePoolIsRefusedNamingBoth.
Green unchanged: PgIntakeIT, PgIntakeContractIT, SftpIngressDedupeIT, PgIntakeJournalIT, PgIntakeRoundTripIT (SINGLE).
Red = compile error or failing assertion, agent/work/CORE-6b-red.log.

## Constraints
CLAUDE.md design rules; only runtime touches Postgres; Intake.save signature unchanged; core change limited to the BatchingWriter
constructor (PIT on core ≥ 80 %); fake clock/timer, held replies, no sleeps; sizes long, limits finite and range-checked; javap /
Context7 for the pg client. Floors no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, reactor `verify -Pit`, coverage check, scoped PIT on core batch; logs
agent/work/CORE-6b-*.log.
