#!/usr/bin/env bash
# Script test for the moved coverage modules of scripts/ratchet.java, in a scratch copy of the repository: the
# mapping's root is mapping/, the UI is ui/ beside it. A coverage.tsv row of the mapping's old editor follows its
# code into ui/ in one step: ui/src/model to ../ui/src/mapping/model, and ui/src/studio/store, as well as its
# older name ui/src/editor/store, to ../ui/src/mapping/studio/store.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
java_src=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

root=$tmp/mapping
ui=$tmp/ui
# summary <lines covered> <lines total> <branches covered> <branches total>: the same figures for a file of each
# directory, at the UI's own paths
summary() {
  mkdir -p "$ui/coverage"
  local f='{"lines":{"total":'$2',"covered":'$1'},"branches":{"total":'$4',"covered":'$3'}}'
  cat > "$ui/coverage/coverage-summary.json" <<JSON
{"total":{},"$ui/src/mapping/studio/store/a.ts":$f,"$ui/src/mapping/model/b.ts":$f}
JSON
}
run() { (cd "$root" && java "$java_src" "$@" 2>&1); }
mkdir -p "$root/ratchet/oracle" "$root/engine" "$ui/src/mapping/studio/store" "$ui/src/mapping/model"
: > "$root/ratchet/oracle/corpus.txt"; : > "$root/ratchet/oracle/differential.txt"; : > "$root/ratchet/mutation.tsv"
: > "$root/ratchet/bench.tsv"
echo "export const a = 1;" > "$ui/src/mapping/studio/store/a.ts"
echo "export const b = 1;" > "$ui/src/mapping/model/b.ts"

# at the baseline: carried, not failed, and the oldest name moves in one step
printf 'ui/src/editor/store\t90\t80\nui/src/model\t90\t80\n' > "$root/ratchet/coverage.tsv"
summary 90 100 80 100
out=$(run check); rc=$?
check "check at baseline: exit" "$rc" 0
check "check at baseline: the oldest name moved" \
  "$(echo "$out" | grep -c '^MOVED coverage ui/src/editor/store -> ../ui/src/mapping/studio/store$')" 1
check "check at baseline: the model moved" "$(echo "$out" | grep -c '^MOVED coverage ui/src/model -> ../ui/src/mapping/model$')" 1
check "check at baseline: nothing NOT_RUN" "$(echo "$out" | grep -c 'NOT_RUN')" 0
check "check leaves the file" "$(cat "$root/ratchet/coverage.tsv")" "$(printf 'ui/src/editor/store\t90\t80\nui/src/model\t90\t80')"

# above the baseline: regen writes the new names with the higher values
printf 'ui/src/studio/store\t90\t80\nui/src/model\t90\t80\n' > "$root/ratchet/coverage.tsv"
summary 100 100 100 100
out=$(run regen --all); rc=$?
check "regen: exit" "$rc" 0
check "regen: the store moved" "$(echo "$out" | grep -c '^MOVED coverage ui/src/studio/store -> ../ui/src/mapping/studio/store$')" 1
check "regen: no NEW row" "$(echo "$out" | grep -c 'NEW  coverage')" 0
check "regen: rows under the new names" "$(cat "$root/ratchet/coverage.tsv")" \
  "$(printf '../ui/src/mapping/model\t100\t100\n../ui/src/mapping/studio/store\t100\t100')"

# below the old baseline: a drop, still failed
printf 'ui/src/studio/store\t100\t100\n' > "$root/ratchet/coverage.tsv"
summary 90 100 100 100
out=$(run check); rc=$?
check "drop: exit" "$rc" 1
check "drop: message" "$(echo "$out" | grep -c 'coverage ../ui/src/mapping/studio/store lines')" 1

# no UI report against a baseline: a UI row is NOT_RUN, as the backend job builds no UI report
rm -rf "$ui/coverage"
printf '../ui/src/mapping/model\t100\t100\n' > "$root/ratchet/coverage.tsv"
cp -R "$root/ratchet" "$tmp/baseline"
out=$(run check --baseline "$tmp/baseline"); rc=$?
check "baseline without a UI report: exit" "$rc" 0
check "baseline without a UI report: NOT_RUN" "$(echo "$out" | grep -c '^NOT_RUN coverage ../ui/src/mapping/model$')" 1
check "baseline without a UI report: no JaCoCo failure" "$(echo "$out" | grep -c 'no JaCoCo report')" 0
exit $fails
