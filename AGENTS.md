# Flottila — agents, ownership, evidence

Every agent and tool working in this repository (Claude Code, its subagents, Codex, any other)
follows `CLAUDE.md`; this file adds who does what and what counts as evidence. The full protocol is
`docs/WORKFLOW.md`, which calls the main session the coordinator; here it is the architect, the
same role.

## Roles

| Role | Model | Effort | Tools | Owns |
| --- | --- | --- | --- | --- |
| architect (the main session) | claude-fable-5-1 | high (session setting) | all | task selection, acceptance specs and task packets, approval of the red tests, design decisions, findings, the shape of public types in `flottila-api` and of the JSON Schemas (designed in the packet, written by the implementer), BACKLOG / HANDOFF / PROGRESS, commits |
| test-author (`.claude/agents/test-author.md`) | claude-opus-5-5 | high | read, search, edit, write, bash | the red tests of one task, observed red |
| implementer (`.claude/agents/implementer.md`) | claude-opus-5-5 | high (`xhigh` per call for the hard paths) | read, search, edit, write, bash | one bounded task at a time, inside its allowed paths |
| verifier (`.claude/agents/verifier.md`) | claude-sonnet-5-5 | low | bash, read, search | running the checks, reporting observed results |
| scout (`.claude/agents/scout.md`) | claude-haiku-5-5 | medium | read, search | locating code, requirements, fixtures, prior results; factual context only |
| reviewer (`.claude/agents/reviewer.md`) | claude-opus-5-5 | high | read, search | independent review of every completed task |
| gate-reviewer (`.claude/agents/gate-reviewer.md`) | claude-fable-5-1 | high | read, search | the phase-gate rows only |

The architect delegates everything that produces or runs code. It never edits `src/`, never
writes a test, never runs Maven. It writes the spec and the packet, reads the test files to
approve them, reads handbacks, decides, and commits. A one-line fix the reviewer names goes back
to the implementer, resumed by name.

Model per call: the architect may pass `model: sonnet` to the implementer for CI scripts, docs,
fixtures and mock behaviours, and `effort: xhigh` for the batcher, admission, `RequestScope`,
`BlobStore` tiers, the WorkQueue claim path and public types. The definition's model is the
default. Fable 5.1 runs only as the architect and as the gate reviewer.

Concurrency: at most two subagents at once beside the architect, and exactly one writer — while
the test-author or the implementer owns a task, nobody else edits source, the ledger files or git;
the scout, verifier and reviewer never write. No nested delegation, no agent teams, no scheduled
loops (D-007, D-NEW).

A finished agent stays addressable by name in the session: a second review round, a partial
hand-back at the turn cap, or a one-line fix resumes the same agent with its context instead of
starting a fresh one.

Memory: implementer, test-author and reviewer keep `.claude/agent-memory/<name>/MEMORY.md`
(committed; durable facts only, never task state; under 120 lines — only the first 200 lines are
loaded). The architect reads a memory file only when a handback points at it.

## Acceptance spec (architect → test-author)

Task id; module(s) and allowed test paths; the behaviours to guarantee, one line each with the
proposed test name; fixtures; which cases are property or contract-suite cases; what counts as
red (a failing assertion, or a compile error on a type the task introduces). The architect
approves the written tests by reading the test files, not the handback.

## Task packet (architect → implementer)

Task id from `agent/BACKLOG.md`; base commit; allowed paths; the red test(s) that define
acceptance (approved by the architect); expected behaviour in two sentences; the ARCHITECTURE
sections that govern the change; checks to run; known constraints. Only the context the task needs
— the implementer does not see the conversation.

## Handback (any agent → architect)

At most 40 lines. Changed paths; one line per path group; commands executed with observed results
(evidence word, counts line, log path under `target/`); remaining uncertainty; proposed next
action. A failure quotes the failing test and at most 10 lines of its section. A turn cap returns
partial work, marked as such. No agent updates BACKLOG / HANDOFF / PROGRESS or commits to `main`.

## Verification (verifier, after every handback and before every commit)

The architect names the commands; the verifier runs them with output under `target/<task>-*.log`
and reports exit codes, counts lines and the failing section. The architect reads the tail of the
log itself for the evidence line it writes into PROGRESS.md — a claim without observed output
behind it is not evidence.

## Review (reviewer, after every task)

Input from the architect, by path: the diff (`target/<task>.diff`), the test output, and the scoped
PIT mutation report for the classes the task touched. The reviewer answers, per test, "would this
fail if the code were wrong?" against the checklist in `docs/WORKFLOW.md` and returns `PASS` or
`FAIL — <reason>`, every blocking reason in one round. The architect records the verdict in
PROGRESS.md; `FAIL` reopens the task. After a second `FAIL` the architect rewrites the packet (the
spec was wrong, not only the code) rather than running a third round on the same packet. The
reviewer is never the agent that wrote the test.

## Gate review (gate-reviewer, on gate rows only)

Run once per gate row on the gate commit. Returns `gate: MET` or `gate: NOT_MET — <reasons>`;
`NOT_MET` makes the named condition the only ready task.

## Evidence vocabulary

Every check in PROGRESS.md carries one of: `VERIFIED_PASS` (ran, observed green),
`EXECUTED_FAIL` (ran, observed red), `NOT_RUN`, `ENVIRONMENT_BLOCKED` (could not run here, why).
A claim without the observed output behind it is not evidence. A skeleton is not the feature.
