#!/usr/bin/env bash
# Script test for the top-level gate logs (#125): scripts/gate-ticket.sh and scripts/gate-spec.sh, started by absolute
# path from another directory, write .logs/<ticket>-gate.log and .logs/<spec>-spec-gate.log under their own
# repository root, equal to the console output. Scratch root with stubbed mvnw, java, git and helper scripts: the real
# gate is never run.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

root="$tmp/root"
elsewhere="$tmp/elsewhere"
mkdir -p "$root/scripts" "$root/ratchet" "$root/bin" "$elsewhere"
cp "$here/../gate-ticket.sh" "$here/../gate-spec.sh" "$root/scripts/"
ok='#!/usr/bin/env bash\nexit 0\n'
printf "$ok" > "$root/bin/java"
printf "$ok" > "$root/bin/git"
printf "$ok" > "$root/scripts/check-lf-checkout.sh"
printf "$ok" > "$root/scripts/check-trailers.sh"
# MVNW_EXIT sets the exit code of every ./mvnw step
printf '#!/usr/bin/env bash\nexit "${MVNW_EXIT:-0}"\n' > "$root/mvnw"
# the summary call prints a warning to stderr, which the log must hold too, then one line per module; the --run call
# prints nothing
printf '#!/usr/bin/env bash\n[ "$1" = --run ] && exit 0\nshift\necho "pit-summary: warning" >&2\nfor m in "$@"; do echo "$m 9/9 survivors 0 floor 0 ok"; done\n' \
  > "$root/scripts/pit-summary.sh"
# a slow tee: it writes only after its input closed and a pause, so a gate that returns before tee finished leaves
# an incomplete log and console file behind
printf '#!/usr/bin/env bash\nin=$(cat)\nsleep 0.2\nprintf "%%s\\n" "$in" | %s "$@"\n' "$(command -v tee)" > "$root/bin/tee"
chmod +x "$root/mvnw" "$root/bin/"* "$root/scripts/"*
printf 'engine/xdm\t0\t9\n' > "$root/ratchet/mutation.tsv"

# gate <script> <args...>: run it by absolute path from $elsewhere; console output (stdout and stderr) in $out, read
# from a file as soon as the script returned (a pipe would wait for tee), exit code in $code
gate() {
  rm -rf "$root/.logs" "$elsewhere/.logs"
  (cd "$elsewhere" && PATH="$root/bin:$PATH" "$root/scripts/$1" "${@:2}" > "$tmp/console" 2>&1)
  code=$?
  out=$(cat "$tmp/console")
}
evidence() { grep -cE '^(VERIFIED_PASS|EXECUTED_FAIL|NOT_RUN) ' <<< "$out"; }

gate gate-ticket.sh 7 engine/xdm
check "ticket: exit" "$code" 0
check "ticket: evidence lines" "$(evidence)" 8
check "ticket: pit summary" "$(tail -n1 <<< "$out")" "engine/xdm 9/9 survivors 0 floor 0 ok"
check "ticket: log equals console" "$(cat "$root/.logs/7-gate.log" 2>&1)" "$out"
check "ticket: no log in the current directory" "$(ls -A "$elsewhere")" ""
MVNW_EXIT=1 gate gate-ticket.sh 7 engine/xdm
check "ticket failing: exit" "$code" 1
check "ticket failing: log equals console" "$(cat "$root/.logs/7-gate.log" 2>&1)" "$out"

gate gate-spec.sh 8 engine/xdm
check "spec: exit" "$code" 0
check "spec: evidence lines" "$(evidence)" 7
check "spec: pit summary" "$(grep -c '^engine/xdm 9/9 survivors 0 floor 0 ok$' <<< "$out")" 1
check "spec: log equals console" "$(cat "$root/.logs/8-spec-gate.log" 2>&1)" "$out"
check "spec: no log in the current directory" "$(ls -A "$elsewhere")" ""
MVNW_EXIT=1 gate gate-spec.sh 8 engine/xdm
check "spec failing: exit" "$code" 1
check "spec failing: log equals console" "$(cat "$root/.logs/8-spec-gate.log" 2>&1)" "$out"

exit "$fails"
