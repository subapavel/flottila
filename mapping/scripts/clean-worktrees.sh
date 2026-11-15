#!/usr/bin/env bash
# Remove merged agent worktrees (.claude/worktrees/agent-* and verify-*) of the current repository.
# Usage: scripts/clean-worktrees.sh [base-ref]   (default origin/main)
# Per worktree, one line:
#   - HEAD not an ancestor of the base: kept, "not merged".
#   - clean: unlocked if locked, removed.
#   - dirty: its index tree and its working tree (untracked files included) are each compared with
#     the trees of the base's history. Both found: nothing unique is lost, removed with --force.
#     Otherwise kept, and the paths that differ from its HEAD are listed.
set -uo pipefail
base=${1:-origin/main}
git rev-parse --verify -q "$base^{commit}" > /dev/null || { echo "clean-worktrees: cannot resolve $base" >&2; exit 2; }
self=$(git rev-parse --show-toplevel)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git rev-list --format=%T "$base" | grep -v '^commit ' | sort -u > "$tmp/trees"

in_history() { grep -qxF "$1" "$tmp/trees"; }

# the tree of a worktree's files, untracked included, without touching its index
work_tree() {
  cp "$(git -C "$1" rev-parse --git-path index)" "$tmp/idx" 2> /dev/null || : > "$tmp/idx"
  GIT_INDEX_FILE=$tmp/idx git -C "$1" add -A > /dev/null 2>&1
  GIT_INDEX_FILE=$tmp/idx git -C "$1" write-tree
}

git worktree list --porcelain | sed -n 's/^worktree //p' | while IFS= read -r wt; do
  case "$(basename "$wt")" in agent-* | verify-*) ;; *) continue ;; esac
  case "$wt" in */.claude/worktrees/*) ;; *) continue ;; esac
  [ "$wt" = "$self" ] && { echo "$wt: kept, current worktree"; continue; }
  head=$(git -C "$wt" rev-parse HEAD 2> /dev/null) || { echo "$wt: kept, unreadable"; continue; }
  if ! git merge-base --is-ancestor "$head" "$base"; then
    echo "$wt: kept, not merged"
    continue
  fi
  if [ -z "$(git -C "$wt" status --porcelain)" ]; then
    git worktree unlock "$wt" 2> /dev/null
    if git worktree remove "$wt"; then echo "$wt: removed"; else echo "$wt: kept, remove failed"; fi
    continue
  fi
  idx=$(git -C "$wt" write-tree)
  wk=$(work_tree "$wt")
  if in_history "$idx" && in_history "$wk"; then
    git worktree unlock "$wt" 2> /dev/null
    if git worktree remove --force "$wt"; then
      echo "$wt: removed (forced), index and working tree are already in $base history"
    else echo "$wt: kept, forced remove failed"; fi
  else
    paths=
    for t in "$idx" "$wk"; do
      in_history "$t" || paths="$paths$(git diff-tree -r --name-only "$head" "$t" | tr '\n' ' ')"
    done
    echo "$wt: kept, unique paths: $paths"
  fi
done
git worktree prune
