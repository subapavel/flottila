#!/usr/bin/env bash
# Script test for the moved coverage modules of scripts/ratchet.java (#161, #152), in a scratch root:
# a coverage.tsv row under ui/src/editor/store follows the module to ui/src/studio/store.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
java_src=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# summary <lines covered> <lines total> <branches covered> <branches total>, for the new path
summary() {
  mkdir -p "$tmp/ui/coverage"
  cat > "$tmp/ui/coverage/coverage-summary.json" <<EOF
{"total":{},"$tmp/ui/src/studio/store/a.ts":{"lines":{"total":$2,"covered":$1},"branches":{"total":$4,"covered":$3}}}
EOF
}
run() { (cd "$tmp" && java "$java_src" "$@" 2>&1); }
mkdir -p "$tmp/ratchet/oracle" "$tmp/engine" "$tmp/ui/src/studio/store"
: > "$tmp/ratchet/oracle/corpus.txt"; : > "$tmp/ratchet/oracle/differential.txt"; : > "$tmp/ratchet/mutation.tsv"
echo "export const a = 1;" > "$tmp/ui/src/studio/store/a.ts"

# at the baseline: carried, not failed
printf 'ui/src/editor/store\t90\t80\n' > "$tmp/ratchet/coverage.tsv"
summary 90 100 80 100
out=$(run check); rc=$?
check "check at baseline: exit" "$rc" 0
check "check at baseline: moved" "$(echo "$out" | grep -c 'MOVED coverage ui/src/editor/store -> ui/src/studio/store')" 1
check "check leaves the file" "$(cat "$tmp/ratchet/coverage.tsv")" "ui/src/editor/store	90	80"

# above the baseline: regen writes the new name with the higher values
summary 100 100 100 100
out=$(run regen --all); rc=$?
check "regen: exit" "$rc" 0
check "regen: moved" "$(echo "$out" | grep -c 'MOVED coverage ui/src/editor/store -> ui/src/studio/store')" 1
check "regen: no NEW row" "$(echo "$out" | grep -c 'NEW  coverage')" 0
check "regen: row under the new name" "$(cat "$tmp/ratchet/coverage.tsv")" "ui/src/studio/store	100	100"

# below the old baseline: a drop, still failed, row kept
printf 'ui/src/editor/store\t100\t100\n' > "$tmp/ratchet/coverage.tsv"
summary 90 100 100 100
out=$(run check); rc=$?
check "drop: exit" "$rc" 1
check "drop: message" "$(echo "$out" | grep -c 'coverage ui/src/studio/store lines')" 1
exit $fails
