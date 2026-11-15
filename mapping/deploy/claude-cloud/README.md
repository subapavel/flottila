# Claude Code Cloud environment

Where `/wayfinder`, `/run-spec` and `/gate` run. Adapted from Flottila's environment (same VM:
Ubuntu 24.04, 4 vCPU, 16 GB; JDK 21, Maven, Node and Docker pre-installed).

## Environment (Claude desktop → Code → Cloud → new environment)

| Setting | Value |
| --- | --- |
| Name | `flottila-mapping` |
| Network access | Custom, "include the default list of common package managers" checked, plus `mcp.context7.com` and `*.blob.core.windows.net` (GitHub Actions artifacts) |
| Setup script | contents of `deploy/claude-cloud/setup.sh` (installs JDK 25 beside JDK 21, approves the Context7 MCP server in `~/.claude/settings.json`, prints the Node version) |
| Permission mode | Auto |

Environment variables:

| Variable | Value | Why |
| --- | --- | --- |
| `JAVA_HOME` | `/opt/temurin-25` | installed by the setup script |
| `BASH_DEFAULT_TIMEOUT_MS` | `600000` | Maven and npm runs |
| `BASH_MAX_TIMEOUT_MS` | `1800000` | same |
| `MAVEN_OPTS` | `-Xmx2g` | three parallel builds on 16 GB |
| `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` | `4` | three writers + one reader or merger (`AGENTS.md`) |
| `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH` | `1` | no nested delegation |
| `CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL` | `1h` | a build outlasts the default 5-minute cache |
| `CLAUDE_CODE_AUTO_COMPACT_WINDOW` | `400000` | compact early; decisions degrade long before the limit |
| `GIT_AUTHOR_NAME` / `GIT_COMMITTER_NAME` | owner's name | commits and DCO sign-off are the owner's |
| `GIT_AUTHOR_EMAIL` / `GIT_COMMITTER_EMAIL` | owner's e-mail | |
| `CONTEXT7_API_KEY` | owner's Context7 key | `.mcp.json` sends it as a header; without it the free quota runs out |

Not set: `CLAUDE_CODE_EFFORT_LEVEL` (overrides every per-call effort),
`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS`. The only secret is `CONTEXT7_API_KEY`; never print it.

## Starting a session

1. Code → Cloud → environment `flottila-mapping` → repository `subapavel/flottila-mapping` → new session.
   Only this repository: with several, the session starts in `/home/user` and no `.mcp.json` loads.
2. `/model claude-opus-5-5`, then `/effort medium` (two messages).
3. First message of a session: what to run (`/wayfinder …`, `/run-spec #<spec>`, `/gate`).

Known limits (observed in Flottila, re-check here): tags cannot be pushed; `gh issue`/`gh pr`
may fail on GraphQL — use the REST forms in `docs/agents/issue-tracker.md`.
