#!/usr/bin/env bash
# Script test for scripts/hooks/commit-msg (#50): the hook checks one message file with the rules of
# scripts/check-trailers.sh, for git commit and git merge alike. Builds a throwaway repo whose own config sets
# core.hooksPath; the shared repository's config is never touched.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

repo=$tmp/repo
mkdir -p "$repo/scripts/hooks"
g() { git -C "$repo" "$@"; }
g init -q -b main
# the hook and the script work in the repository they live in, so the throwaway repo holds its own copies
cp "$here/../check-trailers.sh" "$repo/scripts/check-trailers.sh"
cp "$here/../hooks/commit-msg" "$repo/scripts/hooks/commit-msg"
g config user.email a@b.c
g config user.name t
g config core.hooksPath scripts/hooks
g commit -q --allow-empty --no-verify -m base

# hook <file>: runs the hook as git does (from the top of the work tree), output in $tmp/hook.out
hook() { (cd "$repo" && scripts/hooks/commit-msg "$1" > "$tmp/hook.out" 2>&1; echo $?); }

# the hook rejects a message missing one of the three trailers, with the text check-trailers.sh prints
printf 'feat: x\n\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
check "missing Ticket: hook rc" "$(hook "$tmp/msg")" 1
check "missing Ticket: hook text" "$(cat "$tmp/hook.out")" "commit message lacks 'Ticket: #<n>': feat: x"
printf 'feat: x\n\nTicket: #3\nSigned-off-by: o <o@x.y>\n' > "$tmp/msg"
check "missing Co-authored-by: hook rc" "$(hook "$tmp/msg")" 1
check "missing Co-authored-by: hook text" "$(cat "$tmp/hook.out")" \
  "commit message lacks 'Co-authored-by: Claude <noreply@anthropic.com>': feat: x"
printf 'feat: x\n\nTicket: #3\nCo-authored-by: Claude <noreply@anthropic.com>\n' > "$tmp/msg"
check "missing Signed-off-by: hook rc" "$(hook "$tmp/msg")" 1
check "missing Signed-off-by: hook text" "$(cat "$tmp/hook.out")" \
  "commit message has 0 Signed-off-by lines, expected exactly 1: feat: x"

# the hook accepts a message with all three trailers, silently
good='feat: ok\n\nTicket: #1\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n'
printf '%b' "$good" > "$tmp/msg"
check "all three trailers: hook rc" "$(hook "$tmp/msg")" 0
check "all three trailers: hook prints nothing" "$(cat "$tmp/hook.out")" ""
# a path relative to the top of the work tree, as git passes it
cp "$tmp/msg" "$repo/msg.txt"
check "relative message path: hook rc" "$(hook msg.txt)" 0
rm "$repo/msg.txt"

# the same verdict and text as check-trailers.sh <range> on every message case of trailers-test.sh: each case is
# run through the hook directly, through git commit with the hook enabled, and through the range check
base=$(g rev-parse HEAD)
merge_subject="Merge $(printf '%040d' 1 | tr 0 a) into $(printf '%040d' 2 | tr 0 b)"
names=(good "two Signed-off-by" "missing Ticket" "missing Co-authored-by" "missing Signed-off-by"
  "extra Co-Authored-By" "lower-case co-authored-by key" "Claude-Session line" "lower-case claude-session key"
  "no trailers" "GitHub PR merge commit")
msgs=("$good" "${good}Signed-off-by: o <o@x.y>\n"
  'feat: x\n\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n'
  'feat: x\n\nTicket: #3\nSigned-off-by: o <o@x.y>\n'
  'feat: x\n\nTicket: #3\nCo-authored-by: Claude <noreply@anthropic.com>\n'
  "${good}Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n" "${good}co-authored-by: Someone <s@x.y>\n"
  "${good}Claude-Session: https://example.invalid/session/1\n" "${good}claude-session: x\n"
  'docs: no trailers\n' "$merge_subject\n")
want=(0 1 1 1 1 1 1 1 1 1 0)
for i in "${!names[@]}"; do
  name=${names[$i]}
  printf '%b' "${msgs[$i]}" > "$tmp/msg"
  check "$name: hook rc" "$(hook "$tmp/msg")" "${want[$i]}"
  g checkout -q -b "case$i" main
  g commit -q --allow-empty -F "$tmp/msg" > /dev/null 2>&1
  check "$name: git commit rc" "$?" "${want[$i]}"
  if [ "${want[$i]}" -ne 0 ]; then
    check "$name: git commit made no commit" "$(g rev-parse HEAD)" "$base"
    g commit -q --allow-empty --no-verify -F "$tmp/msg"
  fi
  (cd "$repo" && scripts/check-trailers.sh "$base..HEAD" > "$tmp/range.out" 2>&1)
  check "$name: check-trailers.sh rc" "$?" "${want[$i]}"
  sha=$(g rev-parse --short=7 HEAD)
  check "$name: same text" "$(cat "$tmp/hook.out")" "$(sed '$d' "$tmp/range.out" | sed "s/^$sha /commit message /")"
done

# git merge runs the hook too: a merge commit without trailers is refused, one with them is made
g checkout -q -b side main
printf '%b' "${good/feat: ok/feat: side}" > "$tmp/msg"
g commit -q --allow-empty -F "$tmp/msg"
g checkout -q -b m main
printf '%b' "${good/feat: ok/feat: main side}" > "$tmp/msg"
g commit -q --allow-empty -F "$tmp/msg"
before=$(g rev-parse HEAD)
g merge -q --no-ff side -m "Merge side" > /dev/null 2>&1
check "merge without trailers: git merge rc" "$?" 1
check "merge without trailers made no commit" "$(g rev-parse HEAD)" "$before"
g merge --abort > /dev/null 2>&1
printf 'Merge side\n\nTicket: #1\nCo-authored-by: Claude <noreply@anthropic.com>\nSigned-off-by: o <o@x.y>\n' \
  > "$tmp/msg"
g merge -q --no-ff side -F "$tmp/msg" > /dev/null 2>&1
check "merge with trailers: git merge rc" "$?" 0
check "merge with trailers made the merge commit" "$(g rev-parse HEAD^2)" "$(g rev-parse side)"

# the message mode's own usage errors
(cd "$repo" && scripts/check-trailers.sh --message > /dev/null 2>&1)
check "--message without a file is rc 2" "$?" 2
printf '%b' "$good" > "$tmp/msg"
(cd "$repo" && scripts/check-trailers.sh --message "$tmp/msg" "$tmp/msg" > /dev/null 2>&1)
check "--message with two files is rc 2" "$?" 2
(cd "$repo" && scripts/check-trailers.sh --message "$tmp/no-such-file" > /dev/null 2>&1)
check "--message with a missing file is rc 2" "$?" 2

[ $fails -eq 0 ] && echo "ALL PASS"
exit $fails
