# CONSIDER-15 — the designer's re-expression of the branch error-handling design

Working artifact, 2026-11-15. This is the **designer agent's output**, not an owner decision and
not yet approved by the architect. It says which clauses of the session branch's design the core
rework invalidated, and what each must say now. The branch design itself is quoted in
`/tmp/claude-0/-home-user-flottila/dbeae818-9397-55f1-b03c-4e92e71fe443/scratchpad/branch-design/design-extract.md`.
"extract:N" below refers to a line of that file.

## 1. Clause by clause

**C1** — extract:24 says `in` is "the failed step's input envelope, the same envelope a park
records (D-045)". → Keep that input but drop the equality: a worker park records the ingress at
step 1 with its attempt (D-135, `FlowWorker.java:697`). The handler may not write reserved props
(`attempt`, FIX-47), because they key its rows.

**C2** — extract:32 says "the step's own retry runs out first … the wrapper between the step's
recorder and its parking". → The in-step `retry` runs out first, and for at-least-once the
`delivery.retries` schedule too. A deferred attempt (`FlowWorker.java:687-691`) never reaches the
handler. The handler runs only where the worker would park: `isFinal` (`:712-719`) or the last
hand-out, attempts == bound(item) (`:626-628`; this includes a `/retry` row's single hand-out,
max_attempts k+1), and it replaces the step-1 park. On `build()` and replay, which keep per-step
Parking (`FlowBuilder.java:286`, `:462`; the worker's `:386` has none), it stays the wrapper
between recorder and Parking.

**C3** — extract:32's fallback and branch D-103's "at-least-once parks or leaves the item to its
lease". → Only an ordinary failure falls back to the park. A `FencedException`, interrupt or
`Error` raised inside the handler or by the marker write surfaces itself, with the original
suppressed into it, so `FlowWorker.java:683` leaves the item to its lease. Never parked (§6:278).
Never-handled is decided by **type, before `on:` matching**: interrupt, `FencedException` (not a
`Fault`, so INTERNAL by default, `Fault.java:6`, and it may wrap a DOWNSTREAM cause,
`FencedException.java:16`), `Error` (a crash, D-135), `AttemptsExhaustedException`.

**C4** — fenced runs: the design is silent. → A failure met under a closed fence reaches no
handler, just as it is neither deferred nor parked (§4:207). The fence is checked before the
handler starts and before the marker write, which stands for a park. If it is closed: no handler
steps, no marker, the row is left to its lease. The takeover rerun is attempt k+1 and may handle
again.

**C5** — extract:29/:32, the marker row and "exactly one durable record". → Marker key
(id, step = last of the handler range, attempt k = the failed input's `attempt` prop, 1 if
absent). This is the V19 PK (`V19__message_attempt.sql:14`); the first write wins within an
attempt (D-140). Handler rows are (id, handler step, k) and sort after attempt k's main rows. New
guarantee: **at most one record per attempt, and the latest attempt decides.** If the complete
after a durable marker is lost (fenced settle, `FlowWorker.java:570-589`), the item reruns as
k+1, or past the bound parks without a run (`:648-655`). So one id can hold both a HANDLED marker
and a DLQ row.

**C6** — extract:28/:30 "at all, ends and errors; nothing at off", and branch D-102's
`ERRORS = FAILED or HANDLED` (`RecordingPolicy.java:58-59`). → Interim until CORE-13. Under Q-9
the metadata of every step is always recorded and `ends` is gone (`CORE-REWORK.md:136-137`), so
the marker and handler rows are recorded at every level in every mode, and at-most-once "nothing
at off" goes. The `errors` switch keeps the failed step's input body even when the failure was
handled (`:132`).

**C7** — extract:34 / branch D-105 "a run that ends HANDLED is a successful run". → Should read
"a **message whose latest attempt** ends HANDLED". A run is now an attempt (D-140), and the FAILED
rows of deferred attempts must not count in the error rate.

**C8** — extract:34, replay and DLQ retry. → `Replay.from` takes the latest attempt whose step
n−1 row is OK (`Replay.java:103`, not `:101`; D-140), so the marker is never a replay input.
Handler steps lie outside 1..steps and are refused 400 (`AdminServer.java:151`). Both `/replay`
routes mint a new id with `replayOf` (D-136), run the handler of the version deployed now
(§6:292) and are unfenced (§4:207). A handled failure leaves no DLQ row. A DLQ row that does
exist (rethrow, unmatched kind, handler or marker failed, exhausted, parked before the handler was
deployed) taken by the same-id `/retry` runs as attempt k+1, its last hand-out, and ends HANDLED
or parks again at step 1.

**C9** — extract:36 ".maximumRedeliveries stays the step's retry", plus extract:5-7. → Still the
in-step `retry`. Add that `delivery.retries` (a rerun from the ingress) also runs before the
handler. Version 3.4 becomes main's current (`ARCHITECTURE.md:91`, 3.21); "D-045 / D-049" becomes
"D-045 as amended by D-135, D-049"; every branch id gets its new id from section 4.

## 2. New conflicts, each needing a decision

**N1** On main a deadline is a retryable DOWNSTREAM Nack (`FlowBuilder.java:681`,
`TargetLimit.java:158`, `NackException.java:29`), so branch D-103's "deadline passed is never
handled" cannot be told apart by kind. The handler also shares the run's deadline (§4:195, D-100),
so its own `to` is refused after a timeout. **Decide:** give the handler its own finite deadline,
and say whether deadline Nacks may be handled.

**N2** Handler sends carry the message id as `x-message-id` (§5:261, D-113). A partner that
dedupes will drop a compensation as a duplicate. **Decide:** the same id or a derived one — wire
format, expensive to reverse.

**N3** A park without a run (`FlowWorker.java:648-655`) never reaches a handler. Either state
that, or run the handler on the ingress with a synthetic `Failure`.

**N4** Where the awaited marker lives: Postgres keeps only the last N hours (§8:353), CORE-8
removes monitoring waits, and CORE-10 may move step metadata to ClickHouse
(`CORE-REWORK.md:109-111`). Name it a park-equivalent exception and fix its home before CORE-8/10.

**N5** Error retention (`CORE-REWORK.md:141-144`): does a HANDLED message count as an "error
message"? The D-105 logic says no; **the owner's brief wins, so the owner must answer.**

**N6** A handled replay returns normally, so the admin answer is 200 COMPLETE
(`AdminServer.java:837-841`). **Decide:** keep COMPLETE or add a new `outcome:"HANDLED"` — wire.

**N7** Branch D-107 (owner 2026-11-14: no replay of a successful message) contradicts main's
D-136 / D-142 (2026-11-13, new-id replay). C8 follows main. The C7 rule (deferred attempts do not
count) is branch D-106, which main does not have.

## 3. What survives as written

- The declaration surface: `onError` as one name (`none` allowed) per flow, step or route branch;
  `errorHandlers[] {name, on, then, reply, steps}`; no nesting and no loops; the Camel items not
  carried over.
- `ErrorHandler.apply(Envelope, Failure)`, `Failure(step, kind, text)` and the read-only `error.*`
  scope, never a prop. The refusal at §5:246 still stands.
- The sync row (`reply.status` goes into `http.status`, still a prop: `HttpComponent.java:84`,
  `:240`, `:501`), the at-most-once drop, and "the rest of the main pipeline does not run".
- Row names `<handler>:<step>` and `onError:<handler>`, outcome HANDLED, `error` text,
  metadata-only marker, error iff FAILED or HANDLED (today `Snapshot.java:46`, `:67` have only
  OK/FILTERED/FAILED; `outcome` is text with no check constraint, `V2:7`), the `handled` counter,
  and `rethrow` as the operator's way to get a DLQ row.

## 4. Ids

The designer proposed D-144..D-147. **The architect has shifted them by one**, because
`agent/packets/FIX-195.md` was written first and holds D-144 for the BatchingWriter policy. The
numbering to use is:

D-145 ← D-102. D-146 ← D-103, widened per C3. D-147 ← D-105. D-148 ← D-106, only if C7 is
adopted. F-37 ← F-26 (still true: `ARCHITECTURE.md:241`). F-38 ← F-27.

Not ported with this row: branch D-104 (BatchingWriter, unrelated — it is FIX-195's D-144 instead,
see that packet), D-107..D-110 and F-29 (the keying is done on main by D-140/V19; the direction
question is N7).

Never reuse main's D-102..D-110, F-26/F-27/F-29 or the two CONSIDER-11 rows (`BACKLOG.md:290`,
`:399`).

