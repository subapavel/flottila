# Flottila — agents, ownership, evidence

Every agent and tool working in this repository (Claude Code, its subagents, Codex, any other)
follows `CLAUDE.md`; this file adds who does what and what counts as evidence. The full protocol is
`docs/WORKFLOW.md`.

## Roles

| Role | Model | Effort | Tools | Owns |
| --- | --- | --- | --- | --- |
| coordinator (the main session) | claude-fable-5-1 | high (session setting) | all | task selection, task packets, design-heavy code, integration, BACKLOG / HANDOFF / PROGRESS, architecture changes |
| implementer (`.claude/agents/implementer.md`) | claude-sonnet-5-5 | high | read, search, edit, write, bash | one bounded task at a time, inside its allowed paths |
| scout (`.claude/agents/scout.md`) | claude-haiku-5-5 | medium | read, search | locating code, requirements, fixtures, prior results; factual context only |
| reviewer (`.claude/agents/reviewer.md`) | claude-fable-5-1 | high | read, search | independent review of every completed task: are the tests real, does the change keep the architecture |

At most two agents active at once, including the coordinator. Exactly one writer: while the
implementer owns a task, the coordinator does not edit source, the ledger files or git. Scout and
reviewer never write. No nested delegation, no agent teams, no scheduled loops.

The coordinator keeps for itself: `RequestScope`, admission control, `BlobStore` tiers, the
persistence batcher, the WorkQueue claim path, anything that changes a public type in
`flottila-api`, the JSON Schemas, and every architecture finding.

## Task packet (coordinator → implementer)

Task id from `agent/BACKLOG.md`; base commit; allowed paths; the failing test(s) that define
acceptance (written or approved by the coordinator); expected behaviour in two sentences; checks to
run; known constraints. Only the context the task needs — the implementer does not see the
conversation.

## Handback (implementer → coordinator)

Changed paths; candidate commit or diff; commands executed with observed results; remaining
uncertainty; proposed next action. A turn cap returns partial work, marked as such. The
implementer does not update BACKLOG / HANDOFF / PROGRESS and does not commit to `main`.

## Review (reviewer, after every task)

Input from the coordinator: the diff, the test output, and the scoped PIT mutation report for the
classes the task touched. The reviewer answers, per test, "would this fail if the code were wrong?"
against the checklist in `docs/WORKFLOW.md` and returns `PASS` or `FAIL — <reason>`. The
coordinator records the verdict in PROGRESS.md; `FAIL` reopens the task.

## Evidence vocabulary

Every check in PROGRESS.md carries one of: `VERIFIED_PASS` (ran, observed green),
`EXECUTED_FAIL` (ran, observed red), `NOT_RUN`, `ENVIRONMENT_BLOCKED` (could not run here, why).
A claim without the observed output behind it is not evidence. A skeleton is not the feature.
