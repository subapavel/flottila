# OPS-1 — scout's map of what contradicts the operations brief (2026-11-15, read-only)

READ: OPERATIONS-BRIEF whole; ARCHITECTURE whole (568 lines); CORE-REWORK whole; README and root
HANDOVER.md whole; CLAUDE.md, AGENTS.md, GATE, QUICKSTART, PROTOTYPE, WORKFLOW, NOTES by topic grep;
BACKLOG by topic grep plus OPS/CORE/FIX/CONSIDER rows. Not read: most closed BACKLOG rows, packets,
findings, bench/RESULTS, DECISIONS beyond D-147. Line numbers are ARCHITECTURE's at 3e78044.

## 1. Snapshots / log / message_log [brief §2.1-2.2]
- ARCH §8 :346 "a snapshot after every stage"; :348 "step -> `Snapshot` (…)" and §6 :293 "Recording" are separate from any log. Contradicts §2.1 (one record stream).
- ARCH §8 :348 and :354 "Postgres last-N-hours copy" of snapshots; §7 :340 `messages(...)` holds step rows in Postgres. Brief §1 bans a Postgres `message_log`; brief §3's route is journal -> mover -> ClickHouse. Partial conflict.
- ARCH names no log table and no interface/connection/message log owners.
- Refused contacts and TLS failures today: ARCH silent. Only: 503 (§4 :193), "logged at WARNING" for trail rows (§8 :348), "logged and dropped" at-most-once (§4 :206, §6 :279); TLS only for Postgres start (§9 :390).
- Built on the old statement: OPS-2 supersedes packets/CONSIDER-13-design-draft.md (its `message_log` at :120). CORE-8 and CORE-10 carry D-147 amendments; CORE-13 has none. P2-S1 pending "snapshots to the Kafka monitoring topic".
- Other living docs: PROTOTYPE :58 "worker -> bounded batch queue -> Kafka -> ClickHouse; event bus drops silently" (v3.3 column); GATE :59 Kafka monitoring topic (stretch); NOTES :132 P2-S1/S2 pending; HANDOVER :93 "F-13 a closing trail drops whole timelines", :132 P2-S1; README :5 lists "Apache Kafka" in the stack. Pointer only: GATE :23-25 check 6; README :9; NOTES :229-231.

## 2. Step facts, error record, client facts, secrets [§2.3-2.7]
- ARCH §6 :293 "metadata of every step always (time, duration, status, headers, props, keys)"; no info-level "one fact" concept anywhere.
- Error record: ARCH §8 :348 lists only "error"; no fixed core or optional fields. §10 :420 "extension name and version on every span, snapshot and metric".
- Client facts (address, certificate, TLS version, user name): ARCH silent. Only §9 :391 "remote address" on the lost-work discard audit row (D-126).
- Secrets: ARCH §5 :242 `headers` persisted as jsonb, no marker rule. §9 :386 secret references only; "until FIX-92, text … may show it". FIX-92 pending.

## 3. Levels, threshold, trace [§2.8-2.9]
- ARCH has no log level. "level" means body recording: §6 :293 "today level all | ends | errors | off … replaced as SAP PO does it (CORE-REWORK O-13, Q-9)". Same word, different meaning.
- ARCH §6 :293 says "per flow"; the brief says "per interface". ARCH has no "interface" term.
- Run-time switch and trace override: ARCH silent. §8 :362 "trace id on the message record" only.
- Agrees: body levels none/inputs/changes plus errors/outputs, body recording separate from logging (ARCH §6 :293; CORE-REWORK §5 Q-9 :128-148).

## 4. Author voice, mapping trace [§2.10-2.11]
- CONTRADICTION: ARCH §10 :453 "Services … `Services.log()`" is a platform log API. Brief §2.10: no new R8 logging API.
- ARCH §10 :416 names the PO facade `MappingTrace`. No on-demand trace. No mention of `run.log`, jq `debug`/`stderr`, `xsl:message`.
- StepResources exists in api (ARCH changelog :18; NOTES :241). Brief: no new type there.

## 5. Write path, loss, retention [§3]
- CONTRADICTION: ARCH §8 :348 "a row still waiting after … maxWait (10 s) … is logged at WARNING … and counted in snapshotWriteFailures". A row not written is a drop; brief §3 "Nothing is dropped". Changelog :33 (3.4, D-071) records it.
- ARCH §4 :193 per-flow admission gives 503; brief §3: one platform-wide buffer, intake stops platform-wide, the alert names the top writer.
- ARCH §8 :346 "a node crash may lose the last few sync records" (sync only, as CORE-REWORK Q-6 :117-119). Brief §3 covers any detail record.
- Journal full: ARCH §4 :212 and CORE-10 "refuses new at-least-once messages with 503"; brief: intake stops platform-wide.
- Agrees: ARCH §8 :346 "monitoring is not best-effort"; :348 "nothing dropped silently".
- Retention: ARCH §6 :293 TTL by flow with sync/async/error periods, silent on "record outlives payload". CORE-10 amended with it; CORE-13 not.
- Scope to keep apart: at-most-once "logged and dropped" (ARCH §4 :206, §6 :279, §4 :194) is delivery, not records.
- Built on drop-or-count: FIX-49 "settle … as metadata-only with a WARNING"; FIX-51; FIX-80 "skip persist/record".