Implementation rows become CONSIDER-15a… No FIX id is needed.

> **Resolved by the architect, 2026-11-15:** D-144 belongs to FIX-195's BatchingWriter policy;
> the error handling takes D-145..D-148 as section 4 now states.

## 5. Added by the architect on 2026-11-15, from the owner's two questions

**Option C, a third shape for `then: handled`.** The owner asked what function the DLQ serves here,
on the premise that a dead-letter queue is the last place a message can go. That premise makes a
third option visible, which neither the designer nor the advisor put forward: the handler runs, the
outcome is HANDLED, but the message still leaves a record in the operator's list — marked handled
rather than failed, not a failure needing attention. All three functions of the park survive: the
operator sees it, the row pins the body's segment so the bytes are not swept at
`FLOTTILA_JOURNAL_KEEP_MAX_AGE`, and `/retry` stays possible. The cost is one row per handled
message and its retention. It does **not** remove C5's crash window — a marker durable with the
complete lost still reruns — but it turns a silent wrong outcome into a visible one, which is the
trade this project makes everywhere else. Weigh C against the row's own "handled → no park" text.

**The scoping question is narrower than it was put.** Sync has no DLQ (the error goes to the
caller) and at-most-once has none (the failure is logged and the message dropped) —
`ARCHITECTURE.md` §4, the delivery-mode table. The branch design gives both of them the handler
all the same: extract:30 gives at-most-once the marker on the trail and nothing to return, and
extract:32 has a handled failure reach "neither the park, nor the sync error reply, nor the
at-most-once drop". So `then: handled` and the DLQ-suppression question can arise only in
at-least-once. Phases 1 and 2 of the advisor's sequencing would serve two of the three delivery
modes completely, which is a stronger argument for sequencing than the advisor made.

**What at-most-once cannot have, and why it is not a hole.** It has no durable save, so a handler
there can act only synchronously on the same virtual thread — call an endpoint, raise an alert,
send the message elsewhere. "Someone picks it up later" cannot exist, because nothing durable was
written; wanting that is wanting at-least-once, which is a per-flow choice the author already
makes. A forward that itself fails has nowhere to go and is logged. State this in §6 rather than
leaving a reader to infer it.
