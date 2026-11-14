#!/usr/bin/env bash
# jq-ref's time per document for the bench ids of ADR-0024 (ADR-0019, "Timing the reference"). Run it right after
# JMH, in the same session: it times the reference jq on the documents the JMH scenarios generate.
#
#   bench/jq-ref.sh [dir]
#
#   [dir]  where the inputs are written; default bench/jmh/target/inputs (under target/, so never committed)
#
# Environment: JQ_REF (default /usr/local/bin/jq-ref), JMH_JAR (default bench/jmh/target/benchmarks.jar, from
# ./mvnw -B -Pbench package; its class Inputs writes the documents), MACHINE (default claude-cloud-4vcpu).
#
# Per cell: one discarded run, then the best of 5 wall-clock runs, output to /dev/null. A 1k document runs as 10 000
# concatenated copies, a 100k document as 100, a 10m document once, the ints of G4.1e4 (10^4 elements) as 100 (K).
# Per document = (best - start-up) / K, where
# start-up is the best of 5 runs of the same filter on /dev/null. G7.process.1k is one whole process on one 1k
# document, start-up included: its best of 5, with nothing subtracted (G7 has no factor, ADR-0019).
# Output: a header of `# key: value` lines, then one tab-separated line per bench id.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 2

dir=${1:-bench/jmh/target/inputs}
jq_ref=${JQ_REF:-/usr/local/bin/jq-ref}
jar=${JMH_JAR:-bench/jmh/target/benchmarks.jar}
machine=${MACHINE:-claude-cloud-4vcpu}

if [ ! -f "$jar" ]; then
  echo "jq-ref.sh: $jar is missing; build it with ./mvnw -B -Pbench package" >&2
  exit 2
fi
java -cp "$jar" io.github.subapavel.jqj.bench.Inputs "$dir"

# copies <file> <k> <out>: <out> is <k> copies of <file>, which ends in its only LF.
copies() {
  local doc i
  doc=$(< "$1")
  for ((i = 0; i < $2; i++)); do printf '%s\n' "$doc"; done > "$3"
  if [ "$(stat -c %s "$3")" -ne $(($2 * $(stat -c %s "$1"))) ]; then
    echo "jq-ref.sh: $3 is not $2 copies of $1" >&2
    exit 1
  fi
}
copies "$dir/orders-1k.json" 10000 "$dir/orders-1k-x10000.json"
copies "$dir/orders-100k.json" 100 "$dir/orders-100k-x100.json"
copies "$dir/recs-100k.json" 100 "$dir/recs-100k-x100.json"
copies "$dir/ints-1e4.json" 100 "$dir/ints-1e4-x100.json"

# best_us <args…>: jq-ref <args…> once discarded, then the best of 5 wall-clock runs in µs. The clock is bash's
# EPOCHREALTIME read in place: a $(…) around it would add a fork to every run.
best_us() {
  local best='' t0 t1 i
  "$jq_ref" "$@" > /dev/null
  for i in 1 2 3 4 5; do
    t0=${EPOCHREALTIME/[.,]/}
    "$jq_ref" "$@" > /dev/null
    t1=${EPOCHREALTIME/[.,]/}
    if [ -z "$best" ] || [ $((t1 - t0)) -lt "$best" ]; then best=$((t1 - t0)); fi
  done
  echo "$best"
}

# cell <id> <k> <file> <jq args…>: one line, per document = (best - start-up) / k.
cell() {
  local id=$1 k=$2 file=$3 best startup
  shift 3
  best=$(best_us "$@" "$file")
  startup=$(best_us "$@" /dev/null)
  awk -v id="$id" -v f="$*" -v k="$k" -v b="$best" -v s="$startup" \
    'BEGIN { printf "%s\t%s\t%d\t%.3f\t%.3f\t%.3f\n", id, f, k, b / 1000, s / 1000, (b - s) / k }'
}

echo "# machine: $machine"
echo "# date: $(date -u +%Y-%m-%dT%H:%MZ)"
echo "# commit: $(git describe --always --dirty)"
echo "# cpu: $(nproc) x $(grep -m 1 '^model name' /proc/cpuinfo | sed 's/^model name[[:space:]]*:[[:space:]]*//')"
echo "# kernel: $(uname -r)"
echo "# java: $(java -version 2>&1 | grep -v '^Picked up' | head -n 1)"
echo "# jq-ref: $("$jq_ref" --version)"
printf 'id\tfilter\tK\tbest_ms\tstartup_ms\tper_document_us\n'
cell G1.1k 10000 "$dir/orders-1k-x10000.json" -c .a.b.c
cell G1.100k 100 "$dir/orders-100k-x100.json" -c .a.b.c
cell G1.10m 1 "$dir/orders-10m.json" -c .a.b.c
g2='.orders[] | select(.total > 100) | {id, total}'
cell G2.1k 10000 "$dir/orders-1k-x10000.json" -c "$g2"
cell G2.100k 100 "$dir/orders-100k-x100.json" -c "$g2"
cell G2.10m 1 "$dir/orders-10m.json" -c "$g2"
g3='group_by(.k) | map({k: .[0].k, n: length})'
cell G3.100k 100 "$dir/recs-100k-x100.json" -c "$g3"
cell G3.10m 1 "$dir/recs-10m.json" -c "$g3"
cell G4.1e4 100 "$dir/ints-1e4-x100.json" -c 'reduce .[] as $x ([]; . + [$x])'
cell G6.compact.10m 1 "$dir/orders-10m.json" -c .
cell G6.pretty.10m 1 "$dir/orders-10m.json" .
cell G7.engine.1k 10000 "$dir/orders-1k-x10000.json" -c .a
process=$(best_us -c .a "$dir/orders-1k.json")
awk -v b="$process" 'BEGIN { printf "G7.process.1k\t-c .a\t1\t%.3f\t-\t%.3f\n", b / 1000, b }'
