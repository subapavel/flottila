# OPS-1 — the opponent's hand-back on the designer's draft (2026-11-15, read-only; the architect decides)

Verdict: SOUND WITH CHANGES. The structure holds (numbers kept, bold sub-headings, built stated as built, one finding); four items bind
or misstate more than the brief does, and the checks protect neither the built facts nor brief coverage.
ARCH = docs/ARCHITECTURE.md, BRIEF = docs/OPERATIONS-BRIEF.md, CR = docs/CORE-REWORK.md. Lines at c017e9c.

## Changes, by weight
1. Amendments item 1 and §14 :556 — the draft replaces the built path "worker → bounded batch queue → Postgres" by an undesigned one
   inside a paragraph that says "are final" (ARCH:98-103) and overrides the body. Keep the built path and append "to be replaced by
   memory → writer → the node's journal → mover → ClickHouse (BRIEF §3; OPS-2, CORE-8, CORE-10, pending) [amended 3.25]".
2. Header rule "where the two meet, the operations brief wins" — BRIEF:4 says only "Base: the core rework"; BRIEF:15-17 amends
   CORE-8/10/13 and Vector, nothing more. The general rule silently settles four meets; put each in §14 as open with its row:
   - Q-6 (CR:117-119): sync records go "through the mover's batches, not the durable journal path"; BRIEF:88 sends every record to the
     node's journal (OPS-2).
   - Q-9 (CR:131-132): `errors` is a switch; BRIEF:40-41 puts that body in the error record's fixed core (OPS-5, CORE-13).
   - Q-9 step `skip` (CR:145-148) and D-110 "carried, not recorded" (CLAUDE.md:51) against "maximum" (BRIEF:60-61) and "nothing is
     truncated" (BRIEF:90). D-110 is not in BRIEF §1's list, so it stands — say so (OPS-3).
   - Q-9 sync/async/error periods (CR:139-141) against record/payload periods (BRIEF:99-100) (CORE-13).
3. "Interface = one deployed flow" — right without a dispatcher, wrong to bind behind one (see a).
4. The term "record" already names what the stream is stored in: `JournalRecord`, `RecordFormat`, `LargeRecord`, "segments of encrypted
   records" (ARCH:214), the deployment "record" (ARCH:401). Define "log record", "journal record", "deployment record" once at the head of
   §8; never bare in §4 or §8.
5. Keep-as-built is applied unevenly: :214 is kept but :372 drops `tenant` from the identity and :391 becomes "the installation key",
   while `flow.schema.json:13` still says "tenant / package / flow:id @ version" and `MessageContext.java:30` cites §9. State :372 and
   :391 as built ("the tenant position holds `default`; goes with OPS-9") and add one FIX row for the schema description and the comment.
6. "Today" placement: superseded built text also sits in the §8 stores table (ARCH:356), the screens (:362), §3 :178, §4 :195/:214/
   :224-226 and §6 :295. One rule in the header: each sub-heading opens with "Today (built)", then "To be (OPS-n, pending)". Keep the
   words "Write path" first in that heading: `Trail.java:21`, `Recorder.java:18`, `Snapshot.java:16` and
   `deploy/postgres/V2__snapshots.sql:1` cite "§8 write path".
7. Flat statements of non-decisions: §3 "TLS ends in R8" (BRIEF:51 says "should"; CONSIDER-21 parks where TLS ends); §4 "MessageContext is
   the author-log hook" (BRIEF:67-68 "Direction, to verify at design"). Add a third class beside [inferred]: "to verify / not thought
   through" (BRIEF:67-68, :73, :180).
8. Journal merge: it undoes D-149 on the day it was made (the root journal moved to docs/archive/ unedited), is not appending
   (WORKFLOW:49), makes the archive's own header false, cuts blame and adds about 1,600 lines to a file read at every task. Cheaper: one
   journal means one file written to — leave the archive byte-identical and add a closing pointer at the foot of `docs/PROGRESS.md` naming
   the archive, its order and the two-branch window (25 archive entries are dated 10-29..11-03).
