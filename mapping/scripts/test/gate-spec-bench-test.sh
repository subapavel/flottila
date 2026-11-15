#!/usr/bin/env bash
# Script test for spec-gate step 5 of scripts/gate-spec.sh (#184): the machine label, the re-run of the regressed
# scenarios and what step 4 writes, in a scratch root with stubbed mvnw and scripts/pit-summary.sh. A java stub
# plays benchmarks.jar (it copies the canned JSON of its n-th call to the -rff file) and runs the real
# scripts/ratchet.java otherwise. A nproc stub prints $NPROC.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
real_java=$(command -v java)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
lines() { echo "$1" | grep -cF -- "$2"; }

mkdir -p "$tmp/scripts" "$tmp/ratchet/oracle" "$tmp/bin" "$tmp/engine" "$tmp/bench/jmh/target" "$tmp/canned"
cp "$here/../gate-spec.sh" "$here/../ratchet.java" "$tmp/scripts/"
: > "$tmp/bench/jmh/target/benchmarks.jar"
: > "$tmp/ratchet/oracle/corpus.txt"; : > "$tmp/ratchet/oracle/differential.txt"
: > "$tmp/ratchet/mutation.tsv"; : > "$tmp/ratchet/coverage.tsv"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/mvnw"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/scripts/pit-summary.sh"
printf '#!/usr/bin/env bash\necho "$NPROC"\n' > "$tmp/bin/nproc"
cat > "$tmp/bin/java" <<EOF
#!/usr/bin/env bash
root=\$(cd "\$(dirname "\$0")/.." && pwd)
if [ "\$1" = -jar ]; then
  echo "\$*" >> "\$root/jmh-calls"
  n=\$(wc -l < "\$root/jmh-calls")
  out=; prev=
  for a in "\$@"; do [ "\$prev" = -rff ] && out=\$a; prev=\$a; done
  cp "\$root/canned/jmh-\$n.json" "\$out"
  [ -f "\$root/canned/jmh-\$n.fail" ] && exit 1
  exit 0
fi
exec "$real_java" "\$@"
EOF
cat > "$tmp/bin/npm" <<'EOF'
#!/usr/bin/env bash
root=$(cd "$(dirname "$0")/.." && pwd)
if [ "$*" = "run e2e -- tree-20128" ]; then
  echo "$TREE_P95_FILE" >> "$root/tree-calls"
  n=$(wc -l < "$root/tree-calls")
  [ -f "$root/canned/tree-$n" ] || exit 1
  printf '{"scenario":"ui/tree-20128/scroll","score":%s,"unit":"ms"}\n' "$(cat "$root/canned/tree-$n")" > "$TREE_P95_FILE"
fi
exit 0
EOF
mkdir -p "$tmp/ui"
echo '{}' > "$tmp/ui/package.json"
chmod +x "$tmp/mvnw" "$tmp/scripts/pit-summary.sh" "$tmp/bin/nproc" "$tmp/bin/java" "$tmp/bin/npm"

B=io.flottila.mapping.bench
M=claude-cloud-4vcpu
# tree <p95 of run 1> <run 2> <run 3>: the canned scores of the three tree runs; a missing one makes that run fail
tree() { rm -f "$tmp"/canned/tree-*; local i=1; for v in "$@"; do echo "$v" > "$tmp/canned/tree-$i"; i=$((i + 1)); done; }
tree 20.5 30 25
treerow="ui/tree-20128/scroll	$M	25	ms"
latency() { printf '[{"benchmark":"%s.OrdersBench.smallLatency","mode":"avgt","primaryMetric":{"score":0,"scoreUnit":"us/op","rawData":[[%s]]},"secondaryMetrics":{}},{"benchmark":"%s.OrdersBench.compile","mode":"avgt","primaryMetric":{"score":0,"scoreUnit":"ms/op","rawData":[[2]]},"secondaryMetrics":{}}]\n' "$B" "$2" "$B" > "$tmp/canned/jmh-$1.json"; }
row="orders/small/latency	$M	100	us/op"
# gate [<label>] [<nproc>]: the gate's output; the bench.tsv row, the calls and the logs reset first
gate() {
  printf '%s\n%s\n' "$row" "$treerow" > "$tmp/ratchet/bench.tsv"
  rm -rf "$tmp/jmh-calls" "$tmp/tree-calls" "$tmp/.logs" "$tmp/ui/.logs"
  (cd "$tmp" && MACHINE_LABEL=${1:-} NPROC=${2:-4} PATH="$tmp/bin:$PATH" scripts/gate-spec.sh 1 2>&1)
}
calls() { if [ -f "$tmp/jmh-calls" ]; then wc -l < "$tmp/jmh-calls"; else echo 0; fi; }
regex='^(?:io\.flottila\.mapping\.bench\.OrdersBench\.smallLatency)$'

