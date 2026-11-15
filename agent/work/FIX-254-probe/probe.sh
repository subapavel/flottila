#!/bin/sh
# FIX-254 probe: runs `bin/rail8 test examples/demo` N times and keeps every run whose report differs from the
# expected one. A measurement, never a check: it exits 0 whatever it sees.
# usage: probe.sh <tree> <label> <cpu-list> <N> <load> <jfr>
#   <cpu-list>  taskset's list for the launched JVM, e.g. 0 or 0,1 or 0-3
#   <load>      number of busy loops started on the same cpu list for the duration (0 = none)
#   <jfr>       yes = record contended monitors and pauses (monitor.jfc) for every run, kept for deviating runs only
set -u
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tree=$1; label=$2; cpus=$3; n=$4; load=$5; jfr=$6
out="$here/$label"
rm -rf "$out"; mkdir -p "$out"
pids=
k=0
while [ "$k" -lt "$load" ]; do
  taskset -c "$cpus" sh -c 'while :; do :; done' &
  pids="$pids $!"
  k=$((k+1))
done
trap 'for p in $pids; do kill "$p" 2>/dev/null; done' EXIT INT TERM
base=${JAVA_TOOL_OPTIONS:-}
start=$(date +%s)
i=1; dev=0
while [ "$i" -le "$n" ]; do
  f="$out/run-$i"
  if [ "$jfr" = yes ]; then
    JAVA_TOOL_OPTIONS="$base -Xlog:jfr+startup=off -XX:StartFlightRecording=filename=$f.jfr,settings=$here/monitor.jfc"
  else
    JAVA_TOOL_OPTIONS="$base"
  fi
  export JAVA_TOOL_OPTIONS
  (cd "$tree" && taskset -c "$cpus" bin/rail8 test examples/demo) > "$f.out" 2> "$f.err"
  code=$?
  if [ "$code" -eq 0 ] && cmp -s "$f.out" "$here/expected.txt"; then
    rm -f "$f.out" "$f.err" "$f.jfr"
  else
    dev=$((dev+1))
    echo "exit $code" >> "$f.err"
    echo "$label run $i DEVIATES (exit $code): $(diff "$here/expected.txt" "$f.out" | grep '^>' | tr '\n' ' ')"
  fi
  i=$((i+1))
done
end=$(date +%s)
echo "$label: cpus=$cpus load=$load jfr=$jfr runs=$n deviating=$dev seconds=$((end-start))"
