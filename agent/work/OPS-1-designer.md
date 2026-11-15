# OPS-1 — the designer's draft of the rewrite's shape (2026-11-15, read-only; the architect decides)

Line numbers are ARCHITECTURE's at c017e9c. Unbuilt items are marked "(OPS-n, pending; OPS-BRIEF §x)": the brief has no O-/Q- ids.

## A. Disposition by section
- Header :1-9 — rewrite. "Under revision" names both briefs and their scope: CORE-REWORK covers the core (CORE-12, 3.14, F-34);
  OPERATIONS-BRIEF covers records, levels, metrics, alerts, permissions, agents and callers (D-147; OPS-1, 3.25, F-38). Where the two meet,
  the operations brief wins (it is built on the rework; its §1 overrides Q-3 by rejecting Vector). One rule for both: what is built is
  stated as built, what is not is marked, and [inferred] items are never stated as design but listed in §14 with their row. Fix :1-2
  "unchanged" (false since 3.4).
- Amendments item 1 :100-101 — "worker → bounded batch queue → Postgres" becomes "record in memory → writer → node journal → mover →
  ClickHouse (OPS-2; CORE-10, pending) [amended 3.25]". §14 :556 follows it. Items 2-8 stay.
- §2 :165 — keep; add "Vector; Prometheus/Alertmanager as a required part (D-147)" to the rejected list. §1 and §12 untouched.
- §3 — :177 "Micrometer + OTel exporters" → metrics by the record path, OTel an optional export (OPS-8); "LB in front" → load balancer
  optional, TLS ends in R8 (OPS-5). :178 Postgres "last-N-hours monitoring": keep as built; the short copy is decided by Q-3's
  measurement (OPS-2). :180 ClickHouse row: add records and metrics (OPS-2, OPS-8). :183 developer portal → a separate door with no
  monitoring (OPS-11). :186 keep, plus "operating modes: CONSIDER-21, parked".
- §4 — :195 per-flow admission: keep (built; CLAUDE.md:27); add one platform-wide record buffer: when full, intake stops platform-wide
  (OPS-2) and the alert names the top writer (OPS-8). :198 "tenant is default … until §9 tenants and OTel land" → the built `tenant`
  field holds `default` (OPS-9); the trace id comes from the sender (OPS-8); MessageContext is the author-log hook (OPS-4). :208
  at-most-once "dropped": keep (delivery, not records). :214 "per-tenant key" and "full journal … 503": keep as built, pointers to OPS-9
  and OPS-2. :224 Event bus and :226 Writer: keep as built, marked "replaced by the record path (OPS-2; CORE-8, CORE-10, pending)".
- §5 :244 headers row: add "secrets replaced by a marker, never stored (OPS-5)". §7 :342 `messages`: keep as built, plus "no Postgres
  message log (D-147)". §11 :502 fixtures masked by the PII policy → CONSIDER-20.
- §6 — :295 Recording keeps Q-9 (CORE-13), using the term "body level". Add: body recording is its own setting beside the log threshold,
  and the trace override raises both (OPS-3); the info fact is composed at display time from the step metadata that is always kept
  (OPS-5); the record outlives the payload (OPS-2 → CORE-13). Dry replay → CONSIDER-22. :293 Dispatcher: keep, worded "PO interface".
- §8 — rewrite whole (headings in B). Today's built path stays as one "Today" paragraph marked "goes with OPS-2 / CORE-8, pending": the
  trail, 256 rows, `maxWait` 10 s, WARNING plus `snapshotWriteFailures`, metadata-only rows (D-064, D-066, D-071).
- §9 — :372 identity → `package / kind:id @ version`, a package belongs to a team (OPS-9). :374 consumer entitlements → caller
  assignments (OPS-11). :387 wallet: keep, plus team and shared secrets (OPS-9). :390 the three planes and `vertx-auth-abac` → "Teams and
  permissions" (OPS-9); masking → CONSIDER-20. :391 "per tenant" → the installation key (built: `default`); "PII masking before an AI
  tool" → OPS-10's option. :393 audit: keep (D-126), plus payload views (OPS-9) and proposals (OPS-10). :407 probes: keep, plus the
  liveness check from outside (OPS-8).
- §10 — :418 `MappingTrace`: keep; the on-demand mapping trace goes to §8 (OPS-6). :422 "every span, snapshot and metric" → every record
  and metric, and a span when exported. :424 add the engines' errors as one error record (OPS-4). :426 "application and subscription" →
  caller and assignment with limits, the portal a separate door (OPS-11); the API and product objects stay. :428 provider "per tenant" →
  open; the guardrail → OPS-10. :430-455 Service extensions: keep the text, marked "parked by the owner (CONSIDER-19)"; delete
  `Services.log()`.
