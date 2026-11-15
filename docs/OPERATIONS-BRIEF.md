# Operations brief — log, monitoring, permissions, alerts (the owner's brief)

> **Status:** the owner's decisions from the brainstorm of 2026-11-14/15 (D-147), written by the
> architect from that session. Base: the core rework (`docs/CORE-REWORK.md`). Where this brief and
> `docs/ARCHITECTURE.md` differ, this brief is the owner's intent; ARCHITECTURE is rewritten to it
> in OPS-1, with a changelog entry per section. Items marked **[inferred]** were stated by the
> advisor and not contradicted by the owner: confirm each with the owner before building on it.
> The only hard constraint the owner set: no loss of performance.

## 1. What this replaces

- The message-log draft `agent/packets/CONSIDER-13-design-draft.md` (written against the old
  core, before the rework) is superseded: no Postgres `message_log` table, no drop on overload,
  no lines-per-attempt bound, no new public logging type in `StepResources`.
- CORE-8, CORE-10 and CORE-13 are built to this brief: they carry records (§2), not only trail
  rows, and "a bounded queue that fills is an alert" means wait and alert, never drop (§3).
- Vector as the collector of the platform's logs and metrics (CONSIDER-11) is rejected; so is any
  other new dependency for this (no Kafka, no Prometheus as a required part).

## 2. The log

1. **One record stream.** A step, an author line, an error, a send attempt, an operator or agent
   action and a channel event are records of one shape (time, owner, level, kind, text, fields).
   A message's log is its records in order. There is no separate "snapshots" and "log".
