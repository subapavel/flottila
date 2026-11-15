#!/usr/bin/env bash
# Script test for scripts/pit-summary.sh (#67). Builds PIT report fixtures in a temp tree.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../pit-summary.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }

# report <module> <killed> <survived> <non-viable>
report() {
  local d=$tmp/$1/target/pit-reports
  mkdir -p "$d"
  {
    echo "<mutations>"
    for ((i = 0; i < $2; i++)); do echo "<mutation detected='true' status='KILLED'><x/></mutation>"; done
    for ((i = 0; i < $3; i++)); do echo "<mutation detected='false' status='SURVIVED'><x/></mutation>"; done
    for ((i = 0; i < $4; i++)); do echo "<mutation detected='false' status='NON_VIABLE'><x/></mutation>"; done
    echo "</mutations>"
  } > "$d/mutations.xml"
}
printf 'engine/low\t90\nengine/good\t80\nengine/exact\t75\nengine/none\t50\n' > "$tmp/mutation.tsv"
report engine/low 17 3 2   # 85 % < floor 90
report engine/good 9 1 0   # 90 % >= 80
report engine/exact 3 1 0  # 75 % == 75
export PIT_ROOT=$tmp

out=$("$tool" "$tmp/mutation.tsv" engine/low); rc=$?
check "below floor: exit" "$rc" 1
check "below floor: line" "$out" "engine/low 17/20 85% floor 90% DROP"
out=$("$tool" "$tmp/mutation.tsv" engine/good engine/exact); rc=$?
check "at/above floor: exit" "$rc" 0
check "at/above floor: lines" "$out" "engine/good 9/10 90% floor 80% ok
engine/exact 3/4 75% floor 75% ok"
out=$("$tool" "$tmp/mutation.tsv" engine/none); rc=$?
check "missing report: exit" "$rc" 1
check "missing report: line" "$out" "engine/none no PIT report DROP"
out=$("$tool" "$tmp/mutation.tsv" engine/good engine/low); rc=$?
check "mixed: exit" "$rc" 1

# surviving-count format (#87): `module TAB survivors TAB generated`; one more survivor is a drop
printf 'engine/mapping\t10\t454\nengine/same\t10\t454\nengine/better\t10\t454\n' > "$tmp/survivors.tsv"
report engine/mapping 443 11 5   # taken at 444/454: 10 survivors
report engine/same 444 10 0
report engine/better 445 9 1
out=$("$tool" "$tmp/survivors.tsv" engine/mapping); rc=$?
check "one more survivor: exit" "$rc" 1
check "one more survivor: line" "$out" "engine/mapping 443/454 survivors 11 floor 10 DROP"
out=$("$tool" "$tmp/survivors.tsv" engine/same engine/better); rc=$?
check "same or fewer survivors: exit" "$rc" 0
check "same or fewer survivors: lines" "$out" "engine/same 444/454 survivors 10 floor 10 ok
engine/better 445/454 survivors 9 floor 10 ok"

out=$("$tool" "$tmp/survivors.tsv" engine/none); rc=$?
check "no floor row, no report: exit" "$rc" 1
report engine/none 5 1 0
out=$("$tool" "$tmp/survivors.tsv" engine/none); rc=$?
check "no floor row: line" "$out" "engine/none 5/6 survivors 1 floor none ok"

# --run <log> <module>...: each module's PIT section is preceded by a line naming the module
cat > "$tmp/fakemvn" <<'FAKE'
#!/usr/bin/env bash
echo "pit output for $*"
[ "${FAKE_FAIL:-}" = "$3" ] && exit 1
exit 0
FAKE
chmod +x "$tmp/fakemvn"
PIT_MVN="$tmp/fakemvn" "$tool" --run "$tmp/pit.log" engine/a engine/b > /dev/null; rc=$?
check "run: exit" "$rc" 0
check "run: labelled sections" "$(cat "$tmp/pit.log")" "=== PIT engine/a ===
pit output for -B -pl engine/a -am test-compile org.pitest:pitest-maven:mutationCoverage
=== PIT engine/b ===
pit output for -B -pl engine/b -am test-compile org.pitest:pitest-maven:mutationCoverage"
FAKE_FAIL=engine/a PIT_MVN="$tmp/fakemvn" "$tool" --run "$tmp/pit.log" engine/a engine/b > /dev/null; rc=$?
check "run: one module fails, exit" "$rc" 1
check "run: failure does not stop the next module" "$(grep -c '=== PIT' "$tmp/pit.log")" 2
exit $fails
