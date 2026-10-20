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
3. Write the acceptance spec (`AGENTS.md`); the test-author writes the red tests; approve them by
   reading the test files. Red first: the failing test exists, observed red, before the code.
   Then the task packet.
4. Implement through the implementer (Opus 5.5; `model: sonnet` for CI, docs, fixtures). The
   coordinator never implements. One writer at a time.
5. Verify through the verifier: `mvn -B -ntp verify` green, ArchUnit green, coverage not
   decreased, scoped PIT on the touched classes. The verifier reports exit codes and the failing
   section; the coordinator reads the log tail itself and records each check with its evidence
   state.
6. Review by the reviewer agent (Opus 5.5), the diff handed over as `target/<task>.diff`. When
   the diff goes to the reviewer the architect runs `touch target/.batch-in-review` (§10). `FAIL`
   reopens the task at step 4; a second `FAIL` reopens it at step 3 with a rewritten spec. A gate
   row is reviewed by the gate-reviewer (Fable 5.1) instead.
7. Commit and push to `main`: `rm -f target/.batch-in-review && git commit …`. Append the
   PROGRESS entry, rewrite HANDOFF, set the row to `done`. These three edits go in the same
   commit as the code.
8. Continue with the next `ready` row without asking. Stop only on `GATE_MET` (docs/GATE.md
   satisfied → write `HANDOVER.md`) or `IDLE_NO_READY_TASK`.

The VM can be paused or reclaimed at any time. Git and `agent/HANDOFF.md` are the recovery point;
nothing that matters may exist only in the conversation or in an unpushed working tree.

## 2. Files

| File | Purpose | Written by |
| --- | --- | --- |
| `agent/BACKLOG.md` | one row per task: id, phase, title, depends-on, status, evidence | coordinator |
| `agent/HANDOFF.md` | ≤ 20 lines of ≤ 160 characters: state, last commit, next exact action, open issues, pointers; rewritten after every task | coordinator |
| `agent/NOTES.md` | module notes (shapes, commands, open follow-ups) that HANDOFF used to carry; one section per module, read on demand | coordinator |
| `.claude/agent-memory/<name>/MEMORY.md` | durable facts an agent learned (build quirks, verified APIs, recurring findings); never task state | that agent |
| `PROGRESS.md` | append-only journal, one entry per task, ≤ 15 lines (format below) | coordinator |
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

## 10. Turn economy (D-053)

The architect runs on the most expensive model and every one of its turns is priced over the whole
session context. Measured on 2026-10-20 over the session of 2026-10-19 (FIX-14 … FIX-6): 119 turns
answered the environment's Stop hook ("uncommitted changes, please commit and push") while a task was
mid-flight, and 131 turns only relayed a background subagent's completion — against 41 turns that
carried an owner message. The rules below remove most of them.

- **A. Wait in the foreground.** When the architect would otherwise go idle waiting for one
  subagent, it launches that subagent with `run_in_background: false`: the turn stays open, no Stop
  hook fires. With two subagents in flight the first runs in the background and the second in the
  foreground. Background only when the architect has other work to do while waiting. A *resumed*
  agent (a message to an existing agent) always runs in the background; a fresh agent can run in
  the foreground. The same holds for `gh run watch`: foreground, with a timeout.
- **B. Mark the batch under review.** When a batch leaves for the reviewer the architect runs
  `touch target/.batch-in-review` (`target/` is gitignored). The commit command removes it:
  `rm -f target/.batch-in-review && git commit …`.
- **C. Gated Stop hook.** `.claude/hooks/stop-hook-git-check.gated.sh` fronts the environment's
  `~/.claude/stop-hook-git-check.sh`: silent while the marker exists, silent on a `claude/*` branch
  with a dirty tree (the normal mid-batch state there; Flottila works on `main`, so that gate is
  inert here), at most one reminder per 30 minutes, then the original hook runs so its signing and
  unpushed-commit checks still apply.
- **D. Installed at session start, not by the Setup script.** The environment manager rewrites
  `~/.claude/stop-hook-git-check.sh` at every container start (its mtime equals the boot time), so
  the swap happens in a `startup|resume` SessionStart hook in `.claude/settings.json`: it backs the
  original up to `~/.claude/stop-hook-git-check.orig.sh` and copies the gated script over it,
  idempotently (it checks for the marker string `batch-in-review`). The agent never installs or
  edits its own hooks: changes under `.claude/` are committed by the owner from a patch the agent
  leaves in `agent/patches/`.
- **E. Author self-check list.** `implementer.md` and `test-author.md` carry a "Self-check before
  every hand-back" list that mirrors the reviewer's memory, so the author checks what the reviewer
  will check. When a review FAIL repeats a lesson, the lesson goes into the reviewer memory and the
  author self-check in the same commit.
- **F. Smaller review units.** A fix packet names file, line, the evidence to open and the sentence
  to write, nothing else; closure checks are limited to the fix list. A large review is split by
  file set across two read-only reviewers in parallel. Every long-running writer or reviewer keeps a
  running hand-back file from its first turn, so a turn-limit stop loses nothing.
- **Stop-hook messages are advisory.** A reminder that arrives while a task is mid-flight is
  declined in one line; it never triggers a commit of an unreviewed tree.