# no label: measured and printed, nothing judged, bench.tsv and bench/RESULTS.md untouched
latency 1 105
out=$(gate)
check "no label: one JMH run" "$(calls)" 1
check "no label: fail on error" "$(sed -n 1p "$tmp/jmh-calls" | grep -c -- '-foe true')" 1
check "no label: measured" "$(lines "$out" 'VERIFIED_PASS  5 jmh  (.logs/1-jmh.log)')" 1
check "no label: judgement NOT_RUN" "$(echo "$out" | grep -c '^NOT_RUN  *5 bench judgement')" 1
check "no label: bench.tsv untouched" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n%s' "$row" "$treerow")"
check "no label: no RESULTS.md" "$(test -e "$tmp/bench/RESULTS.md" && echo written || echo none)" none
out=$(gate other-label)
check "other label: judgement NOT_RUN" "$(echo "$out" | grep -c '^NOT_RUN  *5 bench judgement')" 1

# the label but not 4 CPUs: the cross-check fails, nothing judged or written
out=$(gate $M 2)
check "nproc 2: fail" "$(lines "$out" 'EXECUTED_FAIL  5 bench judgement  (MACHINE_LABEL=claude-cloud-4vcpu, but nproc is 2)')" 1
check "nproc 2: bench.tsv untouched" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n%s' "$row" "$treerow")"

# the label, a pass: one JMH run, judged, step 4 writes the new compile/orders row
out=$(gate $M)
check "pass: one JMH run" "$(calls)" 1
check "pass: judged" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement  (.logs/1-bench.log)')" 1
check "pass: written" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' "compile/orders	$M	2	ms/op" "$row" "$treerow")"

# the label, a regression, then two re-runs of the regressed scenario only (#193), judged on the median of the
# three files: 130, 110, 105 gives 110, a pass; step 4 writes from the same three files
latency 1 130
latency 2 110
latency 3 105
out=$(gate $M)
check "re-run: three JMH runs" "$(calls)" 3
check "re-run: only the regressed" "$(sed -n 2p "$tmp/jmh-calls" | grep -cF -- "-rff .logs/1-jmh-2.json $regex")" 1
check "re-run: and again" "$(sed -n 3p "$tmp/jmh-calls" | grep -cF -- "-rff .logs/1-jmh-3.json $regex")" 1
check "re-run: fail on error" "$(sed -n 2,3p "$tmp/jmh-calls" | grep -c -- '-foe true')" 2
check "re-run: both measured" "$(echo "$out" | grep -c 'VERIFIED_PASS  5 jmh re-run [23]  (.logs/1-jmh-[23].log)')" 2
check "re-run: first judgement kept" "$(grep -c 'is worse than the baseline' "$tmp/.logs/1-bench.log")" 1
check "re-run: judged on three runs" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement on three runs  (.logs/1-bench-2.log)')" 1
check "re-run: the median" "$(grep -c 'ok   orders/small/latency 110 us/op' "$tmp/.logs/1-bench-2.log")" 1
check "re-run: no failed judgement" "$(lines "$out" 'EXECUTED_FAIL  5 bench')" 0
check "re-run: written" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n' "compile/orders	$M	2	ms/op" "$row" "$treerow")"

# the label, a regression in the median: 130, 125, 110 gives 125 (the later file alone would pass); the gate fails,
# step 4 writes nothing
latency 2 125
latency 3 110
out=$(gate $M)
check "median regressed: three JMH runs" "$(calls)" 3
check "median regressed: fail" "$(lines "$out" 'EXECUTED_FAIL  5 bench judgement on three runs  (.logs/1-bench-2.log)')" 1
check "median regressed: worse" "$(grep -c 'orders/small/latency: 125 us/op is worse' "$tmp/.logs/1-bench-2.log")" 1
check "median regressed: regen refuses" "$(lines "$out" 'EXECUTED_FAIL  4 ratchet regen')" 1
check "median regressed: bench.tsv untouched" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n%s' "$row" "$treerow")"

# the median in the first file (130, 140, 105 gives 130) and in the last file (140, 125, 130 gives 130): the gate
# judges the median wherever it sits, so a judgement on one fixed file (the second, the first re-run) fails here
for order in "130 140 105" "140 125 130"; do
  set -- $order
  latency 1 "$1"
  latency 2 "$2"
  latency 3 "$3"
  out=$(gate $M)
  check "median at an end ($order): three JMH runs" "$(calls)" 3
  check "median at an end ($order): fail" "$(lines "$out" 'EXECUTED_FAIL  5 bench judgement on three runs  (.logs/1-bench-2.log)')" 1
  check "median at an end ($order): the median" "$(grep -c 'orders/small/latency: 130 us/op is worse' "$tmp/.logs/1-bench-2.log")" 1
done

