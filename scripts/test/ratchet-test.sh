#!/usr/bin/env bash
# Script test for scripts/ratchet.java (#25, ADR-0020). Throwaway repo root per case, set through RATCHET_ROOT.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tool=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$tmp/out"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in:"; sed 's/^/     /' "$tmp/out"; fails=1; fi; }

T=$'\t'
n=0
fresh() { n=$((n + 1)); root=$tmp/r$n; mkdir -p "$root/ratchet/oracle" "$root/oracle/target/oracle-reports" "$root/quality"
  printf 'module\tclass\tlines\tbranches\tmutation\nquery-core\tC\t90\t80\t80\njq-cli\t-\t80\t-\t-\n' > "$root/quality/floors.tsv"; }
run() { RATCHET_ROOT=$root RATCHET_MACHINE=m1 RATCHET_JMH=$root/jmh.json java "$tool" "$@" > "$tmp/out" 2>&1; echo $?; }
report() { printf '%b' "$2" > "$root/oracle/target/oracle-reports/$1.tsv"; }

# --- oracle pass list: check ---
fresh
printf 'a#1\tinterp\nb#2\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}PASS${T}\nc#3${T}FAIL${T}x\n"
check "all listed pass" "$(run check)" 0
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}FAIL${T}boom\n"
check "listed case fails" "$(run check)" 1
has "names the failing id" 'b#2'
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}STALE${T}\n"
check "listed case stale" "$(run check)" 1
report jqtest-interp "a#1${T}PASS${T}\n"
check "listed case missing from report" "$(run check)" 1
has "missing named" 'missing'

# --- drop a pass-list line -> that is not a failure of check, but regen records a rise on add ---
fresh
printf 'a#1\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp "a#1${T}PASS${T}\nb#2${T}PASS${T}\n"
check "unlisted passing case is fine" "$(run check)" 0
check "regen oracles" "$(run regen --oracles)" 0
has "regen reports the rise" '+1'
check "regen wrote the new id" "$(printf 'a#1\tinterp\nb#2\tinterp\n' | cmp - "$root/ratchet/oracle/jqtest.txt"; echo $?)" 0
report jqtest-interp "a#1${T}FAIL${T}\nb#2${T}PASS${T}\n"
check "regen refuses a drop" "$(run regen --oracles)" 1
check "refused regen leaves the file" "$(printf 'a#1\tinterp\nb#2\tinterp\n' | cmp - "$root/ratchet/oracle/jqtest.txt"; echo $?)" 0

# --- retirement of a missing id when the source sha changed ---
fresh
mkdir -p "$root/src"; echo changed > "$root/src/jq.test"
printf '# source src/jq.test sha256 0000\njq.test#abc\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp ""
check "missing id retired when source sha differs" "$(run check)" 0
has "retirement warns" 'warning'
fresh
mkdir -p "$root/src"; echo same > "$root/src/jq.test"
sha=$(sha256sum "$root/src/jq.test" | cut -d' ' -f1)
printf '# source src/jq.test sha256 %s\njq.test#abc\tinterp\n' "$sha" > "$root/ratchet/oracle/jqtest.txt"
report jqtest-interp ""
check "missing id fails when source sha equal" "$(run check)" 1

# --- def column ---
fresh
printf 'x\td1\tinterp\n' > "$root/ratchet/oracle/differential.txt"
report differential-interp "x${T}PASS${T}${T}d1\n"
check "def equal" "$(run check)" 0
report differential-interp "x${T}PASS${T}${T}d2\n"
check "def changed" "$(run check)" 1
has "def named" 'def'

# --- compile.txt mode column ---
fresh
printf 'x\td1\tconservative\nx\td1\tcopy-only\n' > "$root/ratchet/oracle/compile.txt"
report compile-conservative "x${T}PASS${T}${T}d1\n"
report compile-copy-only "x${T}FAIL${T}${T}d1\n"
check "compile mode lines checked per mode" "$(run check)" 1
has "failing mode named" 'copy-only'

