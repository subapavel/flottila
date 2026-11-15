#!/usr/bin/env bash
# Commit trailer check (#67, docs/WORKFLOW.md section 11). Every commit in <range> has exactly one Signed-off-by,
# a "Ticket: #<n>" line and a "Co-authored-by: Claude <noreply@anthropic.com>" line. Merge commits written by the
# merger are checked; GitHub's own PR merge commit ("Merge <sha> into <sha>") is skipped.
#
#   scripts/check-trailers.sh <range>        e.g. origin/main..HEAD
set -uo pipefail
if [ $# -ne 1 ]; then
  echo "usage: scripts/check-trailers.sh <range>" >&2
  exit 2
fi
case "$1" in
  *..*) ;;
  *)
    echo "usage: scripts/check-trailers.sh <range>; '$1' has no '..' and would check the whole history" >&2
    exit 2
    ;;
esac
cd "$(dirname "$0")/.." || exit 2
if ! shas=$(git rev-list "$1"); then
  echo "cannot resolve range $1 in $(pwd)" >&2
  exit 1
fi
if [ -z "$shas" ]; then
  echo "checked 0 commits in $1: the range is empty, nothing was verified" >&2
  exit 1
fi
rc=0
n=0
for sha in $shas; do
  msg=$(git log -1 --format=%B "$sha")
  subject=$(printf '%s\n' "$msg" | head -n 1)
  if [[ "$subject" =~ ^Merge\ [0-9a-f]{40}\ into\ [0-9a-f]{40}$ ]]; then
    continue
  fi
  n=$((n + 1))
  signed=$(printf '%s\n' "$msg" | grep -c '^Signed-off-by: ')
  if [ "$signed" -ne 1 ]; then
    echo "${sha:0:7} has $signed Signed-off-by lines, expected exactly 1: $subject"
    rc=1
  fi
  if ! printf '%s\n' "$msg" | grep -Eq '^Ticket: #[0-9]+$'; then
    echo "${sha:0:7} lacks 'Ticket: #<n>': $subject"
    rc=1
  fi
  if ! printf '%s\n' "$msg" | grep -Fxq 'Co-authored-by: Claude <noreply@anthropic.com>'; then
    echo "${sha:0:7} lacks 'Co-authored-by: Claude <noreply@anthropic.com>': $subject"
    rc=1
  fi
done
echo "checked $n commits in $1"
exit "$rc"