9. Living documents the draft misses, all on the journal path: WORKFLOW:49, :93, :169; CLAUDE.md:83; AGENTS.md:97/106/117;
   `.claude/agents/implementer.md:41`, `test-author.md:32` (the implementer's ban names root `PROGRESS.md`, which no longer exists).
   AGENTS.md, WORKFLOW.md and `.claude/agents` have no tenant, Prometheus or masking text.
10. The grep checks: `no_required_prometheus` fails the right text and passes ARCH:364 left untouched; `tenant_only_built_fields` passes a
    mechanical "per-team key"; `no_bare_level` fails on "Caching, two levels", "row-level", "ZSTD level"; `no_inferred_item_as_design`
    never fires; nothing protects built facts. Add an id-set no-decrease check (every D-/F-/FIX-/CORE- id cited in the body before is
    cited after, or listed in the finding) and a coverage table — each numbered item and bullet of the brief → sub-heading or §14 — as the
    reviewer's artefact.
11. Brief passages with no home or row: BRIEF:95-96 (the platform measures lines and bytes per interface) — no OPS row; BRIEF:97-98
    (unusual inputs are the author's) — no home; BRIEF:130-131 (payload values as separate fields) binds OPS-2's record shape, belongs in
    §8 Records; BRIEF:168-169 (lockout, technical users too) also touches §9 Identity.
12. Conflict 3 (the Phase 3 gate clause): a finding is enough, no issue (WORKFLOW:138 reserves the issue for a gate missed after two
    honest attempts). Use GATE.md:73-75's form: date, the owner's sentence, no words beyond BRIEF:93-94, `draft — review at hand-over`.
13. One finding with numbered points (precedent F-34), the removed guardrails (masking before a model) as their own points. Do not fix the
    finding's number in advance; cite passages by sub-heading and phrase, not by line; re-run the scout over `mapping/` after BRAND-3.

## Missed alternatives
- Thin rewrite: ARCHITECTURE states what is built, removes the contradictions and points to BRIEF §x for each pending area; the detail
  enters when each OPS-n is designed and opposed. No paraphrase drift, nothing bound before a class-C design exists.
- One ISSUE as the carrier to the owner (with defaults): the [inferred] items, (a) behind a dispatcher, (c), (d), the gate wording, the
  CLAUDE.md sentence. §14 alone does not reach the owner, who reads HANDOFF and PROGRESS.

## The open questions
a. Flow where no dispatcher is involved; the dispatcher case is the owner's. Non-binding wording: "interface is the operator's word for
   a deployed flow; which unit it names behind a dispatcher flow is open (§14; OPS-3, OPS-9, OPS-11)".
b. Yes — it follows (BRIEF:90, :91, :16; sync always writes step metadata, CR:136; Q-8 allows 503). Use the built door behaviours (503 +
   Retry-After, pollers pause). Open for OPS-2: one node's journal against "one buffer for the whole platform", in-flight work, replay.
c. The owner's, through OPS-9. Write "enforced at the one decision point; a key per team is open (OPS-9)".
d. Not a choice: the brief's examples are run-time state; git-first deploy is untouched. Keep "no direct production deploy (PR + CI
   only)" and add the proposal rule for run-time state and settings. Open with OPS-10: whether a merged PR counts as approval "in R8",
   and whether an agent key inherits its user's "build and deploy" right.
e. The owner has spoken (BRIEF:90). Record it with a D-line; the true wording is "A message's log has no per-message cap (D-147); its
   memory is bounded by the platform's record buffer, which stops intake when full (OPS-2, pending)" — "the one bound is the platform
   buffer" is false today (256 rows, `maxWait` 10 s).

## Risks whatever is chosen
§8 and §9 become a second copy of the brief in other words, read as binding by the OPS-2..11 designers. BRIEF:91 with :145: one caller
over its limit writes refusals until the platform buffer stops everyone (CONSIDER-23) — name that row in the loss contract. The greps
cannot catch a wrong meaning. HANDOVER is "written once, at GATE_MET" (WORKFLOW:54). CONSIDER-13 names two things.