# --- the compile-mode oracle (ADR-0020 amendment for #60): a build without -Pcompile-oracle writes no report ---
fresh
printf 'x\td1\tconservative\n' > "$root/ratchet/oracle/compile.txt"
check "check without a compile report passes" "$(run check)" 0
has "check says the compile oracle did not run" 'NOT_RUN compile: no report'
printf 'a#1\tinterp\n' > "$root/ratchet/oracle/jqtest.txt"
check "another oracle without its report still fails" "$(run check)" 1
has "its listed id is missing" 'jqtest: a#1 interp is missing from the report'
# the ticket gate runs the profile and requires the compile report (F-46, #89)
fresh
printf 'x\td1\tconservative\n' > "$root/ratchet/oracle/compile.txt"
check "check --require-compile without a compile report fails" "$(run check --require-compile)" 1
has "its listed compile id is missing" 'compile: x conservative is missing from the report'
report compile-conservative "x${T}PASS${T}${T}d1\n"
check "check --require-compile with the report passes" "$(run check --require-compile)" 0
check "check with an unknown option is usage" "$(run check --frobnicate)" 2
fresh
printf 'x\td1\tconservative\n' > "$root/ratchet/oracle/compile.txt"
report compile-conservative "x${T}PASS${T}${T}d1\ny${T}FAIL${T}disagree: stdout differs at byte 0${T}d2\n"
check "regen refuses a Disagreement (F-29)" "$(run regen --oracles)" 1
has "the Disagreement named" 'compile: y conservative disagrees'
check "refused regen leaves compile.txt" "$(printf 'x\td1\tconservative\n' | cmp - "$root/ratchet/oracle/compile.txt"; echo $?)" 0
report compile-conservative "z${T}PASS${T}${T}d3\n"
check "regen fails a listed id missing from an existing compile report" "$(run regen --oracles)" 1
has "the missing id named" 'compile: x conservative is missing from the report'
fresh
# w is a row without a detail column: not a Disagreement, and not a pass
report compile-conservative "w${T}FAIL\nx${T}PASS${T}${T}d1\ny${T}FAIL${T}left out: the conservative run ended with BUDGET STEPS${T}d2\njq.test#abc${T}FAIL${T}stub: the optimised run reached a Stub: s${T}abc\n"
check "regen writes compile.txt" "$(run regen --oracles)" 0
check "compile.txt holds the conservative line of every passing case" "$(printf 'x\td1\tconservative\n' | cmp - "$root/ratchet/oracle/compile.txt"; echo $?)" 0

# --- drop compile <file> (F-46, #88): only listed ids leave, and only as FAIL stub: rows; else nothing is written ---
fresh
mkdir -p "$root/docs/findings"; echo finding > "$root/docs/findings/F-9.md"
listed=$'# jq-ref 1.8.2 sha256 00\na\td1\tconservative\nb\td2\tconservative\nc\td3\tconservative\nd\td4\tconservative\n'
listed+=$'e\td5\tconservative\nf\td6\tconservative\ng\td7\tconservative\nh\td8\tconservative\n'
printf '%s' "$listed" > "$root/ratchet/oracle/compile.txt"
report compile-conservative "a${T}FAIL${T}stub: the optimised run reached a Stub: s${T}d1\nb${T}FAIL${T}stub: the conservative run reached a Stub: s${T}d2\nc${T}FAIL${T}stub: s${T}d3\nd${T}PASS${T}${T}d4\ne${T}FAIL${T}left out: x${T}d5\nf${T}CRASH${T}stub: s${T}d6\ng${T}FAIL\n"
unchanged() { check "$1 leaves compile.txt" "$(printf '%s' "$listed" | cmp - "$root/ratchet/oracle/compile.txt"; echo $?)" 0; }
# refuse <name> <expected message> <drop file lines…>: a refused drop exits 1, names why, and writes nothing
refuse() { local name=$1 why=$2; shift 2; printf '%s\n' "$@" > "$root/drop.txt"
  check "drop refuses $name" "$(run drop compile drop.txt)" 1; has "$name named" "$why"; has "$name: nothing written" 'drop refused: nothing written'; unchanged "$name"; }