# the label, a missed target and no regression (#193): re-run twice, judged on three files; 600, 450, 480 gives 480,
# the target <= 500 is met
row="orders/small/latency	$M	550	us/op"
latency 1 600
latency 2 450
latency 3 480
out=$(gate $M)
check "target: three JMH runs" "$(calls)" 3
check "target: only the missed" "$(sed -n 3p "$tmp/jmh-calls" | grep -cF -- "-rff .logs/1-jmh-3.json $regex")" 1
check "target: first judgement kept" "$(grep -c 'misses the target' "$tmp/.logs/1-bench.log")" 1
check "target: judged on three runs" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement on three runs  (.logs/1-bench-2.log)')" 1

# a JMH run that fails (a heap fork out of memory under -foe true) fails the gate in any run, whatever the judgement
row="orders/small/latency	$M	100	us/op"
latency 1 105
touch "$tmp/canned/jmh-1.fail"
out=$(gate $M)
check "failed run: one JMH run" "$(calls)" 1
check "failed run: fail" "$(lines "$out" 'EXECUTED_FAIL  5 jmh  (.logs/1-jmh.log)')" 1
check "failed run: judged" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement  (.logs/1-bench.log)')" 1
rm "$tmp/canned/jmh-1.fail"
latency 1 130
latency 2 110
latency 3 105
touch "$tmp/canned/jmh-3.fail"
out=$(gate $M)
check "failed re-run: fail" "$(lines "$out" 'EXECUTED_FAIL  5 jmh re-run 3  (.logs/1-jmh-3.log)')" 1
check "failed re-run: judged" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement on three runs  (.logs/1-bench-2.log)')" 1
rm "$tmp/canned/jmh-3.fail"

# the tree scenario (#188): three runs of `npm run e2e -- tree-20128`, each with its own result file in TREE_P95_FILE,
# the median in ui/.logs/<spec>-tree-20128.json. Unlabelled it is printed; labelled it is judged with the JMH file.
tcalls() { if [ -f "$tmp/tree-calls" ]; then wc -l < "$tmp/tree-calls"; else echo 0; fi; }
tfile="$tmp/ui/.logs/1-tree-20128.json"
tjson() { printf '{"scenario":"ui/tree-20128/scroll","score":%s,"unit":"ms"}\n' "$1"; }
row="orders/small/latency	$M	100	us/op"
latency 1 105
out=$(gate)
check "tree, no label: three runs" "$(tcalls)" 3
check "tree, no label: own file per run" "$(sort -u "$tmp/tree-calls" | wc -l)" 3
check "tree, no label: runs pass" "$(echo "$out" | grep -c 'VERIFIED_PASS  5 tree-20128 run')" 3
check "tree, no label: median file" "$(cat "$tfile")" "$(tjson 25)"
check "tree, no label: median printed" "$(lines "$out" 'tree-20128 p95 median 25 ms')" 1
check "tree, no label: bench.tsv untouched" "$(cat "$tmp/ratchet/bench.tsv")" "$(printf '%s\n%s' "$row" "$treerow")"

# the label: the median is judged and written by step 4 (a gain moves the baseline)
tree 10 30 12
out=$(gate $M)
check "tree pass: median file" "$(cat "$tfile")" "$(tjson 12)"
check "tree pass: judged" "$(lines "$out" 'VERIFIED_PASS  5 bench judgement  (.logs/1-bench.log)')" 1
check "tree pass: gain" "$(grep -c 'GAIN ui/tree-20128/scroll 25 -> 12' "$tmp/.logs/1-bench.log")" 1
check "tree pass: written" "$(grep -c "^ui/tree-20128/scroll	$M	12	ms$" "$tmp/ratchet/bench.tsv")" 1

# the label, a regressed tree result: fails without a re-run of anything
treerow="ui/tree-20128/scroll	$M	10	ms"
tree 20 30 25
out=$(gate $M)
check "tree regression: no JMH re-run" "$(calls)" 1
check "tree regression: fail" "$(lines "$out" 'EXECUTED_FAIL  5 bench judgement  (.logs/1-bench.log)')" 1
check "tree regression: worse" "$(grep -c 'ui/tree-20128/scroll: 25 ms is worse than the baseline 10 ms' "$tmp/.logs/1-bench.log")" 1
check "tree regression: regen refuses" "$(lines "$out" 'EXECUTED_FAIL  4 ratchet regen')" 1

# the label, a tree run that fails: no median file, nothing to judge
treerow="ui/tree-20128/scroll	$M	25	ms"
tree 20 30
out=$(gate $M)
check "tree run fails: reported" "$(lines "$out" 'EXECUTED_FAIL  5 tree-20128 run 3')" 1
check "tree run fails: no median file" "$(test -e "$tfile" && echo written || echo none)" none
check "tree run fails: no result" "$(grep -c 'ui/tree-20128/scroll: no result' "$tmp/.logs/1-bench.log")" 1
check "tree run fails: judgement fails" "$(lines "$out" 'EXECUTED_FAIL  5 bench judgement  (.logs/1-bench.log)')" 1
exit "$fails"
