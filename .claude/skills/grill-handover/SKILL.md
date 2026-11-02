---
name: grill-handover
description: Walk the owner through the decisions waiting for hand-over, in rounds with a recommended answer each.
disable-model-invocation: true
---

<!-- Built from mattpocock/skills `grilling` and `to-questionnaire` (MIT, see
     ../LICENSE-mattpocock-skills). Owner-attended only: never during autonomous execution. -->

The owner has opened a review of what Flottila left for them: drafts marked `draft — review at
hand-over`, findings, owner-only issues and `CONSIDER-*` rows. Reach a shared decision on each,
**relentlessly**, until nothing is silently assumed.

## Steps

1. **Collect.** Have the scout list, with one line each and a path: the `pending at hand-over` and
   `owner at hand-over` items in `agent/HANDOFF.md`; every `docs/DECISIONS.md` row still marked
   draft; open `docs/findings/F-*.md`; open `issues/ISSUE-*.md`; `CONSIDER-*` in
   `agent/BACKLOG.md`. If the owner named a subset (an id range, a module), take only that. Done
   when every item has an id, a one-line summary and what it blocks.

2. **Build the design tree.** A decision depends on another when its answer changes once the other
   is answered (e.g. a schema name waits on the decision that keeps the schema). The **frontier**
   is every item whose prerequisites are settled.

3. **Ask in rounds.** Ask the whole frontier at once, at most 7 questions per round, most
   expensive-to-reverse first (public types, Postgres schema, JSON Schemas, admin routes, compose
   topology — `docs/WORKFLOW.md` §6). Word each so "yes" accepts your recommendation:

   ```
   ❓ **Q1 — D-087 lock format**: <what was decided, what it costs to reverse, the alternatives>

   ➡️ <recommended answer, one line, and why>
   ```

   Facts are your job, never the owner's: a question that needs a fact from the code goes to the
   scout, and only the questions downstream of it wait. Decisions are the owner's: ask and wait.

4. **Record each answer** before the next round:
   - accepted draft → the DECISIONS row loses `draft — review at hand-over` and gains `reviewed
     <date>`; a changed one gets the change written into the row and a BACKLOG row for the work;
   - finding → status line in the F-file; issue → its answer appended to the ISSUE file;
   - `CONSIDER-*` → `ready` row with a class, or left with the owner's reason.
   Recompute the frontier and ask the next round.

5. **Done** when the frontier is empty and every collected item carries a recorded answer or an
   explicit "deferred — <reason>". Commit the ledger edits as one `docs:` commit; HANDOFF lists
   what moved.

## Async variant: a questionnaire

When the owner says the answers need someone else (a partner, an operator), write
`issues/QUESTIONNAIRE-<slug>.md` instead of asking: ask the owner only who it goes to and what
they need back, then write it with **Purpose**, **From / To / How answers are used**, one paragraph
of **Context**, **How to answer** (deadline, "I don't know" is useful), one `##` section per theme
with one-idea questions most-important-first, an empty `>` answer stub under each and a one-line
*why this matters* where a question could be misread, and **Anything else?** at the end.
