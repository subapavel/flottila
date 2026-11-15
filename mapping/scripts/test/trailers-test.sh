#!/usr/bin/env bash
# Script test for scripts/check-trailers.sh (#67). Builds a throwaway repo with good and bad commits.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../check-trailers.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

repo=$tmp/repo
mkdir "$repo"
g() { git -C "$repo" "$@"; }
g init -q -b main
# the script works in the repository it lives in (#122), so the throwaway repo holds its own copy
mkdir "$repo/scripts"
cp "$tool" "$repo/scripts/check-trailers.sh"
g config user.email a@b.c
g config user.name t
good() { printf '%s\n\nTicket: #1\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n' "$1" > "$tmp/msg"; }
commit() { g commit -q --allow-empty -F "$tmp/msg"; }
g commit -q --allow-empty -m base
base=$(g rev-parse HEAD)

run() { (cd "$repo" && scripts/check-trailers.sh "$base..HEAD" > "$tmp/out" 2>&1; echo $?); }

good "feat: ok"
commit
check "good commit" "$(run)" 0

g checkout -q -b dup main
good "feat: dup"
echo "Signed-off-by: o <o@x.y>" >> "$tmp/msg"
commit
check "two Signed-off-by" "$(run)" 1
grep -q "Signed-off-by" "$tmp/out"
check "dup names the trailer" "$?" 0

g checkout -q -b noticket main
printf 'feat: x\n\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
commit
check "missing Ticket" "$(run)" 1

g checkout -q -b nocoauth main
printf 'feat: x\n\nTicket: #3\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
commit
check "missing Co-authored-by" "$(run)" 1

g checkout -q -b nosign main
printf 'feat: x\n\nTicket: #3\nCo-authored-by: Claude <noreply@anthropic.com>\n' > "$tmp/msg"
commit
check "missing Signed-off-by" "$(run)" 1

# a merge commit written by the merger is checked like any other
g checkout -q -b side main
good "feat: side"
commit
g checkout -q -b m main
good "feat: main side"
commit
g merge -q --no-ff side -m "Merge side"
check "merge without trailers" "$(run)" 1
good "Merge side"
g commit -q --amend -F "$tmp/msg"
check "merge with trailers" "$(run)" 0

# GitHub's own PR merge commit ("Merge <sha> into <sha>") is skipped
g commit -q --amend -m "Merge $(g rev-parse HEAD~1) into $(g rev-parse HEAD^2)"
check "GitHub PR merge commit" "$(run)" 0
exit $fails