2. **Three logs by owner**: the message; the interface (deploys and setting changes with their
   actor, channel events, refused contacts); the connection (TLS failures, unknown paths,
   malformed requests — whatever cannot be attributed to an interface, found by time and the
   peer's address). Code that runs outside a message logs to the narrowest owner known at that
   moment: the message, else the interface, else the code's own log. A successful call has its
   message and is not repeated in the connection log **[inferred]**.
3. **Info level, successful message**: every step shows its start, its end and one fact — what
   it decided or whom it contacted (receivers chosen, mapping name and version, endpoint, attempt,
   status code). It is built from the step metadata that CORE-REWORK Q-9 stores always; the
   sentence is composed at display time.
4. **A running step is visible from its start.** One rule for every step: each reports start and
   end, and the writer merges the two when they meet in one write batch. Records leave the node
   continuously, not at run end. A few seconds between a step's start and its display is
   acceptable **[inferred]**. Every send attempt is its own record, and a message waiting behind
   another in an ordered queue says which one it waits for **[inferred]**.
5. **Error record**: a fixed core (step, attempt, time, the step's error text, the body that
   entered the failing step) plus optional fields only when the step knows them (code, place in
   the artefact, place in the data, the value, the contract). What a step does not know is absent;
   nothing is guessed.
6. **Connection failures are recorded in full the first time** (TLS, certificates, WS-Security),
   so no reproduction tool like SAP's XPI Inspector is needed: the handshake stage, which side
   refused what, the peer's chain and what our trust store lacks; when the peer refuses us, the
   certificate we presented (or that none matched its request), the authorities it asked for and
   the alert it returned. An outbound failure belongs to the message and step that made the call.
7. **Every contact records all that is known about the client**: address and port (directly, or
   through a trusted load balancer when one stands in front — R8 does not require one, and TLS
   should end in R8 so that certificates and handshake failures stay visible), the user name
   presented even when the login failed, the client certificate, TLS version, cipher and server
   name, method, path, headers, sizes, timings. Secrets in headers are replaced by a marker and
   never stored. A refused input (failed validation, failed login) is found by the same search as
   a message, with the reason.
8. **Levels**: error, warning, info, debug, trace. The author picks a line's level in code. The
   threshold is a per-interface setting, switched at run time without a deploy (as in SAP
   Integration Suite), and applies to every writer: platform, components, engines, author code,
   libraries. Payload recording stays its own setting (CORE-REWORK Q-9).
9. **Trace is not a separate mechanism**: a time-limited override that sets the log threshold and
   the body recording to their maximum, always for the whole interface, expiring by itself. Who
   switched it and when is recorded. Its main use is a message that passes but gives a wrong
   result; an error needs no trace, because the error record is complete the first time.
10. **Author voice — no new R8 logging API.** Custom code and its libraries use standard Java
    logging (`System.Logger`; SLF4J for libraries); the platform attributes each line to its
    message and step through the bound `MessageContext`. Lines from threads the code starts
    itself lose the link. *Direction, to verify at design: the hook with isolated extension
    classloaders.*
    - Mapping UDF: standard logging with the five levels; `run.log(text)` stays as shorthand for
      info (flottila-mapping already sends it to `System.Logger` outside a test run).
    - jq: `debug` and `stderr` both map to debug (`stderr` is a function an author calls, not an
      error); R8 ships a small jq module with levelled functions (`log_info`, `log_warning`, …)
      built on `debug`, so jqj needs no change (to verify).
    - XSLT: `xsl:message` is info, with `terminate="yes"` error **[inferred]**.
    - The three engines' errors become the same error record: code, place in the artefact, text.
    - Platform resources that custom code uses (database, cache) may record the lookups
      themselves: the query and row count at debug, the returned values at trace **[inferred]**.
11. **Mapping trace on demand**: not stored at run time; produced later by re-running the mapping
    traced over the stored input (the mapping engine gives identical output traced and untraced).
    Conditions: the step's input is stored, the version that ran is used, and the results of
    lookups into changing tables are recorded at run time.
12. **Search** also runs across messages in the log — every message with the same error, every
    message where author code wrote a given text **[inferred]**.

## 3. Write path and loss contract

- **Asynchronous, never on the message's path.** The message thread puts the record in memory and
  goes on; a writer takes batches to the node's journal; the mover carries them to ClickHouse —
  the path the rework builds for bodies.
- **Nothing is dropped and nothing is truncated.** No per-message line bound. One buffer for the
  whole platform; when it is full, intake stops for the whole platform ("ClickHouse is down — the
  system is not working — act at once") and the alert names the interface that writes most.
- **Accepted by the owner:** a node crash may lose the last moments of that node's detail
  records. The message and the proof of its receipt are not lost, and the takeover record says so.
- **Capacity is sizing plus reserve.** The platform measures the numbers for it itself: lines,
  metadata bytes and compressed payload bytes per message of each interface.
- **Unusual inputs are the author's responsibility**, tested with the mock tools and with the
  mapping tried on the expected payload. Not everything can be guarded and it will not be tried.
- **Retention**: the message record (steps, log, errors) outlives the payload; both periods are
  configurable, a default plus a per-interface override **[scheme inferred]**. Long retention is
  one of the things that sets R8 apart.
- **To measure**: info, debug and trace against the CORE-REWORK Q-1 targets.

## 4. Metrics and OpenTelemetry

- **R8 is self-sufficient**: metrics and alerts live inside the platform; metrics go to ClickHouse
  by the same path. Prometheus and Alertmanager are no longer required.
- **OpenTelemetry is an optional export**: a step record is a span, so nothing is measured twice;
  the trace id is taken from the sender, stored on the message, passed to the receiver and
  searchable.
- A minimal liveness check from outside is still needed: a platform that is down cannot alert.

## 5. Permissions

- **One installation per customer** (ARCHITECTURE's tenant level goes). Inside it, **optional
  teams with an enforced boundary**: a package belongs to a team; interfaces, messages, logs,
  payloads, deployments and secrets are separated by team; no name prefixes (the owner's example:
  E.ON's one tenant for four countries, `czint_` prefixes, everyone seeing everyone's monitoring).
  An installation without teams behaves as one team.
- **Rights, roles, assignments.** A role is a named bundle of rights; an assignment gives a
  person or a directory group a role in a scope — installation, team, package or interface —
  optionally for a limited time (e.g. payload in production for two hours, with a reason). No
  policy engine now, no third level above roles. Everything (UI, API, MCP) asks one decision
  point, so conditions on data can be added later; the owner knows no case that needs them.
- **Rights** (sketch, **[inferred]** as a list): see interfaces and messages; see payload and
  payload values in the log; act on messages (restart, cancel — who may act may approve an
  agent's proposal); change run-time settings (trace only with the payload right); build and
  deploy; manage the team's secrets; manage the team's members; create shared secrets; administer
  the installation; see across teams. Rights are assigned per environment.
- **Payload has its own permission**, as in SAP Integration Suite; payload values in log records
  are separate fields so that they can be hidden.
- **Secrets belong to a team**; shared ones are created only by a user with a special permission.
- **Every payload view is recorded** (who, when, which message; also a search over payloads, a
  mapping trace, a download), configurable. Switching it off needs a special permission and is
  itself recorded **[inferred]**.
- **Agents.** An agent's key has the rights of the user who created it (the default,
  configurable); it can be narrowed, never widened, and follows that user's rights
  **[inferred]**. It is not masked by default; "agent keys get masked payloads" is an option for
  customers that may not send sensitive data to a model provider. **Anything an agent changes is
  a proposal in R8 that a human approves in R8** (a message's state, closing an alert); proposal,
  approval and action are records in the message's log.
- **Callers** (external technical users — the systems and partners that call an interface): one
  shared object to which interfaces are assigned, managed by the interface's team. The same
  object is the API manager's application; the assignment is the subscription and may carry
  limits. A refused call is in the interface log with who tried. The check runs in memory on the
  hot path.
- **External people only through the developer portal**, a separate door: generate keys, try
  APIs through Swagger, perhaps aggregate statistics of their own calls. No monitoring, no
  details, no account in the platform.

## 6. Alerts

- **An alert inbox per team**, as in SAP Integration Suite: new, taken over (by whom), resolved.
  Channels only notify and link to the inbox: email, webhook, Slack, Teams, PagerDuty (the lack
  of channels is the owner's main complaint about SAP PO and Integration Suite). An alert may
  also travel through an ordinary R8 interface; alerts about the platform's own health keep a
  direct path **[inferred]**.
- **One cause is one alert with a count**, and the alert states the cause from the error record.
  An agent may attach an analysis and a proposal (configurable which alerts, what exactly).
- **An alert never closes by itself**: a human closes it, or an agent at a human's prompt. R8
  only notes on it that the cause has passed **[inferred]**. Notes on an alert stay with it;
  an alert nobody takes over is escalated after a set time **[inferred]**.
- **Groups**: messages (errors, silence, running or waiting too long, unusual volume); channels
  (cannot connect, refused inbound connections, certificate expiry including the peer's);
  platform (buffer filling, intake stopped, ClickHouse or the database down, node takeover, a
  proposal waiting too long); security (failed logins, signs of attack, unusual behaviour,
  sensitive changes).
- **R8 also acts by itself, configurable** — e.g. N failed logins, then a block — for technical
  users too: the owner counts the user name as sensitive, and a lockout is a signal that it leaked.

## 7. Parked by the owner (rows in `agent/BACKLOG.md`)

- Custom code as a whole (CONSIDER-19): Java code inside the platform with database, cache and
  third-party libraries, as an EJB on SAP NetWeaver Java; its work outside a message; the link
  from a message to the table load its values came from.
- Masking of sensitive fields (CONSIDER-20): per interface, per field (the owner's example: only
  an employee's birth number); to be brainstormed after this brief runs.
- Operating modes of R8 (CONSIDER-21): hybrid (a cloud part for external clients and cloud
  integrations plus an on-premise part for internal systems); cloud only with a cloud connector;
  on-premise only with a web dispatcher. Not thought through.
- Dry replay of a whole message (CONSIDER-22): store the input and what cannot be repeated,
  reproduce the rest on demand — "a good path if what really changes can always be told for
  certain" (owner). Two guards proposed: the step type decides, and an output fingerprint per step.
- Counted summaries for repeated identical refusals (CONSIDER-23).
