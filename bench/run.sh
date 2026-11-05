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
# and metadata-only, `cpu` (FIX-108) the sync flow passthrough and xslt at c=1 and c=64, each with --cpu-sampling off, PT0.004S and
# PT0.002S one after the other, BENCH_CPU_ROUNDS (3) rounds, runs named node-cpu-<step>-c<c>-<sampling>-r<round>;
# BENCH_MESSAGES / BENCH_WARMUP / BENCH_CONCURRENCY apply to every run but ack's and cpu's concurrency), BENCH_COMMIT_DELAY (unset —
# the server's own: a commit_delay in µs, 0 to 100000; set server-wide with ALTER SYSTEM + pg_reload_conf() before the first
# ack run, checked in pg_settings, and in force for every run after it, later scenarios included, until the EXIT trap resets
# it (ALTER SYSTEM RESET + pg_reload_conf()) when the script exits, failed or not; the ack runs are named node-ack-c<c>-cd<µs>. NodeBenchMain never sets it. The role needs, from a superuser, Postgres 15+:
# `GRANT ALTER SYSTEM ON PARAMETER commit_delay TO <role>` and `GRANT EXECUTE ON FUNCTION pg_reload_conf() TO <role>`).
# A node run that exits non-zero stops the script, with one exception: in the cpu scenario (refusals_ok=1) an exit 3 whose record
# counts no error (errors 0: every failure is the flow's admission refusing, 503) stands — refusals occur even at 1 in flight against
# the default limit 64×c, a permit outliving its reply (FIX-113), and FIX-112 judges their share. Every node run's summary names its
# refused and warm-up failures ("refused R of M", "warm-up failed W"); the flow's limit stays NodeBenchMain's default (no --max-in-flight).
# `clickhouse` (CORE-2, needs Docker, not Postgres): ClickHouseCompressionMain against clickhouse/clickhouse-server:25.8.33.6 (the compose
# pin) started with `docker run` on 127.0.0.1:BENCH_CH_PORT (18123), default server settings, data inside the container on the host's
# disk; stopped and removed with its data when the scenario ends, failed or not. BENCH_CH_ARGS (unset: the full matrix) passes harness
# arguments, e.g. "--sizes 100 --counts 50 --levels 1 --runs 1". Writes bench/out/clickhouse-compression.{json,log}, prints the table.
# `open` (CORE-15): one NodeBenchMain run open loop (requests sent at times fixed in advance, whatever the answers) of
# BENCH_OPEN_SCENARIO (ack; sync, or echo — the raw echo alone, the harness' own ceiling) at BENCH_RATE msg/s (5000, passed through:
# NodeBenchMain refuses a value outside 1..100000), after a linear BENCH_RAMP (NodeBenchMain's PT1M unless set) for BENCH_WINDOW
# (PT10M unless set); BENCH_PG_CPU (unset) names Postgres' cgroup v2 directory or pid for its CPU over the window
# (--pg-cpu-source). Named node-open-<scenario>-r<rate>; its summary prints the record's open fields (bench/RESULTS.md, "Harness:
# open loop (CORE-15)").
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PGHOST:=localhost}" "${PGPORT:=5432}" "${PGUSER:=flottila}" "${PGPASSWORD:=flottila}" "${BENCH_DB:=flottila_bench}"
: "${BENCH_MESSAGES:=20000}" "${BENCH_WARMUP:=1000}" "${BENCH_CONCURRENCY:=64}" "${BENCH_SCENARIOS:=batched}"
export PGPASSWORD
commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
# a record names the commit it measured: a tree with changes (tracked or untracked, bench/out is ignored) is not that commit
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then commit="$commit+dirty"; fi
ack_suffix=""
if [[ -n "${BENCH_COMMIT_DELAY:-}" ]]; then
  if ! [[ "$BENCH_COMMIT_DELAY" =~ ^[0-9]+$ ]] || (( BENCH_COMMIT_DELAY > 100000 )); then
    echo "BENCH_COMMIT_DELAY: a commit_delay in µs, 0 to 100000, was '$BENCH_COMMIT_DELAY'" >&2
    exit 2
  fi
  ack_suffix="-cd$BENCH_COMMIT_DELAY"
