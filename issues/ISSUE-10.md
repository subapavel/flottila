# ISSUE-10 — what "no replay" leaves to the owner: the gate's check 7, cancel, and five smaller words
Task: FIX-262
Date: 2026-11-15 (D-156, F-55)

## What is needed
The owner's word on each item below. D-156 took replay out of Rail8; F-55 wrote that into
`docs/ARCHITECTURE.md` (3.28) and rows FIX-263 to FIX-266 take it out of the code and the other
documents. Each item states the default that holds until the answer, so no work waits.

1. **The gate's check 7** (`docs/GATE.md`, "Replay and DLQ" — the gate agreed with the owner on
   2026-10-17). Its first sentence asks for `POST /admin/messages/{id}/replay?from=3`, an operation
   that no longer exists. Is this enough in its place: the partner is down, the message lands in
   the failed list after its retries, one manual retry delivers it under the same id once the
   partner is back, and the message's history shows every attempt?
   Default: FIX-266 strikes the replay sentence (forced by D-156) and keeps the rest of the check as
   it is, marked `draft — review at hand-over`; FIX-268 measures the stronger form above anyway and
   records it. `HANDOVER.md` recorded check 7 as met on evidence of the removed route; it will say
   "not measured since D-156" until FIX-268. Its first line read "GATE_MET. Checks 1–9 hold."; since
   FIX-266 it reads "GATE_MET on 2026-10-30 ... check 7 is not measured since 2026-11-15". Whether
   the verdict stands in between is yours to say. Default: it stands as the record of 2026-10-30,
   with that note.
   The core gate's condition 6 ("No Kafka in the core", `docs/GATE.md`) names replay in both of
   its lists - what must pass with the `kafka` profile off, and what must not depend on Kafka. A
   change of a target there is the owner's. Default: FIX-266 takes the word out of both lists
   (forced by D-156: there is nothing left to pass), marked `draft — review at hand-over`; FIX-268
   measures the condition again.
2. **What a cancelled message counts as.** When an operator cancels a failed message, does it count
   as a failure of its flow, or as a category of its own?
   Default: a failure of its flow (the architect's choice on the side branch, never answered).
3. **What cancel covers.** Is cancel only for messages in the failed list, or also for ones held or
   lost before they got there? Must the operator give a reason?
   Default: the failed list only, and a reason is required — relaxing either later is compatible,
   tightening is not. FIX-267 designs cancel on that default.
4. **The parked idea of recomputing payloads.** With replay gone, do you still want the idea of
   storing only the input and what cannot be repeated, and recomputing the other steps' payloads
   for display (CONSIDER-22)? It would never send anything again.
   Default: it stays parked; nothing is built.
5. **After a production fault.** P2-20 was a bulk replay ("5 000 completed messages of a faulty
   version replay through the fixed one"), asked for on 2026-10-29; it is withdrawn with D-156. Do
   you want a bulk retry or cancel of failed messages in its place — and is there still a need to
   send messages that finished, with a wrong result, again?
   Default: nothing bulk is built; the question is kept here.
6. **The root `CLAUDE.md`** still says "captured, searchable and replayable" in its first paragraph.
   `agent/patches/FIX-262-claude-replayable.patch` changes that one line.
   Default: unchanged until the owner applies the patch; no session edits the file.
7. **The two briefs** mention replay in three places (`docs/CORE-REWORK.md:34`, `:151`;
   `docs/OPERATIONS-BRIEF.md:181`). Leave them as written, or do you want a patch?
   Default: left as history; F-55 says D-156 overrides them on replay.

## Why the agent cannot decide it
Item 1 changes the gate, which is the owner's agreement and whose miss is an issue by the working
rules. Items 2 to 5 are product behaviour only the owner can state; item 2 was the architect's
guess and D-156 names it unanswered. Items 6 and 7 are the owner's own files.

## What was tried
The designer drafted the change and the architecture advisor opposed it
(`agent/work/FIX-262-designer.md`, `agent/work/FIX-262-advisor.md`). They name six of these as
the owner's and the advisor recommends the defaults above; item 5 and the core gate's condition 6
under item 1 are the architect's additions, the second after the review.

## Default if the owner does not answer
As stated per item. Nothing above blocks FIX-263 to FIX-268.

## Owner's answer
