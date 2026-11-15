# FIX-262 — the designer's draft (class C), as handed back 2026-11-15

Written to this file by the architect, unchanged except for the wrapping; the designer writes no file. Tree 616ee25b.
Where the designer did not read or could not verify something, the draft says so (last paragraph).

## 1 Proposal

**Q1. F-55 (template form)**
- Title: "F-55 — replay leaves Rail8: one id per message; retry and cancel of a failed one are the only operations".
  Date 2026-11-15, task FIX-262, version 3.27 → 3.28 (`ARCHITECTURE.md:124` is 3.27).
- Assumption: §1:174 says "replayable". §4:242, §6:313 and :327 describe a new copy under a new id (D-136). §8:386 and
  :452 promise "replay from any recorded step".
- How it fails: the owner's scenario is one message under one id (find it, remove the cause, then retry or cancel it),
  and he sees no use case for replay (D-156, `DECISIONS.md:164`; `decisions-extract.md:6`). Both routes mint a new id
  (`AdminServer.java:736`, `:755`), so one business message gets two histories.
- Severity: major. A documented mechanism goes; the intent stays: every message, step and payload recorded and searchable.
- Evidence: D-156; `AdminServer.java:410-413`; `agent/work/FIX-262-scout.md`.
- Change made:
  - Removed: both routes, `Replay`, `Envelope.REPLAY_OF`.
  - Unchanged: `/retry` (`AdminServer.java:772-802`, `WorkQueue.restart`).
  - Written in, citing D-156: no cap on retries; a sync message and a cancelled one are never retried; a retry that
    succeeded shows only in the message's history; a cancelled message counts as a failure of its flow (the default,
    draft, unanswered).
  - Cancel: named in the text, built by its own row.
