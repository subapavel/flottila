# MAIN-1 scout 2 - facts for FIX-214 and FIX-212 (scout a5942640faf308a33, 2026-11-15; the relay lines read by the architect)

## FIX-214 - PeerProtocolTest.aLinkPausedByAnAbandonedConsumerIsClosedAfterATimeoutAndItsReadsFail
- Test: platform/runtime/src/test/java/io/rail8/runtime/journal/PeerProtocolTest.java:742-770. Only :767 names a side:
  `assertEquals(FrameRelay.Side.DIALER, wire.awaitClosedBy(0, "node-b closes the paused link"), "closed by node-b: " + wire.describe())`.
  :766 and :769 assert that the reads fail retryable.
- Fixture: platform/runtime/src/test/java/io/rail8/runtime/journal/peer/FrameRelay.java, `Connection` :133-178. Two pump threads,
  `pump(down, up, toListener, Side.DIALER)` and `pump(up, down, toDialer, Side.LISTENER)` (:141-142). In `pump(from, to, pipe, fromSide)`
  an EOF on the read of a frame head closes with `fromSide` (:157-158); EVERY other IOException - the read of `from` and the write to
  `to` inside `pipe.forward(out, frame)` alike - closes with `pipe.refused() != null ? Side.RELAY : fromSide` (:169-171).
  `close(side)` is synchronized and first-wins (:173-177).
- So when node-b (the dialer) closes its socket, the pump B->A reports DIALER (EOF or reset on its read), and the pump A->B, if it is
  writing a frame to the dialer's closed socket, reports LISTENER - the side it reads from, not the side that closed. Whichever pump
  reports first decides `closedBy`.
- Production: PeerLink's watchdog (:250-265) on the dialer's link closes it (`close(new IOException("the peer link with ... gave no
  answer within RAIL8_PEER_TIMEOUT ..."))`); `ended()` :239-247 keeps the first reason; reads fail through PeerReads.java:112
  (`RemoteBodyException`). ARCHITECTURE section 4 (line 244) promises "closed, failing its reads".
- History: passed in BASE-1 runs 1 and 3, failed in run 2; failed in `ci` run 193 (job 132679541252).

## FIX-212 - StepBodiesIT.aRerunOfAStampingStepKeepsEachAttemptsRowAndAReplayAfterARestartReadsAttemptTwosBytes
- Test: platform/runtime/src/test/java/io/rail8/runtime/flow/StepBodiesIT.java:424-453. Flow: STAMP, CRASH_FIRST, THREE.
  `Crash extends Error` (:135), thrown at :226-227 when ATTEMPT and REPLAY_OF are absent.
- :442 `rowsOf(id, 5);   // ingress, stamp of attempts 1 and 2, crash-first and three (attempt 2's)`; `rowsOf` waits through `until`
  (:370-377) for `count == n`, 20 s on `System.nanoTime`.
- The code, read: six rows. ingress; attempt 1 stamp OK; attempt 1 crash-first FAILED (Recorder.java:87-90 records FAILED for a
  throwing step under policy ALL and rethrows; FlowWorker.java:666-669 closes the trail in finally, "an Error too"; :683 an Error is
  no failed attempt, the item is left to the lease); attempt 2 stamp, crash-first and three OK. Key (id, step, attempt): no collision.
- The expectation 5 came with FIX-175 (agent/packets/FIX-175.md O3: "one row per step -> one per step and attempt"); its comment
  omits attempt 1's crash-first row. `count == 5` can hold only for an instant, between two flushes of attempt 2's rows.
- No log under agent/work shows StepBodiesIT passing: it fails in the BRAND-1 A/B/C verify logs and in BASE-1 runs 1 and 3. The log
  the FIX-175 PROGRESS entry cites (agent/work/FIX-175-verify6.log) is not in the tree.
