#!/usr/bin/env bash
# Script test for the bench judgement of scripts/ratchet.java (#184, ADR-0021 "The bench ratchet"), in a scratch
# root, on canned JMH 1.37 JSON (one rawData array per fork, gc.alloc.rate.norm in B/op) and a canned tree result.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
java_src=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
run() { (cd "$tmp" && java "$java_src" "$@" 2>&1); }
lines() { echo "$1" | grep -cF -- "$2"; }

B=io.flottila.mapping.bench
M=claude-cloud-4vcpu
# bm <benchmark> <unit> <rawData> [<alloc rawData> [<params object>]]: one JMH result; primaryMetric.score is 999
# on purpose, the judgement reads rawData
bm() {
  local secondary="" params=""
  [ -n "${4:-}" ] && secondary="\"gc.alloc.rate.norm\":{\"score\":999,\"scoreError\":1,\"scoreUnit\":\"B/op\",\"rawData\":$4}"
  [ -n "${5:-}" ] && params="\"params\":$5,"
  printf '{"jmhVersion":"1.37","benchmark":"%s","mode":"avgt","threads":1,"forks":3,%s"primaryMetric":{"score":999,"scoreError":1,"scoreUnit":"%s","rawData":%s},"secondaryMetrics":{%s}}' \
    "$1" "$params" "$2" "$3" "$secondary"
}
# jmh <file> <result>...: a JMH JSON file, a top-level array
jmh() { local f=$1; shift; local IFS=,; echo "[$*]" > "$tmp/$f"; }
tree() { printf '{"scenario":"ui/tree-20128/scroll","score":%s,"unit":"ms"}\n' "$2" > "$tmp/$1"; }
rows() { printf '%s\n' "$@" > "$tmp/ratchet/bench.tsv"; }

mkdir -p "$tmp/ratchet/oracle" "$tmp/engine"
: > "$tmp/ratchet/oracle/corpus.txt"; : > "$tmp/ratchet/oracle/differential.txt"
: > "$tmp/ratchet/mutation.tsv"; : > "$tmp/ratchet/coverage.tsv"

ten_mib='{"inputBytes":"10485760"}'
base_rows=(
  "orders/small/latency	$M	100	us/op"
  "orders/small/alloc	$M	8000	B/op"
  "orders/large/throughput	$M	25	MB/s"
  "orders/large/alloc	$M	50000000	B/op"
  "compile/orders	$M	2	ms/op"
  "ui/tree-20128/scroll	$M	22	ms")

# pass: medians of the per-fork means within the tolerance; the heap fork only completes; bench writes nothing
rows "${base_rows[@]}"
jmh pass.json \
  "$(bm $B.OrdersBench.smallLatency us/op '[[100,110],[120,120],[90,90]]' '[[8500],[8400],[8600]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[400],[420],[380]]' '[[5.0E7],[5.0E7],[5.0E7]]' "$ten_mib")" \
  "$(bm $B.OrdersBench.largeHeap ms/op '[[900]]')" \
  "$(bm $B.OrdersBench.compile ms/op '[[2.0],[2.2]]' '[[100000],[100000]]')"
tree tree.json 24.5
out=$(run bench pass.json tree.json); rc=$?
check "pass: exit" "$rc" 0
check "pass: median of fork means" "$(lines "$out" 'ok   orders/small/latency 105 us/op (baseline 100)')" 1
check "pass: alloc from gc.alloc.rate.norm" "$(lines "$out" 'ok   orders/small/alloc 8500 B/op (baseline 8000)')" 1
check "pass: MB/s from inputBytes and avgt" "$(lines "$out" 'ok   orders/large/throughput 25 MB/s (baseline 25)')" 1
check "pass: median of two forks" "$(lines "$out" 'ok   compile/orders 2.1 ms/op (baseline 2)')" 1
check "pass: tree result" "$(lines "$out" 'ok   ui/tree-20128/scroll 24.5 ms (baseline 22)')" 1
check "pass: heap completes" "$(lines "$out" 'ok   orders/large/heap completed')" 1
check "pass: no re-run" "$(lines "$out" 'RERUN')" 0
check "pass: verdict" "$(lines "$out" 'RATCHET BENCH OK')" 1
check "pass: bench writes nothing" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' "${base_rows[@]}")"

