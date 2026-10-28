#!/usr/bin/env bash
# Flottila bench harness (WORKFLOW §4 `bench` job; ARCHITECTURE §13 Phase 0 spike 1).
# Builds flottila-bench, runs the batched-persistence benchmark against a NATIVE PostgreSQL (never a
# container: CLAUDE.md), writes bench/out/<name>.json per configuration and checks for regressions
# against the baselines recorded in bench/RESULTS.md. Only numbers from native Linux runs are
# recorded in RESULTS.md, by hand, with machine, date, configuration and commit.
#
# Environment: PGHOST (localhost), PGPORT (5432), PGUSER (flottila), PGPASSWORD (flottila),
# BENCH_DB (flottila_bench — created if missing, via psql as PGUSER), BENCH_MESSAGES (20000),
# BENCH_WARMUP (1000), BENCH_CONCURRENCY (64), BENCH_MACHINE (the machine label the run is judged
# against in RESULTS.md, D-037; unset → "host:<hostname>", which no baseline matches: set it on purpose,
# e.g. BENCH_MACHINE="cloud VM, 4 vCPU" on the coordinator's VM), BENCH_SCENARIOS (batched — what runs, space-separated:
# `batched` the BatchingWriter pair, the CI regression job's; the node bench (NodeBenchMain, P1-10a): `sync` keep and
# metadata-only against the raw echo, `ack` the at-least-once 202 at c=8 and c=64, `worker` passthrough and xslt, each keep
# and metadata-only; BENCH_MESSAGES / BENCH_WARMUP / BENCH_CONCURRENCY apply to every run but ack's).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PGHOST:=localhost}" "${PGPORT:=5432}" "${PGUSER:=flottila}" "${PGPASSWORD:=flottila}" "${BENCH_DB:=flottila_bench}"
: "${BENCH_MESSAGES:=20000}" "${BENCH_WARMUP:=1000}" "${BENCH_CONCURRENCY:=64}" "${BENCH_SCENARIOS:=batched}"
export PGPASSWORD
commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "select 1 from pg_database where datname = '$BENCH_DB'" | grep -q 1; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -c "create database $BENCH_DB"
fi

mvn -B -ntp -q -pl flottila-bench -am -DskipTests package
jar="$(ls flottila-bench/target/flottila-bench-*.jar | grep -v -E 'sources|javadoc' | head -1)"
cp="$jar:flottila-bench/target/lib/*"
mkdir -p bench/out

summary() { # name
  echo "$1: $(python3 -c "import json,sys; j=json.load(open('bench/out/$1.json')); print(f\"{j['throughputPerSecond']:.0f} msg/s, p50 {j['p50Ms']:.2f} ms, p95 {j['p95Ms']:.2f} ms, p99 {j['p99Ms']:.2f} ms, max {j['maxMs']:.1f} ms\" + (f\", overhead p50 {j['overheadP50Ms']:.2f} ms\" if 'overheadP50Ms' in j else ''))")"
}

run() { # name sync-commit
  java -cp "$cp" io.flottila.bench.BenchMain --host "$PGHOST" --port "$PGPORT" --db "$BENCH_DB" --user "$PGUSER" --password "$PGPASSWORD" \
    --messages "$BENCH_MESSAGES" --warmup "$BENCH_WARMUP" --concurrency "$BENCH_CONCURRENCY" \
    --batch-size 100 --window-ms 10 --commit-concurrency 4 --sync-commit "$2" --name "$1" --out "bench/out/$1.json" --commit "$commit" \
    > "bench/out/$1.log"
  summary "$1"
}

run_node() { # name, then NodeBenchMain's own arguments (--scenario ...); a later --concurrency overrides BENCH_CONCURRENCY
  local name="$1"; shift
  java -cp "$cp" io.flottila.bench.NodeBenchMain --host "$PGHOST" --port "$PGPORT" --db "$BENCH_DB" --user "$PGUSER" --password "$PGPASSWORD" \
    --messages "$BENCH_MESSAGES" --warmup "$BENCH_WARMUP" --concurrency "$BENCH_CONCURRENCY" \
    --name "$name" --out "bench/out/$name.json" --commit "$commit" "$@" > "bench/out/$name.log"
  summary "$name"
}

for scenario in $BENCH_SCENARIOS; do
  case "$scenario" in
    batched)
      run batched-persistence-sync on
      run batched-persistence-async off ;;
    sync)
      for recording in keep metadata-only; do run_node "node-sync-$recording" --scenario sync --recording "$recording"; done ;;
    ack)
      for c in 8 64; do run_node "node-ack-c$c" --scenario ack --concurrency "$c"; done ;;
    worker)
      for step in passthrough xslt; do
        for recording in keep metadata-only; do run_node "node-worker-$step-$recording" --scenario worker --step "$step" --recording "$recording"; done
      done ;;
    *)
      echo "BENCH_SCENARIOS: unknown scenario '$scenario' (batched, sync, ack, worker)" >&2
      exit 2 ;;
  esac
done

python3 bench/check_regression.py --results bench/RESULTS.md --out bench/out
