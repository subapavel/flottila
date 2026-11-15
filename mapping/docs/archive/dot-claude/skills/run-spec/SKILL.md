---
name: run-spec
description: Build a published spec end to end, autonomously — work its tickets as a task graph with up to three implementers in parallel worktrees, merge each into the spec's integration branch behind an automatic gate, review the whole branch on two axes, and land it on main through one PR. Use when the owner says to build or continue a spec.
disable-model-invocation: true
---

You are the **architect**. You dispatch, approve, merge through the merger and record; you never
edit code or tests and never run builds yourself (`AGENTS.md`). The spec issue number is the
argument; without one, take the spec named in `agent/HANDOFF.md`.

In the cloud VM use the REST forms of `docs/agents/issue-tracker.md` ("Cloud VM: REST only")
wherever a step below names a `gh issue` or `gh pr` command.

Vocabulary: `GLOSSARY.md` (spec, ticket, frontier, integration branch, oracle, ratchet). Tracker
operations and the evidence-comment format: `docs/agents/issue-tracker.md`. Classes and
concurrency: `AGENTS.md`.

## 0. Orient

1. Read `agent/HANDOFF.md`, the spec (`gh issue view <spec> --comments`) and its tickets (titles,
   labels, assignees, blockers — not every body). Run `git status`, `git log -3`, `git worktree list`.
2. Reconcile: a ticket assigned to you whose branch has no merge into the integration branch is
   **in flight from an earlier run**: resume it from its pushed branch, never assume it done.
3. Integration branch `spec/<spec>-<slug>`: create it from `main` and push, or check out the
   existing one at its remote tip. Pin (or update) the spec's status comment.
4. Write `agent/HANDOFF.md` with state `RUNNING` and push it on the integration branch. It names
   work in flight by branch and sha, never by worktree path: worktrees do not survive a session.

## 1. Fill the writer slots

The **frontier**: open sub-issues of the spec with no open blocker, no assignee, no
`ready-for-human`. Up to **three** tickets in flight. Take frontier tickets in issue order and,
for each, start the path its class names:

- **Claim** it (assign), comment the branch pointer: `t/<ticket>-<slug>` from the integration tip.
- **Class C**
  1. designer (foreground when nothing else runs): the ticket, its spec, the ADRs and code by
     path. Approve the draft or narrow it; an ADR draft it contains is committed by you to the
     integration branch before tests start.
  2. test-author, `isolation: "worktree"`, model and effort by class: the acceptance spec
     (`AGENTS.md`). Read the test files it lists — not the hand-back alone — and approve, or send
     one round of corrections to the same agent.
  3. implementer, `isolation: "worktree"`, on the test-author's pushed branch.
- **Class A / B**: implementer, `isolation: "worktree"`, model and effort by class. Its packet
  says `/tdd` at the spec's seams (named in the spec's Testing Decisions), or the oracle case
  ids when the ticket has an `oracle:*` label.

Packets are pointers (ticket URL, branch, base sha, allowed paths, the red, ADR ids, checks), never
copies of the spec, and name only what is specific to the ticket: the command and foreground rules
live in `AGENTS.md` (Command rules) and the agent definitions point to them. Each agent call passes the class's `model` and `effort`.

## 2. When a writer hands back

Take hand-backs in the order they arrive; refill a freed slot from the frontier at once.

1. **Partial** hand-back (turn cap, obstacle): resume the same agent by name, or comment the
   obstacle, label `ready-for-human` if only the owner can clear it, release the slot.
2. **Ticket gate** — verifier on the pushed ticket branch, in its own worktree
   (`git worktree add .claude/worktrees/verify-<ticket> t/<ticket>-<slug>`), the checks of
   `docs/WORKFLOW.md` §6. Any `EXECUTED_FAIL`: a fix packet (file, line, evidence, the sentence)
   to the same implementer.
3. **Class C review** — `mkdir -p .logs && touch .logs/.batch-in-review`, then call the Skill tool with
   `code-review`, fixed point = the ticket branch's merge-base with the integration branch, the
   ticket as the spec; spawn the `reviewer-standards` and `reviewer-spec` agents at xhigh. FAIL →
   every reason in one fix packet to the same implementer, re-gate, re-review the fix list only.
   A second FAIL means the ticket was wrong: designer rewrites it (raise the class if below C),
   restart the ticket.
