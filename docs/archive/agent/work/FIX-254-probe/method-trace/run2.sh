#!/bin/sh
# one traced demo run with the counter agent: run2.sh <tree> <settings.jfc> <out-prefix> <agent.jar> [cpu-list]
tree=$1; jfc=$2; out=$3; agent=$4; cpus=${5:-1-3}
rm -f "$out.jfr" "$out.counts"
(cd "$tree" && JAVA_TOOL_OPTIONS="-javaagent:$agent=$out.counts -Xlog:jfr+startup=off -XX:StartFlightRecording=filename=$out.jfr,settings=$jfc" taskset -c "$cpus" bin/rail8 test examples/demo > "$out.out" 2> "$out.err")
echo "exit=$? $(grep 'sftp-invoice: partner down' "$out.out")"
