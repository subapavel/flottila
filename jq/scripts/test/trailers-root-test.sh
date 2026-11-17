#!/usr/bin/env bash
# Script test for the repository root, the empty range and the bare ref of scripts/check-trailers.sh (#122).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../check-trailers.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

msg() { printf 'feat: x\n\nTicket: #1\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n'; }
mkrepo() { # <dir>: a repo holding its own copy of the script
  git init -q -b main "$1"
  git -C "$1" config user.email a@b.c
  git -C "$1" config user.name t
  mkdir "$1/scripts"
  cp "$tool" "$1/scripts/check-trailers.sh"
  git -C "$1" commit -q --allow-empty -m base
}
a=$tmp/a
b=$tmp/b
mkrepo "$a"
mkrepo "$b"
base=$(git -C "$a" rev-parse HEAD)
msg | git -C "$a" commit -q --allow-empty -F -
msg | git -C "$a" commit -q --allow-empty -F -

# invoked by path from another repository: the range is still checked in the script's own repository
out=$(cd "$b" && "$a/scripts/check-trailers.sh" "$base..HEAD" 2>&1); rc=$?
check "other cwd rc" "$rc" 0
check "other cwd counts a's commits" "$(printf '%s\n' "$out" | tail -n 1)" "checked 2 commits in $base..HEAD"

# a range with no commits is not a pass
out=$("$a/scripts/check-trailers.sh" "HEAD..HEAD" 2>&1); rc=$?
check "empty range rc" "$rc" 1
printf '%s\n' "$out" | grep -q "0 commits"
check "empty range says so" "$?" 0

# a range the repository cannot resolve is not a pass
"$a/scripts/check-trailers.sh" "nosuch..HEAD" > /dev/null 2>&1
check "unknown ref rc" "$?" 1

# a bare ref would check the whole history
out=$("$a/scripts/check-trailers.sh" HEAD 2>&1); rc=$?
check "bare ref rc" "$rc" 2
printf '%s\n' "$out" | grep -q "usage"
check "bare ref prints usage" "$?" 0

# invoked from another repository, the waiver is read from the script's own repository (F-28)
git -C "$a" commit -q --allow-empty -m "docs: no trailers"
git -C "$a" rev-parse HEAD > "$a/scripts/trailers-waiver.txt"
out=$(cd "$b" && "$a/scripts/check-trailers.sh" "$base..HEAD" 2>&1); rc=$?
check "other cwd reads the script's waiver" "$rc" 0
exit $fails