# regression (lower is better beyond 20 %, B/op beyond 10 %, MB/s higher is better), then a pass on the re-run
rows "${base_rows[@]/	25	MB\/s/	32	MB/s}"
jmh first.json \
  "$(bm $B.OrdersBench.smallLatency us/op '[[130],[130],[130]]' '[[9000],[9000],[9000]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[400],[400],[400]]' '[[5.0E7],[5.0E7],[5.0E7]]' "$ten_mib")" \
  "$(bm $B.OrdersBench.compile ms/op '[[2.0],[2.0],[2.0]]')"
out=$(run bench first.json tree.json); rc=$?
check "regression: exit" "$rc" 1
check "regression: latency" "$(lines "$out" 'FAIL bench orders/small/latency: 130 us/op is worse than the baseline 100 us/op beyond 20 %')" 1
check "regression: alloc" "$(lines "$out" 'FAIL bench orders/small/alloc: 9000 B/op is worse than the baseline 8000 B/op beyond 10 %')" 1
check "regression: throughput" "$(lines "$out" 'FAIL bench orders/large/throughput: 25 MB/s is worse than the baseline 32 MB/s beyond 20 %')" 1
check "regression: re-run regex" "$(echo "$out" | grep '^RERUN ')" \
  'RERUN ^(?:io\.flottila\.mapping\.bench\.OrdersBench\.largeThroughput|io\.flottila\.mapping\.bench\.OrdersBench\.smallLatency)$'
jmh second.json \
  "$(bm $B.OrdersBench.smallLatency us/op '[[110],[110],[110]]' '[[8100],[8100],[8100]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[330],[330],[330]]' '[[5.0E7],[5.0E7],[5.0E7]]' "$ten_mib")"
out=$(run bench first.json second.json tree.json); rc=$?
check "re-run: exit" "$rc" 0
check "re-run: the later file wins" "$(lines "$out" 'ok   orders/small/latency 110 us/op (baseline 100)')" 1
check "re-run: no second re-run" "$(lines "$out" 'RERUN')" 0

# a regressed tree result fails, but it is no JMH benchmark to re-run
rows "${base_rows[@]}"
tree tree-worse.json 30
out=$(run bench pass.json tree-worse.json); rc=$?
check "tree regression: exit" "$rc" 1
check "tree regression: fail" "$(lines "$out" 'FAIL bench ui/tree-20128/scroll: 30 ms is worse than the baseline 22 ms beyond 20 %')" 1
check "tree regression: no re-run" "$(lines "$out" 'RERUN')" 0
check "tree regression: verdict" "$(lines "$out" 'RATCHET FAIL: 1 problem(s)')" 1
rows "${base_rows[@]/	25	MB\/s/	32	MB/s}"

# regression twice: the re-run is still worse
jmh again.json "$(bm $B.OrdersBench.smallLatency us/op '[[125],[125],[125]]' '[[8100],[8100],[8100]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[330],[330],[330]]' '[[5.0E7],[5.0E7],[5.0E7]]' "$ten_mib")"
out=$(run bench first.json again.json tree.json); rc=$?
check "regression twice: exit" "$rc" 1
check "regression twice: latency" "$(lines "$out" 'FAIL bench orders/small/latency: 125 us/op is worse')" 1
check "regression twice: verdict" "$(lines "$out" 'RATCHET FAIL: 1 problem(s)')" 1

# median of three (#193): a scenario in three or more files is judged on the median of them, in any file order (the
# three runs of 5a2515f in #189: 34.69, 22.34, 18.79 MB/s); in one or two files the later file wins (above)
rows
jmh run-1.json "$(bm $B.OrdersBench.largeThroughput ms/op '[[288.2675]]' '' "$ten_mib")" \
  "$(bm $B.OrdersBench.smallLatency us/op '[[600]]')"
jmh run-2.json "$(bm $B.OrdersBench.largeThroughput ms/op '[[447.6276]]' '' "$ten_mib")"
jmh run-3.json "$(bm $B.OrdersBench.largeThroughput ms/op '[[532.198]]' '' "$ten_mib")" \
  "$(bm $B.OrdersBench.smallLatency us/op '[[400]]')"
