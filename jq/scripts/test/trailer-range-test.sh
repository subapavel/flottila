#!/usr/bin/env bash
# Script test for scripts/trailer-range.sh, the range the CI trailers job checks (#51).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../trailer-range.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# a repo holding its own copy of the script, and a working directory outside it
r=$tmp/r
git init -q -b main "$r"
git -C "$r" config user.email a@b.c
git -C "$r" config user.name t
mkdir "$r/scripts"
cp "$tool" "$r/scripts/trailer-range.sh"
git -C "$r" commit -q --allow-empty -m one
before=$(git -C "$r" rev-parse HEAD)
git -C "$r" commit -q --allow-empty -m two
sha=$(git -C "$r" rev-parse HEAD)
gone=0123456789abcdef0123456789abcdef01234567
zero=0000000000000000000000000000000000000000
other=$tmp/other
mkdir "$other"
run() { (cd "$other" && bash "$r/scripts/trailer-range.sh" "$@" 2> "$tmp/err"); }

# a push whose before-SHA is gone (history rewritten): fall back to main, say so, no error
out=$(run push "$sha" "$gone"); rc=$?
check "gone before rc" "$rc" 0
check "gone before range" "$out" "origin/main..$sha"
grep -q "$gone" "$tmp/err"
check "gone before says so" "$?" 0

# the all-zero before-SHA of a new branch
out=$(run push "$sha" "$zero"); rc=$?
check "zero before rc" "$rc" 0
check "zero before range" "$out" "origin/main..$sha"

# an existing before-SHA, looked up in the script's own repository, not the working directory
out=$(run push "$sha" "$before"); rc=$?
check "existing before rc" "$rc" 0
check "existing before range" "$out" "$before..$sha"
check "existing before is quiet" "$(cat "$tmp/err")" ""

# a pull request checks base..head
out=$(run pull_request "$sha" "$before"); rc=$?
check "pull request rc" "$rc" 0
check "pull request range" "$out" "$before..$sha"

# any other event (workflow_dispatch) checks main..sha
out=$(run workflow_dispatch "$sha" ""); rc=$?
check "dispatch rc" "$rc" 0
check "dispatch range" "$out" "origin/main..$sha"

# wrong argument count
run push "$sha" > /dev/null
check "two args rc" "$?" 2
grep -q usage "$tmp/err"
check "two args prints usage" "$?" 0
run push "$sha" "$before" x > /dev/null
check "four args rc" "$?" 2
exit $fails
