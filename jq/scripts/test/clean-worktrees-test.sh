#!/usr/bin/env bash
# Script test for scripts/clean-worktrees.sh (#127), on throwaway repositories only.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../clean-worktrees.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

r=$tmp/repo
git init -q -b main "$r"
git -C "$r" config user.email a@b.c
git -C "$r" config user.name t
echo one > "$r/a.txt"
git -C "$r" add a.txt
git -C "$r" commit -q -m c1
c1=$(git -C "$r" rev-parse HEAD)
echo two > "$r/a.txt"
git -C "$r" commit -q -am c2
c2=$(git -C "$r" rev-parse HEAD)
mkdir -p "$r/.claude/worktrees"
w=$r/.claude/worktrees

git -C "$r" worktree add -q --detach "$w/agent-clean" "$c2"
git -C "$r" worktree add -q --detach "$w/agent-stale" "$c2"
git -C "$w/agent-stale" read-tree "$c1"
git -C "$r" worktree add -q --detach "$w/agent-new" "$c2"
echo fresh > "$w/agent-new/new-work.txt"
git -C "$r" worktree add -q --detach "$w/agent-staged" "$c2"
echo staged > "$w/agent-staged/staged-only.txt"
git -C "$w/agent-staged" add staged-only.txt
rm "$w/agent-staged/staged-only.txt"
git -C "$r" worktree add -q --detach "$w/verify-locked" "$c2"
git -C "$r" worktree lock "$w/verify-locked"
git -C "$r" checkout -q -b side
echo side > "$r/side.txt"
git -C "$r" add side.txt
git -C "$r" commit -q -m side
git -C "$r" worktree add -q --detach "$w/agent-unmerged" side
git -C "$r" checkout -q main
# a rewritten commit (#52): another sha, not an ancestor of main, with c2's tree
rw=$(git -C "$r" commit-tree -p "$c1" -m rewritten "$c2^{tree}")
git -C "$r" worktree add -q --detach "$w/agent-rewritten" "$rw"
git -C "$r" worktree add -q --detach "$w/agent-rewritten-dirty" "$rw"
echo fresh > "$w/agent-rewritten-dirty/rewritten-work.txt"

out=$(cd "$r" && "$tool" main 2>&1); rc=$?
printf '%s\n' "$out"
check "rc" "$rc" 0
[ ! -e "$w/agent-clean" ]; check "clean removed" "$?" 0
[ ! -e "$w/agent-stale" ]; check "stale index removed" "$?" 0
[ ! -e "$w/verify-locked" ]; check "locked clean removed" "$?" 0
[ -e "$w/agent-new/new-work.txt" ]; check "new work kept" "$?" 0
[ -e "$w/agent-unmerged" ]; check "unmerged kept" "$?" 0
[ -e "$w/agent-staged" ]; check "staged-only file kept" "$?" 0
printf '%s\n' "$out" | grep "agent-staged" | grep -q "staged-only.txt"
check "staged-only path printed" "$?" 0
printf '%s\n' "$out" | grep "agent-new" | grep -q "new-work.txt"
check "new path printed" "$?" 0
printf '%s\n' "$out" | grep "agent-unmerged" | grep -q "not merged"
check "unmerged reported" "$?" 0
printf '%s\n' "$out" | grep "agent-stale" | grep -q "forced"
check "stale reports force" "$?" 0
[ ! -e "$w/agent-rewritten" ]; check "rewritten with tree in main removed" "$?" 0
printf '%s\n' "$out" | grep "agent-rewritten:" | grep -q "removed, tree already in main"
check "rewritten reports tree in main" "$?" 0
[ -e "$w/agent-rewritten-dirty/rewritten-work.txt" ]; check "rewritten new work kept" "$?" 0
printf '%s\n' "$out" | grep "agent-rewritten-dirty" | grep -q "unique paths: rewritten-work.txt"
check "rewritten new path printed" "$?" 0
printf '%s\n' "$out" | grep "agent-unmerged" | grep -vq "tree already"
check "unmerged tree not in main" "$?" 0
[ "$(printf '%s\n' "$out" | grep -c 'agent-\|verify-')" = 8 ]
check "one line per worktree" "$?" 0

exit "$fails"
