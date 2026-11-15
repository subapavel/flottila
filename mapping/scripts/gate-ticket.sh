#!/usr/bin/env bash
# Ticket gate (docs/WORKFLOW.md section 6, ADR-0017, ADR-0019). Run from any directory; it changes to the
# repository root of the worktree it lives in.
#
#   scripts/gate-ticket.sh <ticket> <modules> [integration-ref]
#
# <ticket>           ticket number, used in the log names (.logs/<ticket>-<check>.log)
# <modules>          touched module directories, comma-separated (engine/xdm,engine/ir); `service` for the service
# [integration-ref]  e.g. origin/spec/21-walking-skeleton. When given, the ui/ steps run only if ui/ or contract/
#                    changed against it (without it they always run), and the trailers of <ref>..HEAD are checked.
#
# Every step runs even if an earlier one failed. Each prints an evidence word (VERIFIED_PASS, EXECUTED_FAIL, NOT_RUN)
# and its log. Exit 1 if any step failed. The last lines are the PIT summary, one per class-C module among <modules>
# (scripts/pit-summary.sh):
#   <module> <killed>/<generated> survivors <S> floor <N> ok|DROP
# or, against a ratchet row of the old format (`module TAB score %`):
#   <module> <killed>/<generated> <score>% floor <N>% ok|DROP
set -uo pipefail

if [ $# -lt 2 ]; then
  echo "usage: scripts/gate-ticket.sh <ticket> <modules> [integration-ref]" >&2
  exit 2
fi
ticket=$1
modules=$2
base=${3:-}

cd "$(dirname "$0")/.."
mkdir -p .logs ui/.logs
# the console output, stderr included, also goes to .logs/<ticket>-gate.log under this root, whatever the caller's
# directory (#125). On exit, close it and wait for tee, so the log is complete when the script returns.
exec > >(tee ".logs/${ticket}-gate.log") 2>&1
tee_pid=$!
trap 'exec >&- 2>&-; wait "$tee_pid"' EXIT
status=0

# step <name> <log> <command...>: run, send all output to the log, print the evidence word
step() {
  local name=$1 log=$2
  shift 2
  if "$@" > "$log" 2>&1; then
    echo "VERIFIED_PASS  $name  ($log)"
  else
    echo "EXECUTED_FAIL  $name  ($log)"
    status=1
  fi
}

skipped() { echo "NOT_RUN         $1  ($2)"; }

# 1: touched and upstream modules, plus the oracle (which pulls in the whole engine)
step "1 build $modules,engine/oracle" ".logs/${ticket}-build.log" \
  ./mvnw -B -pl "${modules},engine/oracle" -am verify

# 1b: PIT on the class-C engine modules among <modules> (floor in ratchet/mutation.tsv, ADR-0019). test-compile first:
# PIT needs the classes of a clean tree; -am puts upstream modules in the reactor, where pit.skip skips those without
# a mutation floor. More survivors than the floor row allows (for an old-format row, a score below it) is
# EXECUTED_FAIL. The summary lines are printed last.
class_c="engine/xdm engine/ir engine/runtime engine/serializer engine/mapping engine/xslt-export engine/api engine/udf-api engine/udf"
pit_modules=()
for m in ${modules//,/ }; do
  case " $class_c " in *" $m "*) pit_modules+=("$m") ;; esac
done
pit_summary=""
if [ ${#pit_modules[@]} -gt 0 ]; then
  pit_list=$(IFS=,; echo "${pit_modules[*]}")
  # one PIT run per module, each section preceded by `=== PIT <module> ===` in the log (#87)
  step "1b pit $pit_list" ".logs/${ticket}-pit.log" \
    scripts/pit-summary.sh --run ".logs/${ticket}-pit.log" "${pit_modules[@]}"
  pit_summary=$(scripts/pit-summary.sh ratchet/mutation.tsv "${pit_modules[@]}") || {
    echo "EXECUTED_FAIL  1b pit floor  (ratchet/mutation.tsv)"
    status=1
  }
else
  skipped "1b pit" "no class-C engine module among $modules"
fi

# 2: the whole reactor compiles; Spotless, enforcer, licences
step "2 compile-all" ".logs/${ticket}-compile-all.log" \
  ./mvnw -B -q -DskipTests verify

# 2b: every tracked file checks out byte-identical on Windows (.gitattributes, #49). Reads the index: stage new files.
step "2b lf checkout" ".logs/${ticket}-lf-checkout.log" \
  scripts/check-lf-checkout.sh

# 2c: the trailers of every commit the ticket adds, merges of the integration tip included (#84 merged with
# six without them). Needs [integration-ref]; without it the range is unknown.
if [ -n "$base" ]; then
  step "2c trailers" ".logs/${ticket}-trailers.log" \
    scripts/check-trailers.sh "$base..HEAD"
else
  skipped "2c trailers" "no integration-ref given"
fi

# 3: the build writes contract/openapi.json; it must equal the committed one. Cheap, so it always runs.
step "3 contract diff" ".logs/${ticket}-contract.log" \
  git diff --exit-code contract/

# 4: ui/
ui_changed=true
if [ -n "$base" ]; then
  if git diff --quiet "$base"...HEAD -- ui contract && git diff --quiet HEAD -- ui contract; then
    ui_changed=false
  fi
fi
if [ "$ui_changed" = true ] && [ -f ui/package.json ]; then
  if [ ! -d ui/node_modules ]; then
    step "4 ui npm ci" "ui/.logs/${ticket}-npm-ci.log" bash -c 'cd ui && npm ci'
  fi
  for check in licences typecheck lint test test:browser e2e; do
    step "4 ui $check" "ui/.logs/${ticket}-${check/:/-}.log" bash -c "cd ui && npm run $check"
  done
  step "4 ui gen:types diff" "ui/.logs/${ticket}-gen-types.log" \
    bash -c 'cd ui && npm run gen:types && git diff --exit-code src/generated'
else
  skipped "4 ui" "ui/ and contract/ unchanged, or no ui/"
fi

# 5: the ratchet
step "5 ratchet" ".logs/${ticket}-ratchet.log" \
  java scripts/ratchet.java check

[ -n "$pit_summary" ] && echo "$pit_summary"
exit "$status"