- Deliberately not changed: recording levels and per-step bodies; D-140/V19 keying; the journal's recovery; the worker's
  reruns; the comment of the applied `V1__m1_schema.sql:37` (Flyway checksum, D-136's precedent `:144`); old changelog
  rows; the sim's seed replay command (§11:612); Kafka log replay (§7:367, :372, §14:719).

**Q2. ARCHITECTURE edits**

| Line | Edit |
|---|---|
| :174 §1 | "captured and searchable, and a failed message is retried or cancelled under its one id" |
| :187 §2 | "hand-over and ingest run on Postgres and the journal" |
| :227 §4 | "for work in flight and for a retry" |
| :236-240 | Column "Replay" becomes "On failure, the operator": sync "nothing; the caller retries, never resent"; at-least-once "retry or cancel, same id"; at-most-once "nothing; logged and dropped" |
| :242 | Delete ", and replays" from "are not fenced". Delete "the 422 texts name `POST /admin/dlq/{id}/replay` … FIX-138b, pending)". The new text: the 422 texts say why and name no other route |
| :250 | Delete "a replay", "a DLQ replay's run", the clause "and except a DLQ replay that ran … run it again)", and "a replay or DLQ replay whose run the pool refused answers 503 …" |
| :277 §5 | Delete "replay-of" |
| :313 | ", a new copy …/replay (D-136)" becomes "; cancel ends it (pending row); no copy under a new id (D-156)" |
| :327 Recording | (1) "a skipped body makes replay … impossible" → "a skipped body is not shown for that step, and the UI says so". (2) Delete "Replay from step N needs … step N−1", "a replay from step n starts from the latest attempt …", and the dry-replay clause (see Q8). (3) "A replay (…) or a same-id DLQ retry … (both exist …)" → "A same-id DLQ retry runs the flow version deployed now (it exists to rerun a parked message after a fix)". (4) "Replay from any recorded step works after a restart" → "A kept step body stays readable after a restart (`…/steps/{n}/body`)". (5) "a message or DLQ replay whose input body … 409" → "a DLQ retry whose ingress body is no longer kept is 409 (§4)". (6) New sentences: one id for life; retry/cancel; the three D-156 rules |
| :366 | Delete "not a replay" |
| :367, :372, :719 | Stay (another meaning) |
| :374 | "a message or DLQ replay of a removed body" → "a DLQ retry of a removed ingress body" |
| :386 | "and replay from any recorded step" → "and every attempt's rows under the message's one id" |
| :425 | Delete "and a message or DLQ replay" |
| :430 | "and for replay" → "and for a retry" |
| :452 | "replay or resend from any step" → "retry or cancel of a failed message, its attempts and its cancel in its log" |
| :657 | Delete "replay," |
| :663 | Delete "(Replay from P2-10b)" |
| :685 | "replay" → "same-id retry" |
| :689 | "replay/resend from the UI" → "retry and cancel from the UI" |
| :718 | "replay window" → "retry window"; "per replay" → "per retry" |
| :729 | Closed (Q8) |

- Changelog row: `| 2026-11-15 | 3.28 | §1, §2, §4, §5, §6, §7, §8, §12-§14: replay leaves the architecture — one id from
  ingress to end; the operator's operations are retry and cancel of a failed message under it, recorded in its log; no
  cap on retries; sync and cancelled messages are never retried; a successful retry shows in the message's history only;
  a cancelled message counts as a failure of its flow (default, unanswered, draft); cancel pending (FIX-262e); the dry
  replay closed | F-55 (major); D-156 |`
- What Recording is for once replay is gone: monitoring only - the timeline, each step's body (GATE check 6), search by
  key, error diagnosis and "save as test case" (:452).
- What retry needs from recording: only attempt 1's ingress row and the ingress body.
  - `FlowWorker.java:640-646` rebuilds every run from the first ingress row.
  - `RESTART` copies `<id>/0/body` (`PgWorkQueue.java:284`, `:293-298`).
  - Verified: it inserts a step-1 row with attempts k and `max_attempts` k+1 (`:297-298`; `WorkQueue.java:269-272`). Its
    next hand-out is attempt k+1.
  - No step N−1 snapshot is needed.

**Q3. Cancel: its own row (FIX-262e, class C) with its own designer pass.** Reasons:
- (a) Its home is undecided. Today there is no message log (§8:386, §7:374; OPS-2 pending). The cancelled state needs a
  persisted shape: a marker row in `messages`, or a lifecycle table as in the branch's D-108
  (`decisions-extract.md:7`). That is a persisted format.
- (b) It must take the same lock as `RESTART`'s `for update` (`PgWorkQueue.java:291-292`) so it cannot race a retry.
- (c) Whether a held or lost work item counts as "failed" is open.
- Sketch for that designer:
  - `POST /admin/dlq/{id}/cancel` takes an actor, as discard does (`AdminServer.java:715-721`), and a required reason.
  - One transaction: delete the DLQ row (`Dlq.remove` stays for this; its only caller today is `Replay.java:159`),
    insert an `audit` row `dlq.cancel` (V16 `audit` table, `V16__audit.sql:5-11`, as discard does at
    `PgWorkQueue.java:266`), and write the terminal marker.
  - A later `/retry` answers a refusal that names the cancel, not today's 404 (`AdminServer.java:775`).
  - A sync message has no DLQ row (§6:313), and `/retry` already answers 422 for it (`AdminServer.java:780-783`).
  - Under the D-110 default a cancel adds to the flow's failures. No per-flow error rate exists yet (OPS-8 pending), so
    the metric is a statement only.
- What FIX-262's documents may promise before cancel exists: "cancel (FIX-262e, pending)" and nothing about its route
  or storage.
- Gap until then: a DLQ row leaves only by a retry that succeeds, and its kept ref pins its journal segment (§4:246).

**Q4. Remove `Envelope.REPLAY_OF` (`Envelope.java:38-39`) now; there is no release.**
- Fix the Javadoc at :43, `Props.java:8`, `Body.java:48` and `BodyLostException.java:7-8`.
- The guard `props->'replayOf' is null` (`PgWorkQueue.java:292`) and its twin at `AdminServer.java:785`/`787` are
  redundant and can go. A replay run drops ATTEMPT (`Replay.java:259-262`) and its park copies those props
  (`Parked.java:58-60`). So any old replay row has no attempt, and both the SQL's `props->>'attempt' = $4` and
  `AdminServer.java:785`'s check already refuse it.
- New 422 texts:
  - :782 "a <mode> message is never resent: its caller retries"
  - :787 "not parked by its flow's worker (step s, attempt a): it cannot be retried"
  - :790 "parked before its key was kept: it cannot be retried in order"
- None names `/replay`. Whether they name cancel once it exists is open.
- Old rows' "replayOf" prop and "replay" step name stay as inert data.

**Q5. `Replay.rebuild` (`Replay.java:250-253`).** Its only caller after the removal is `FlowWorker.verdict` (`:646`).
- Correction to the scout: that call is every worker run's start, not only a retry's.
- Move it as a `private static Envelope fromIngress(Snapshot, String id, String flow, BlobStore)` in `FlowWorker`
  (props = the snapshot's).
- Nothing else survives because retry needs it. Retry never touches `Replay` (`AdminServer.java:772-802`).
  `ReplayRefusedException` and `RowNotRemovedException` are used only by `Replay` and by `ran`/`refusal`
  (`AdminServer.java:831-866`), so both go whole.
- Also go:
  - `FlowBuilder.replay` (all three, `:440-474`) and the Javadoc at :114 and :494
  - `Delivery.admission()` (`:146-148`; its only caller is `FlowBuilder.java:457`)
  - Deployments' `Flow.replay` (`:377`, `:389`, `:400-407`), `replay(String)` (`:488-493`), the close and drain at
    `:656-670`, and the build at `:1518-1537`
  - AdminServer's `replaying` (`:296-297`), `from()` (`:818-829`) and the `ids` parameter (`:292`; used only at `:410`)
- Stays with its Javadoc fixed: `Dlq.remove` (`Dlq.java:30`), needed by cancel.

**Q6. Rows** (section 2). Why deleting these tests is not "weakening a test to go green":
- The behaviour they pin is withdrawn by the owner (D-156) before the code changes. Each test goes in the commit that
  removes its code, with D-156 named in the commit body.
- Every replay test that pins a property retry or monitoring still needs is moved onto the surviving path, not deleted
  (listed per row).
- The deletions and rewrites are the test-author's red, approved by the architect.

**Q7. Gate check 7, `GATE.md:27-29`.** New name: "Retry under one id, and DLQ".
- Partner down → retries per policy → DLQ row at `GET /admin/dlq`.
- `POST /admin/dlq/{id}/retry` answers 202 `{id, attempt: k+1}` with the same id.
- Once the partner is back the message is delivered. `GET /admin/messages/{id}` lists every attempt's rows under that id
  in (attempt, step) order: the failed attempts' FAILED rows, then the last attempt OK to the final step.
- The partner received the same `x-message-id` on each send (D-113 cited, not read). The DLQ is empty afterwards and a
  second `/retry` answers 404.
- This measures what the one-id design promises: one id with its whole history, and one effect at a deduping partner
  (D-136's reason, `:144`). The per-step body that `?from=3` relied on stays measured by check 6 (`GATE.md:25`).
- `GATE.md:98-99`: drop "replay" from both lists.
- `HANDOVER.md:78` records check 7 "met" on replay evidence. It becomes "not measured since D-156". Claim nothing.

**Q8.**
- CONSIDER-22 (`BACKLOG.md:95`, ARCH:729): the dry replay re-runs a recorded message, which is replay as D-156 removes
  it. Close it as an operation. Its other idea, reproducing a step's body for display instead of storing it, could only
  live as a monitoring and storage technique under CORE-13. Whether that half survives is the owner's word.
- CONSIDER-15 designer input, what falls: C8's replay parts (`CONSIDER-15-designer.md:59-63`); N6, the handled-replay
  wire question (`:95-96`); N7's contradiction (`:98-100`), now settled the branch's way; C2's "and replay" Parking
  clause (`:21-22`).
- What stands: C8's same-id `/retry` (`:63-66`); C7, the branch's D-106, now in ARCH through F-55, so C7 can be
  adopted; D-157 unchanged.
- The file's id map (`:118-123`, D-145..D-148) is stale: main's D-146 and D-147 are used elsewhere (`BACKLOG.md:510`;
  ARCH:14).

**Q9. Coverage and mutation floors.** Risks where code that stays was covered only by replay tests:
- (1) `Recorder.start` at a step ≥ 1 (`Recorder.java:48-49`) was exercised only by `RecorderTest:241`. Afterwards the
  only call is at step 0 with INGRESS (`FlowBuilder.java:486`). Keep a direct `recorder.start(policy,"x")` case at
  step 1, or narrow the API.
- (2) `InMemoryDlq.remove` (`:64`) may have been covered only by `ReplayTest`. Not checked; add a Dlq contract case.
- (3) Per-step Parking with `parking=true` for at-least-once (`FlowBuilder.java:493-502`) is reachable afterwards only
  through `build()` (`:286`), which no main code calls (the sim uses delivery and worker, `SimRun.java:138-140`). Check
  that a `build()` park test exists.
- (4) The moved rebuild is safe: it runs on every worker test.
- How the rows keep the floors: each row records api/core line, branch and PIT before and after in its evidence file.
  "No decrease" is read literally: a module total that falls gets a test on the weakest surviving branch, never an
  exception.

## 2 The rows (in order; each one commit, one whole-reactor run)

- **FIX-262 itself [C]**, docs only: F-55, the ARCH edits (Q2), the changelog row. Then the advisor.
- **262a [B]** runtime admin.
  - Code: drop the two routes, handlers, `ran`/`refusal`/`from`/`replaying`, the `ids` parameter (Node:337 not read)
    and the `REPLAY_OF` guard and texts at :785-790.
  - Red: `bothReplayRoutesAnswerAsUnknownRoutes` (same body as an unregistered POST, ROUTER_ERRORS `:422`) and
    `noRetryRefusalNamesAnotherRoute`.
  - Tests that go: `AdminServerReplayTest` (20; move the retry half of `:802` to `AdminDlqRestartTest`),
    `AdminServerReplayRaceTest` (4), `NodeIT:1567`, `SftpIngressDedupeIT:231`, `InvoiceSyncReplayIT` (second case not
    read).
  - Rewritten:
    - `ReplayRetentionIT:211` → retry 409 + GET 404
    - `LostBodyAdminIT:214` → retry 409 + GET 410
    - `NodePoolWaitIT:616`/`:641` → `aDlqRetryRefusedByTheWorkPoolAnswers503AndKeepsTheRow`. ARCH:250 claims this; the
      designer's grep found no `/retry` case in that class.
    - `NodeIT:1715` → same-id retry delivers (if not already pinned; unverified)
    - `AdminServerBrowserGuardTest:118`/`:159` → the fixture moves to `/retry`
    - `namesReplay` at `AdminDlqRestartTest:211` and `AdminDlqRestartIT:276` → asserts the reason
- **262b [B]** core + Deployments + schema + sim. Depends on 262a.
  - Code: drop `Replay`, its two exceptions, `FlowBuilder.replay`, `Delivery.admission`, Deployments' replay; move
    rebuild (Q5). Fix the Javadoc at `Dlq.java:30`, `Parked.java:18,28`, `Trail.java:34-35`, `package-info:2`,
    `Node.java:110`. Remove the description clause in `flow.schema.json:30` and `Check.REPLAY_YIELDS_RECORDED`
    (`Check.java:5`; comment `SimSelfTest.java:32`).
  - Red: a deployed flow opens producers for its delivery and its worker only; the replay's own set
    (`FlowBuilder.java:462`, `:473`) is gone, in `DeploymentsProducersTest`.
  - Tests that go: `ReplayTest` (22), `ReplayEdgeTest` (2), `DeploymentsReplayCloseTest`,
    `DeploymentsProducersTest:382`/`:423`, `FlowBuilderTest:397`/`:442`, `FlowBuilderTargetTest:496`.
  - Rewritten:
    - `FlowBuilderContextTest:222` and `FlowBuilderFenceTest:44` keep their non-replay half
    - `RecorderTest:241` → start at step 1 (Q9)
    - `DlqBodiesIT:334`/`:419` → restart + worker on the fresh store
    - `StepBodiesIT:402`/`:439` → GET body, `?attempt=2`, after a restart
    - `ReferenceEnqueueIT:165` → renamed (still used by `PgIntake.java:473`)
- **262c [A]** api + queue guard. Depends on 262b.
  - Code: drop `Envelope.REPLAY_OF` and the Javadoc (Q4); drop the clause at `PgWorkQueue.java:292`, its Javadoc
    `:281-283` and `WorkQueue.java:274`; `EnvelopeTest:88` goes.
  - Red: `PgWorkQueueRestartIT:140` becomes "a worker's step-1 park with attempt k is restarted whatever its other
    props", plus "a row without the worker's attempt is not restarted".
- **262d [A]** documents outside ARCH. Depends on FIX-262.
  - `README.md:4`/`:10`; GATE checks 7 and 6 (Q7); `HANDOVER.md:49-54`, `:78`, `:108`, `:126`;
    `PROTOTYPE-VS-ARCHITECTURE.md:40` ("kept until D-156"; :17, :28 history, :59 Kafka stay); `agent/NOTES.md:219-224`.
  - `agent/patches/FIX-262-claude-replayable.patch` for `CLAUDE.md:12`, for the owner only.
  - Leave untouched: `V1:37` (Flyway's `validateOnMigrate` is not overridden at `Migrations.java:40-44`; default true,
    not verified on the jar; a comment edit changes the checksum per D-136), `ISSUE-2:16` (history), and the owner's
    briefs `CORE-REWORK.md:34,151` and `OPERATIONS-BRIEF.md:181` (see section 5).
  - Withdraw FIX-138b (`BACKLOG.md:356`) and CONSIDER-14 (`:376`); both rest on replay.
- **262e [C]** cancel design (Q3), then its implementation rows.

## 3 Rejected

- Keeping the routes behind a flag or as deprecated: D-156 says the operation is out, and nothing outside the repo
  calls them.
- Keeping `REPLAY_OF` to read old rows: the attempt check already refuses those rows (Q4).
- Moving rebuild to a public `Snapshot` method: a new public API with one caller.
- Designing cancel inside FIX-262: its persisted shape needs its own C pass.
- Editing the V1 comment: it breaks checksum validation on existing databases.

## 4 Expensive to reverse (draft - review at hand-over)

- Public api: `Envelope.REPLAY_OF` removed.
- Admin wire: both routes gone; `/retry` 422 texts changed.
- The persisted "replayOf" prop no longer written or read.
- GATE check 7's content; the §4 table column.
- D-110 counting default; the cancel route, marker and state (262e).

## 5 Conflicts

- **Id collision.** The branch's D-106/D-109/D-110 collide with main's D-106 (tenant key, ARCH:246), D-109 (group
  commit, ARCH:244) and D-110 (CLAUDE.md, sizes). ARCH must cite D-156, or new main ids, never the branch ids bare.
- **The owner's briefs** "win within their scope" (ARCH:7-8) but mention replay (`CORE-REWORK.md:34`, `:151`;
  `OPERATIONS-BRIEF.md:181`). F-55 must say that D-156, the owner's later word, overrides them on replay.
- **Owner's files.** `CLAUDE.md:12` contradicts §1 until the patch is applied. GATE.md is the gate agreed with the
  owner (`GATE.md:3`).
- **Superseded or stale text.** D-140's replay clauses (`DECISIONS.md:148`) become dead text. D-136 (`:144`) needs a
  superseded marker. HANDOVER:78's "met".
- **"No decrease on any push"** against removing well-covered code (Q9).
- **Scout corrections.** `rebuild` is shared with every worker run, not only retry. Nothing of
  `ReplayRefusedException` or `RowNotRemovedException` survives.

## 6 Open

- **The architect decides:** whether 262a and 262b are one commit or two; where rebuild lives; new D-ids or citing
  through D-156; whether to narrow `Recorder.start`; per-step Parking, dead in production: a later row; whether the 422
  texts point to cancel; how "no decrease" is read.
- **Only the owner can answer:** D-110 counting (unanswered; the default holds); CONSIDER-22's storage half; whether
  cancel covers held or lost work items, and whether a reason is required; the GATE check 7 replacement; applying the
  CLAUDE.md patch; amending the two briefs.

Not read or not verified: the bodies of NodeIT, InvoiceSyncReplayIT, AdminDlqRestart(IT/Test), PgDlqIT and
FlowBuilderTest's `build()` park; D-045, D-067, D-096 and D-113; F-8 and F-11; ARCH:261; `Node.java:337-360`; the
conditions at `Deployments.java:1510-1537`; Flyway's checksum code in the jar; P2-10b's scope.
