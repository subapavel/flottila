# OPS-1 — coverage of `docs/OPERATIONS-BRIEF.md` in the rewritten documents, and the id-set check

Worktree: `/tmp/claude-0/-home-user-flottila/c273a2ff-2165-53cc-97ae-ec99f977618a/scratchpad/ops1` (detached at b702b49, the new `main` after BRAND-1 commit C; uncommitted). Redone in the fix round of 2026-11-15.
"§n" without a file is `docs/ARCHITECTURE.md`; a name after it is the bold sub-heading. "§14 inferred" is the list
"[inferred] or to verify — confirm with the owner before building", "§14 parked" the list "Parked by the owner", "§14 meets" the list
"Where the two briefs meet", "§14 open" the list "Open questions of the rewrite". Every §14 line is also a line of `issues/ISSUE-8.md`.
A brief item that holds a decided part and a marked part has one row for each.

## 1. Coverage table

| Brief item (section, first words) | Where it now stands | Row | Class |
| --- | --- | --- | --- |
| §1 "The message-log draft … is superseded" | §8 One log-record stream (To be, end); §7 Persistence tables (no `message_log`); §8 Write path and loss contract (no drop, no line bound); §8 Author voice and mapping trace (bullet 1: no logging type in `StepResources`) | OPS-2 (OPS-4 for the logging type) | decided |
| §1 "CORE-8, CORE-10 and CORE-13 are built to this brief" | §8 One log-record stream ("they carry log records, not only trail rows"); §8 Write path and loss contract ("wait and alert, never drop") | CORE-8, CORE-10, CORE-13 | decided |
| §1 "Vector … is rejected; so is any other new dependency" | §2 (rejected list); §8 Metrics and OpenTelemetry; `docs/CORE-REWORK.md` Q-3 (dated note) | CONSIDER-11 (rejected), OPS-8 | decided |
| §2.1 "One record stream" | §8 One log-record stream; definitions at the head of §8; §4 Message lifecycle, Body journal and Postgres pools by role (no bare "record": the ingress snapshot's row, journal record, deployment record) | OPS-2 | decided |
| §2.2 "Three logs by owner" | §8 One log-record stream | OPS-2, OPS-5 | decided |
| §2.2 "A successful call … is not repeated in the connection log" | §14 inferred | OPS-5 | inferred |
| §2.3 "Info level, successful message" | §8 Log records (bullet 1); §6 Recording; §8 Screens | OPS-5 (CORE-13 for the metadata) | decided |
| §2.4 "A running step is visible from its start" (start and end merged; records leave continuously) | §8 Log records (bullet 2) | OPS-2 | decided |
| §2.4 "A few seconds between a step's start and its display" | §14 inferred | OPS-2 | inferred |
| §2.4 "Every send attempt is its own record … ordered queue says which one" | §14 inferred | OPS-2 | inferred |
| §2.5 "Error record" | §8 Log records (bullet 3); its meeting with Q-9 `errors`: §14 meets | OPS-5, CORE-13 | decided |
| §2.6 "Connection failures are recorded in full the first time" | §8 Log records (bullet 4) | OPS-5 | decided |
| §2.7 "Every contact records all that is known about the client" | §8 Log records (bullet 5); §5 `headers` row (the marker); §3 node row (no load balancer required); §8 Screens (a refused input in the same search) | OPS-5 | decided |
| §2.7 "TLS should end in R8" | §14 open (Where TLS ends; ISSUE-8 C.7); §3 node row ("where TLS ends is not decided") | OPS-5, CONSIDER-21 | open — a "should" without a marker; parked with the operating modes by the CONSIDER-21 row |
| §2.8 "Levels" | §8 Levels and trace; §6 Recording | OPS-3 | decided |
| §2.9 "Trace is not a separate mechanism" | §8 Levels and trace; §6 Recording; what "maximum" covers: §14 meets | OPS-3 | decided |
| §2.10 "Author voice — no new R8 logging API" | §8 Author voice and mapping trace (bullet 1); §4 Threading (`MessageContext`); §10 (`Services.log()` removed) | OPS-4 | decided |
| §2.10 "the hook with isolated extension classloaders" | §14 inferred | OPS-4 | to verify |
| §2.10 "Mapping UDF" | §8 Author voice and mapping trace (bullet 2); the aside on flottila-mapping is not restated (`mapping/` is not in the tree at b702b49) | OPS-4 | decided |
| §2.10 "jq: `debug` and `stderr` both map to debug" | §8 Author voice and mapping trace (bullet 3) | OPS-4 | decided |
| §2.10 "R8 ships a small jq module with levelled functions" | §14 inferred | OPS-4 | to verify |
| §2.10 "XSLT: `xsl:message` is info" | §14 inferred | OPS-4 | inferred |
| §2.10 "The three engines' errors become the same error record" | §8 Author voice and mapping trace (bullet 4); §10 SAP compatibility runtime | OPS-4 | decided |
| §2.10 "Platform resources … may record the lookups themselves" | §14 inferred | OPS-4 | inferred |
| §2.11 "Mapping trace on demand" | §8 Author voice and mapping trace (bullet 5) | OPS-6 | decided |
| §2.12 "Search also runs across messages in the log" | §14 inferred | OPS-2 | inferred |
| §3 "Asynchronous, never on the message's path" | §8 Write path and loss contract (bullet 1); Amendments item 1; §4 Event bus; §14 item 1; the sync path: §14 meets | OPS-2, CORE-8, CORE-10 | decided |
| §3 "Nothing is dropped and nothing is truncated" | §8 Write path and loss contract (bullet 2); §4 Threading (admission), Body journal, Writer; CLAUDE.md limit rule (in the patch) | OPS-2, OPS-8 | decided |
| §3 "Accepted by the owner" (the loss contract) | §8 Write path and loss contract (bullet 3); §13 Phase 3 gate clause (`draft — review at hand-over`); `docs/CORE-REWORK.md` Q-6 (dated note) | OPS-2 | decided |
| §3 "Capacity is sizing plus reserve" | §8 Write path and loss contract (bullet 4, "(OPS-12, pending)") | OPS-12 | decided |
| §3 "Unusual inputs are the author's responsibility" | §8 Write path and loss contract (bullet 5) | none (a statement; nothing to build) | decided |
| §3 "Retention: the message record … outlives the payload" | §8 Write path and loss contract (bullet 6); §6 Recording; §8 Stores and query surface; against Q-9's periods: §14 meets | CORE-10, CORE-13 | decided |
| §3 "a default plus a per-interface override" | §14 inferred | CORE-13 | inferred |
| §3 "To measure" | §8 Levels and trace (last sentences) | OPS-7 | decided |
| §4 "R8 is self-sufficient" | §8 Metrics and OpenTelemetry; §8 Alerts (bullet 1); §3 node and ClickHouse rows; §13 Phase 3 | OPS-8 | decided |
| §4 "OpenTelemetry is an optional export" | §8 Metrics and OpenTelemetry; §4 Threading (the trace id); §10 Bad-extension policy; §13 Phase 5 gate clause ("shows on every step's log record, which is the span …", in place of "shows on every span") | OPS-8, OPS-2 | decided |
| §4 "A minimal liveness check from outside" | §8 Alerts (bullet 6); §9 Deployment mechanics (the probes paragraph) | OPS-8 | decided |
| §5 "One installation per customer … optional teams" | §9 Teams and permissions (bullet 1); §9 Flow configuration; §4 Threading and Body journal (the built `tenant`); a key per team: §14 open | OPS-9 (FIX-220, after it, for the schema description and the code comment that still state the tenant identity; named in F-38 "For the owner's review") | decided |
| §5 "Rights, roles, assignments" | §9 Teams and permissions (bullet 2); §9 Security (Authorization); §14 (`vertx-auth-abac` closed) | OPS-9 | decided |
| §5 "Rights (sketch, [inferred] as a list)" | §14 inferred; §9 Teams and permissions (bullet 6, a pointer) | OPS-9 | inferred |
| §5 "Payload has its own permission" | §9 Teams and permissions (bullet 3); §8 Log records (bullet 6) | OPS-9, OPS-2 | decided |
| §5 "Secrets belong to a team" | §9 Teams and permissions (bullet 4); §9 Security (Wallet) | OPS-9 | decided |
| §5 "Every payload view is recorded" | §9 Teams and permissions (bullet 5); §9 Security (Actor) | OPS-9 | decided |
| §5 "Switching it off needs a special permission" | §14 inferred | OPS-9 | inferred |
| §5 "Agents" (the key's rights; not masked; proposals) | §9 Agents; §9 Security (Actor); §10 AI, three audiences; §13 Phase 5; PR and the build right: §14 open | OPS-10 | decided |
| §5 "it can be narrowed, never widened, and follows that user's rights" | §14 inferred | OPS-10 | inferred |
| §5 "Callers" | §9 Callers and the developer portal (bullets 1, 2); §10 API management; §13 Phase 5; §14 open (PO sender agreements beside a caller's assignments, §9 Partner directory; ISSUE-8 C.6) | OPS-11 | decided |
| §5 "External people only through the developer portal" | §9 Callers and the developer portal (bullet 3); §3 control-plane row; §10 API management | OPS-11 | decided |
| §6 "An alert inbox per team" (and the channels) | §8 Alerts (bullet 1) | OPS-8 | decided |
| §6 "An alert may also travel through an ordinary R8 interface" | §14 inferred | OPS-8 | inferred |
| §6 "One cause is one alert with a count" | §8 Alerts (bullet 2) | OPS-8 (OPS-10 for the agent's part) | decided |
| §6 "An alert never closes by itself" | §8 Alerts (bullet 3) | OPS-8 | decided |
| §6 "R8 only notes on it that the cause has passed" | §14 inferred | OPS-8 | inferred |
| §6 "Notes on an alert stay with it; … escalated after a set time" | §14 inferred | OPS-8 | inferred |
| §6 "Groups" | §8 Alerts (bullet 4) | OPS-8 | decided |
| §6 "R8 also acts by itself, configurable" | §8 Alerts (bullet 5); §9 Security (Identity) | OPS-8 | decided |
| §7 "Custom code as a whole" | §14 parked; §10 Service extensions (v3.3 text kept, marked parked and not built) | CONSIDER-19 | parked |
| §7 "Masking of sensitive fields" | §14 parked; §9 Security (Authorization, TLS bullets); §10 AI, three audiences; §11 fixtures | CONSIDER-20 | parked |
| §7 "Operating modes of R8" | §14 parked and §14 inferred ("not thought through"); §3 (after the runtime pools) | CONSIDER-21 | parked |
| §7 "Dry replay of a whole message" | §14 parked; §6 Recording | CONSIDER-22 | parked |
| §7 "Counted summaries for repeated identical refusals" | §14 parked; §8 Write path and loss contract (bullet 2) | CONSIDER-23 | parked |
| Status note "The only hard constraint the owner set: no loss of performance" | §8, opening paragraph | — | decided |

Not restated anywhere, on purpose: the brief's comparisons with SAP products and its remarks ("Long retention is one of the things that
sets R8 apart"; "the lack of channels is the owner's main complaint"; the E.ON example). They decide nothing.

## 2. Pending markers and rows

Every "pending" marker the rewrite adds names a row of `agent/BACKLOG.md` at b702b49 — OPS-2 to OPS-11, CORE-8, CORE-10, CORE-13,
CONSIDER-19 to CONSIDER-23 (CONSIDER-11 is named as rejected) — but for one: "(OPS-12, pending)" at §3 "The platform measures the
numbers for it itself: lines, metadata bytes and compressed payload bytes per message of each interface" (§8 Write path and loss
contract, bullet 4; the first round's row placeholder stood there). OPS-12 and FIX-220 (the schema description in
`flow.schema.json` and the code comment in `MessageContext.java` that still state the tenant identity, after OPS-9; cited in F-38
"For the owner's review") are not rows of BACKLOG at b702b49: the architect adds both with the OPS-1 commit. The placeholder stood
only in ARCHITECTURE §8 and in this file; F-38 and ISSUE-8 never held it (searched across line breaks).

## 3. The id-set check

Every `D-<n>`, `F-<n>`, `FIX-<n>`, `CORE-<n>`, `CONSIDER-<n>`, `ISSUE-<n>` (letter suffixes included, e.g. `CORE-4c2`) cited in
`docs/ARCHITECTURE.md` below the Changelog table at HEAD (b702b49, `git show HEAD:docs/ARCHITECTURE.md`) against the worktree's version.

Script (`/tmp/claude-0/-home-user-flottila/c273a2ff-2165-53cc-97ae-ec99f977618a/scratchpad/ops1_idcheck.py`):

```python
import re, subprocess, sys
wt = sys.argv[1]
ID = re.compile(r'\b(?:D|F|FIX|CORE|CONSIDER|ISSUE)-\d+[0-9a-z]*\b')   # with letter suffixes: CORE-4c2, FIX-95a
def body(text):
    i = text.index('## Amendments (Part A, 2026-10-17)')   # the first line below the Changelog table
    return text[i:]
def ids(text):
    return set(ID.findall(body(text)))
before = ids(subprocess.run(['git', '-C', wt, 'show', 'HEAD:docs/ARCHITECTURE.md'], capture_output=True, text=True, check=True).stdout)
after = ids(open(wt + '/docs/ARCHITECTURE.md', encoding='utf-8').read())
key = lambda s: (s.split('-')[0], int(re.match(r'\d+', s.split('-')[1]).group()), s)
print('ids cited in the body before:', len(before), '· after:', len(after))
print('disappeared:', ', '.join(sorted(before - after, key=key)) or 'none')
print('new:', ', '.join(sorted(after - before, key=key)) or 'none')
```

Command:

```
python3 /tmp/claude-0/-home-user-flottila/c273a2ff-2165-53cc-97ae-ec99f977618a/scratchpad/ops1_idcheck.py /tmp/claude-0/-home-user-flottila/c273a2ff-2165-53cc-97ae-ec99f977618a/scratchpad/ops1
```

Output (observed 2026-11-15 on b702b49, after the last edit of the fix round; it replaces the run against c017e9c, whose three lines were the same):

```
ids cited in the body before: 137 · after: 146
disappeared: none
new: CONSIDER-11, CONSIDER-19, CONSIDER-20, CONSIDER-21, CONSIDER-22, CONSIDER-23, D-147, F-38, ISSUE-8
```