fi

open_scenario="${BENCH_OPEN_SCENARIO:-ack}"
case "$open_scenario" in
  ack|sync|echo) ;;
  *) echo "BENCH_OPEN_SCENARIO: unknown open-loop scenario '$open_scenario' (ack, sync, echo)" >&2
     exit 2 ;;
esac

needs_pg=0   # every scenario but clickhouse runs against Postgres
for scenario in $BENCH_SCENARIOS; do
  if [[ "$scenario" != clickhouse ]]; then needs_pg=1; fi
done
if (( needs_pg )) && ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "select 1 from pg_database where datname = '$BENCH_DB'" | grep -q 1; then
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

clickhouse_run() { # CORE-2, in a subshell: its EXIT trap stops and removes the container (and its data) however the run ends
  local image="clickhouse/clickhouse-server:25.8.33.6" name="flottila-bench-clickhouse" port="${BENCH_CH_PORT:-18123}" password="flottila-bench"
  trap "docker rm -f -v $name > /dev/null" EXIT
  docker run -d --name "$name" -p "127.0.0.1:$port:8123" -e CLICKHOUSE_PASSWORD="$password" "$image" > /dev/null
  for _ in $(seq 120); do
    if curl -sf "http://127.0.0.1:$port/ping" > /dev/null; then break; fi
    sleep 0.5
  done
  curl -sf "http://127.0.0.1:$port/ping" > /dev/null || { echo "ClickHouse ($image) did not answer on port $port" >&2; exit 5; }
  # shellcheck disable=SC2086 # BENCH_CH_ARGS is a list of harness arguments, split on purpose
  java -Xmx2g -cp "$cp" io.flottila.bench.ClickHouseCompressionMain --url "http://127.0.0.1:$port" --password "$password" --image "$image" \
    --out bench/out/clickhouse-compression.json --commit "$commit" ${BENCH_CH_ARGS:-} > bench/out/clickhouse-compression.log
  grep '^|' bench/out/clickhouse-compression.log
}

# an open-loop record's summary line (CORE-15), nothing for a closed-loop record
OPEN_SUMMARY='
import json, sys
o = json.load(open(sys.argv[1])).get("open")
if o:
    ms = lambda v: "n/a" if v is None else "%.2f ms" % v
    cpu = ", ".join(k + " " + ("n/a" if v is None else "%.0f ms" % v) for k, v in o["cpuMs"].items())
    pct = "n/a" if o["backlogGrowthPercent"] is None else "%.2f %%" % o["backlogGrowthPercent"]
    why = "" if o["valid"] else " (" + "; ".join(o["invalidReasons"]) + ")"
    print(("open loop: offered {offeredRate:.0f} msg/s, achieved {achievedRate:.1f} msg/s, sent {sent} of {scheduled} scheduled,"
           " accepted {accepted}, refused 503 {refused503}, refused {refused}, timeouts {timeouts}, errors {errors},"
           " clientSaturated {clientSaturated}, p50 {p50}, p99 {p99}, p99.9 {p999}, max {max}, receipts {receipts},"
           " backlog growth {backlogGrowth} ({pct}), cpu over the window: {cpu}; valid {ok}{why}").format(**o, p50=ms(o["p50Ms"]),
          p99=ms(o["p99Ms"]), p999=ms(o["p999Ms"]), max=ms(o["maxMs"]), pct=pct, cpu=cpu, ok=str(o["valid"]).lower(), why=why))
'

