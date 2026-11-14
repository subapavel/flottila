#!/usr/bin/env bash
# The ticket gate (ADR-0014). Runs its steps in order, logs each to .logs/<ticket>-<step>.log, appends a row
# <step> <wall-clock ms> <exit code> per step that ran to .logs/<ticket>-steps.tsv (scripts/gate-spec.sh reads them),
# prints a result line "-- <step>: rc <exit code>, <ms> ms" after each step, and stops at the first failing step
# with that step's exit code. The last line is "gate-ticket: <ticket> passed" only when every step ran with exit
# code 0; after a failing step it is "gate-ticket: <ticket> step <step> failed with exit code <rc> (log …)" (#84).
#
#   scripts/gate-ticket.sh <ticket> <modules> [ref]
#
#   <modules>  comma-separated modules the ticket changes, e.g. query-core,jq-core; no step reads it since
#              F-27 took PIT out, it stays so the callers keep one form
#   [ref]      the ref for the trailer check (<ref>..HEAD) and the recording check (changed since <ref>);
#              default origin/main
#
# Steps, numbered as in ADR-0014 (its step 2, PIT on the changed classes, left the ticket gate by F-27; PIT
# runs only in the spec gate, on whole class-C modules):
#        1 verify       ./mvnw -B -Pbench,compile-oracle verify (the whole reactor; the oracle needs every module; the
#                       compile-mode oracle runs here too, so step 4 sees a compile.txt regression before the merge, F-46)
#        3 lf, trailers, word, record   LF check, trailer check, word check, oracle recordings changed since <ref>
#        4 ratchet      java scripts/ratchet.java check --require-compile (step 1 ran the compile-mode oracle, so its
#                       report is required, F-46)
# Environment: ORACLE_REQUIRE, a file of the ticket's required case ids, becomes -Doracle.require=<file> (ADR-0020).
# It is the only require path: the gate builds none of its own. A relative path is the caller's, made absolute
# before Maven sees it; a path that is not a file fails the gate with exit code 2 before step 1 (#84). The log
# names the file used, or "none" (F-38).
set -uo pipefail
if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: scripts/gate-ticket.sh <ticket> <modules> [ref]" >&2
  exit 2
fi
ticket=$1
ref=${3:-origin/main}
caller=$PWD
cd "$(dirname "$0")/.." || exit 2
mkdir -p .logs
: > ".logs/$ticket-steps.tsv"

now_ms() { date +%s%3N; }

# step <name> <show> <command…>: show=1 prints the whole log, otherwise only its tail on failure.
step() {
  local name=$1 show=$2 started rc ms
  shift 2
  echo "== $name: $*"
  started=$(now_ms)
  "$@" > ".logs/$ticket-$name.log" 2>&1
  rc=$?
  ms=$(( $(now_ms) - started ))
  printf '%s\t%s\t%s\n' "$name" "$ms" "$rc" >> ".logs/$ticket-steps.tsv"
  if [ "$show" = 1 ] || [ "$rc" -ne 0 ]; then tail -n 40 ".logs/$ticket-$name.log"; fi
  echo "-- $name: rc $rc, $ms ms"
  if [ "$rc" -ne 0 ]; then
    echo "gate-ticket: $ticket step $name failed with exit code $rc (log .logs/$ticket-$name.log)" >&2
    exit "$rc"
  fi
}

require=()
if [ -n "${ORACLE_REQUIRE:-}" ]; then
  file=$ORACLE_REQUIRE
  case $file in /*) ;; *) file=$caller/$file ;; esac
  if [ ! -f "$file" ]; then
    echo "gate-ticket: $ticket failed before step verify: ORACLE_REQUIRE is not a file: $file" >&2
    exit 2
  fi
  require=("-Doracle.require=$file")
  echo "== require: $file"
else
  echo "== require: none (ORACLE_REQUIRE unset)"
fi

step verify 0 ./mvnw -B -Pbench,compile-oracle "${require[@]}" verify
step lf 0 scripts/check-lf-checkout.sh
step trailers 0 scripts/check-trailers.sh "$ref..HEAD"
step word 0 scripts/check-core-neutral.sh
recordings=()
while IFS= read -r path; do recordings+=("$path"); done < <(git diff --name-only --diff-filter=AM "$ref"...HEAD -- '*.rec')
if [ ${#recordings[@]} -gt 0 ]; then
  step record 0 scripts/oracle-record.sh --verify "${recordings[@]}"
fi

step ratchet 1 java scripts/ratchet.java check --require-compile
echo "gate-ticket: $ticket passed"