refuse "an id not in compile.txt" 'FAIL compile: x is not in compile.txt' '# finding F-9 the owner allowed it' a x
refuse "a PASS row" 'FAIL compile: d conservative is PASS, not FAIL stub:' '# finding F-9' a d
refuse "a FAIL row that is no Stub" 'FAIL compile: e conservative is FAIL, not FAIL stub:' '# finding F-9' a e
refuse "a stub: detail that is no FAIL" 'FAIL compile: f conservative is CRASH, not FAIL stub:' '# finding F-9' a f
refuse "a FAIL row without a detail" 'FAIL compile: g conservative is FAIL, not FAIL stub:' '# finding F-9' a g
refuse "an id missing from the report" 'FAIL compile: h conservative is missing from the report' '# finding F-9' a h
refuse "a drop file without a finding header" 'the first line of drop.txt must be' a b
refuse "a header that names no finding file" 'docs/findings/F-10.md does not exist' '# finding F-10' a
: > "$root/drop.txt"
check "drop refuses an empty drop file" "$(run drop compile drop.txt)" 1
has "empty drop file named" 'the first line of drop.txt must be'
unchanged "an empty drop file"
printf '# finding F-9: the owner allowed it\na\nb\n' > "$root/drop.txt"
check "drop of listed FAIL stub: ids" "$(run drop compile drop.txt)" 0
has "drop prints a dropped id" 'dropped compile: a conservative'
has "drop prints the other dropped id" 'dropped compile: b conservative'
has "drop prints the count and the finding" 'drop compile: 2 dropped (F-9)'
check "only the listed ids leave; an unlisted FAIL stub: id and the headers stay" \
  "$(printf '# jq-ref 1.8.2 sha256 00\nc\td3\tconservative\nd\td4\tconservative\ne\td5\tconservative\nf\td6\tconservative\ng\td7\tconservative\nh\td8\tconservative\n' | cmp - "$root/ratchet/oracle/compile.txt"; echo $?)" 0
check "drop names only compile" "$(run drop jqtest drop.txt)" 2
check "drop needs a file" "$(run drop compile)" 2

# --- coverage and floors ---
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\noracle/query-core,p,A,0,0,15,85,8,92,0,0,0,0\n' > "$tmp/agg.csv"
mkdir -p "$root/oracle/target/site/jacoco-aggregate"; cp "$tmp/agg.csv" "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "coverage at its line" "$(run check)" 0
sed -i 's/,8,92,/,9,91,/' "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "lines below the ratchet" "$(run check)" 1
has "coverage named" 'query-core'
printf 'module\tlines %%\tbranches %%\nquery-core\t80\t70\n' > "$root/ratchet/coverage.tsv"
sed -i 's/,9,91,/,11,89,/;s/,15,85,/,25,75,/' "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "branches below the floor, above the ratchet" "$(run check)" 1
has "floor named" 'floor'
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
check "module not built is NOT_RUN, not a failure" "$(run check)" 0
has "NOT_RUN printed" 'NOT_RUN'

# --- mutation ---
fresh
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t10\t100\n' > "$root/ratchet/mutation.tsv"
mkdir -p "$root/query-core/target/pit-reports"
pit() { { echo "<mutations>"; for i in $(seq 1 "$1"); do echo "<mutation detected='true' status='KILLED'/>"; done
  for i in $(seq 1 "$2"); do echo "<mutation detected='false' status='SURVIVED'/>"; done; echo "</mutations>"; } > "$root/query-core/target/pit-reports/mutations.xml"; }
pit 90 10
check "mutation at its line" "$(run check)" 0
pit 85 15
check "mutation score dropped" "$(run check)" 1
pit 70 30
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t30\t100\n' > "$root/ratchet/mutation.tsv"
check "mutation under floor 80" "$(run check)" 1
pit 90 10
check "floor-mutation passes" "$(run floor-mutation query-core)" 0
pit 70 30
check "floor-mutation fails under floor" "$(run floor-mutation query-core)" 1

# --- mutation: the module's own report combined with oracle's crossModule report (ADR-0014, #45) ---
# mut <status> [<field>=<value>]: one mutant of class p.A; a field argument changes one of its identifying fields
mut() { local s=$1 c=p.A me=run d='()V' l=7 mu=org.M i=3; shift
  for a in "$@"; do case $a in class=*) c=${a#*=} ;; method=*) me=${a#*=} ;; desc=*) d=${a#*=} ;; line=*) l=${a#*=} ;; mutator=*) mu=${a#*=} ;; index=*) i=${a#*=} ;; esac; done
  printf "<mutation detected='x' status='%s' numberOfTestsRun='1'><sourceFile>A.java</sourceFile><mutatedClass>%s</mutatedClass><mutatedMethod>%s</mutatedMethod><methodDescription>%s</methodDescription><lineNumber>%s</lineNumber><mutator>%s</mutator><indexes><index>%s</index></indexes><blocks><block>1</block></blocks><killingTest/><description>d</description></mutation>\n" "$s" "$c" "$me" "$d" "$l" "$mu" "$i"; }
