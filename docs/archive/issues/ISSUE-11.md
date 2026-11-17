# ISSUE-11 — a message sent twice when a node dies: what the platform should do about it
Task: CONSIDER-24
Date: 2026-11-16 (D-160)

## What is needed
The owner's word on the six items below. On 2026-11-15 the owner asked for the cause of the second
delivery at a node's death ("zjisti příčinu"). The cause is found and pinned by tests, the window
is measured, and a design draft was written and opposed. That row changes nothing in the code.
Each item states the default that holds until the answer, so no work waits.

**The cause, in plain words.** When a message's run ends, the node writes two things, one after the
other: first the message's history (every step, the last one included), then it removes the message
from its work queue. A node that dies between the two leaves a message that is delivered and
recorded as finished, yet still queued. After the node's restart the message runs again from the
start and the partner receives it a second time - under the same message id, with nothing that says
"again". The same happens when the node dies a moment earlier, after the partner answered and
before the history is written; then the history shows a single finished run and nothing tells an
operator that the partner has the message twice.

**How large it is.** Measured on 2026-11-16 (`bench/RESULTS.md`; one machine, the load of the kill
test, about 1 000 messages a second): from the partner's answer to the history's write 4.2 to
4.5 ms on average, from there to the removal from the queue another 2.4 to 2.5 ms. The second
part, between the two writes, is about a third (36 %) of the whole.
Counted directly by the kill test (2026-11-15): in 3 of 10 kills, 1, 6 and 2 messages ran twice
with two finished runs in their history; in the other seven kills none. That count sees only the
second part. The first part - the longer one - the kill test cannot count yet (FIX-272). The
measured times alone would suggest about three times as many per kill as were counted; that is a
computed figure, not an observed one, and the two are not reconciled.

1. **Should the platform look at its own record before it sends again?** When a node died after a
   run was written down as finished and before the message left the queue, the platform could
   complete the message instead of running it again. This helps for the third above only. It must
   not be decided from the history that monitoring settings can switch off - or a monitoring
   setting would decide whether a partner gets a duplicate - and a wrong "already done" loses a
   message, which is worse than a duplicate.
   The one form without that risk is to write the history and the removal in a single database
   transaction. The designer and the advisor both advise against it: it rebuilds the two write
   paths the measured throughput rests on, messages that must keep their order could take part
   only by losing their fast path, and it too helps for that third only. It is yours to choose.
   Default: yes, but not as a mechanism of its own. It is built as the last case of "a retry
   resumes at the failed step" (CONSIDER-13, which you accepted in D-142 for after the core gate
   and before the first production release), on a record the worker itself keeps. Until then such
   a death sends the message twice.