summary() { # name
  local open
  open="$(python3 -c "$OPEN_SUMMARY" "bench/out/$1.json")"
  if [[ -n "$open" ]]; then echo "$1: $open"; return; fi
  echo "$1: $(python3 -c "import json,sys; j=json.load(open('bench/out/$1.json')); print(f\"{j['throughputPerSecond']:.0f} msg/s, p50 {j['p50Ms']:.2f} ms, p95 {j['p95Ms']:.2f} ms, p99 {j['p99Ms']:.2f} ms, max {j['maxMs']:.1f} ms\" + (f\", overhead p50 {j['overheadP50Ms']:.2f} ms\" if 'overheadP50Ms' in j else '') + (f\", process cpu {j['processCpuMsPerMessage']:.3f} ms/msg\" if 'processCpuMsPerMessage' in j else '') + (f\", refused {j['refused']} of {j['messages']}\" if 'refused' in j else '') + (f\", warm-up failed {j['warmupFailed']}\" if 'warmupFailed' in j else ''))")"
}

run() { # name sync-commit
  java -cp "$cp" io.flottila.bench.BenchMain --host "$PGHOST" --port "$PGPORT" --db "$BENCH_DB" --user "$PGUSER" --password "$PGPASSWORD" \
    --messages "$BENCH_MESSAGES" --warmup "$BENCH_WARMUP" --concurrency "$BENCH_CONCURRENCY" \
    --batch-size 100 --window-ms 10 --commit-concurrency 4 --sync-commit "$2" --name "$1" --out "bench/out/$1.json" --commit "$commit" \
    > "bench/out/$1.log"
  summary "$1"
}

run_node() { # name, then NodeBenchMain's own arguments (--scenario ...); a later --concurrency overrides BENCH_CONCURRENCY
  # refusals_ok=1 (the cpu scenario) lets an exit 3 stand when the record counts no error, only refusals (header, FIX-113)
  local name="$1"; shift
  local code=0
  java -cp "$cp" io.flottila.bench.NodeBenchMain --host "$PGHOST" --port "$PGPORT" --db "$BENCH_DB" --user "$PGUSER" --password "$PGPASSWORD" \
    --messages "$BENCH_MESSAGES" --warmup "$BENCH_WARMUP" --concurrency "$BENCH_CONCURRENCY" \
    --name "$name" --out "bench/out/$name.json" --commit "$commit" "$@" > "bench/out/$name.log" || code=$?
  if (( code != 0 )); then
    local errors=""
    if (( code == 3 )) && [[ "${refusals_ok:-0}" == 1 ]]; then
      errors="$(python3 -c "import json; print(json.load(open('bench/out/$name.json'))['errors'])" || true)"
    fi
    if [[ "$errors" != 0 ]]; then
      echo "$name: NodeBenchMain exited $code (errors in its record: ${errors:-not checked}; bench/out/$name.log)" >&2
      exit "$code"
    fi
  fi
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
    cpu)
      for round in $(seq "${BENCH_CPU_ROUNDS:-3}"); do
        for step in passthrough xslt; do
          for c in 1 64; do
            for sampling in off PT0.004S PT0.002S; do
              refusals_ok=1 run_node "node-cpu-$step-c$c-${sampling,,}-r$round" --scenario sync --step "$step" --concurrency "$c" --cpu-sampling "$sampling"
            done
          done
        done
      done ;;
    clickhouse)
      (clickhouse_run) ;;
    open)
      open_args=(--scenario "$open_scenario" --rate "${BENCH_RATE:-5000}")
      if [[ -n "${BENCH_RAMP:-}" ]]; then open_args+=(--ramp "$BENCH_RAMP"); fi
      if [[ -n "${BENCH_WINDOW:-}" ]]; then open_args+=(--window "$BENCH_WINDOW"); fi
      if [[ -n "${BENCH_PG_CPU:-}" ]]; then open_args+=(--pg-cpu-source "$BENCH_PG_CPU"); fi
      run_node "node-open-$open_scenario-r${BENCH_RATE:-5000}" "${open_args[@]}" ;;
    *)
      echo "BENCH_SCENARIOS: unknown scenario '$scenario' (batched, sync, ack, worker, cpu, clickhouse, open)" >&2
      exit 2 ;;
  esac
done

python3 bench/check_regression.py --results bench/RESULTS.md --out bench/out
