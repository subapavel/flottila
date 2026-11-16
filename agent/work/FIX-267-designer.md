# FIX-267 - the designer's draft (2026-11-16), as delivered; wrapped, not reworded

Nothing written, nothing run. Every code fact below was read on disk. Packet: `agent/packets/FIX-267.md`.

## Proposal, in the packet's order

1. **The route.** `POST /admin/dlq/{id}/cancel`. The actor is `X-Rail8-Actor`, handled as discard handles it
   (AdminServer.java:678-682: at most 128 characters, `unknown` when absent). The body is `application/json {"reason"}`:
   the reason is required, stripped, 1..1024 characters, and the body is at most 8 KiB. A JSON body rather than a header
   or a query, because a reason is free text that may not be ASCII. Its answers:
   - 200 `{id, auditId, cancelledAt}`.
   - 400 for a bad actor, reason or body.
   - 404 when there is no DLQ row and no cancel: "only a message in the failed list is cancelled".
   - 409 when a work row of the id still exists: "not settled yet, work item <seq>".
   - 409 when there is no DLQ row and the last event is a retry: "retried at T by A, attempt n". This is the cancel
     that lost a race to `/retry`.
   - 410 `{error, cancelled:{auditId, at, actor, reason}}` when the message is already cancelled: a repeat, or the loser
     of a race with another cancel. Nothing is written.
   - 503 and 500 as on every admin route (D-133).
   `/retry` after a cancel answers 410 with the same `cancelled` object: both of today's 404s (AdminServer.java:697,
   :720) first read the last event.
   **The race.** The cancel locks the DLQ row `for update`, as RESTART's `d` does (PgWorkQueue.java:291-292). Under READ
   COMMITTED the loser finds no row and answers from the last event. The cancel takes no key lock: it touches no work
   row, so it cannot deadlock with restart, which locks the key and then the row (:605-606).
2. **Why it needs no flow and no body.** The cancel reads `dlq` by id only (no step, attempt or key filter), probes
   `work` by id, and writes `dlq` and `audit`. So every row `/retry` refuses can be cancelled (:701, :702-705, :707-710,
   :711-713, :717-718), including the keyed row parked without its key (FIX-263-step1.md:53-55).
   **Which node.** Any member. The route is registered beside `GET /admin/dlq`, which is unconditional (:366), not under
   work+deployments as retry is (:376). The node holding the journal copy does not matter: the cancel reads no body, and
   retention and the sweeper remove the ref and the segment later. There is no fence check; `/retry` has none either.
   **The work probe is what makes "for good" true.** A park commits before its settle (FlowWorker.java:697-698, then
   :745). An unsettled row reruns from ingress and can deliver while it is within its bound (:637). A re-park after a
   delete conflicts with nothing (PgDlq.java:62). Only RESTART creates a work row for a parked id, and it needs the DLQ
   row the cancel has locked. `work(id)` has no index (V1:52-62, V15, V16), so the probe scans the flow's rows on
   `work_flow_seq`. That cost is on the operator path only; an index would cost every intake insert (D-161).
3. **Where the record lives: option (a), one audited delete, no new table.**
   - **The statement** follows DISCARD (PgWorkQueue.java:263-268) in four parts: `d` (the DLQ row, for update), `w` (a
     work row of the id and flow), `gone` (the delete, only when `w` is empty), `aud` (the audit insert from `gone`).
   - **The audit row:** `now(), actor, 'message.cancel', 'message/'||id, detail{flow, step, stepName, error cut to 1024,
     parkedAt, partitionKey, bodyHash, bodySize, reason, remote}`.
   - **V20:** `create index audit_subject on audit (subject, id)`. V16 has no such index (V16__audit.sql:5-12).
   - **What shows afterwards:** the failed list no longer has the message. `GET /admin/messages/{id}` gains
     `events:[{auditId, at, actor, action: retry|cancel, reason, attempt}]`, filtered to those two actions (OPS-9's
     payload views will share the subject). It answers 200 with `steps:[]` when only events remain. `/retry`'s refusal
     reads the last event.
   - **Retention.** No DLQ row pins the message any more, so its rows, bodies and refs go by their age
     (Retention.java:31-39), at the next run when they are older than the max age. Its `processed_ids` go too (:40), so
     a sender's repeat after that is a new message under a new id. The audit row is never deleted (V16:1).
   - **Cost.** Per cancel: one lock, one delete, one insert. Nothing on the hot path; PARK is unchanged.
   - **OPS-2.** The audit row stays the authority, because it is written in the same transaction as the change. OPS-2
     projects `message/<id>` rows into the message's log, and `events` is then superseded.
   - **Upgrade.** An index only. Old DLQ rows cancel as they are.
4. **The record of a retry.** The actor comes from the same header, and an optional `{"reason"}` body is accepted.
   RESTART gains `aud as (insert into audit ... 'message.retry', 'message/'||$1, detail{flow, attempt k+1,
   partitionKey, reason, remote} from ins)`. That is one statement with the delete and the insert (:290-301), so a
   rolled-back restart (lost ref, :608-612) leaves no record. `WorkQueue.restart` gains `actor, reason, remote` and
   stays `Future<Boolean>`. The in-memory queue records nothing; it has no DLQ either (InMemoryWorkQueue.java:123-130).
   The 202 is unchanged. The `retry` event names attempt k+1, whose step rows carry that attempt (D-140).
5. **What a cancelled message is.** No status and no step row are added. Its last step row stays FAILED
   (Snapshot.java:46: `OK, FILTERED, FAILED`), and its end is "failed, closed by a cancel event". The failed list loses
   it. OPS-8 must count it once as a failure of its flow, which is ISSUE-10 item 2's default. A sync or at-most-once
   message is never parked, so a cancel of it answers 404; there is no mode lookup.
