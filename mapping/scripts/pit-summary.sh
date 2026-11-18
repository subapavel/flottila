#!/usr/bin/env bash
# PIT summary per module against the ratchet floor (#67, ADR-0019). One line per module:
#   <module> <killed>/<generated> survivors <S> floor <N> ok|DROP
# Survivors = generated - killed, generated without NON_VIABLE, the same count as scripts/ratchet.java (#87).
# One more survivor than the ratchet row (`module TAB survivors TAB generated`) is a DROP.
# A row of the old format (`module TAB score %`, until the verifier's `regen --all` migrates it) is compared as
# before: `<module> <killed>/<generated> <score>% floor <N>% ok|DROP`, score rounded down.
# Exit 1 if any module is below its floor or has no report. A module without a floor row is only reported.
# After a module's own line, and only when there is something to say (MAP-12b; neither changes the exit code):
#   <module> timed-out <T> of <killed> killed      TIMED_OUT mutants, counted as killed (machine-dependent, F-16)
#   <module> generated <G> below its row's <R>     the row's generated count is higher (a PIT exclusion shows here)
#
#   scripts/pit-summary.sh <mutation.tsv> <module>...
#   scripts/pit-summary.sh --run <log> <module>...    run PIT one module at a time into <log>, each section
#                                                      preceded by `=== PIT <module> ===`; exit 1 if any failed
#   (from the mapping's root; PIT_MVN overrides ../mvnw, the repository's wrapper, for the script test)
#
# PIT_ROOT (default: mapping/, the directory above this script) is where <module>/target/pit-reports/mutations.xml is looked up.
set -uo pipefail
if [ "${1:-}" = "--run" ]; then
  if [ $# -lt 3 ]; then
    echo "usage: scripts/pit-summary.sh --run <log> <module>..." >&2
    exit 2
  fi
  log=$2
  shift 2
  : > "$log"
  rc=0
  for m in "$@"; do
    echo "=== PIT $m ===" >> "$log"
    "${PIT_MVN:-../mvnw}" -B -pl "$m" -am test-compile org.pitest:pitest-maven:mutationCoverage >> "$log" 2>&1 || rc=1
  done
  exit "$rc"
fi
if [ $# -lt 2 ]; then
  echo "usage: scripts/pit-summary.sh <mutation.tsv> <module>..." >&2
  exit 2
fi
tsv=$1
shift
root=${PIT_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
rc=0
for m in "$@"; do
  xml=$root/$m/target/pit-reports/mutations.xml
  if [ ! -f "$xml" ]; then
    echo "$m no PIT report DROP"
    rc=1
    continue
  fi
  all=$(grep -c '<mutation ' "$xml")
  nonviable=$(grep '<mutation ' "$xml" | grep -c "status=['\"]NON_VIABLE['\"]")
  killed=$(grep '<mutation ' "$xml" | grep -c "detected=['\"]true['\"]")
  generated=$((all - nonviable))
  survivors=$((generated - killed))
  if [ "$generated" -eq 0 ]; then score=100; else score=$((killed * 100 / generated)); fi
  read -r floor floor_generated < <(awk -F'\t' -v m="$m" '$1 == m { print $2, $3 }' "$tsv")
  if [ -z "${floor:-}" ]; then
    echo "$m $killed/$generated survivors $survivors floor none ok"
  elif [ -z "${floor_generated:-}" ]; then
    # old format: whole-percent score
    if [ "$score" -lt "$floor" ]; then
      echo "$m $killed/$generated ${score}% floor ${floor}% DROP"
      rc=1
    else
      echo "$m $killed/$generated ${score}% floor ${floor}% ok"
    fi
  elif [ "$survivors" -gt "$floor" ]; then
    echo "$m $killed/$generated survivors $survivors floor $floor DROP"
    rc=1
  else
    echo "$m $killed/$generated survivors $survivors floor $floor ok"
  fi
  # MAP-12b: two lines after the module's own, each only when it has something to say; neither changes the exit
  timed=$(grep '<mutation ' "$xml" | grep "detected=['\"]true['\"]" | grep -c "status=['\"]TIMED_OUT['\"]")
  if [ "$timed" -gt 0 ]; then
    echo "$m timed-out $timed of $killed killed"
  fi
  if [ -n "${floor_generated:-}" ] && [ "$floor_generated" -gt "$generated" ]; then
    echo "$m generated $generated below its row's $floor_generated"
  fi
done
exit "$rc"
