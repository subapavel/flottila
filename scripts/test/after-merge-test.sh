#!/usr/bin/env bash
# Script test for scripts/after-merge.sh (#60, F-29, ADR-0020 amendment for #60). A throwaway root holds copies of
# after-merge.sh and ratchet.java and a stub mvnw that logs its arguments; STUB_FAIL=<rc> makes the stub exit <rc>.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in $3:"; sed 's/^/     /' "$3"; fails=1; fi; }
hasnt() { if grep -q -- "$2" "$3"; then echo "FAIL $1: '$2' found in $3"; fails=1; else echo "ok   $1"; fi; }

T=$'\t'
repo=$tmp/repo
mkdir -p "$repo/scripts" "$repo/ratchet/oracle" "$repo/oracle/target/oracle-reports"
cp "$here/../after-merge.sh" "$here/../ratchet.java" "$repo/scripts/"
printf '%s\n' '#!/usr/bin/env bash' 'echo "mvnw $*" >> "$STUB_CALLS"' 'exit "${STUB_FAIL:-0}"' > "$repo/mvnw"
chmod +x "$repo/mvnw" "$repo/scripts/after-merge.sh"
export STUB_CALLS=$tmp/calls
unset STUB_FAIL
run() { : > "$STUB_CALLS"; (cd "$tmp" && "$repo/scripts/after-merge.sh" "$@" > "$tmp/out" 2>&1; echo $?); }
report() { printf '%b' "$1" > "$repo/oracle/target/oracle-reports/compile-conservative.tsv"; }

check "usage" "$(run)" 2

report "x${T}PASS${T}${T}d1\ny${T}FAIL${T}left out: the conservative run ended with BUDGET STEPS${T}d2\n"
check "green" "$(run 60)" 0
has "verify runs the compile-mode oracle" "mvnw -B -Pbench,compile-oracle verify" "$STUB_CALLS"
check "regen --oracles wrote the conservative line of the passing case" "$(printf 'x\td1\tconservative\n' | cmp - "$repo/ratchet/oracle/compile.txt"; echo $?)" 0
has "regen's summary is shown" "compile: +1 -0" "$tmp/out"
order=$(grep -o '== [a-z]*' "$tmp/out" | tr '\n' ' ')
check "verify before regen" "$order" "== verify == regen "
check "one log per step" "$(ls "$repo/.logs" | tr '\n' ' ')" "60-after-merge-regen.log 60-after-merge-verify.log "

rm "$repo/ratchet/oracle/compile.txt"
export STUB_FAIL=7
check "a failing verify ends with its exit code" "$(run 60)" 7
has "the failing step is named" "step verify failed" "$tmp/out"
hasnt "regen does not run after a failing verify" "== regen" "$tmp/out"
check "nothing was written" "$(test -e "$repo/ratchet/oracle/compile.txt"; echo $?)" 1
unset STUB_FAIL

printf 'x\td1\tconservative\n' > "$repo/ratchet/oracle/compile.txt"
report "x${T}PASS${T}${T}d1\nz${T}FAIL${T}disagree: stdout differs at byte 0${T}d3\n"
check "a Disagreement stops the run (F-29)" "$(run 60)" 1
has "the Disagreement is shown" "compile: z conservative disagrees" "$tmp/out"
has "the failing step is regen" "step regen failed" "$tmp/out"
check "compile.txt is left as it was" "$(printf 'x\td1\tconservative\n' | cmp - "$repo/ratchet/oracle/compile.txt"; echo $?)" 0

exit $fails
