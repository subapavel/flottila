# Workflow — autonomous execution protocol

Agreed in Part A on 2026-10-17. Adapted from the xml-engine-rs working method (bounded tasks,
evidence states, handoff, roles), trimmed to what one public repo with one owner needs. This file
is binding for every session working on Flottila; `CLAUDE.md` summarises it.

## 1. The loop

1. Read `agent/HANDOFF.md`. Its `state` line decides whether work may start (`RUNNING` or
   `NOT_STARTED`; anything else needs the owner's explicit RESUME). Reconcile an interrupted task
   first: a `in-progress` row in BACKLOG whose commit is not on `main` is redone from its last
   pushed state, never assumed done.
2. Pick the first `ready` row in `agent/BACKLOG.md`. A row is `ready` when every id in its
   depends-on column is `done`. Phase gates are rows too; nothing in the next phase becomes
   `ready` until the gate row is `done`.
3. Write the task packet (`AGENTS.md`). Red first: the failing test exists before the code.
4. Implement — yourself or through the implementer. One writer at a time.
5. Verify: `mvn -B -ntp verify` green, ArchUnit green, coverage not decreased, scoped PIT on the
   touched classes. Record each check with its evidence state.
6. Review by the reviewer agent. `FAIL` reopens the task at step 4.
7. Commit and push to `main`. Append the PROGRESS entry, rewrite HANDOFF, set the row to `done`.
   These three edits go in the same commit as the code.
8. Continue with the next `ready` row without asking. Stop only on `GATE_MET` (docs/GATE.md
   satisfied → write `HANDOVER.md`) or `IDLE_NO_READY_TASK`.

The VM can be paused or reclaimed at any time. Git and `agent/HANDOFF.md` are the recovery point;
nothing that matters may exist only in the conversation or in an unpushed working tree.

## 2. Files

| File | Purpose | Written by |
| --- | --- | --- |
| `agent/BACKLOG.md` | one row per task: id, phase, title, depends-on, status, evidence | coordinator |
| `agent/HANDOFF.md` | ≤ 20 lines: state, last commit, next exact action, open issues; rewritten after every task | coordinator |
| `PROGRESS.md` | append-only journal, one entry per task (format below) | coordinator |
| `docs/DECISIONS.md` | reversible decisions, one line each | coordinator |
| `docs/findings/F-<n>.md` | contradictions between practice and ARCHITECTURE.md | coordinator |
| `issues/ISSUE-<n>.md` | things only the owner can resolve | coordinator |
| `bench/RESULTS.md` | every measured number, with machine, date, configuration, commit | coordinator |
| `HANDOVER.md` | written once, at `GATE_MET` | coordinator |

Status values in BACKLOG: `pending | ready | in-progress | done | blocked`.

Campaign states in HANDOFF: `NOT_STARTED | RUNNING | BLOCKED | PAUSED_QUOTA | STOPPED_USER |
IDLE_NO_READY_TASK | GATE_MET`. A reopened session does not clear `STOPPED_USER` or
`PAUSED_QUOTA`; only the owner does, by saying so.

### PROGRESS entry format

```
## P1-04 · 2026-10-21 · <commit hash>
Test first: MessageStoreTest.snapshotPerStepIsQueryableByKey
Changed: flottila-runtime (PgMessageStore, schema V3), flottila-core (RecordingLevel)
Checks: mvn verify VERIFIED_PASS · ArchUnit VERIFIED_PASS · coverage core 93/91 runtime 78/76 VERIFIED_PASS · PIT touched classes 84 % VERIFIED_PASS · integration (Testcontainers) VERIFIED_PASS
Measured: snapshot write p99 0.9 ms at 5k msg/s (cloud VM, 4 vCPU) — bench/RESULTS.md §P1-04
Review: PASS
Decisions: D-014
Deviation: none
Next: P1-05
```

## 3. Commits

- One task, one commit, direct to `main`. Never push red; a red `main` makes "fix main" the only
  ready task.
- Conventional Commits: `feat(core): …`, `fix(runtime): …`, `test(api): …`, `chore(ci): …`,
  `bench: …`, `docs: …`, `refactor(…)`. Scope = module without the `flottila-` prefix.
- Body: what and why, then the evidence line (the `Checks:` line of the PROGRESS entry).
- Trailers, in this order: `Task: P1-04`, `Co-authored-by: Claude <noreply@anthropic.com>`,
  `Signed-off-by: <owner name> <owner email>` (DCO; the author identity is the owner's, set through
  the environment's git variables — see `deploy/claude-cloud/README.md`).
- Tags cannot be pushed from the cloud session; gates are recorded as commit hashes in
  `bench/RESULTS.md` and `PROGRESS.md`.

## 4. CI gates (GitHub Actions, every push to `main`)

| Job | What | Blocking |
| --- | --- | --- |
| `build` | compile, unit tests, ArchUnit, JaCoCo floors and no-decrease check (±2 pp noise band, D-029), DCO check | yes |
| `integration` | Testcontainers tests (`-Pit`): Postgres, Kafka, ClickHouse, Valkey, S3 — run inside the `build` job since D-030 so one JaCoCo measurement covers unit and integration tests | yes |
| `bench` | harness against native Postgres on the runner; artifact uploaded; fails only on a regression > 20 % in throughput against the last baseline **for the same machine label** in `bench/RESULTS.md` (p99, 50 % band, is gated only where the marker carries it: the VM, not the shared runner — D-037, D-039) | yes, since B-1 |
| `mutation` | PIT on `api` and `core`, nightly | reported; ≥ 80 % is a phase-gate condition |

Gate numbers themselves (latency, throughput, ledger) are measured by the coordinator on the cloud
VM, labelled "cloud VM, 4 vCPU", and written to `bench/RESULTS.md`.

## 5. TDD and the review checklist

Red first, always. The reviewer receives the diff, the test output and a scoped PIT run on the
touched classes, and answers per test: *would this test fail if the code were wrong?*

- asserts observable behaviour, not mock internals or "no exception thrown";
- kills mutants in the touched classes — a test that kills none is fake by definition;
- no `@Disabled`, no skipped cases, no swallowed exceptions, no `assertTrue(true)`, no sleeps;
- mocks only at the SPI / store boundary, never of the class under test;
- the test name states the behaviour it protects;
- the test was written or approved before the code (the PROGRESS `Test first:` line names it).

Verdict `PASS` or `FAIL — <reason>` in the PROGRESS entry. `done` requires `PASS`. The reviewer
is never the agent that wrote the test.

Phase gate additionally requires: coverage floors met (api/core 90 % line and branch,
runtime/components 75 %), PIT ≥ 80 % on api/core, measured on the gate commit by CI, numbers in
`bench/RESULTS.md`. Below the floor, "raise coverage of module X" is the only ready task.

## 6. Decisions

Before the hand-over gate nothing is irreversible in the strict sense; "irreversible" means
*expensive to reverse once the owner starts writing flows*.

| Class | Examples | Action |
| --- | --- | --- |
| reversible | library versions, internal shapes, test layout, step order inside a phase, thresholds inside the ranges ARCHITECTURE §14 proposes | decide, one line in `docs/DECISIONS.md`, continue |
| expensive to reverse | public types in `flottila-api`, Postgres schema, the JSON Schemas, admin API routes, Compose topology | design it, stamp `schemaVersion` / `@since`, mark `draft — review at hand-over`, list it in HANDOVER.md, continue |
| owner-only | money, credentials, external accounts; a dependency under AGPL/GPL/BSL/SSPL; a §13 gate missed after two honest attempts; rewriting or deleting git history | `issues/ISSUE-<n>.md`, row `blocked`, next ready task |

## 7. Findings — when practice contradicts ARCHITECTURE.md

During autonomous execution the coordinator is the architect of record. A contradiction is never
worked around silently:

1. `docs/findings/F-<n>.md` with assumption / how it fails / severity (`blocking | major | minor`)
   / evidence / change made — template in `docs/findings/TEMPLATE.md`.
2. `blocking` or `major`: implement the smallest change that keeps the documented intent, edit
   `docs/ARCHITECTURE.md` in the same commit, add a changelog line at the top (date, finding id,
   what changed, version bump v3.3 → v3.4 …). `minor`: finding and changelog note only.
3. The PROGRESS entry says `Deviation: F-<n>`. HANDOVER.md lists every architecture change for the
   owner's review.

## 8. Blocked, issues, failures

- Blocked = no progress on this task without the owner's answer, a credential, a network domain,
  or a tool failure that persists after one retry and one alternative route. Write the issue, set
  the row `blocked`, take the next ready task. `BLOCKED` as campaign state only when no ready task
  remains.
- `issues/ISSUE-<n>.md`: what is needed, why the agent cannot decide it, what was tried, what the
  default would be if the owner does not answer.
- Never guess a library API, a licence, a number. Never invent work outside BACKLOG to spend
  allowance; add a row with a reason first.
- Quota or capacity exhaustion: stop. HANDOFF is already current after the last task; do not
  produce a final report after the limit. The owner resumes.

## 9. What the owner sees

Nothing is reported outside the repo. The owner reads `HANDOVER.md` at the gate, and
`agent/HANDOFF.md` + `PROGRESS.md` at any time. Chat messages per task are fine but carry nothing
that is not also in the files.
