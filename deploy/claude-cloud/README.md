# Claude Code Cloud — the environment Part B runs in

Agreed in Part A on 2026-10-17 (docs/DECISIONS.md D-006 … D-008). The autonomous session runs in
an Anthropic-hosted Claude Code Cloud VM (Ubuntu 24.04, 4 vCPU, 16 GB RAM, 30 GB disk; JDK 21 +
Maven, Docker, native PostgreSQL pre-installed). Nothing else is rented or deployed.

## 1. One-time GitHub steps (owner, from the laptop)

1. Create the **public** repository `subapavel/flottila` on GitHub, empty (no README, no licence,
   no .gitignore — the repo already has them).
2. Push the existing repository and the milestone-1 tag:
   ```
   git remote add origin https://github.com/subapavel/flottila.git
   git push -u origin main
   git push origin proto-m1
   ```
   (Tags cannot be pushed from the cloud session; this one is pushed from here.)
3. Branch protection on `main`: block force pushes and deletion. Nothing else — the agent pushes
   directly to `main`.
4. Give Claude access to the repository through the GitHub connection in the Claude app
   (Settings → Connectors, or the repository picker in the Code tab). No token is created by hand.

## 2. The environment (Claude Desktop → Code tab → Cloud → new environment)

| Setting | Value |
| --- | --- |
| Name | `flottila` |
| Network access | **Custom**, with "also include the default list of common package managers" checked, plus two domains: `mcp.context7.com` and `*.blob.core.windows.net` (GitHub Actions artifacts redirect to Azure blob storage, which the GitHub proxy does not cover; needed to read the runner's JaCoCo reports, FIX-7) |
| Setup script | the contents of `deploy/claude-cloud/setup.sh` |
| Permission mode | Auto |

Environment variables:

| Variable | Value | Why |
| --- | --- | --- |
| `JAVA_HOME` | `/opt/temurin-25` | installed by the setup script |
| `BASH_DEFAULT_TIMEOUT_MS` | `600000` | Maven and Testcontainers runs |
| `BASH_MAX_TIMEOUT_MS` | `1800000` | same |
| `MAVEN_OPTS` | `-Xmx3g` | |
| `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` | `2` | one writer beside the architect, plus a reader (scout, verifier or reviewer) — AGENTS.md keeps the one-writer rule |
| `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH` | `1` | no nested delegation |
| `CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL` | `1h` | a subagent's cache otherwise expires after 5 minutes, which a Maven run exceeds |
| `CLAUDE_CODE_AUTO_COMPACT_WINDOW` | `400000` | compact at 400k tokens instead of near the 1M limit: every turn re-sends the whole window, and decisions degrade long before the limit |
| `GIT_AUTHOR_NAME` | owner's name | commit identity and DCO sign-off are the owner's (D-004) |
| `GIT_AUTHOR_EMAIL` | owner's e-mail | |
| `GIT_COMMITTER_NAME` | owner's name | |
| `GIT_COMMITTER_EMAIL` | owner's e-mail | |

Not set: `CLAUDE_CODE_EFFORT_LEVEL` (it would override the subagents' efforts),
`CLAUDE_CODE_SUBAGENT_MODEL_FORCE`, `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS` (AGENTS.md: no teams).
The same values live in `.claude/settings.json` (committed) together with the `SessionStart`
hook that re-injects `git status`, `git log -3` and `agent/HANDOFF.md` after every compaction;
the environment variables matter for a session whose settings file predates them. No secrets: the
environment holds no credentials, no cloud SDK logins, no publishing keys. Optional: a Context7 API key as a *network secret* for
`mcp.context7.com`; then add `"headers": {"CONTEXT7_API_KEY": "${CONTEXT7_API_KEY}"}` to the
server in `.mcp.json`.

## 3. Starting the autonomous session

1. Code tab → Cloud → environment `flottila` → repository `subapavel/flottila` → new session.
2. Send, as two separate messages, and check the model line shows both:
   ```
   /model claude-opus-5-5
   /effort medium
   ```
   `medium` is the agreed default (D-054; `.claude/settings.json` sets the same through `model`
   and `modelSettings`). Raise to `high` only if PROGRESS.md shows review FAILs on spec grounds.
   The session is the architect only: tests, code, checks and reviews run on subagents whose model
   and effort follow the row's class (AGENTS.md); class-C design goes to the designer (Opus 5.5,
   xhigh); Fable 5.1 runs only as the gate-reviewer. Do not set `CLAUDE_CODE_EFFORT_LEVEL` in the
   environment: it overrides every per-call effort.
3. Paste the contents of `prompts/PART-B.md` as the first message. The agent reads the repo,
   confirms its understanding in one short message, and starts P0-01.
4. After that first reply, paste `/goal ` followed by the contents of `prompts/GOAL.txt`. The goal
   is what makes the session continue from task to task without a new prompt.

## 4. Stopping, resuming, reading progress

- **Stop:** the session's Stop control, then `/goal clear`. Check the task list for remaining
  workers and cancel them. The agent's state file (`agent/HANDOFF.md`) is already current after
  the last pushed task.
- **Quota:** when the included allowance is exhausted the session pauses. Nothing is lost;
  everything is on `main`.
- **Resume:** open the session (or a new one on the same repo), send `/goal` again with
  `prompts/GOAL.txt`, and the message `RESUME`. The agent reconciles `agent/HANDOFF.md` and
  `agent/BACKLOG.md` against `main` before continuing.
- **Reading:** `agent/HANDOFF.md` (where it is), `PROGRESS.md` (what happened, with
  measurements), `issues/` (what it needs from you), `HANDOVER.md` (at the gate).

## 5. What this environment can and cannot do

Can: build and test on JDK 25, reach Maven Central and Docker Hub, run Testcontainers (Postgres,
Kafka, ClickHouse, Valkey, S3), run native Postgres for benchmarks, push to `main`, reach Context7.
Cannot: push tags, use `gh pr`/`gh issue` (GraphQL is blocked — REST via `gh api` works), reach
any host outside the allowlist, deploy anything anywhere. Benchmarks are labelled "cloud VM,
4 vCPU" and are indicative; a final rerun on dedicated hardware is a later, separate step.
