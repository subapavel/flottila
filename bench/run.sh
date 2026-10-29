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
# metadata-only against the raw echo, `ack` the at-least-once 202 at c=1, c=8 and c=64, `worker` passthrough and xslt, each keep
# and metadata-only; BENCH_MESSAGES / BENCH_WARMUP / BENCH_CONCURRENCY apply to every run but ack's), BENCH_COMMIT_DELAY (unset —
# the server's own: a commit_delay in µs, 0 to 100000; set server-wide with ALTER SYSTEM + pg_reload_conf() before the first
# ack run, checked in pg_settings, and in force for every run after it, later scenarios included, until the EXIT trap resets
# it (ALTER SYSTEM RESET + pg_reload_conf()) when the script exits, failed or not; the ack runs are named node-ack-c<c>-cd<µs>. NodeBenchMain never sets it. The role needs, from a superuser, Postgres 15+:
# `GRANT ALTER SYSTEM ON PARAMETER commit_delay TO <role>` and `GRANT EXECUTE ON FUNCTION pg_reload_conf() TO <role>`).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PGHOST:=localhost}" "${PGPORT:=5432}" "${PGUSER:=flottila}" "${PGPASSWORD:=flottila}" "${BENCH_DB:=flottila_bench}"
: "${BENCH_MESSAGES:=20000}" "${BENCH_WARMUP:=1000}" "${BENCH_CONCURRENCY:=64}" "${BENCH_SCENARIOS:=batched}"
export PGPASSWORD
commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
ack_suffix=""
if [[ -n "${BENCH_COMMIT_DELAY:-}" ]]; then
  if ! [[ "$BENCH_COMMIT_DELAY" =~ ^[0-9]+$ ]] || (( BENCH_COMMIT_DELAY > 100000 )); then
    echo "BENCH_COMMIT_DELAY: a commit_delay in µs, 0 to 100000, was '$BENCH_COMMIT_DELAY'" >&2
    exit 2
  fi
  ack_suffix="-cd$BENCH_COMMIT_DELAY"
fi

if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "select 1 from pg_database where datname = '$BENCH_DB'" | grep -q 1; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -c "create database $BENCH_DB"
fi

mvn -B -ntp -q -pl flottila-bench -am -DskipTests package
jar="$(ls flottila-bench/target/flottila-bench-*.jar | grep -v -E 'sources|javadoc' | head -1)"
cp="$jar:flottila-bench/target/lib/*"
mkdir -p bench/out

pg_admin() { # statement — one autocommit statement on the postgres database (ALTER SYSTEM refuses a transaction block)
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -tAc "$1"
}

reset_commit_delay() {
  pg_admin "alter system reset commit_delay" > /dev/null
  pg_admin "select pg_reload_conf()" > /dev/null
}

set_commit_delay() { # µs — in force for every later run of the script, reset when it exits; runs start once a new session sees it
  trap reset_commit_delay EXIT
  pg_admin "alter system set commit_delay = $1" > /dev/null
  pg_admin "select pg_reload_conf()" > /dev/null
  for _ in $(seq 50); do
    [[ "$(pg_admin "select setting from pg_settings where name = 'commit_delay'")" == "$1" ]] && return 0
    sleep 0.1
  done
  echo "commit_delay did not become $1 after pg_reload_conf()" >&2
  exit 4
}

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
      if [[ -n "$ack_suffix" ]]; then set_commit_delay "$BENCH_COMMIT_DELAY"; fi
      for c in 1 8 64; do run_node "node-ack-c$c$ack_suffix" --scenario ack --concurrency "$c"; done ;;
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
