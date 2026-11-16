# ISSUE-11 — a message sent twice when a node dies: what the platform should do about it
Task: CONSIDER-24
Date: 2026-11-16 (D-160)

## What is needed
The owner's word on the five items below. On 2026-11-15 the owner asked for the cause of the second
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
   recognise, which the sender prevents with a dedupe key (read in the code, not shown by a test).
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
5. **A delivered message shown as failed.** Read in the code, not yet shown by a test (FIX-271
   stages it): when the node dies in that window on the message's last allowed attempt - with no
   retries configured that is the first - the message ends in the failed list although the partner
   has it, and an operator's retry sends it a third time. Should that be closed sooner than item 1's
   default?
   Default: FIX-271 shows first whether it happens. If it does, it is closed together with item 1;
   until then the message's history shows the operator the earlier, finished run.

## Why the agent cannot decide it
Items 1 and 5 decide whether the platform may ever treat a message as done without running it - a
change of what "at least once" means, expensive to reverse once operators rely on it. Items 2 to 4
are a public promise to partners. All five are product behaviour.

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
- Items 2 to 5: not answered in words; their defaults hold.
