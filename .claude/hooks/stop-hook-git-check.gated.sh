#!/bin/bash
# Gated front for the cloud session's Stop hook ~/.claude/stop-hook-git-check.sh (written by the
# environment manager at every container start). Silent while a batch is under review, skips the
# dirty-tree reminder on claude/* branches, throttles the rest, then runs the original hook.
# Installed by the startup|resume SessionStart hook in .claude/settings.json (committed by the
# owner); the agent never installs or edits its own hooks. See docs/WORKFLOW.md "Turn economy".
input=$(cat)
[[ "$(printf '%s' "$input" | jq -r '.stop_hook_active' 2>/dev/null)" == "true" ]] && exit 0
top=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

# Gate 1 — a batch is under review (architect: touch on hand-off to the reviewer, rm in the commit command).
[[ -f "$top/target/.batch-in-review" ]] && exit 0

# Gate 2 — autonomous branch with a dirty tree: the normal mid-batch state. A clean tree falls through.
if [[ "$(git branch --show-current 2>/dev/null)" == claude/* ]]; then
  if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
    exit 0
  fi
fi

# Gate 3 — at most one reminder per 30 minutes.
stamp=/tmp/stop-hook-git-check.last
if [[ -f "$stamp" && $(( $(date +%s) - $(stat -c %Y "$stamp" 2>/dev/null || echo 0) )) -lt 1800 ]]; then exit 0; fi
touch "$stamp"

orig=~/.claude/stop-hook-git-check.orig.sh
[[ -x "$orig" ]] || exit 0
printf '%s' "$input" | exec "$orig"
