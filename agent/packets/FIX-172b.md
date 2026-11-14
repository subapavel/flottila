# FIX-172b — a typed pool refusal on the transaction path (class B)

Row: agent/BACKLOG.md FIX-172b. Follows FIX-172 (D-132).

Facts (scout, after FIX-172 6b2a0f9):
- No store has a common transaction helper. `pool.withTransaction` at PgWorkQueue.java:342, :541, :591 (discard), PgBatchSink:50,
  PgSegmentRows:157/:183, Retention:434; `pool.getConnection()` at PgIntake:377.
- PgDlq, PgDeploymentStore, PgMessageStore, JournalKeys use preparedQuery/query only → a wait timeout there is exactly
  VertxException → already 503 by FIX-172. There is no DLQ discard route (GET /admin/dlq, POST /admin/dlq/:id/retry only).
- So the routes that still answer 500 on a wait timeout are the ones reaching a withTransaction: POST /admin/work/:seq/discard
  (PgWorkQueue:591). A replay's run maps any other exception to 502 PARKED/FAILED (AdminServer ran() :761-774).
- The FIX-172 docs (§4, changelog 3.10, D-132, AdminServer javadoc :56-61/:92-93) name "DLQ retry and discard, deploy" — wrong.
- FIX-172's implementer: on getConnection/withTransaction the wait timeout is `NoStackTraceThrowable("Timeout")` (not yet observed in an IT).

## Decision
P1 One runtime helper (package-private or in `io.flottila.runtime.pg`, no interface), e.g. `PgTx.inTransaction(pool, fn)`: acquire with
  `pool.getConnection()`; a failure of that acquire step — before any statement ran — becomes `PoolRefusedException` (runtime,
  unchecked, cause kept); then begin, run fn, commit/rollback, close, as `withTransaction` does. Structural, no type or message
  sniffing of the cause. Every `withTransaction` call above and PgIntake's `getConnection` go through it.
P2 AdminServer: `refusedByThePool` also accepts `PoolRefusedException`; `ran()` (replay) answers 503 for a pool refusal instead of
  502 FAILED (the run did not start or could not take a connection; retryable), checked before the generic branch.
P3 Intake: unchanged behaviour (any failed save is a retryable Nack → 503).
P4 Docs: §4 sentence, changelog row 3.11, D-133 (amends D-132): every admin route answers 503 when the pool refuses it, whether
  by a query (exactly VertxException) or at a transaction's acquire (PoolRefusedException); retire the "still answers 500"
  sentences and the AdminServer javadoc's; fix the wrong route list.
P5 FLOTTILA_PG_POOL_QUEUE env refusal names 1..65536 (pass the real range to number(); the ctor check stays).

## Acceptance (red first)
A1 IT (NodePoolWaitIT fixture, work pool held, wait PT0.2S): POST /admin/work/:seq/discard answers 503 with the fixed text, and the
   work row is still there; after release the discard answers its normal status. First, observe and record today's class/status
   (expected 500) in the red log.
A2 IT: a replay (POST /admin/messages/:id/replay or the route the store uses) under a held work pool answers 503, not 502.
A3 IT: POST /admin/dlq/:id/retry and PUT deploy under a held work pool answer 503 (expected already green — pins FIX-172 for them).
A4 unit: the helper turns an acquire failure into PoolRefusedException and leaves a failure inside fn as it was (not wrapped).
A5 the intake pool's queue bound: with FLOTTILA_PG_POOL_QUEUE 1 and the intake pool held, the next waiting save is refused at once
   (503) while the first waits.
A6 NodeConfig: FLOTTILA_PG_POOL_QUEUE 2147483648 / 65537 refused with a message naming 1..65536.

## Red approved (architect, 2026-11-11)
API: `io.flottila.runtime.pg.PgTx.inTransaction(Pool, Function<SqlConnection, Future<T>>)` → `Future<T>`; `PoolRefusedException(Throwable cause)`.
Tests: NodePoolWaitIT :446 A1 (red 500, NoStackTraceThrowable observed), :481 A2 (green: refused before ran()), :506 A3 retry (green),
:532 A3 deploy (red 500, finding 1), :561 A5 (green, pins); AdminServerReplayTest :891 (red 502), :922 (pins); PgTxTest :97, :124 (red),
:149/:162/:174/:184 (pins); NodeConfigPoolWaitTest :188 (red for values outside int). Logs FIX-172b-red-*.log (shimmed scratch copy).
O1 (finding 1) deploy: a pool refusal on the deploy's record write (exactly VertxException, ConnectionPoolTooBusyException or
   PoolRefusedException — the same `refusedByThePool` test) answers 503 with the deploy's rolled-back body, not 500: add a
   DeployRefusedException kind BUSY → 503 (mapping beside CONFLICT/INVALID/FAILED/STOPPING, AdminServer ~:884). D-133 names it.
O2 (finding 2) the run's refusal is pinned at the store seam (AdminServerReplayTest:891); accepted.
M11 (PgIntake on raw getConnection) accepted as equivalent: any failed save is a retryable Nack (P3). Implementer tests approved: PgTxTest:197, DeploymentsRecordTest:379.

## Review r1 FAIL → fix round (architect)
Docs done by the architect: §4, changelog 3.11, D-133 (undeploy BUSY; the DLQ-retry row-removal 500 exception), D-132 retired marker.
F1 undeploy: Deployments:539-542 a record delete the pool refused (PgTx.refusedByThePool) → Kind.BUSY → 503, not an IOException 500.
   Tests (test-author): NodePoolWaitIT DELETE /admin/packages/{name} under the held work pool → 503, package still deployed, 200 after
   release; DeploymentsRecordTest via Ledger.failNextDelete(Throwable): each refusal type BUSY, a VertxException subclass stays as today.
F2 PgTx: two PgTxTest cases — begin fails: same instance, not PoolRefusedException, calls == [getConnection, begin, close];
   rollback fails: fn's failure the result's (same instance), close called. Hand mutants M15/M16 (reviewer's) must die.
F3 evidence: NodePoolWaitIT XML counts on disk; list the 60 older report files (expected flottila-bench).
Not done (non-blocking): the replay 503 body keeping the replay id.
Red approved r2: DeploymentsRecordTest.aRecordDeleteThePoolRefusedIsBusyAndChangesNothing (red), NodePoolWaitIT.anUndeployRefusedByTheWorkPool…:569
(red: refused at queue.hasItems, not the delete), PgTxTest begin-fails and rollback-fails (green; kill hand mutants (a)/(b), FIX-172b-r2-red-mutants.log).
O3 undeploy: a pool refusal at `queue.hasItems` (Deployments.queued, ~:1800) is also Kind.BUSY → 503, not wrapped in IOException;
   the implementer adds a CountingQueue fail hook and its own unit case for it.
Architect after the fix round: BUSY bodies are the refusal's own error line (docs say so); O3 named in §4/D-133; deploy's own queue question → FIX-172c (filed), docs enumerate the routes instead of 'every route'.
