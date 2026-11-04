# CORE-6a — intake round trips: prepared-statement cache and pipelined statements (class B; D-109, F-26, FIX-95b)

Architect 2026-11-04. Base: f7d90cc (CORE-4d). Design: D-109 (the designer's CORE-6 draft, approved with BEGIN awaited).
Code: store/PgIntake.java (the save's transaction), batch/PgBatchSink.java, node/Node.java:175 (pool options),
journal/PgWire.java (counting relay), PgIntakeJournalIT:236-238 (today's 8 waits at level none, uncached CaseDatabase pool).

## Scope
1. **Cache**: `static PgConnectOptions Node.connectOptions(NodeConfig.Pg)` with `setCachePreparedStatements(true)`; the node's pool
   and the counting ITs use it. A second save on one connection sends no Parse.
2. **Pipelined turns** in PgIntake's save, on one connection, statements of a turn sent together and awaited together:
   turn 0 BEGIN (awaited alone: a failed BEGIN must never leave pipelined inserts autocommitting); turn 1 (keyed only)
   CLAIM_KEY + FIRST_ID, both always sent — FIRST_ID returning the arrival's own id = claimed, another id = duplicate (→ ROLLBACK,
   answers that id), none = gone (as today); rows turn: messages, message_keys, work (ENQUEUE_REF returning the xid), KEEP when
   keepInput; then the `abandoned` check; then COMMIT alone, never pipelined (PgIntakeIT.anAbandonedSaveIsRolledBack). A turn is
   sent only after every reply of the previous one succeeded; a failure in a turn rolls back and maps as today.
   Waits per save: unkeyed 3 (BEGIN, rows, COMMIT), keyed 4. Statements unchanged unkeyed; keyed +1 (FIRST_ID always sent).
3. **PgBatchSink**: a pipelined `write(SqlClient, List)` used by the intake's rows turn; the BatchingWriter's own path unchanged.
4. **API by evidence**: whether a connection from `pool.getConnection()` pipelines (PgConnectOptions pipelining limit, default),
   and whether a failed query in a Transaction sends ROLLBACK ahead of later submitted statements — javap on the resolved
   vertx-pg-client 5.1.x jar or Context7, and the held-reply IT decides. If a borrowed connection does not pipeline, stop and
   hand back (6a = cache only is an architect decision, not the implementer's).

Not here: group commit, the INTAKE_GROUP keys, BatchingWriter changes, ARCHITECTURE §4 / D-084 restatement (all CORE-6b).

## Red tests (test-author)
PgIntakeRoundTripIT (node connect options over PgWire): .anUnkeyedSaveSendsItsRowsBeforeAnyRowReply (with the first row
statement's reply held, every row statement reaches the relay and COMMIT does not; 3 waits, 1 COMMIT, 1 journal force — a force
count on HeldFiles if needed) / .aKeyedSaveClaimsInItsOwnTurn (4 waits, claim + first-id in one turn) / .aDuplicateSendsNoRowAndRollsBack
(only BEGIN, claim, first-id, ROLLBACK) / .theNodePoolParsesAStatementOnce (second save on the one connection: no Parse) /
.aFailedRowStatementRollsBackAndCommitsNothing (a refused row: no row of the save visible, answer as today);
NodeConnectOptionsTest (cache on, the NodeConfig.Pg fields carried). Green unchanged: every PgIntakeIT case, PgIntakeContractIT,
SftpIngressDedupeIT, PgIntakeJournalIT (its uncached pool keeps its counts). Red = compile error or failing assertion,
agent/work/CORE-6a-red.log.

## Constraints
CLAUDE.md design rules; only runtime touches Postgres; core untouched; Intake.save signature unchanged; javap / Context7 for the
pg client, never memory; no sleeps (held replies); floors no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, reactor `verify -Pit`, coverage check; logs agent/work/CORE-6a-*.log.

## Red approved (architect 2026-11-04)
Red observed: test-compile 4 errors on `Node.connectOptions`; semantic reds against a shimmed scratch copy (agent/work/CORE-6a-red.log).
Tests: journal/PgIntakeRoundTripIT (6: unkeyed rows before any row reply, kept input joins the rows turn, keyed claim turn,
duplicate, parse once, failed row rolls back), node/NodeConnectOptionsTest (3), AtLeastOnceIntakeIT.theNodesPoolParsesEachIntakeStatementOncePerConnection;
PgWire fixture gains turns (`countTurns`, `turnsSince`, `awaitQuiet`, public `mark`/`since`), other ITs green with it.
Evidence (javap vertx-sql-client-codec 5.1.10 + probe): a borrowed connection pipelines (limit 256), but an uncached statement
pauses the pipeline until its Parse answers; a failed statement's ROLLBACK goes only after every pending reply.
Architect decisions: (1) KEEP's SQL (2514 chars) exceeds the 2048 cache SQL limit → `connectOptions` sets
`setPreparedStatementCacheSqlLimit(8192)` (a finite constant; a NodeConfigConnectOptions case asserts it by value); KEEP's text is
not shortened here. (2) The 3/4 wait counts are for a warm connection; a connection's first save pays its Parses (accepted).
(3) "A failed BEGIN sends nothing more" is held by structure (BEGIN alone, awaited), not by a case (accepted).
Architect (implementer handback): the claim's rowCount decides "claimed", as today; FIRST_ID is always sent and read only when
the claim inserted nothing (scope 2's "own id = claimed" was shorthand; today's mapping keeps a re-save of an in-doubt arrival
a duplicate answering its own id instead of failing on the messages key). M8/M9 (`Future.join` → `Future.all`) judged
equivalent by the implementer — for the reviewer.

## Review r1 FAIL (2026-11-04) — architect decisions
Two pins missing, code expected right (mutant-pinned cases, test-author): (1) the claim turn's own cause — a BEFORE INSERT
trigger raising on processed_ids: the save fails with the trigger's text (never 25P02), wire BEGIN, claim, first-id, ROLLBACK
only, key free, no messages/work row; kills PgIntake:214 `Future.join(first, inserted)`. (2) The sink's statement order — a
trigger refusing `insert into messages`, keepInput true: the cause is the trigger's text; kills PgBatchSink:63 reversed.
Verifier mutants after: those two, PgIntake:188 `join(recorded, kept, queued)` and `join(queued, recorded, kept)`, Node.java:176
inline PgConnectOptions. Accepted: M8/M9 equivalent (join kept). Evidence next round: XML testcase count, no stale reports, git status.
