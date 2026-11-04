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
