#!/usr/bin/env bash
# Script test for HANDOVER.md (#190, GATE check 9): the file exists and names every item of the check.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
doc=$here/../../HANDOVER.md
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if [ -f "$doc" ] && grep -qF -- "$2" "$doc"; then echo "ok   $1"; else echo "FAIL $1: HANDOVER.md lacks '$2'"; fails=1; fi; }

check "HANDOVER.md exists" "$([ -f "$doc" ] && echo yes || echo no)" yes
# start the stack from a clean clone (JDK 25, Node 22)
has "JDK 25" "JDK 25"
has "Node 22" "Node 22"
has "dev service" "./mvnw -pl service -am quarkus:dev"
has "dev UI" "npm run dev"
has "one-origin jar (-Pui)" "-Pui"
has "loopback bind" "127.0.0.1"
has "workspace" "workspace"
has "installed mvn" "installed Maven"
has "Windows section" "Windows PowerShell"
has "not verified by the gate" "not verified by the gate"
has "benchmarks.jar on Windows" 'bench\jmh\target\benchmarks.jar'
# draw and run a mapping
has "New mapping" "New mapping"
has "Signature" "Signature"
has "Definition" "Definition"
has "Test tab" "**Test**"
has "Display Queue" "Display Queue"
# gates and benchmarks
has "ticket gate" "scripts/gate-ticket.sh"
has "spec gate" "scripts/gate-spec.sh"
has "machine label" "MACHINE_LABEL=claude-cloud-4vcpu"
has "-Pbench" "-Pbench"
has "bench results" "bench/RESULTS.md"
# open findings, ADRs to revisit, next three specs
has "open findings" "Open findings"
has "ADRs to revisit" "ADRs to revisit"
has "next three specs" "Next three specs"
has "JSON output" "JSON output"
has "bytecode tier" "bytecode tier"
has "PO importer" "PO importer"
exit $fails
