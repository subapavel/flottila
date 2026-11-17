# ISSUE-12 — cancelling a failed message: six things the design had to assume
Task: FIX-267
Date: 2026-11-16 (D-162) · answered and closed 2026-11-16 (D-163)

## What is needed
The owner's word on the six items below. Cancel is designed (D-162; `docs/ARCHITECTURE.md` §6, *The
operator's acts on a failed message*) and not built yet. Each item states the default the design
uses, so no work waits; an answer that differs changes the row named with the item.

**What cancel is, in plain words.** A message that failed for good waits in the failed list. An
operator can send it again (retry) or end it (cancel). A cancelled message leaves the failed list,
is never sent again, still counts as a failure of its interface, and the platform records who
cancelled it, when and why. The same record is kept for a retry from now on. Both show in the
message's history.

**A defect found on the way (F-57).** Putting a message into the failed list is two internal
writes. The gap between them is normally a moment; when the second write fails it lasts up to one
work lease, and when the node that held the message goes down inside it, until that node is back.
A retry issued in that gap can deliver the message twice, or deliver it and put it back into the
failed list. It is read in the code, not yet shown by a test. The design closes it: such a retry,
and such a cancel, is answered "not settled yet" and changes nothing; the operator repeats it
later. The fix is the next row (FIX-275) and lands before the gate's check 7 is measured again
(FIX-268).

1. **The sender sends a cancelled message again.** Where a sender's message carries a dedupe key
   (today only a file picked up over sftp has one), the platform remembers the key for as long as
   it keeps the message's history. A cancelled message keeps its key like any other. So a repeat
   under the same key, while the history is kept, is answered with the cancelled message's id and
   nothing runs - and nothing appears in the failed list either.
   Default: the key stays until the history is removed by age. The other way - the key is freed at
   the cancel - lets a sender's automatic repeat bring back, under a new id, a message an operator
   has just ended. (FIX-277.)
   Withdrawn 2026-11-16: the item mixed two unrelated things (F-58) - see the owner's answer.
2. **Must the operator say who they are?** Until logins exist, the caller of the admin API names
   itself in a request header and nothing checks the name.
   Default: a cancel without a name is refused; a retry without a name is accepted and recorded as
   "unknown". Requiring the name on retry later would break callers that work today; requiring it
   on cancel now breaks nobody, because cancel is new. (FIX-278.)
3. **May the permanent record copy the error text?** The record of a cancel is never deleted
   today; whether it ever is, and after how long, is open (CONSIDER-29).
   Default: it does not copy the failed step's error text. An error text can quote values from the
   payload; copied, they would outlive the payload's retention and be readable without the right
   to see payloads. The error stays in the message's history for as long as the history is kept.
   (FIX-277.)
4. **How long is a cancelled message's history kept?**
   Default: as any other message's, by its age. A message already older than the retention period
   when it is cancelled loses its history at the next clean-up; who cancelled it, when and why
   stays. The other way keeps the history for a full period counted from the cancel. (FIX-277.)
5. **A reason on a retry.**
   Default: optional. A cancel requires one (ISSUE-10 item 3's default). Requiring it on retry
   later would break callers. (FIX-278.)
6. **Undoing a cancel.**
   Default: never. A cancelled message runs again only when its sender sends it again - which is
   why item 1 matters.

## Why the agent cannot decide it
Items 1, 4 and 6 say what an operator's "cancel" promises to a sender and to an auditor; items 2, 3
and 5 fix what a public route requires and what a record that is never deleted contains. Each is
cheap now and expensive once someone relies on it, and none follows from D-156 or from the brief.

## What was tried
The designer drafted cancel and the record of a retry; the architecture advisor opposed the draft
twice; the architect decided (D-162). The four hand-backs are `agent/work/FIX-267-designer.md`,
`-designer-2.md`, `-advisor.md` and `-advisor-2.md`. Items 1 to 3 are the advisor's ("not put to the
owner and should be"); items 4 to 6 are the designer's.

## Default if the owner does not answer
The six defaults above. The design is marked `draft — review at hand-over` and its rows (FIX-275 to
FIX-278) are built on them.

## Owner's answer
2026-11-16, in the session, item by item (the owner's words in translation; recorded as D-163).

1. "A message picked up over SFTP has its own message id in Rail8, and keeps it when its
   processing fails on the way to the receiver. When the sender sends it again it is not the same
   message - it has another id. You are mixing two unrelated things."
   The item is withdrawn. Checked in the code: the SFTP dedupe key is the file's path, its time of
   change on the server and its content's hash, so it recognises the platform picking up one file
   twice, not a file its sender uploads again. A cancelled message's key stays with its history, as
   any message's, and nothing is decided by it. The documents that said otherwise are corrected
   (F-58).
2. "This is solved together with roles and authorization." Read as: no name is required on either
   act now; both record the name the caller gives, "unknown" when none; the real identity comes
   with roles and authorization (OPS-9).
3. "Of course - we need to trace it." The record of a cancel copies the failed step's error text,
   and the message's history shows it with the cancel. The copy is cut to its first 4 096
   characters and says so when it is cut: the text has no bound of its own and the record is never
   deleted. Where a flow records nothing, the cut copy is the only one. Stated consequence: the
   text can quote payload values and stays after the payload is removed; who may read the record
   is decided with the payload permission (OPS-9), and how long the record is kept is CONSIDER-29.
4. The default: a cancelled message's history is kept by its age, as any message's.
5. "No reason - permissions solve it, let us not complicate it." And, told the same day that this
   would be applied to a cancel as well: "a reason for a cancel may optionally be there, that is
   not a bad idea." So: a retry takes no reason; a cancel takes an optional one, never required.
6. No: a cancel is never undone.

Closed. D-163 amends the design (ARCHITECTURE 3.32); FIX-276, FIX-277 and FIX-278 are built on the
answers.
