# OPS-1 — what the architect decided (2026-11-15), after the designer (OPS-1-designer.md) and the opponent (OPS-1-advisor.md)

This file is the packet for the rewrite; the architect writes the text. It becomes the D-line of the OPS-1 commit.

1. **Thin rewrite.** ARCHITECTURE states what is built and removes every contradiction with `docs/OPERATIONS-BRIEF.md`. For each area the
   brief decides, the section gets a short "To be (OPS-n, pending; OPERATIONS-BRIEF §x)" paragraph that states only what the brief states
   as decided, in the brief's own terms and no mechanism beyond it. The detail enters when each OPS-n is designed and opposed. Items the
   brief marks [inferred], "to verify" or "not thought through" are never stated as design: they are listed in §14 with their row and
   carried to the owner in one issue with defaults.
2. **Numbers stay.** No section is inserted or renumbered (code comments, schemas and an applied migration cite §8, §9, §11-§14 by
   number). §8 and §9 use the document's bold sub-headings. Each rewritten sub-heading opens with "Today (built)" and then "To be". The
   heading that holds the write path keeps the words "Write path" first.
   §8 sub-headings: One log-record stream · Log records · Levels and trace · Author voice and mapping trace · Write path and loss
   contract · Stores and query surface · Screens · Metrics and OpenTelemetry · Alerts.
   §9 gains after Security: Teams and permissions · Agents · Callers and the developer portal.
3. **Amendments item 1:** the built path stays; "to be replaced by memory → writer → the node's journal → mover → ClickHouse (BRIEF §3;
   OPS-2, CORE-8, CORE-10, pending)" is appended. §14 follows it.
4. **Header:** names both briefs and the scope of each. No general precedence rule: the operations brief amends CORE-8, CORE-10, CORE-13
   and rejects Vector (its §1), nothing more. The four places where the two briefs meet (Q-6 sync records' path; Q-9 `errors` against the
   error record's fixed core; Q-9 `skip` and D-110 against "nothing is truncated" — D-110 stands; Q-9 retention periods against
   record/payload periods) are listed in §14 as open, each with its row.
5. **Terms.** "log record" (the new stream), "journal record", "deployment record" — never a bare "record" in §4 or §8; "snapshot" only
   for the built row type. "flow" stays the document's word; "interface is the operator's word for a deployed flow; which unit it names
   behind a dispatcher flow is open". "tenant" only where it names a built field, stated as built ("the tenant position holds
   `default`; goes with OPS-9"); "installation" and "team" are defined in §9. "level" is always qualified: log level / threshold, body
   level, recording level (the built `all|ends|errors|off`). "trace" alone is the timed override; "trace level", "trace id", "mapping
   trace" are said in full.
6. **The opponent's answers to a-e are taken:** (a) as in 5; (b) the platform-wide stop uses the built door behaviours (503 +
   Retry-After, pollers pause), the rest is OPS-2's; (c) "enforced at the one decision point; a key per team is open (OPS-9)"; (d) "no
   direct production deploy (PR + CI only)" stays, the proposal rule is added for run-time state and settings, two points open with
   OPS-10; (e) CLAUDE.md's limit rule gains "A message's log has no per-message cap (D-147); its memory is bounded by the platform's
   record buffer, which stops intake when full (OPS-2, pending)".
7. **The Phase 3 gate clause** changes on the owner's word (BRIEF §3, the loss contract): a point of the finding, worded with no words
   beyond the brief's, marked `draft — review at hand-over`. No issue for it (no gate was missed).
8. **Journals:** the archive stays byte-identical where D-149 put it. `docs/PROGRESS.md` is the one journal written to; a closing pointer
   at its foot names the archive, its order and the window in which both hold entries. Every living document that names the journal's
   path is corrected (WORKFLOW, CLAUDE.md, AGENTS.md, `.claude/agents/implementer.md`, `test-author.md`).
9. **Recording:** one finding, severity major, numbered points (the removed guardrails — masking before a model — as their own points);
   one changelog row per changed section, each citing the finding's point; version 3.25. One issue carries the open questions and the
   [inferred] items to the owner, each with the default the documents now state.
10. **Rows:** a row for what the brief asks and no OPS row covers (the platform measures log lines and bytes per interface); a FIX row for
    the schema description and the code comments that still state the tenant identity (after OPS-9); payload values as separate fields go
    to §8 Log records and OPS-2; lockout for technical users goes to §9 Identity.
11. **Other living documents:** README (Kafka leaves the stack list), HANDOVER (only the lines the brief contradicts; its findings list is
    not OPS-1's), GATE (the two stretch items marked superseded), PROTOTYPE-VS-ARCHITECTURE (the monitoring path and Kafka rows),
    CORE-REWORK (the owner's text stays; two dated bracket notes at Q-3 and Q-6), NOTES (two lines). After BRAND-3 the scout reads
    `mapping/`'s living documents against the brief.
12. **The reviewer's artefacts:** a coverage table — every numbered item and bullet of the brief → the sub-heading that holds it, or §14
    with its row — in `agent/work/OPS-1-coverage.md`; and an id-set check: every D-/F-/FIX-/CORE- id cited in ARCHITECTURE's body before
    the rewrite is cited after it, or listed in the finding as removed with its reason.

Rejected: the full rewrite that restates the brief's design in ARCHITECTURE (paraphrase drift; it would bind before any class-C design
exists); merging the two journal files; a general "the operations brief wins" rule; "interface = flow" as a definition.