xml() { { echo '<?xml version="1.0" encoding="UTF-8"?>'; echo '<mutations partial="false">'; cat; echo '</mutations>'; } > "$root/$1/target/pit-reports/mutations.xml"; }
fresh
mkdir -p "$root/query-core/target/pit-reports" "$root/oracle/target/pit-reports"
{ mut SURVIVED; mut NO_COVERAGE line=8; mut KILLED line=9; mut KILLED line=10; } | xml query-core
check "own report only: as before" "$(run floor-mutation query-core)" 1
has "own report only counts its own kills" 'query-core: 50 % (2 surviving of 4)'
# line 7 is killed by oracle only, line 9 by its own report only, line 10 by both
{ mut KILLED; mut NO_COVERAGE line=8; mut SURVIVED line=9; mut KILLED line=10; mut KILLED class=p.Other; } | xml oracle
run floor-mutation query-core > /dev/null
has "a mutant killed by either report or by both is killed, counted once" 'query-core: 75 % (1 surviving of 4)'
for f in class=p.B method=other 'desc=(I)V' line=6 mutator=org.N index=4; do
  { mut SURVIVED; } | xml query-core
  { mut KILLED "$f"; } | xml oracle
  run floor-mutation query-core > /dev/null
  has "an oracle kill with another $f is another mutant" 'query-core: 0 % (1 surviving of 1)'
done
{ mut NO_COVERAGE; } | xml query-core
{ mut NO_COVERAGE; } | xml oracle
run floor-mutation query-core > /dev/null
has "NO_COVERAGE in both reports stays not killed" 'query-core: 0 % (1 surviving of 1)'
{ for l in $(seq 1 10); do mut SURVIVED line=$l; done; for l in $(seq 11 100); do mut KILLED line=$l; done; } | xml query-core
{ for l in $(seq 1 5); do mut KILLED line=$l; done; } | xml oracle
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t5\t100\n' > "$root/ratchet/mutation.tsv"
check "check reads the combined score" "$(run check)" 0
printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t10\t100\n' > "$root/ratchet/mutation.tsv"
mkdir -p "$root/oracle/target/site/jacoco-aggregate" "$root/jq-cli/target/site/jacoco"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\noracle/query-core,p,A,0,0,10,90,5,95\n' > "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\nj,p,A,0,0,0,0,10,90\n' > "$root/jq-cli/target/site/jacoco/jacoco.csv"
printf '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":50.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "regen --all with both reports" "$(run regen --all)" 0
check "regen --all writes the combined count" "$(printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t5\t100\n' | cmp - "$root/ratchet/mutation.tsv"; echo $?)" 0
rm "$root/query-core/target/pit-reports/mutations.xml"
check "oracle's report alone is no report of the module" "$(run floor-mutation query-core)" 0
has "module without its own report is NOT_RUN" 'NOT_RUN mutation query-core'

# --- mutation: jq-cli's crossModule report joins the same way as oracle's (ADR-0014, #47) ---
fresh
mkdir -p "$root/query-core/target/pit-reports" "$root/oracle/target/pit-reports" "$root/jq-cli/target/pit-reports"
# line 7 is killed by jq-cli only, line 8 by oracle only, line 9 by neither, line 10 by its own report
{ mut SURVIVED; mut SURVIVED line=8; mut SURVIVED line=9; mut KILLED line=10; } | xml query-core
{ mut KILLED line=8; mut SURVIVED line=9; } | xml oracle
{ mut KILLED; mut NO_COVERAGE line=9; mut KILLED class=p.Cli; } | xml jq-cli
run floor-mutation query-core > /dev/null
has "a mutant killed by jq-cli's or oracle's report is killed" 'query-core: 75 % (1 surviving of 4)'
rm "$root/oracle/target/pit-reports/mutations.xml"
run floor-mutation query-core > /dev/null
has "jq-cli's report counts without oracle's" 'query-core: 50 % (2 surviving of 4)'
rm "$root/query-core/target/pit-reports/mutations.xml"
run floor-mutation query-core > /dev/null
has "jq-cli's report alone is no report of the module" 'NOT_RUN mutation query-core'