2. **The part that cannot be closed.** A death after the partner answered and before the platform
   noted it always sends twice: the partner's system and the platform's database are two systems
   and nothing commits in both at once. Is it acceptable that Rail8 says so plainly and tells
   partners to recognise a repeat by the message id?
   Default: yes. FIX-270 writes the sentences into the architecture, marked
   `draft — review at hand-over`. Two limits it will state: a file put on an SFTP server carries
   no message id today; and when the sender itself sends a message twice (its answer was lost)
   without a dedupe key, Rail8 sees two messages with two ids - a repeat the partner cannot
   recognise. A dedupe key prevents it, but only an SFTP inbox has one today (the file): a sender
   over HTTP cannot give one, so its resend after a lost answer is a second message (read in the
   code, not shown by a test; CONSIDER-27 - corrected 2026-11-16 by the review of FIX-270).
   Corrected again 2026-11-16 (F-58, the owner's remark on ISSUE-12): the SFTP file key recognises
   one file picked up twice by the platform - same path, time of change and content - not a file
   its sender uploads again. A sender's resend is another message, over SFTP too.
3. **An attempt number for the partner.** Should every delivery carry a number that says which
   attempt it is?
   Default: no. The number cannot say "you had this before": the second attempt after the partner
   answered "busy" and the second attempt after a death look the same. A partner that recognises
   repeats by the id gains nothing; a header in the public interface is hard to take back and easy
   to add later.
4. **A partner that cannot recognise a repeat.** What does Rail8 tell the author of a flow to such
   a partner?
   Default: the documentation names the two choices - an operation on the partner's side that is
   safe to repeat, or the delivery mode "at most once", which can lose a message instead of
   repeating it.
5. **A delivered message shown as failed.** When a run is left unsettled on the message's last
   allowed attempt - with no retries configured that is the first; after an operator's retry it is
   that one attempt - the next hand-out is parked without running: the message ends in the failed
   list although the partner may have it, and an operator's retry sends it again. Two existing
   tests show it for a death in the middle of a send
   (`FlowWorkerFenceTest.aNodeKilledMidSendIsRerunInDoubtWithTheSameIdAndParkedPastMaxAttempts`,
   `FlowWorkerRetryTest.aRestartedRowTakenOverInDoubtIsParkedUnsent`). Not staged yet: the same
   after a death between the two writes, and what the history then shows (FIX-271). Corrected
   2026-11-16: this item first said "read in the code, not yet shown by a test"; the review of
   FIX-270 named the two tests. Should that be closed sooner than item 1's default?
   Default: no sooner - it is closed together with item 1. Until then the history does not tell
   the operator that the partner has the message: after a death in the middle of a send, or before
   the run's rows were written, it shows nothing of the attempt the partner received - only the
   park (the doubt is counted in `work.doubts`). Only after a death between the two writes are the
   finished run's rows there; that they are shown beside the park is not staged yet. FIX-271
   stages the remaining case.
6. **What "no duplicate" means in the two gates** (added 2026-11-16, F-56). The Phase 2 gate says
   "Postgres failover during load with no loss or duplicate" (ARCHITECTURE section 13); hand-over
   check 5 says a kill between a file's pick-up and its delete "loses nothing and duplicates
   nothing" (`docs/GATE.md`). Read toward the partner neither can hold by design: a run whose
   removal from the queue failed - the database failing over, the node killed - is sent again under
   the same id (items 1 and 2; D-160, D-161).
   Default: both are read as "no message is lost and none is saved twice"; a repeat toward the
   partner under the same id is at-least-once working as designed. Both sentences carry a dated
   note saying so, marked `draft — review at hand-over`. When either is measured, the partner's
   receipts per id are recorded beside the result (FIX-272's count), so the reading never hides a
   repeat. If you mean more - no repeat toward the
   partner in those two tests - say so; no change of the platform can promise it for the part
   before the history is written (item 2).

## Why the agent cannot decide it
Items 1 and 5 decide whether the platform may ever treat a message as done without running it - a
change of what "at least once" means, expensive to reverse once operators rely on it. Items 2 to 4
are a public promise to partners. All five are product behaviour. Item 6 asks what a gate check
means, and a gate's meaning is the owner's.

## What was tried
Eight tests stage the death and pin what happens (`FlowWorkerRerunWindowTest`, `RerunWindowIT`);
every other place that can give a second run was read (`agent/work/CONSIDER-24-scout-3.md`); the
window was measured three times under two loads (`bench/RESULTS.md`, `bench/sql/settle-gap.sql`);
the designer drafted the options and the architecture advisor opposed the draft
(`agent/work/CONSIDER-24-designer.md`, `agent/work/CONSIDER-24-advisor.md`). Both reject writing
the history and the removal in one transaction (D-160 says why). The advisor found the draft's
check unsafe as written and named the safe form in item 1's default.
Still open on the session's side, each with its row: whether a second run can start beside a
living, slow one (FIX-273 - the scout and the advisor read the code differently, so it is staged
first); the kill test counts only one of the two kinds of second delivery (FIX-272).

## Default if the owner does not answer
As stated per item. Nothing in the code changes; FIX-270 writes the documentation as a draft.

## Owner's answer
2026-11-16, in the session, after the cause, the window and the cost of each option were explained
in plain words: "souhlas nespomalovat, tohle je výjimečný případ" - agreed, do not slow the
platform down; this is an exceptional case. Recorded as D-161.
- Item 1: answered for its second paragraph. The history and the removal are not written in a single
  transaction: the two writes stay apart, and the measured throughput is not traded for this case.
  The item's default stands as written - the check is built only as the last case of "a retry
  resumes at the failed step" (CONSIDER-13), never as a mechanism of its own. Until then such a
  death sends the message twice.
- Items 2 to 5: not answered in words; their defaults hold. (Item 6 was added after this answer;
  its default holds until it is answered.)
