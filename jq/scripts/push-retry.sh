#!/usr/bin/env bash
# Usage: scripts/push-retry.sh <repo-dir> <refspec>
# Pushes <refspec> to origin, retrying only the transient GitHub rejection
# "fatal error in commit_refs_rules" with backoff. Never forces. Any other failure stops at once.
# PUSH_RETRY_WAITS: space-separated waits in seconds between attempts (default "30 60 120 240 480").
# Exit 0 pushed, 1 rejected (every attempt, or a non-transient error), 2 usage.
set -uo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <repo-dir> <refspec>" >&2; exit 2; }
dir=$1
refspec=$2
read -r -a waits <<< "${PUSH_RETRY_WAITS:-30 60 120 240 480}"

attempt=0
while :; do
  attempt=$((attempt + 1))
  out=$(git -C "$dir" push origin "$refspec" 2>&1)
  rc=$?
  printf '%s\n' "$out"
  if [ "$rc" -eq 0 ]; then exit 0; fi
  case "$out" in
    *"fatal error in commit_refs_rules"*) ;;
    *) echo "push-retry: non-transient rejection, not retrying" >&2; exit 1 ;;
  esac
  if [ "$attempt" -gt "${#waits[@]}" ]; then
    echo "push-retry: rejected on all $attempt attempts" >&2
    exit 1
  fi
  wait=${waits[$((attempt - 1))]}
  echo "push-retry: transient rejection, attempt $attempt, waiting ${wait}s" >&2
  sleep "$wait"
done