- §13 — :544 Phase 3 "OTel spans, Micrometer, both alert layers" → records (OPS-2..7), metrics and alerts inside the platform (OPS-8),
  OTel optional; its gate clause is Conflict 3. :548 Phase 5 → OPS-11, agent tooling → OPS-10.
- §14 — :562 abac ticked as closed (D-147). New bullets: "Parked by the owner: CONSIDER-19..23" and "[inferred], confirm before
  building" (Conflict 9).

## B. Structure
Renumbering is not affordable. §8 is cited about 20 times outside ARCHITECTURE (10 code comments in core.record and runtime.store,
GATE:59, CORE-REWORK:36, 6 in BACKLOG, and `deploy/postgres/V2__snapshots.sql:1`, an applied migration that stays byte-identical). §9
about 19 (Deployments, Secrets, MessageContext, cli, 5 tests, PROTOTYPE:62, 6 in BACKLOG). §10: none (the hits cite WORKFLOW §10).
§11-§14: about 60 in about 40 files (schemas, ArchUnit Rules, fixtures, bench), all of which an inserted section would shift.
So the numbers stay and the parts use the document's own bold sub-headings, cited by name (like "§4 Body journal"):
- §8 "Monitoring: records, metrics, alerts": One record stream (owners: message, interface, connection, code) · Records (shape, info
  fact, error record, connection failures, client facts) · Levels and trace · Author voice and mapping trace · Write path and loss
  contract (+ Today) · Stores and query surface · Three screens · Metrics and OpenTelemetry · Alerts.
- §9 becomes "…, security, permissions" and adds after Security: Teams and permissions · Agents · Callers and the developer portal.

## C. Terms
- flow / interface: keep "flow" (the built names use it: `kind: flow`, `/admin/flows`, `messages.flow`, flow.schema.json; "interface"
  already means a Java interface and a PO interface). Define both in §6's opening sentence ("an interface, the operator's word, is one
  deployed flow"). Write "PO interface" at :293, :374, :424, :546.
- tenant / team: replace. No tenant level. Define "installation" and "team" in §9 Teams and permissions. "tenant" survives only where it
  names a built field (`MessageContext.tenant`, `journal_keys.tenant`).
- level: never bare. "Log level"/"threshold" = the five levels (§8 Levels and trace). "Body level" = none / inputs / changes (§6).
  "Recording level" only for the built `all|ends|errors|off` until CORE-13.
- trace: "trace" alone is the timed override; "trace level" the fifth level; "trace id" and "span" are OpenTelemetry; "mapping trace" is
  OPS-6. "Record" names the new stream; "snapshot" only the built row type until OPS-2.

## E. Recording
One finding, F-38, severity major (WORKFLOW §7.2: documented mechanisms are replaced — Prometheus/Alertmanager, the tenant level, agent
masking, the write path that drops and counts). Version 3.25, one changelog row per changed section, each citing "F-38, D-147" (the brief
asks for "a changelog entry per section"). HANDOVER §5 lists F-38 (WORKFLOW §7.3). The CLAUDE.md wording gets a D-line.

## F. Other documents
- CLAUDE.md:51 — add "the record stream's one bound is the platform buffer; a message has no line count (D-147)" (Conflict 1).
- README.md:5 — "Apache Kafka" drops out of the stack.
- HANDOVER.md — :132 P2-S1/S2 → "superseded (D-134; ClickHouse fed by the mover with records, CORE-10, OPS-2)"; the §5 list at :88-98
  stops at F-21: add F-22..F-38.
- GATE.md — strike the stretch items at :59-60 as superseded (a stretch item never blocks). Check 6 at :23-26 stays.
- QUICKSTART.md — no change.
- PROTOTYPE-VS-ARCHITECTURE.md — :58 → "record → node journal → mover → ClickHouse (OPS-2, CORE-10)"; :59 "Kafka per flow" → add-on.
- CORE-REWORK.md — keep the owner's text; bracket notes: Q-3 :111-112 "[Vector rejected, D-147]", Q-6 :119 "[widened: any detail record,
  OPERATIONS-BRIEF §3]".
- NOTES.md — :227 "replaced by the record stream (OPS-2, CORE-8)"; :241 "no logging type in StepResources (D-147)".
- Journals — `docs/PROGRESS.md` stays the one journal, newest first. Below its oldest entry (2026-10-29) goes a seam line, then the
  archive's 108 entries in reverse order, each byte-identical and not interleaved; the seam states that 2026-10-29..11-03 holds entries
  from two branches. The archive file is removed in the same commit, with its blob sha in the commit body.