4. **Merge** — merger (one at a time; queue the rest) with the integration branch and the ticket
   branch. A semantic conflict goes back to the ticket's implementer: "merge the integration tip,
   resolve, re-run the scoped checks". After a merge: verifier regenerates `ratchet/` on the
   integration branch; a ratchet drop is a failure of this ticket.
5. **Record** — evidence comment (`docs/agents/issue-tracker.md`), close the ticket, update the
   spec's status comment and `agent/HANDOFF.md`, append every hand-back's `Worth recording:` lines
   to `.claude/agent-memory/<role>/MEMORY.md` (AGENTS.md, Memory), push, remove the verify worktree.
6. **Carry or ticket** — a point a review leaves open is carried to the spec review only when it
   is a judgement call. One that gives invalid output, contradicts an ADR, or names a test that
   cannot fail is a ticket (or a finding, `docs/WORKFLOW.md` §7) now, before the next merge.
   Reviewers label each non-blocking observation `carry` (a judgement call) or `ticket` (meets
   the criteria above). File every `ticket` point before the next merge; carry only `carry`
   points. If you disagree with a label, say why in the ticket's evidence comment; do not
   drop it silently.

## 3. Finish the spec

When every ticket is closed (or the frontier is empty and only `ready-for-human` tickets remain —
then stop with `IDLE_NO_FRONTIER`, see §5):

1. **Spec gate** — verifier, full checks on the integration branch.
2. **Review** — `mkdir -p .logs && touch .logs/.batch-in-review`; call the Skill tool with `code-review`, fixed
   point `main`, the spec issue as the spec, reviewers `reviewer-standards` and `reviewer-spec`
   at high. Keep the two reports separate. A diff over about 150 files splits the Standards axis
   into one reviewer per module group (engine, service, ui + contract), each with its own paths,
   so each one reports within its turn cap. Run them in waves of two readers (AGENTS.md,
   Concurrency).
3. **Fix** — one implementer (Opus 5.5, high) per module group (engine + corpus; service + ui +
   contract), each on its worktree branch `t/<spec>-review-<group>` with that group's findings as
   one packet of at most about 8 (split a larger group); the spec-3 fix packet of 14 hit the turn
   cap. Merger one at a time; spec gate again. A second round only on what the first fix
   touched.
4. **PR** — first commit `agent/HANDOFF.md` with state `SPEC_DONE` and the next action on the
   integration branch and push it: `main` refuses direct pushes, so the handoff reaches `main` with
   the spec merge. Then call the Skill tool with `pr` for the body (summary, evidence lines, merge danger),
   `gh pr create --base main --head spec/<spec>-<slug>`, the body closing the spec. Wait for CI in
   the foreground (`gh pr checks --watch`, with a timeout). Red CI is a fix packet, not a retry.
5. **Merge** — `rm -f .logs/.batch-in-review && gh pr merge --merge` (never squash, never
   rebase, never force). Run `scripts/clean-worktrees.sh`: it removes every
   merged agent worktree, forces only one whose index and files are already in `origin/main`
   history, keeps the rest with a reason, then prunes.
6. Suggest `/retro` to the owner in one line.

## 4. Waiting and turns

- One agent in flight and nothing else to do: run it in the foreground. Two or more: background,
  and act on each completion as it arrives.
- A resumed agent (`SendMessage`) keeps its context: fixes, second rounds and partial hand-backs
  always resume, never respawn.
- Read hand-backs, test files you approve and `git diff --stat`; never whole patches or logs.
- A Stop-hook reminder while tickets are in flight is declined in one line.

## 5. Stopping

Never ask the owner during a run. Stop only on:

- `SPEC_DONE` — PR merged;
- `IDLE_NO_FRONTIER` — every open ticket is blocked or `ready-for-human`; list them with reasons;
- a quota or capacity stop — `agent/HANDOFF.md` is already current after the last record step.

The state line of `agent/HANDOFF.md` names which one, and the next exact action.
