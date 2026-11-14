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

## Red approved (architect 2026-11-04)
Red observed: test-compile errors on the assumed API; semantic reds against a shimmed scratch copy — core 5/5, NodeConfig 2/3,
PgIntakeGroupIT 15/15 (agent/work/CORE-6b-red.log, state agent/work/CORE-6b-state.md). Tests: journal/PgIntakeGroupIT (15: the 12
listed + wholly decided at take, decided while waiting for a connection, byte limit with headers), PgIntakeGroupPropertyIT (jqwik;
no semantic red — invariants the single path keeps; it fails a variant without the abandoned check: accepted as a contract
property), node/NodeConfigIntakeGroupTest (3), core BatchingWriterTest (+5), BatchingWriterPropertyTest (+1); PgWire
`holdNextConnection()`. Assumptions accepted: `NodeConfig.intakeGroup()`; `groupStats()` record (groups, members, bytes, reruns; a
group of one counts); `io.flottila.api.Timer`; core OverloadedException with capacity() = max × concurrency × 16; an Overloaded
member is rolledBack(); "full by bytes" = queued bytes ≥ batchBytes; the weight pinned by lower bounds only.
Architect decision (concurrency vs pool): the concurrency bound is max(1, pool − 1). When _GROUP_CONCURRENCY is unset, the default is
min(4, max(1, pool − 1)), so pool 4 → 3 and pool 1 → 1, and the ITs that set FLOTTILA_PG_POOL_SIZE=4 stay unchanged. An explicit value above
the bound is refused (IAE naming both keys). The test-author adds the pool-4 and pool-1 default cases.
Architect (implementer handback): accepted — (1) PgIntakeGroupIT's `open(group, pool, warm)` fixture fix: a Vert.x 5.1.10 pool prefers an
idle connection on the caller's loop (javap SimpleConnectionPool$Acquire), so the held connect needs a cold pool; no assertion
changed (a wrong fixture expectation, reason in the commit); (2) `IntakeGroup.SINGLE` = (1, 64 MiB, PT0S, concurrency 1024): a
group of one goes at once as today, bounded by the pool's wait; (3) a failed group re-runs its survivors one after another (at
most `concurrency` connections); decided unkeyed members fail abandoned without a re-run, decided keyed members re-run.

## Review r1 FAIL (2026-11-04) — architect decisions
Tests (test-author): (a) a failed group with ≥ 2 survivors, the first re-run's COMMIT held: the next survivor's BEGIN is not sent
until it is released (kills all-at-once re-runs, PgIntake:419); (b) poison + a decided keyed duplicate in one group: the duplicate
answers the first id, its body evicted (kills :414 `m.dropped()` → `m.abandoned().isComplete()`); (c) SINGLE asserted by value —
concurrency 1024, bytes 64 MiB (mutant 1024 → 2); (d) a refused COMMIT (an error reply, not a lost one) answers its own cause and
leaves openSaves() empty with no settle: a known abort is rolledBack(), only a lost reply is in doubt (PgIntake:301, packet item 5).
Code/docs (implementer): (d)'s fix; core Intake javadoc :24-26 restated (abandoned is asked when the member is taken, after its
connection and before COMMIT; a save failing earlier may have asked it); D-082's late-save rule and D-071's backlog line amended
(decided members dropped early; Overloaded at max × concurrency × 16). Mutants next round: :408 `size()==1` → false, :266 empty
return dropped, :414, :419, SINGLE 1024 → 2, (d)'s branch.
Architect: D-109 / BACKLOG wording "every member" → "every member writing rows" (done here). For CORE-GATE: a decided keyed
claimant whose key is free rolls its group back and forces sequential re-runs — measure under save-timeout storms in keyed flows.
Accepted: the unreachable leak after `forced` (:239-242); the window re-arm with no free slot (existing).
Red approved r1 (architect): PgIntakeGroupIT.theSurvivorsOfAFailedGroupReRunOneAfterTheOther / .aDecidedKeyedDuplicateInAPoisonedGroupStillAnswersTheFirstId
/ .aRefusedGroupCommitAnswersItsOwnCauseAndLeavesNothingOpen (red); NodeConfigIntakeGroupTest.singleIsAGroupOfOneWithNoWindow by
value (agent/work/CORE-6b-r1-red.log; mutants for (a)-(c) killed). (a)'s kill depends on the mutant's re-runs reaching BEGIN before
the held COMMIT (a free pool connection): accepted, the correct code holds deterministically.

## Review r2 FAIL (2026-11-05) — architect decisions
(1) Known abort = an ErrorResponse of severity ERROR only (the connection survives it: deferred constraint, serialization). FATAL / PANIC
(e.g. 57P01 while COMMIT waits for a sync standby, after "already committed locally") or any failure that closes the connection stays
in doubt when the xid is known. Accessor by javap on vertx-pg-client 5.1.10, never memory. Case (test-author, PgWire): COMMIT forwarded,
the server's CommandComplete swallowed, a FATAL 57P01 ErrorResponse answered, connection closed → members stay open, settle committed
(savedWhole); kills "any DatabaseException". (2) D-082 amendment (DECISIONS:91) and PgIntake transaction() javadoc :290-293 narrowed to
"an error reply of severity ERROR"; Intake javadoc nit: the take-time ask is for unkeyed saves only. (3) R1 named: a failing group of
one (EdgeIT.aLostBeginFailsTheSaveAndWritesNothing or a new bounded case) asserts reruns() == 0 and one BEGIN since the mark.
Mutants next: guard as "any DatabaseException" and "an ERROR reply in doubt" (the latter killed by (d)); R1 with its named case.
Red approved r2 (architect): PgIntakeGroupIT.aFatalErrorAfterTheGroupCommitKeepsEveryMemberOpenUntilItSettlesCommitted (red: 0 open,
expected 2; PgWire `fatalReplyTo`); EdgeIT.aLostBeginFailsTheSaveAndWritesNothing + reruns() == 0 and one BEGIN since the mark (R1 now a
named kill in 2.6 s at its first assertion; agent/work/CORE-6b-r2-red.log).
