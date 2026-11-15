#!/bin/bash
# FIX-254: traced demo runs with the counter agent; counts the runs in which the clock thread found a quiet sample while a
# flow thread was inside FlowWorker.failed() - the fault itself, whether or not the report's flow time changed.
# usage: hunt.sh <tree with the built cli jar> <out-dir> <runs> <countagent.jar> [cpu-list]
# The agent jar: javac -d classes CountAgent.java && jar cfm countagent.jar manifest.txt -C classes .   (manifest: Premain-Class: CountAgent)
here=$(cd "$(dirname "$0")" && pwd); tree=$1; out=$2; runs=$3; agent=$4; cpus=${5:-1-3}
mkdir -p "$out"; hits=0; deviating=0
for i in $(seq 1 "$runs"); do
  "$here/run2.sh" "$tree" "$here/trace3.jfc" "$out/r$i" "$agent" "$cpus" > "$out/r$i.line"
  cmp -s "$out/r$i.out" "$here/../expected.txt" || { deviating=$((deviating + 1)); echo "run $i DEVIATES: $(grep -v -x -F -f "$here/../expected.txt" "$out/r$i.out" | head -1)"; }
  if python3 "$here/hit.py" "$out/r$i.jfr" > "$out/r$i.hit"; then hits=$((hits + 1)); echo "run $i HIT: $(cat "$out/r$i.hit")"
  else cmp -s "$out/r$i.out" "$here/../expected.txt" && rm -f "$out/r$i".*; fi
done
echo "hunt: runs=$runs quiet-inside-failed=$hits deviating-reports=$deviating"
