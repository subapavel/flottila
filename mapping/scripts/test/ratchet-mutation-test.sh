#!/usr/bin/env bash
# Script test for the mutation rows of scripts/ratchet.java (#87), in a scratch root.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
java_src=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# report <module> <killed> <survived> <non-viable>
report() {
  local d=$tmp/$1/target/pit-reports
  mkdir -p "$d"
  {
    echo "<mutations>"
    for ((i = 0; i < $2; i++)); do echo "<mutation detected='true' status='KILLED'><x/></mutation>"; done
    for ((i = 0; i < $3; i++)); do echo "<mutation detected='false' status='SURVIVED'><x/></mutation>"; done
    for ((i = 0; i < $4; i++)); do echo "<mutation detected='false' status='NON_VIABLE'><x/></mutation>"; done
    echo "</mutations>"
  } > "$d/mutations.xml"
}
run() { (cd "$tmp" && java "$java_src" "$@" 2>&1); }
mkdir -p "$tmp/ratchet/oracle" "$tmp/engine"
: > "$tmp/ratchet/oracle/corpus.txt"; : > "$tmp/ratchet/oracle/differential.txt"; : > "$tmp/ratchet/coverage.tsv"

# new format: 10 survivors listed
printf 'engine/mapping\t10\t454\n' > "$tmp/ratchet/mutation.tsv"
report engine/mapping 443 11 3
out=$(run check); rc=$?
check "one more survivor: exit" "$rc" 1
check "one more survivor: message" "$(echo "$out" | grep -c 'mutation engine/mapping: 11 surviving mutants')" 1
report engine/mapping 444 10 0
out=$(run check); rc=$?
check "same survivors: exit" "$rc" 0
report engine/mapping 445 9 0
out=$(run regen --all); rc=$?
check "fewer survivors: regen exit" "$rc" 0
check "fewer survivors: row" "$(cat "$tmp/ratchet/mutation.tsv")" "engine/mapping	9	454"
report engine/mapping 440 14 0
out=$(run regen --all); rc=$?
check "regen refuses a rise in survivors: exit" "$rc" 1
check "regen refuses a rise in survivors: row kept" "$(cat "$tmp/ratchet/mutation.tsv")" "engine/mapping	9	454"

# old format: whole percent, compared as before and migrated by regen
printf 'engine/mapping\t97\n' > "$tmp/ratchet/mutation.tsv"
report engine/mapping 443 11 0   # 97.6 % -> 97
out=$(run check); rc=$?
check "old row at floor: exit" "$rc" 0
report engine/mapping 430 24 0   # 94 %
out=$(run check); rc=$?
check "old row below floor: exit" "$rc" 1
report engine/mapping 443 11 0
out=$(run regen --all); rc=$?
check "old row regen: exit" "$rc" 0
check "old row regen: migrated" "$(cat "$tmp/ratchet/mutation.tsv")" "engine/mapping	11	454"
exit $fails
