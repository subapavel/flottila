#!/usr/bin/env bash
# The ratchet check on GitHub (MAP-12b, D-164 (2), (3); the contract is agent/packets/MAP-12b.md §2 and §3).
#
#   scripts/ratchet-ci.sh push      the backend job's last step: scripts/ratchet-baseline.sh finds the baseline, then
#                                   `java scripts/ratchet.java check --baseline <its tree>`; when the lookup fails,
#                                   plain `java scripts/ratchet.java check` still holds the build against the rows
#   scripts/ratchet-ci.sh nightly   after the nightly job's PIT step: scripts/pit-summary.sh holds the fresh PIT
#                                   reports against ratchet/mutation.tsv (its rows and every module with a report)
#
# The date in scripts/ratchet-enforced-from (one line, YYYY-MM-DD, UTC) is read first, in both modes. Before that day
# a failed lookup, a failing check and a mutation drop are reported (a warning annotation, exit 0); from that day each
# is an error annotation and exit 1. A RISE line of the check is repeated as a warning on any day. Exit 2 on any day,
# each with its own error annotation. In this order, before the lookup, the check or the summary is called: the
# mapping's root cannot be entered; the date file, RATCHET_TODAY or the clock is not one ISO date (or the two dates
# cannot be compared); in the nightly mode ratchet/mutation.tsv is missing or no readable regular file; the temporary
# directory cannot be made. After them: the check wrote no verdict (its last line and its exit code do not agree on
# one), or the summary exited with neither 0 nor 1. The usage, on stderr, comes before all of these. GNU date
# (`date -u -d`) is assumed and not checked. Annotations go to stdout; the lookup's, the check's and the summary's
# lines pass through, and with GITHUB_STEP_SUMMARY set they are appended to it in one fenced block. Runs from any
# directory: it changes to the mapping's root. RATCHET_TODAY, when set and not empty, replaces the clock's date
# (`date -u +%F`), for the script test only; it must be one real ISO date.
set -uo pipefail

usage() {
  echo "usage: scripts/ratchet-ci.sh push | nightly" >&2
  exit 2
}
[ $# -eq 1 ] || usage
mode=$1
case $mode in
  push | nightly) ;;
  *) usage ;;
esac
cd "$(dirname "$0")/.." || { echo "::error::ratchet: cannot change to the mapping's root"; exit 2; }

iso() { [[ $1 =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && [ "$(date -u -d "$1" +%F 2> /dev/null)" = "$1" ]; }

# ---- the date file: exactly one line, one ISO date ----

datefile=scripts/ratchet-enforced-from
bad_date_file() {
  echo "::error::ratchet: the date file $datefile $1"
  exit 2
}
[ -f "$datefile" ] || bad_date_file "is missing"
content=$(cat -- "$datefile"; printf x)   # the x keeps the file's last newline
content=${content%x}
date=${content%$'\n'}
if [ "$content" != "$date"$'\n' ] || ! iso "$date"; then
  bad_date_file "does not hold exactly one line, one ISO date YYYY-MM-DD"
fi
if [ -n "${RATCHET_TODAY:-}" ]; then
  today=$RATCHET_TODAY
  iso "$today" || { echo "::error::ratchet: RATCHET_TODAY is not an ISO date"; exit 2; }
else
  today=$(date -u +%F)
  iso "$today" || { echo "::error::ratchet: the clock's date is not an ISO date"; exit 2; }
fi
# fail-closed: a comparison that cannot be made (test's exit 2) is exit 2, never "not enforced"
[ "${today//-/}" -ge "${date//-/}" ]
case $? in
  0) enforced=yes ;;
  1) enforced=no ;;
  *) echo "::error::ratchet: today's date cannot be compared with the date file's"; exit 2 ;;
esac

# the nightly's rows: a file that cannot be read would leave every module without a floor ("floor none ok")
if [ "$mode" = nightly ] && ! { [ -f ratchet/mutation.tsv ] && [ -r ratchet/mutation.tsv ]; }; then
  echo "::error::ratchet: ratchet/mutation.tsv is missing"
  exit 2
