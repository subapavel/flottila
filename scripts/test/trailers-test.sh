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

# only the exact co-author line of WORKFLOW section 11 is allowed, and no session line (#49)
g checkout -q -b extracoauth main
good "feat: extra co-author"
echo "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" >> "$tmp/msg"
commit
extra=$(g rev-parse --short=7 HEAD)
check "extra Co-Authored-By" "$(run)" 1
grep -q "^$extra .*Co-Authored-By: Claude Opus 5.5" "$tmp/out"
check "extra co-author names the commit and the line" "$?" 0

g checkout -q -b lowercoauth main
good "feat: lower-case co-author"
echo "co-authored-by: Someone <s@x.y>" >> "$tmp/msg"
commit
check "lower-case co-authored-by key" "$(run)" 1

g checkout -q -b session main
good "feat: session"
echo "Claude-Session: https://example.invalid/session/1" >> "$tmp/msg"
commit
session=$(g rev-parse --short=7 HEAD)
check "Claude-Session line" "$(run)" 1
grep -q "^$session .*Claude-Session:" "$tmp/out"
check "session line names the commit" "$?" 0

g checkout -q -b lowersession main
good "feat: lower-case session"
echo "claude-session: x" >> "$tmp/msg"
commit
check "lower-case claude-session key" "$(run)" 1

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

# the waiver (F-28): scripts/trailers-waiver.txt lists the full SHAs of commits the check skips
waiver=$repo/scripts/trailers-waiver.txt
g checkout -q -b waived main
g commit -q --allow-empty -m "docs: no trailers"
bare=$(g rev-parse HEAD)
check "no waiver file: no waivers" "$(run)" 1
grep -q "lacks 'Ticket" "$tmp/out"
check "no waiver file names the trailer" "$?" 0
grep -q "waiver" "$tmp/out"
check "no waiver file is not reported" "$?" 1
printf '# comment\n\n%s\n' "$bare" > "$waiver"
check "listed SHA is waived" "$(run)" 0
grep -q "^${bare:0:7} waived" "$tmp/out"
check "waived commit is named" "$?" 0
check "waived commit is not counted" "$(tail -n 1 "$tmp/out")" "checked 1 commits in $base..HEAD"
printf '%s\n' "$base" > "$waiver"
check "unlisted SHA still fails" "$(run)" 1
printf '%s\n' "${bare:0:7}" > "$waiver"
check "abbreviated SHA is an error" "$(run)" 2
grep -q "40-hex" "$tmp/out"
check "bad line names the rule" "$?" 0
printf '%s\n' "$bare" "$bare nope" > "$waiver"
check "line with trailing text is an error" "$(run)" 2
rm "$waiver"
exit $fails
