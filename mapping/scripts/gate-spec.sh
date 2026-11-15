#!/usr/bin/env bash
# Spec gate (docs/WORKFLOW.md section 6, ADR-0017, ADR-0019, ADR-0021). Run by the verifier on the integration
# branch, one at a time. Changes to the repository root of the worktree it lives in.
#
#   [MACHINE_LABEL=claude-cloud-4vcpu] scripts/gate-spec.sh <spec> [pit-modules]
#
# <spec>         spec issue number, used in the log names (.logs/<spec>-<check>.log)
# [pit-modules]  class-C module directories PIT runs on (step 1b), comma-separated. Default: the modules in
#                the first column of ratchet/mutation.tsv (a module without a row has no floor to hold). The verifier
#                narrows it to the modules the spec touched.
# MACHINE_LABEL  the machine the verifier runs on; only claude-cloud-4vcpu (with nproc = 4) judges the benchmarks
#                and lets step 4 write ratchet/bench.tsv (ADR-0021). Unset elsewhere: step 5 only measures.
#
# Order: 1, 1b, 2, 5, 4, 3. Step 5 writes the JMH JSON that step 4 reads. Step 4 reads the reports of steps 1, 1b
# and 2, and step 3 must run after it: -Pui needs clean (ADR-0017), and that clean deletes the JaCoCo and PIT
# reports of service and its upstream modules. Every step runs even if an earlier one failed; each prints an
# evidence word and its log. Exit 1 if any step failed. The script leaves ratchet/ modified; the verifier
# commits it. The last lines are the PIT summary, one per module (scripts/pit-summary.sh:
# `module killed/generated survivors S floor N ok|DROP`, or against a ratchet row of the old format
# `module killed/generated score% floor N% ok|DROP`), then the corpus and differential case counts.
set -uo pipefail