6. **`Dlq.remove` goes** (Dlq.java:30-31; InMemoryDlq.java:62-67; PgDlq.java:74, :147-150; its cases in PgDlqIT and
   InMemoryDlqTest). FIX-264 removes its only main caller, the cancel cannot use it, and an unaudited delete is the
   stopgap F-55 rejects. **The core API:**
   - `Dlq.cancel(String id, String actor, String reason, String remote): Future<Cancel>`, with `sealed Cancel
     {Done(Event), Unsettled(long seq), NotParked(Optional<Event> last)}`.
   - `Dlq.events(String id): Future<List<Event>>`, with `record Event(long auditId, Instant at, String actor, Kind
     kind, Optional<String> reason, OptionalInt attempt)` and `enum Kind {RETRY, CANCEL}`.
   - `InMemoryDlq` takes a `Clock`.

## Rejected

- **(b) A marker on the DLQ row.** Every DLQ reader (LIST, GET, RESTART, PARK's on-conflict, Retention.java:33, :74,
  :81) would have to skip it. The row would pin its message for ever, an unbounded limit. `Parked`, a public record,
  would grow.
- **(b) A marker in `messages`** (a step row, or a CANCELLED outcome). That table is the recorder's best-effort table
  and is moving to ClickHouse. The Outcome enum is persisted and public. There would be no shared transaction with the
  DLQ delete.
- **(c) An events or lifecycle table.** It is a second who-did-what table beside V16's identical shape, and a Postgres
  per-message log, which section 8 rules out ("No Postgres `message_log` table", ARCHITECTURE.md:452).
- **200 on a repeat cancel.** It would drop the second request's actor and reason, and claim an act that request did
  not do.
- **409 instead of 410 for "cancelled".** Clients could not tell a permanent end from "not deployed here" (409, :701).
- **Reason in a query or a header.** A query lands in proxy logs; a header is not UTF-8-safe.
- **A guard in PARK.** It is a read per park for a case the work probe already excludes.
- **A key lock or a fence check on cancel.** The cancel writes no work row.

## Expensive to reverse (each `draft — review at hand-over`)

- The route, its `{reason}` body and its 200/400/404/409/410 answers.
- `/retry`'s 410 and new 409, and its optional body.
- The timeline's `events` field and its 200 with `steps:[]`.
- The audit actions `message.cancel` and `message.retry`, the subject `message/<id>` and the detail keys.
- In core: `Dlq.cancel`, `Dlq.events`, `Event`, `Cancel`, the `WorkQueue.restart` signature, and the removal of
  `Dlq.remove`.

## Conflicts

- CLAUDE.md wants the clock through `Runtime`, but the audit `at` is the database's `now()`, as in DISCARD
  (PgWorkQueue.java:266).
- FIX-263's red case "no `/retry` refusal names another route": row c replaces it (its 422 and 409 texts name cancel).
- ARCHITECTURE needs edits at: section 4, line 244 ("no operation until cancel"); section 6, line 331 ("neither
  operation leaves a record"); section 7, line 370 (the restart); section 8, line 390 (the timeline is snapshots only);
  section 9, line 504 (audit = discard only).
- The in-memory twins cannot answer Unsettled or record a retry. The precedent is WorkQueue.java:273-277.

## Open: the owner's, each with a default

- After a cancel the history goes by normal age, possibly at the next run. Default: yes. The alternative is to keep it
  for a max age after the cancel.
- Retry's reason. Default: optional. Note that requiring it later would be a tightening.
- Undoing a cancel. Default: never.

## Open: the architect's

- 410 or 409 for a cancelled message.
- A JSON body or a query for the reason. The Vert.x APIs this needs (`BodyHandler.setBodyLimit`,
  `HttpServerRequest.body()`) are unverified: no javap was run.

## Implementation rows, in order

- **a [B], after FIX-264.** Scope: V20 and the store methods in both stores; a new `DlqContract`, since only PgDlqIT
  and InMemoryDlqTest exist; `Dlq.remove` goes. Red test, contract:
  `DlqContract.cancelEndsTheParkedRowAndRecordsOneEvent` - a cancel with an actor and a reason empties `get` and `list`
  for the id and gives one CANCEL event; a second cancel answers NotParked with that event and writes nothing.
  Postgres only: `PgDlqCancelIT.refusedWhileAWorkRowOfTheIdExists`, and `cancelAndRestartOnOneRowOneWins` - both calls
  wait on a held row lock, seen in `pg_locks` with no sleep, and exactly one commits. Property, in memory: no sequence
  of park, cancel and get brings a cancelled id back.
- **b [B], after FIX-265 (both edit RESTART).** Scope: the record of a retry. Red test:
  `PgWorkQueueRestartAuditIT.aRestartWritesOneRetryEventInItsStatement` - a restart gives one RETRY event with its
  actor and attempt k+1; a restart that finds no row, or a lost ref, writes none.
- **c [B], after a and b.** Scope: the admin routes. Red test:
  `AdminServerCancelTest.cancelledMessageLeavesTheListAndRetryAnswers410NamingIt`. Also: 400 without a reason, 404 for
  an unknown or sync id, 410 on a second cancel, and a timeline that lists both events.

## Not read

`FlowBuilder.build`'s per-step parking; the journal's recovery (assumed: it cannot recreate a work row for a parked
id); PgMessageStore; the AdminServer tests; the OPS-8 and CORE-13 rows; ARCHITECTURE beyond the lines cited.
