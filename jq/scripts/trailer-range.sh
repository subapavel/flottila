#!/usr/bin/env bash
# Prints the commit range the CI trailers job hands to scripts/check-trailers.sh (#51).
#
#   scripts/trailer-range.sh <event> <sha> <from>
#
# pull_request: <from> is the base sha, the range is <from>..<sha>.
# push: <from> is the before-SHA. If it names a commit in this repository the range is <from>..<sha>;
#   if it does not (empty, the all-zero sha of a new branch, or a commit a force-push removed) the range
#   is origin/main..<sha> and a line on stderr says so.
# any other event: origin/main..<sha>.
# The commit is looked up in the script's own repository. Exit codes: 0 ok, 2 usage.
set -uo pipefail
[ $# -eq 3 ] || { echo "usage: $0 <event> <sha> <from>" >&2; exit 2; }
event=$1
sha=$2
from=$3
repo=$(cd "$(dirname "$0")/.." && pwd)
case "$event" in
  pull_request) echo "$from..$sha" ;;
  push)
    if git -C "$repo" cat-file -e "$from^{commit}" 2> /dev/null; then
      echo "$from..$sha"
    else
      echo "before-SHA '$from' is not a commit here (new branch or rewritten history): checking origin/main..$sha" >&2
      echo "origin/main..$sha"
    fi ;;
  *) echo "origin/main..$sha" ;;
esac