if [ $# -lt 1 ]; then
  echo "usage: scripts/gate-spec.sh <spec> [pit-modules]" >&2
  exit 2
fi
spec=$1

cd "$(dirname "$0")/.."
mkdir -p .logs ui/.logs
# the console output, stderr included, also goes to .logs/<spec>-spec-gate.log under this root, whatever the caller's
# directory (#125). On exit, close it and wait for tee, so the log is complete when the script returns.
exec > >(tee ".logs/${spec}-spec-gate.log") 2>&1
tee_pid=$!
trap 'exec >&- 2>&-; wait "$tee_pid"' EXIT
pit_modules=${2:-$(cut -f1 ratchet/mutation.tsv | paste -sd, -)}
status=0

step() {
  local name=$1 log=$2
  shift 2
  if "$@" > "$log" 2>&1; then
    echo "VERIFIED_PASS  $name  ($log)"
  else
    echo "EXECUTED_FAIL  $name  ($log)"
    status=1
  fi
}

skipped() { echo "NOT_RUN         $1  ($2)"; }

# 1: everything, oracles and JaCoCo floors included, bench compiles
step "1 verify -Pbench" ".logs/${spec}-build.log" \
  ./mvnw -B clean verify -Pbench
step "1 contract diff" ".logs/${spec}-contract.log" \
  git diff --exit-code contract/

# 1b: PIT on the class-C modules (the build's 80 % threshold, then the survivor floor of ratchet/mutation.tsv,
# ADR-0019). test-compile first: PIT needs the classes of a clean tree;
# -am puts upstream modules in the reactor, and modules without a mutation floor set pit.skip.
# One PIT run per module, each section preceded by `=== PIT <module> ===` in the log (#87).
IFS=, read -ra pit_list <<< "$pit_modules"
step "1b pit $pit_modules" ".logs/${spec}-pit.log" \
  scripts/pit-summary.sh --run ".logs/${spec}-pit.log" "${pit_list[@]}"

# 1b summary: per module against the floors in ratchet/ as committed (step 4 rewrites them). Printed last, with the
# oracle case counts of step 1 (step 3's clean deletes the reports).
pit_summary=$(scripts/pit-summary.sh ratchet/mutation.tsv "${pit_list[@]}") || {
  echo "EXECUTED_FAIL  1b pit floor  (ratchet/mutation.tsv)"
  status=1
}
# oracle_count <class-name-part>: "<tests> cases, <failed> failed or skipped" over the engine/oracle surefire reports
oracle_count() {
  local tests=0 bad=0 file attr v
  for file in engine/oracle/target/surefire-reports/TEST-*"$1"*.xml; do
    [ -f "$file" ] || continue
    for attr in tests failures errors skipped; do
      v=$(grep -m1 -o "<testsuite [^>]*" "$file" | grep -o " $attr=\"[0-9]*\"" | grep -o '[0-9]*')
      if [ "$attr" = tests ]; then tests=$((tests + ${v:-0})); else bad=$((bad + ${v:-0})); fi
    done
  done
  echo "$tests cases, $bad failed or skipped"
}
oracle_summary="corpus: $(oracle_count Corpus)
differential: $(oracle_count Differential)"

# 2: ui/
if [ -f ui/package.json ]; then
  step "2 ui npm ci" "ui/.logs/${spec}-npm-ci.log" bash -c 'cd ui && npm ci'
  for check in licences typecheck lint test test:browser e2e; do
    step "2 ui $check" "ui/.logs/${spec}-${check/:/-}.log" bash -c "cd ui && npm run $check"
  done
  step "2 ui gen:types diff" "ui/.logs/${spec}-gen-types.log" \
    bash -c 'cd ui && npm run gen:types && git diff --exit-code src/generated'
  step "2 ui build" "ui/.logs/${spec}-build.log" bash -c 'cd ui && npm run build'
else
  skipped "2 ui" "no ui/"
fi

# 5: JMH (ADR-0021), before step 4. Judged (scripts/ratchet.java bench) only when the verifier names the machine:
# MACHINE_LABEL=claude-cloud-4vcpu, cross-checked with nproc = 4. Without the label step 5 measures and prints, and
# step 4 leaves ratchet/bench.tsv alone; the gate never writes bench/RESULTS.md. A first judgement that prints RERUN
# (a regression or a missed target) re-runs those benchmarks twice, into .logs/<spec>-jmh-2.json and -jmh-3.json, and
# judges again on all three files, a scenario in three of them on its median (#193); step 4 reads the same files. A
# clean first judgement stays one run. Any JMH run that fails (a heap fork, -foe true) fails the gate.
bench_results=()
# The tree scenario (#188): three runs of ui/e2e/tree-20128.spec.ts against the fake API. TREE_P95_FILE names the
# file the spec writes its result to; the median of the three goes to ui/.logs/<spec>-tree-20128.json, the shape
# scripts/ratchet.java bench reads. A run that fails or writes nothing leaves no median file (the judgement then
# finds no result). Printed whatever the label; judged below only on the labelled machine. Without ui/ step 2
# already says NOT_RUN.
tree_file=
if [ -f ui/package.json ]; then
  tree_scores=()
  for run in 1 2 3; do
    run_file="$PWD/ui/.logs/${spec}-tree-20128-${run}.json"
    rm -f "$run_file"
    step "5 tree-20128 run $run" "ui/.logs/${spec}-tree-20128-${run}.log" \
      bash -c "cd ui && TREE_P95_FILE='$run_file' npm run e2e -- tree-20128"
    score=$(sed -n 's/.*"score": *\([0-9.]*\).*/\1/p' "$run_file" 2> /dev/null)
    [ -n "$score" ] && tree_scores+=("$score")
  done
  if [ "${#tree_scores[@]}" = 3 ]; then
    median=$(printf '%s\n' "${tree_scores[@]}" | sort -n | sed -n 2p)
    tree_file="ui/.logs/${spec}-tree-20128.json"
    printf '{"scenario":"ui/tree-20128/scroll","score":%s,"unit":"ms"}\n' "$median" > "$tree_file"
    echo "tree-20128 p95 median $median ms (runs ${tree_scores[*]})"
  fi
fi
jmh() { java -jar bench/jmh/target/benchmarks.jar -foe true -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc -rf json -rff "$@"; }
if [ -f bench/jmh/target/benchmarks.jar ]; then
  step "5 jmh" ".logs/${spec}-jmh.log" jmh ".logs/${spec}-jmh.json"
  if [ "${MACHINE_LABEL:-}" != claude-cloud-4vcpu ]; then
    skipped "5 bench judgement" "no MACHINE_LABEL=claude-cloud-4vcpu: measured and printed only"
  elif [ "$(nproc)" != 4 ]; then
    echo "EXECUTED_FAIL  5 bench judgement  (MACHINE_LABEL=claude-cloud-4vcpu, but nproc is $(nproc))"
    status=1
  else
    bench_results=(".logs/${spec}-jmh.json")
    [ -n "$tree_file" ] && bench_results+=("$tree_file")
    if java scripts/ratchet.java bench "${bench_results[@]}" > ".logs/${spec}-bench.log" 2>&1; then
      echo "VERIFIED_PASS  5 bench judgement  (.logs/${spec}-bench.log)"
    else
      rerun=$(sed -n 's/^RERUN //p' ".logs/${spec}-bench.log")
      if [ -n "$rerun" ]; then
        for run in 2 3; do
          step "5 jmh re-run $run" ".logs/${spec}-jmh-${run}.log" jmh ".logs/${spec}-jmh-${run}.json" "$rerun"
          bench_results+=(".logs/${spec}-jmh-${run}.json")
        done
        step "5 bench judgement on three runs" ".logs/${spec}-bench-2.log" \
          java scripts/ratchet.java bench "${bench_results[@]}"
      else
        echo "EXECUTED_FAIL  5 bench judgement  (.logs/${spec}-bench.log)"
        status=1
      fi
    fi
  fi
else
  skipped "5 jmh" "bench/jmh/target/benchmarks.jar is not built (./mvnw -B -Pbench -pl bench/jmh -am package -DskipTests)"
fi

# 4: ratchet regeneration, before step 3. Needs every report; refuses a drop. The verifier commits ratchet/.
# With bench results (step 5 judged) it also writes bench.tsv.
step "4 ratchet regen" ".logs/${spec}-ratchet.log" \
  java scripts/ratchet.java regen --all "${bench_results[@]}"

# 3: the stack, one jar serving UI and /api. Last: its clean deletes the reports step 4 read.
step "3 stack package -Pui" ".logs/${spec}-stack-package.log" \
  ./mvnw -B -Pui -pl service -am clean package -DskipTests
if [ -f ui/package.json ]; then
  step "3 stack e2e" "ui/.logs/${spec}-e2e-stack.log" bash -c 'cd ui && npm run e2e:stack'
fi

echo "$pit_summary"
echo "$oracle_summary"
exit "$status"
