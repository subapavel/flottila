#!/usr/bin/env bash
# The verifier's step after every merge into the integration branch (F-29, ADR-0020 amendment for #60): a full verify
# that runs the compile-mode oracle, then the oracle pass lists. A required case that fails stops the verify; a
# Disagreement or a drop makes regen refuse and write nothing. Either stops the next merge until it is fixed.
#
#   scripts/after-merge.sh <ticket>
#
# Steps: verify  ./mvnw -B -Pbench,compile-oracle verify
#        regen   java scripts/ratchet.java regen --oracles
# Logs: .logs/<ticket>-after-merge-<step>.log. Stops at the first failing step, shows its log's tail and exits with
# its exit code; 2 is usage.
set -uo pipefail
if [ $# -ne 1 ]; then
  echo "usage: scripts/after-merge.sh <ticket>" >&2
  exit 2
fi
ticket=$1
cd "$(dirname "$0")/.." || exit 2
mkdir -p .logs

step() { # <name> <command…>
  local name=$1 log rc
  shift
  log=.logs/$ticket-after-merge-$name.log
  echo "== $name: $*"
  "$@" > "$log" 2>&1
  rc=$?
  if [ "$rc" -ne 0 ]; then
    tail -n 40 "$log"
    echo "after-merge: step $name failed with exit code $rc (log $log)" >&2
    exit "$rc"
  fi
}

step verify ./mvnw -B -Pbench,compile-oracle verify
step regen java scripts/ratchet.java regen --oracles
tail -n 20 ".logs/$ticket-after-merge-regen.log"
echo "after-merge: $ticket done"