out=$(run bench run-1.json run-2.json run-3.json); rc=$?
check "median of three: exit" "$rc" 0
check "median of three: the median" "$(lines "$out" 'NEW  orders/large/throughput 22.34 MB/s')" 1
check "median of three: target met" "$(lines "$out" 'misses the target')" 0
check "median of three: two files, the later wins" "$(lines "$out" 'NEW  orders/small/latency 400 us/op')" 1
# the median in the first file (22.34, 34.69, 18.79) and in the last file (34.69, 18.79, 22.34): a judgement on one
# fixed file (the second) would give 34.69 and 18.79
out=$(run bench run-2.json run-1.json run-3.json); rc=$?
check "median in the first file: exit" "$rc" 0
check "median in the first file: the median" "$(lines "$out" 'NEW  orders/large/throughput 22.34 MB/s')" 1
out=$(run bench run-1.json run-3.json run-2.json); rc=$?
check "median in the last file: exit" "$rc" 0
check "median in the last file: the median" "$(lines "$out" 'NEW  orders/large/throughput 22.34 MB/s')" 1

# missed targets, no baselines: small latency <= 500 us/op, large throughput >= 20 MB/s, compile/orders <= 5 ms/op,
# tree < 33.3 ms; each boundary itself passes except the tree's
rows
jmh targets.json \
  "$(bm $B.OrdersBench.smallLatency us/op '[[501]]')" \
  "$(bm $B.WideBench.smallLatency us/op '[[500]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[501]]' '' "$ten_mib")" \
  "$(bm $B.WideBench.largeThroughput ms/op '[[500]]' '' "$ten_mib")" \
  "$(bm $B.OrdersBench.compile ms/op '[[5.01]]')" \
  "$(bm $B.WideBench.compile ms/op '[[50]]')"
tree tree-slow.json 33.3
out=$(run bench targets.json tree-slow.json); rc=$?
check "targets: exit" "$rc" 1
check "targets: latency" "$(lines "$out" 'FAIL bench orders/small/latency: 501 us/op misses the target <= 500 us/op')" 1
check "targets: throughput" "$(lines "$out" 'FAIL bench orders/large/throughput: 19.96 MB/s misses the target >= 20 MB/s')" 1
check "targets: compile" "$(lines "$out" 'FAIL bench compile/orders: 5.01 ms/op misses the target <= 5 ms/op')" 1
check "targets: tree" "$(lines "$out" 'FAIL bench ui/tree-20128/scroll: 33.3 ms misses the target < 33.3 ms')" 1
check "targets: only these four" "$(lines "$out" 'misses the target')" 4
check "targets: new scenarios" "$(lines "$out" 'NEW  wide/large/throughput 20 MB/s')" 1
# a missed target re-runs its JMH benchmark like a regression (#193); the tree result is no JMH benchmark
check "targets: re-run regex" "$(echo "$out" | grep '^RERUN ')" \
  'RERUN ^(?:io\.flottila\.mapping\.bench\.OrdersBench\.compile|io\.flottila\.mapping\.bench\.OrdersBench\.largeThroughput|io\.flottila\.mapping\.bench\.OrdersBench\.smallLatency)$'

# missing row: a bench.tsv row with no result fails (no tree file here), and so does another machine's row
rows "${base_rows[@]}" "wide/small/latency	$M	100	us/op" "contexts/small/latency	other-machine	100	us/op"
out=$(run bench pass.json); rc=$?
check "missing row: exit" "$rc" 1
check "missing row: wide" "$(lines "$out" 'FAIL bench wide/small/latency: no result')" 1
check "missing row: tree" "$(lines "$out" 'FAIL bench ui/tree-20128/scroll: no result')" 1
check "missing row: other machine" "$(lines "$out" "FAIL bench contexts/small/latency: machine label other-machine, this judgement is for $M")" 1
check "missing row: verdict" "$(lines "$out" 'RATCHET FAIL: 3 problem(s)')" 1