fi

work=$(mktemp -d) || { echo "::error::ratchet: cannot create a temporary directory"; exit 2; }
trap 'rm -rf "$work"' EXIT

# summary <file>...: the files' lines appended to the step summary in one fenced block
summary() {
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  { echo '```'; awk 1 "$@"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
}
# lines <file>: its lines, the last one also when it has no newline
lines() { awk 1 "$1"; }

# ---- nightly: the fresh mutation result against ratchet/mutation.tsv ----

if [ "$mode" = nightly ]; then
  # the rows' modules and every module directory (one or two levels deep) with a report, sorted, each once
  mapfile -t modules < <(
    {
      awk -F'\t' '$1 != "" {print $1}' ratchet/mutation.tsv
      for xml in */target/pit-reports/mutations.xml */*/target/pit-reports/mutations.xml; do
        if [ -f "$xml" ]; then printf '%s\n' "${xml%/target/pit-reports/mutations.xml}"; fi
      done
    } | LC_ALL=C sort -u
  )
  PIT_ROOT=$PWD scripts/pit-summary.sh ratchet/mutation.tsv "${modules[@]}" | tee "$work/summary.out"
  rc=${PIPESTATUS[0]}
  summary "$work/summary.out"
  if [ "$rc" != 0 ] && [ "$rc" != 1 ]; then
    echo "::error::ratchet: the mutation summary wrote no verdict (exit $rc)"
    exit 2
  fi
  while IFS= read -r line; do
    case $line in
      *" DROP")
        if [ "$enforced" = yes ]; then echo "::error::mutation ratchet failed: $line"
        else echo "::warning::mutation ratchet would fail from $date: $line"; fi ;;
    esac
  done < <(lines "$work/summary.out")
  if [ "$enforced" = yes ] && [ "$rc" = 1 ]; then exit 1; fi
  exit 0
fi

# ---- push: the lookup, then the check ----

scripts/ratchet-baseline.sh "$work/baseline" | tee "$work/lookup.out"
lookup_rc=${PIPESTATUS[0]}
args=(check)
if [ "$lookup_rc" = 0 ]; then
  lookup_failed=no
  args+=(--baseline "$work/baseline")
else
  lookup_failed=yes
  reason=$(sed -n 's/^LOOKUP FAILED: //p' "$work/lookup.out" | head -n 1)
  [ -n "$reason" ] || reason="scripts/ratchet-baseline.sh exited $lookup_rc"
  if [ "$enforced" = yes ]; then echo "::error::ratchet: LOOKUP FAILED - $reason"
  else echo "::warning::ratchet: LOOKUP FAILED - $reason (reported until $date)"; fi
fi
java scripts/ratchet.java "${args[@]}" | tee "$work/check.out"
check_rc=${PIPESTATUS[0]}
summary "$work/lookup.out" "$work/check.out"

# the verdict is the check's last line and its exit code together
verdict=$(lines "$work/check.out" | tail -n 1)
if [ "$check_rc" = 0 ] && [ "$verdict" = "RATCHET CHECK OK" ]; then
  check_failed=no
elif [ "$check_rc" = 1 ] && [[ $verdict =~ ^RATCHET\ FAIL:\ [0-9]+\ problem\(s\)$ ]]; then
  check_failed=yes
else
  echo "::error::ratchet: the check wrote no verdict (exit $check_rc)"
  exit 2
fi
while IFS= read -r line; do
  case $line in
    "RISE "*) echo "::warning::ratchet: $line - regenerate ratchet/" ;;
  esac
done < <(lines "$work/check.out")
if [ "$check_failed" = yes ]; then
  if [ "$enforced" = yes ]; then echo "::error::ratchet failed: $verdict"
  else echo "::warning::ratchet would fail from $date: $verdict"; fi
fi
if [ "$enforced" = yes ] && { [ "$lookup_failed" = yes ] || [ "$check_failed" = yes ]; }; then exit 1; fi
exit 0