## 6. Metrics, OTel, trace id [§4]
- CONTRADICTION: ARCH §3 :175 node "Micrometer + OTel exporters", mandatory; §8 :362 "Micrometer -> Prometheus"; §8 :364 "platform (Prometheus/Alertmanager …)". §13 Phase 3 :537 "OTel spans, Micrometer". Brief §4: Prometheus not required, OTel optional.
- ARCH §4 :196 "trace id the message id until … OTel land" vs the brief "trace id taken from the sender, stored, searchable".
- Agrees: metrics to ClickHouse (ARCH §8 :355); /live and /ready (ARCH §9 :405).
- Vector: CORE-REWORK Q-3 :111-112 "Vector is a candidate … (CONSIDER-11)" vs brief §1 rejected.

## 7. Tenancy, permissions, agents, callers [§5]
- "tenant" in ARCH: :196, :212 (journal per-tenant key), :355 (CH key), :370 identity `tenant / package / kind:id`, :388 control-plane roles "scoped tenant -> package -> flow", :389 payload encryption per tenant, :426 AI provider per tenant. Brief: one installation, optional teams, no tenant level.
- ARCH :388 "three authorization planes"; §14 :555 open "vertx-auth-abac or own policy model". Brief: no policy engine, one decision point, scopes installation/team/package/interface.
- CONTRADICTION (agents): ARCH §10 :426 "agent role with scoped permissions … PII masking before any payload reaches a model"; :389 "per-flow PII masking … before any payload reaches an AI tool". Brief §5: agent key = its user's rights, not masked by default.
- Payload views: ARCH silent. Audit: :391 actor human/agent/model; wallet "audit log per read" (:385). Secrets' team ownership: ARCH silent.
- Callers: ARCH §10 :424 "API, product, application and subscription objects" vs the brief's one object. ARCH :372 consumer entitlements. Developer portal: ARCH :424, §3 :181 (no "no monitoring" rule).

## 8. Alerts [§6]
- ARCH §8 :364 two layers (Prometheus/Alertmanager; email, webhook, Slack, Teams, PagerDuty; silence detection). Agrees on channels and silence; no inbox, take-over, count, never-closes-itself or lockout anywhere.
- FIX-187 (pinned-segment alert) pending, agrees with the platform group.

## 9. Custom code, masking, deployment, dry replay [§7]
- ARCH §10 :411-453 holds a full custom-code design, not marked parked (brief §7 parks it, CONSIDER-19).
- Masking: ARCH :389 per-flow PII policy; :500 fixtures "masked by the flow's PII policy". Brief parks CONSIDER-20.
- Deployment modes: ARCH §3 :184 (systemd, Compose, K8s, cloud), silent on hybrid, cloud connector, web dispatcher. ARCH :291 "Dispatcher" is a flow term — a collision with CONSIDER-21's "web dispatcher".
- Dry replay: ARCH §6 :293 replay from N needs the output of N-1. CONSIDER-22 pending.

## (a) ARCHITECTURE sections
Status 1-9; Changelog 11-93 (rows 3.3-3.22); Amendments 94-131 (Part A overrides); title 132-136;
§1 Purpose 138-142; §2 Decisions final 144-163; §3 Topology 165-184; §4 Runtime core 186-224;
§5 Message model 226-262; §6 Pipeline 264-293 (recording at 293); §7 Components/queues/persistence
295-342; §8 Monitoring/alerting 344-364 (write path 348, stores 350-358, screens 360, tracing 362,
alerting 364); §9 Config/security/deploy 366-407 (flow config 370, security 383-391, deployment
393-405); §10 Extensions/API mgmt/AI 409-453; §11 Testing 455-500; §12 Repo layout 502-521;
§13 Plan 523-543; §14 Open decisions 545-568.
The header :6-9 says "the brief wins" but names only CORE-REWORK, not OPERATIONS-BRIEF.
The changelog's latest version is 3.22 (not 3.14).

## (b) CLAUDE.md
:51 "Every other limit is finite; never default one to unlimited" vs brief §3 "No per-message line
bound". A tension to resolve in words: the bound is the platform's one buffer, not a per-message
count. No other design-rule line changes.

## (c) Journals
docs/PROGRESS.md is newest-first, 2026-11-15 down to 2026-10-29 (P2-05). The old root journal, now
docs/archive/PROGRESS-until-2026-11-03.md, is oldest-first, 108 headings, 2026-10-17 BOOTSTRAP to
2026-11-03 P2-10b WIP. No entry is in both by id for the overlap window.
