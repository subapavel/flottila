# FIX-263 step 1 - the test-author's hand-back (2026-11-16), as delivered, shortened only in wording

All files under `platform/runtime/src/test/java/dev/rail8/runtime/`. Nothing committed by the test-author.
It stopped once at its turn limit and was resumed; nothing was lost.

Deleted (they pinned only the withdrawn routes): `AdminServerReplayTest` (all 17 listed cases and its helpers; the class),
`AdminServerReplayRaceTest` (4; the class), `ReplayRetentionIT` (replaced), `NodeIT:1567` and `:1715` (the `/retry`
twins :1681, :1845 stay), `NodePoolWaitIT:616`.

Moved - where each property lives now:
- the permit that comes back (`:662`): no new case; core's
  `DeliveryTest.aDeliveryOverItsInFlightRequestLimitIsARetryableNack` pins it.
- off the event loop (`:685`): new `AdminDlqRestartTest.aRetryRunsOnAVirtualThreadNeverTheEventLoop` (the thread at the
  queue's `restart`).
- the token (`:802`): new `AdminDlqRestartTest.aRetryNeedsTheToken` (no token, a wrong token: 401, nothing changed).
- the same id twice (`RaceTest:230`): already pinned by `AdminDlqRestartIT.retryAnswers202AndRunsTheItemOnceUnderItsId
  OnTheNodeHoldingItsBody` (303-304: a second `/retry` is 404 and writes nothing); a retry racing a row's removal by
  `PgWorkQueueRestartIT.aRowRemovedWhileTheRestartWaitsOnItIsNotRestarted`.
- `AdminServerBrowserGuardTest`: the guarded POST is `POST /admin/dlq/m-404/retry`, with a recording DLQ as the "never
  reached" witness; the no-Origin witness pins the route's own 404. The server is built without the `ids` constructor.
- `NodePoolWaitIT:641` is `aDlqRetryRefusedByTheWorkPoolAnswers503`; its 503 half is green today. After the pool is
  released the witness is that the route itself answers - not a 202, because of the keyed-flow gap below.
- `ReplayRetentionIT:211` is `RetentionRetryIT.aParkedMessageOutlivesTheRetentionAndItsRetryDeliversIt` (green): over
  Postgres a worker parks X, a delivered K finishes, both aged 30 days; after one retention run K's rows and ref are gone
  and X's stay; GET of K's body 404 "no step 0 recorded"; `/retry` of X 202 {X, attempt 2}; the worker delivers X once.
- `LostBodyAdminIT:214` is `aLostBodyIsRefused409ForRetryAndItsGetIs410` (green): `/retry` 409 "body lost by
  ACCEPT_LOSS", the DLQ row unchanged, nothing queued or run; GET 410; the sibling K reads 200 and its retry delivers.
- Shared fixtures: the two ITs share the new `admin/AdminPgFixture`. The old class's own fixtures (Probe, Hold,
  ForgettingBlobs, RemovalFailingDlq, `shop()`) went with it: nothing used them after the rewrites.

Changed expectations (red today - the texts still say "use /replay"): `AdminDlqRestartTest.aSyncFlowsRowIs422`,
`aRowWithoutAnAttemptOfAtLeastOneIs422` (helper `namesNoOtherRoute`: no "/replay", no "use /", no "/retry");
`AdminDlqRestartIT:321` and `:345` (the same helper; :321 also checks the reason). The `REPLAY_OF` row and ", a replay"
stay for FIX-265.

New red cases: `AdminDlqRestartTest.noRetryRefusalNamesAnotherRoute` (sync, step 2, no attempt, attempt 0, a replay's
row, a keyed row without its key; a 202 witness); `NodeIT.bothReplayRoutesAnswerAsUnknownRoutes` (at node level, so it
compiles once the `ids` parameter goes).

Observed:
- EXECUTED_FAIL (red, as intended) `agent/work/FIX-263-ta-red.log`: `AdminDlqRestartTest` 9 tests, 3 failures - "no
  pointer to /replay: message m-sync ... ; use /replay ==> expected: <false> but was: <true>"; BrowserGuard 4/4 green.
- EXECUTED_FAIL (red) `agent/work/FIX-263-ta-it.log`: `AdminDlqRestartIT` 6 tests, 2 failures ("...parked before its
  key was kept; use /replay"); `LostBodyAdminIT` 1/1 and `RetentionRetryIT` 1/1 green.
- EXECUTED_FAIL (red) `agent/work/FIX-263-ta-it2.log`: `NodeIT` 40 tests, 1 failure - "POST /admin/messages/<id>/replay
  ?from=3 answers as an unregistered route ... expected: <404> but was: <200>"; `NodePoolWaitIT` 16/16 green.
- A scratch copy of `AdminServer` without the two routes and the three pointers turned the red classes green, 20/20
  (`agent/work/FIX-263-ta-mutant.log`).

Found otherwise than the list:
- Removing the `ids` parameter breaks test compilation: `AdminServerCpuTest:158` (needs `sampling`; no constructor takes
  it without `ids`) and `AdminServerTargetsTest:118` pass ids.
- A keyed flow's message that was parked with no key (in invoice-async, one that fails before its key is extracted)
  is refused by `/retry` with 422 "parked before its key was kept" and can never be retried: the guard cannot tell it
  from a row parked before V17.
- The GET's "(removed by retention)" text is still pinned by `AdminServerTest:496/509/549` and `JournalBlobStoreIT:413`;
  the route-level pool refusal's 503 by `AdminServerTest:1138`.

Not run: the whole runtime module, the reactor, coverage. Unit classes ran by `surefire:test@default-test -Dtest=...`
(the test-author's recorded way round the heap-bounded fork), the ITs by failsafe `-Dit.test`.
