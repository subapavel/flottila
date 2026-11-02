---
name: retro
description: Retrospective on a Flottila session — ranked improvements to the agents' environment.
disable-model-invocation: true
---

<!-- Adapted from mattpocock/skills `retro` (MIT, see ../LICENSE-mattpocock-skills). -->

The owner has asked for a **retrospective**: improvements to the environment the Flottila agents
work in (steering files, agent definitions, checks, hooks, tooling), so the next campaign runs
cheaper and fails less. You propose; the owner decides. Nothing here edits the code under review.

## Steps

1. Read `.claude/skills/writing-for-agents/SKILL.md` for the writing rules.

2. Read the primary sources for the session the owner names (default: the current one):
   - the session transcript under `~/.claude/projects/<project>/*.jsonl` — send the reading to the
     scout or a general-purpose agent and take back counts and quotes, never the raw file;
   - the PROGRESS entries and commit bodies of the tasks in the session (`git log --format=%B`);
   - review verdicts and their reasons, `agent-memory/*/MEMORY.md`, `issues/`, `docs/findings/`.

   Done when every task of the session has: its turn count, its review rounds with each FAIL
   reason, and every Stop-hook or relay-only turn attributed to it.

3. Look for candidates in these categories.

   - **Turn economy** (`docs/WORKFLOW.md` §10): turns that only answered a Stop hook or relayed a
     subagent's completion; background launches that should have been foreground; review rounds
     beyond the first. Count them, as §10 does, and compare with the last measurement.
   - **Repeated FAIL**: a review reason that recurs across tasks. The lesson goes into the
     reviewer memory *and* the author self-check in the same change (§10 E); a recurrence after
     that is a finding about the self-check wording.
   - **Automated checks**: a mistake a deterministic check could have caught. Read the existing
     guardrail first — `flottila-archtest` (ArchUnit), `ci/` scripts, the CI workflow, the
     SessionStart and Stop hooks (`.claude/settings.json`, `.claude/hooks/`) — so an existing check that is unwired or silently broken is the
     finding, not a reinvention. A **mechanical** rule (banned API, import shape, file location,
     dependency direction) gets a check, not a sentence; an ArchUnit change is an architecture
     change and needs a finding first (`CLAUDE.md`).
   - **Navigation**: the session searched long for something a pointer in HANDOFF, NOTES or an
     agent definition would have named.
   - **Steering load**: lines in `CLAUDE.md` / `AGENTS.md` / agent definitions that duplicate
     another file or are **no-ops** (the model behaves that way by default). Removing load is a
     finding as much as adding a rule.
   - **Tool economy**: expensive calls (whole-file reads, unbounded logs, repeated Maven runs on
     the reactor root) that a narrower command would have replaced.
   - **Information access**: a fact the agent needed and could not reach (a network domain, a
     log, Context7 quota — ISSUE-1).
   - **Class fit** (D-054): rows whose class proved too low (second FAIL) or too high (class-C
     effort on a mechanical change).

4. Present the candidates to the owner in order of severity, each with: evidence (count, task id,
   quote), the proposed change, its target file, and which load it spends (context or cognitive).

5. For each candidate the owner accepts: a change under `.claude/` goes to
   `agent/patches/retro-<date>.patch`; a reversible process decision gets a `docs/DECISIONS.md`
   line; work that needs code gets a BACKLOG row with the reason. Done when every accepted
   candidate has exactly one of these and every rejected one is named as rejected.

## Reference: implementation vs review

The implementer carries the most context pressure (exploration, code, debugging); the reviewer
the least (a diff and named files). Standards are enforced in review, so a new judgement rule goes
to the reviewer checklist; once it has cost a review round, or when it is known up front to be
costly, it goes into the author self-check too, in the same change (§10 E).
