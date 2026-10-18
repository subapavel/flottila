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
# e.g. BENCH_MACHINE="cloud VM, 4 vCPU" on the coordinator's VM).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PGHOST:=localhost}" "${PGPORT:=5432}" "${PGUSER:=flottila}" "${PGPASSWORD:=flottila}" "${BENCH_DB:=flottila_bench}"
: "${BENCH_MESSAGES:=20000}" "${BENCH_WARMUP:=1000}" "${BENCH_CONCURRENCY:=64}"
export PGPASSWORD
commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "select 1 from pg_database where datname = '$BENCH_DB'" | grep -q 1; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -c "create database $BENCH_DB"
fi

mvn -B -ntp -q -pl flottila-bench -am -DskipTests package
jar="$(ls flottila-bench/target/flottila-bench-*.jar | grep -v -E 'sources|javadoc' | head -1)"
cp="$jar:flottila-bench/target/lib/*"
mkdir -p bench/out

run() { # name sync-commit
  java -cp "$cp" io.flottila.bench.BenchMain --host "$PGHOST" --port "$PGPORT" --db "$BENCH_DB" --user "$PGUSER" --password "$PGPASSWORD" \
    --messages "$BENCH_MESSAGES" --warmup "$BENCH_WARMUP" --concurrency "$BENCH_CONCURRENCY" \
    --batch-size 100 --window-ms 10 --commit-concurrency 4 --sync-commit "$2" --name "$1" --out "bench/out/$1.json" --commit "$commit" \
    > "bench/out/$1.log"
  echo "$1: $(python3 -c "import json,sys; j=json.load(open('bench/out/$1.json')); print(f\"{j['throughputPerSecond']:.0f} msg/s, p50 {j['p50Ms']:.2f} ms, p95 {j['p95Ms']:.2f} ms, p99 {j['p99Ms']:.2f} ms, max {j['maxMs']:.1f} ms\")")"
}

run batched-persistence-sync on
run batched-persistence-async off

python3 bench/check_regression.py --results bench/RESULTS.md --out bench/out