## Acceptance checks (grep-based)
`ops1_no_required_prometheus` (Prometheus, Alertmanager, Micrometer only in changelog rows or next to "optional");
`ops1_tenant_only_built_fields`; `ops1_no_services_log`; `ops1_every_pending_marker_names_a_backlog_row`;
`ops1_no_inferred_item_as_design` (each [inferred] sentence of the brief is matched only in §14);
`ops1_changelog_row_per_changed_section`; `ops1_no_bare_level`; `ops1_journal_entry_count` (after = before + 108);
`ops1_journal_entries_byte_identical`.

## Rejected
Splitting §8 or inserting sections (renumbering cost; V2:1 would point at the wrong section). A §15 after §14. Replacing "flow" with
"interface". One changelog row for everything. Interleaving the journals by date. Keeping two journal files with a pointer. Deleting §10
Service extensions (the brief parks custom code, it does not reject it).

## Expensive to reverse
"interface = one deployed flow" binds OPS-3's schema keys, OPS-9's scope and OPS-11's assignment target. The team as the scope above the
package binds OPS-9's later SQL. The §13 Phase 3 gate wording. The "Today" paragraph, which CORE-8 is measured against. OPS-1 itself
changes no API, wire format or SQL.

## Conflicts
1. CLAUDE.md:51 "never default one to unlimited" vs BRIEF:90 "No per-message line bound". The finite bound is the platform buffer
   (BRIEF:90-91); say so in words and record it. CLAUDE.md:27 (per-flow semaphore) vs the platform stop: two separate bounds, no conflict.
2. D-071 (ARCH:33, :350) and FIX-49/51/80 are built: Trail.java:31, :33, :164 (WARNING, metadata-only), AdminServer.java:228
   `snapshotWriteFailures`. BRIEF:90 "Nothing is dropped" contradicts them. State them as built and mark OPS-2 / CORE-8.
3. Q-6 (CORE-REWORK:119) and ARCH:348: a crash may lose "the last few sync records"; BRIEF:93-94 widens that to any detail record.
   ARCH:544's Phase 3 gate "a dropped node leaves no gap in the timeline" contradicts BRIEF:93-94: a gate condition changes on the
   owner's word; record it in F-38 and HANDOVER.
4. D-106's tenant is built: JournalKeys.java:24 `journal_keys(key_id, tenant, …)`, RecordFormat.java:12, MessageContext.java:28-31,
   flow.schema.json:13. BRIEF:115 "tenant level goes". SQL schema and journal format: state as built with one value. Code comments
   (MessageContext.java:30 "§9") go stale — the implementer's to fix.
5. ARCH:214 and Q-8 (CORE-REWORK:126, built in CORE-4d): 503 for at-least-once when the journal is full; BRIEF:90-92 stops the whole
   platform. Compatible, the built one narrower. The brief does not say what sync does (Open b).
6. `Services.log()` (ARCH:455) is not built — no `Services` anywhere in platform/: a plain deletion. Micrometer, OTel and Prometheus
   are absent from every pom and source file.
7. Unbuilt design to rewrite: ARCH:391 and :428 (masking before a model) vs BRIEF:138-139; ARCH:428 "PR + CI only" vs BRIEF:127-128
   and :139-140 (Open d); ARCH:177 "LB in front" vs BRIEF:50-51; ARCH:390 and :562 (abac) vs BRIEF:122-123; GATE.md:59-60 vs D-134 and
   BRIEF:17-18.
8. BRIEF:12 and D-147 name `CONSIDER-13-design-draft.md` (the message log), but BACKLOG's CONSIDER-13 row is "resume a retry", which
   ARCH:210 cites. ARCHITECTURE cites the draft by path only.
9. [inferred] items are not design; each points to its row: BRIEF:30 → OPS-5; :38-39 and :82-83 → OPS-2; :74 and :76-77 → OPS-4; :100
   (the scheme) → CORE-13 (Q-9 covers it); :125 and :134-135 → OPS-9; :138 → OPS-10; :156-157 and :160-162 → OPS-8.

## Open (the architect's decisions)
a. Is an interface one deployed flow, or a PO interface behind one dispatcher flow (ARCH:293)? Decides where the threshold, the team and
   the caller attach.
b. Does the platform-wide stop refuse sync calls with 503 and pause pollers? BRIEF:91 says only "intake stops".
c. Does "separated by team" (BRIEF:116-118) mean a journal key per team, or the permission check only?
d. Agents and deploy: PR plus CI, or a proposal approved in R8?
e. Is CLAUDE.md:51 the architect's to edit, or the owner's?

## The draft's three weakest points (the designer's own)
1. "interface = flow" rests on a BACKLOG row, not on the brief.
2. Reversing 108 journal entries is a reorder that a strict reader may call rewriting history; the alternative (a pointer) fails "one
   journal".
3. The citation counts come from a `§ ?n` grep, so some hits may cite WORKFLOW or brief sections; the §7, §11 and §12 dispositions come
   from the scout's map, not the designer's own reading.