# --- bench: empty tolerance skipped ---
fresh
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t\n' > "$root/ratchet/bench.tsv"
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":900.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "empty tolerance skipped by check" "$(run check)" 0
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t10\n' > "$root/ratchet/bench.tsv"
check "tolerance set, slower beyond it" "$(run check)" 1
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":105.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "tolerance set, within it" "$(run check)" 0
# A method name is its ADR-0024 id with '.' written '_': a slower G6_compact_10m must fail the G6.compact.10m line.
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG6.compact.10m\tm1\t100\tus/op\t10\n' > "$root/ratchet/bench.tsv"
echo '[{"benchmark":"io.github.subapavel.jqj.bench.Gates.G6_compact_10m","mode":"avgt","primaryMetric":{"score":900.0,"scoreUnit":"us/op"}}]' > "$root/jmh.json"
check "method G6_compact_10m reads as id G6.compact.10m" "$(run check)" 1
# A NaN score is no score: it fails, as a bare literal and as JMH 1.37 writes it ("NaN", a string).
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t10\n' > "$root/ratchet/bench.tsv"
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":NaN,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "bare NaN score fails" "$(run check)" 1
has "NaN named" 'bench G1 has no finite score (NaN or INF)'
echo '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":"NaN","scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "JMH's \"NaN\" score fails" "$(run check)" 1
has "\"NaN\" named" 'bench G1 has no finite score (NaN or INF)'
check "regen --all refuses a NaN score" "$(run regen --all)" 1
has "regen names the NaN" 'bench G1 has no finite score (NaN or INF)'
# A scenario with a tolerance that the machine's JMH result lacks fails.
echo '[{"benchmark":"x.G2","mode":"avgt","primaryMetric":{"score":50.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
check "scenario missing from the JMH result fails" "$(run check)" 1
has "missing scenario named" 'bench G1 is missing from the JMH result'

# --- regen --all ---
fresh
printf 'module\tlines %%\tbranches %%\nquery-core\t92\t85\n' > "$root/ratchet/coverage.tsv"
check "regen --all requires every report" "$(run regen --all)" 1
has "missing report named" 'query-core'
mkdir -p "$root/oracle/target/site/jacoco-aggregate" "$root/jq-cli/target/site/jacoco" "$root/query-core/target/pit-reports"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\noracle/query-core,p,A,0,0,10,90,5,95\n' > "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\nj,p,A,0,0,0,0,10,90\n' > "$root/jq-cli/target/site/jacoco/jacoco.csv"
pit 95 5
printf '[{"benchmark":"x.G1","mode":"avgt","primaryMetric":{"score":50.0,"scoreUnit":"ms/op"}}]' > "$root/jmh.json"
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t100\tms/op\t\n' > "$root/ratchet/bench.tsv"
check "regen --all with every report" "$(run regen --all)" 0
check "coverage rose" "$(printf 'module\tlines %%\tbranches %%\njq-cli\t90\t100\nquery-core\t95\t90\n' | cmp - "$root/ratchet/coverage.tsv"; echo $?)" 0
check "mutation written" "$(printf 'module\tsurviving mutants\tgenerated mutants\nquery-core\t5\t100\n' | cmp - "$root/ratchet/mutation.tsv"; echo $?)" 0
check "empty tolerance overwritten" "$(printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\n' | cmp - "$root/ratchet/bench.tsv"; echo $?)" 0
# A toleranced scenario missing from this machine's JMH result fails regen --all; another machine's line, a line
# with an empty tolerance and a line without the tolerance column do not.
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\nG3\tm2\t100\tms/op\t10\nG4\tm1\t100\tms/op\t\nG5\tm1\t100\tms/op\n' > "$root/ratchet/bench.tsv"
check "regen --all keeps lines without a tolerance and other machines' lines" "$(run regen --all)" 0
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\nG2\tm1\t100\tms/op\t10\n' > "$root/ratchet/bench.tsv"
check "regen --all refuses a toleranced scenario missing from the result" "$(run regen --all)" 1
has "regen names the missing scenario" 'bench G2 is missing from the JMH result of m1'
check "refused regen leaves bench.tsv" "$(printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\nG2\tm1\t100\tms/op\t10\n' | cmp - "$root/ratchet/bench.tsv"; echo $?)" 0
printf 'scenario\tmachine\tscore\tunit\ttolerance%%\nG1\tm1\t50\tms/op\t\n' > "$root/ratchet/bench.tsv"
printf 'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\noracle/query-core,p,A,0,0,10,90,9,91\n' > "$root/oracle/target/site/jacoco-aggregate/jacoco.csv"
check "regen --all refuses a coverage drop" "$(run regen --all)" 1

echo "usage error"
check "no arguments" "$(run)" 2
check "unknown command" "$(run frobnicate)" 2

exit $fails