# unknown unit, a unit that differs from the row's or the target's, no inputBytes, no scenario name
rows "wide/small/latency	$M	100	ms/op"
jmh units.json \
  "$(bm $B.OrdersBench.smallLatency ns/op '[[100]]')" \
  "$(bm $B.OrdersBench.largeThroughput ns/op '[[100]]' '' "$ten_mib")" \
  "$(bm $B.ContextsBench.largeThroughput ms/op '[[100]]')" \
  "$(bm $B.WideBench.smallLatency us/op '[[100]]')" \
  "$(bm $B.OrdersBench.compile us/op '[[2000]]')" \
  "$(bm $B.OrdersBench.warmup us/op '[[1]]')" \
  "$(bm org.flottila.mapping.bench.OrdersBench.smallLatency us/op '[[1]]')"
printf '{"scenario":"ui/tree-20128/scroll","score":20,"unit":"fps"}\n' > "$tmp/tree-fps.json"
out=$(run bench units.json tree-fps.json); rc=$?
check "units: exit" "$rc" 1
check "units: unknown unit" "$(lines "$out" 'FAIL bench orders/small/latency: unknown unit ns/op')" 1
check "units: unknown tree unit" "$(lines "$out" 'FAIL bench ui/tree-20128/scroll: unknown unit fps')" 1
check "units: throughput needs a time per op" "$(lines "$out" "FAIL bench $B.OrdersBench.largeThroughput: unit ns/op is not a time per operation")" 1
check "units: no inputBytes" "$(lines "$out" "FAIL bench $B.ContextsBench.largeThroughput: no inputBytes param")" 1
check "units: row unit" "$(lines "$out" 'FAIL bench wide/small/latency: unit us/op, the baseline is in ms/op')" 1
check "units: target unit" "$(lines "$out" 'FAIL bench compile/orders: unit us/op, the target is in ms/op')" 1
check "units: no scenario name" "$(lines "$out" "FAIL bench $B.OrdersBench.warmup: no scenario name")" 1
check "units: other package" "$(lines "$out" 'FAIL bench org.flottila.mapping.bench.OrdersBench.smallLatency: no scenario name')" 1
check "units: verdict" "$(lines "$out" 'RATCHET FAIL: 8 problem(s)')" 1

# regen --all with the final results: a baseline moves only on a gain beyond the tolerance, new rows are written,
# the heap fork has no row
rows "${base_rows[@]}"
jmh gain.json \
  "$(bm $B.OrdersBench.smallLatency us/op '[[75],[75],[75]]' '[[7500],[7500],[7500]]')" \
  "$(bm $B.OrdersBench.largeThroughput ms/op '[[300],[300],[300]]' '[[5.0E7],[5.0E7],[5.0E7]]' "$ten_mib")" \
  "$(bm $B.OrdersBench.largeHeap ms/op '[[900]]')" \
  "$(bm $B.OrdersBench.compile ms/op '[[1.7],[1.7],[1.7]]')" \
  "$(bm $B.WideBench.compile ms/op '[[0.051234],[0.051234],[0.051234]]')"
tree tree-fast.json 17
out=$(run regen --all gain.json tree-fast.json); rc=$?
check "regen: exit" "$rc" 0
check "regen: gain" "$(lines "$out" 'GAIN orders/small/latency 100 -> 75 us/op')" 1
check "regen: bench.tsv" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' \
  "compile/orders	$M	2	ms/op" \
  "compile/wide	$M	0.05123	ms/op" \
  "orders/large/alloc	$M	50000000	B/op" \
  "orders/large/throughput	$M	33.33	MB/s" \
  "orders/small/alloc	$M	8000	B/op" \
  "orders/small/latency	$M	75	us/op" \
  "ui/tree-20128/scroll	$M	17	ms")"

# regen without bench results (off the VM) leaves bench.tsv alone; check ignores it
rows "${base_rows[@]}"
out=$(run regen --all); rc=$?
check "regen without results: exit" "$rc" 0
check "regen without results: bench.tsv kept" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' "${base_rows[@]}")"
out=$(run check); rc=$?
check "check ignores bench.tsv" "$rc" 0

# regen refuses a regression and writes nothing
out=$(run regen --all first.json tree.json); rc=$?
check "regen regression: exit" "$rc" 1
check "regen regression: bench.tsv kept" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' "${base_rows[@]}")"
exit $fails
