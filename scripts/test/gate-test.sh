#!/usr/bin/env bash
# Script test for scripts/gate-ticket.sh and scripts/gate-spec.sh (#25, ADR-0014). A throwaway repo holds copies of
# the gate scripts and ratchet.java, a stub mvnw that logs its arguments, stub check scripts, and a stub java for
# the JMH jar. STUB_FAIL=<word>:<rc> makes the stub mvnw or check script whose arguments contain <word> exit <rc>.
# STUB_COMPILE_REPORT=<printf rows> makes the stub mvnw, when called with the compile-oracle profile, write them as
# oracle/target/oracle-reports/compile-conservative.tsv.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected '$3' got '$2'"; fails=1; else echo "ok   $1"; fi; }
has() { if grep -q -- "$2" "$3"; then echo "ok   $1"; else echo "FAIL $1: no '$2' in $3:"; sed 's/^/     /' "$3"; fails=1; fi; }
hasnt() { if grep -q -- "$2" "$3"; then echo "FAIL $1: '$2' found in $3"; fails=1; else echo "ok   $1"; fi; }

repo=$tmp/repo
mkdir -p "$repo/scripts" "$repo/quality" "$repo/ratchet" "$repo/stubbin"
g() { git -C "$repo" "$@"; }
cp "$here/../gate-ticket.sh" "$here/../gate-spec.sh" "$here/../ratchet.java" "$here/../census.java" "$repo/scripts/" 2>/dev/null
printf 'module\tclass\tlines\tbranches\tmutation\nquery-core\tC\t90\t80\t80\njq-core\tC\t90\t80\t80\njq-cli\t-\t80\t-\t-\n' > "$repo/quality/floors.tsv"
# a recording in the base commit that no later commit changes
mkdir -p "$repo/oracle/src/test/resources/json"
echo old > "$repo/oracle/src/test/resources/json/old.rec"

stub='#!/usr/bin/env bash
echo "$(basename "$0") $*" >> "$STUB_CALLS"
case "${STUB_FAIL:-}" in
  "") ;;
  *) w=${STUB_FAIL%%:*}; rc=${STUB_FAIL##*:}; case "$(basename "$0") $*" in *"$w"*) exit "$rc" ;; esac ;;
esac
case "$(basename "$0") $*" in
  "mvnw "*compile-oracle*) if [ -n "${STUB_COMPILE_REPORT:-}" ]; then
      mkdir -p oracle/target/oracle-reports; printf "$STUB_COMPILE_REPORT" > oracle/target/oracle-reports/compile-conservative.tsv; fi ;;
esac
exit 0'
printf '%s\n' "$stub" > "$repo/mvnw"
for s in check-lf-checkout check-trailers check-core-neutral oracle-record; do printf '%s\n' "$stub" > "$repo/scripts/$s.sh"; done
# stub java: the JMH jar writes its result file, everything else runs the real java
cat > "$repo/stubbin/java" <<JAVA
#!/usr/bin/env bash
if [ "\$1" = "-jar" ]; then
  echo "java \$*" >> "\$STUB_CALLS"
  case "\${STUB_FAIL:-}" in jmh:*) exit "\${STUB_FAIL##*:}" ;; esac
  while [ \$# -gt 0 ]; do [ "\$1" = "-rff" ] && echo '[]' > "\$2"; shift; done
  exit 0
fi
exec $(command -v java) "\$@"
JAVA
chmod +x "$repo/mvnw" "$repo/scripts/"*.sh "$repo/stubbin/java"

g init -q -b main
g config user.email a@b.c
g config user.name t
g add -A
g commit -q -m base
g branch base
mkdir -p "$repo/query-core/src/main/java/io/github/subapavel/jqj/query" "$repo/jq-core/src/main/java/io/github/subapavel/jqj/core"
echo 'class Foo {}' > "$repo/query-core/src/main/java/io/github/subapavel/jqj/query/Foo.java"
echo 'class Bar {}' > "$repo/jq-core/src/main/java/io/github/subapavel/jqj/core/Bar.java"
echo 'module x {}' > "$repo/query-core/src/main/java/module-info.java"
echo 'x' > "$repo/query-core/README.md"
g add -A
g commit -q -m change

export STUB_CALLS=$tmp/calls
export PATH=$repo/stubbin:$PATH
export RATCHET_MACHINE=m1
unset STUB_FAIL

ticket() { : > "$STUB_CALLS"; rm -rf "$repo/.logs"; (cd "$repo" && scripts/gate-ticket.sh "$@" > "$tmp/out" 2>&1; echo $?); }

check "ticket: usage" "$(ticket 25)" 2
check "ticket: green" "$(ticket 25 query-core,jq-core base)" 0
has "step 1 is the whole reactor with -Pbench and the compile-mode oracle (F-46)" "mvnw -B -Pbench,compile-oracle verify" "$STUB_CALLS"
# F-27: PIT left the ticket gate, even with changed classes in class-C modules
hasnt "no PIT call with changed query-core and jq-core classes" "pitest" "$STUB_CALLS"
hasnt "no class list for PIT" "targetClasses" "$STUB_CALLS"
check "mvnw runs only step 1" "$(grep -c '^mvnw ' "$STUB_CALLS")" 1
has "step 3 LF check" "check-lf-checkout.sh" "$STUB_CALLS"
has "step 3 trailer check on ref..HEAD" "check-trailers.sh base..HEAD" "$STUB_CALLS"
has "step 3 word check" "check-core-neutral.sh" "$STUB_CALLS"
check "log of step 1, no PIT log" "$(ls "$repo/.logs" | grep -c '^25-\(verify\|pit\|pit-floor\)\.log$')" 1
has "step 1 log written" "25-verify.log" "$(ls "$repo/.logs" > "$tmp/ls"; echo "$tmp/ls")"
has "step timings recorded" "^verify	" "$repo/.logs/25-steps.tsv"
hasnt "no pit timing recorded" "^pit" "$repo/.logs/25-steps.tsv"
order=$(grep -o 'mvnw -B -Pbench,compile-oracle verify\|test org.pitest\|check-lf\|check-trailers\|check-core\|java ' "$STUB_CALLS" | tr '\n' ' ')
check "steps in ADR order" "$order" "mvnw -B -Pbench,compile-oracle verify check-lf check-trailers check-core "
# #84: every step that ran prints its result and has its row, and "passed" is the last line
steps() { cut -f1,3 "$repo/.logs/25-steps.tsv" | tr '\t\n' ': '; }
check "green: every step recorded with its exit code (#84)" "$(steps)" "verify:0 lf:0 trailers:0 word:0 ratchet:0 "
results=$(grep -o '^-- [a-z]*: rc [0-9]*, [0-9]* ms$' "$tmp/out" | sed 's/, [0-9]* ms$//' | tr '\n' ' ')
check "green: a result line per step, name and rc (#84)" "$results" "-- verify: rc 0 -- lf: rc 0 -- trailers: rc 0 -- word: rc 0 -- ratchet: rc 0 "
check "green: passed is the last line (#84)" "$(tail -n 1 "$tmp/out")" "gate-ticket: 25 passed"
has "no require file is visible in the log (F-38, #84)" "^== require: none (ORACLE_REQUIRE unset)$" "$tmp/out"

printf 'jq.test#a\n' > "$tmp/req.txt"
: > "$STUB_CALLS"; rm -rf "$repo/.logs"
(cd "$repo" && ORACLE_REQUIRE=$tmp/req.txt scripts/gate-ticket.sh 25 query-core base > "$tmp/out" 2>&1)
has "required case file passed to the oracle" "-Doracle.require=$tmp/req.txt" "$STUB_CALLS"
has "the require file used is visible in the log (F-38, #84)" "^== require: $tmp/req.txt$" "$tmp/out"
# #84: the variable is the only require path; a relative one is the caller's, so Maven gets it absolute
: > "$STUB_CALLS"; rm -rf "$repo/.logs"
(cd "$tmp" && ORACLE_REQUIRE=req.txt repo/scripts/gate-ticket.sh 25 query-core base > "$tmp/out" 2>&1)
has "a relative require path is resolved against the caller's directory (#84)" "-Doracle.require=$tmp/req.txt" "$STUB_CALLS"
# #84: a missing require file fails before Maven starts
: > "$STUB_CALLS"; rm -rf "$repo/.logs"
(cd "$repo" && ORACLE_REQUIRE=$tmp/none.txt scripts/gate-ticket.sh 25 query-core base > "$tmp/out" 2>&1; echo $? > "$tmp/rc")
check "a missing require file fails the gate (#84)" "$(cat "$tmp/rc")" 2
check "a missing require file: Maven never starts (#84)" "$(grep -c '^mvnw ' "$STUB_CALLS")" 0
check "a missing require file: the last line names it (#84)" "$(tail -n 1 "$tmp/out")" \
  "gate-ticket: 25 failed before step verify: ORACLE_REQUIRE is not a file: $tmp/none.txt"
hasnt "a missing require file: no passed line (#84)" "passed" "$tmp/out"
check "a missing require file: no step recorded (#84)" "$(steps)" ""

export STUB_FAIL=verify:7
check "ticket: step 1 fails, its exit code" "$(ticket 25 query-core base)" 7
hasnt "nothing after the failing step 1" "check-lf" "$STUB_CALLS"
has "failing step named" "verify" "$tmp/out"
hasnt "step 1 fails: no passed line (#84)" "passed" "$tmp/out"
check "step 1 fails: its row is the only one (#84)" "$(steps)" "verify:7 "
has "step 1 fails: its result line (#84)" "^-- verify: rc 7, [0-9]* ms$" "$tmp/out"
check "step 1 fails: the last line names the step and its exit code (#84)" "$(tail -n 1 "$tmp/out")" \
  "gate-ticket: 25 step verify failed with exit code 7 (log .logs/25-verify.log)"

# a failing PIT cannot stop the ticket gate: none runs (F-27)
export STUB_FAIL=pitest:9
check "ticket: no PIT to fail" "$(ticket 25 query-core base)" 0
hasnt "no PIT runs" "pitest" "$STUB_CALLS"
has "step 3 reached after step 1" "check-lf" "$STUB_CALLS"

export STUB_FAIL=check-trailers:4
check "ticket: step 3 trailer check fails" "$(ticket 25 query-core base)" 4
hasnt "ratchet not reached" "ratchet.java check" "$tmp/out"
hasnt "a later step fails: no passed line (#84)" "passed" "$tmp/out"
check "a later step fails: every step that ran has its row (#84)" "$(steps)" "verify:0 lf:0 trailers:4 "
results=$(grep -o '^-- [a-z]*: rc [0-9]*' "$tmp/out" | tr '\n' ' ')
check "a later step fails: a result line per step that ran (#84)" "$results" "-- verify: rc 0 -- lf: rc 0 -- trailers: rc 4 "
check "a later step fails: the last line names the step and its exit code (#84)" "$(tail -n 1 "$tmp/out")" \
  "gate-ticket: 25 step trailers failed with exit code 4 (log .logs/25-trailers.log)"
unset STUB_FAIL

# no changed class: the gate runs as with changed classes
check "ticket: nothing to mutate" "$(ticket 25 query-core HEAD)" 0
hasnt "no PIT without changed classes" "pitest" "$STUB_CALLS"
hasnt "no PIT step announced" "== pit" "$tmp/out"

# step 4: the ratchet decides
printf 'jq.test#a\tinterp\n' > "$repo/ratchet/oracle-listed.tmp"
mkdir -p "$repo/ratchet/oracle"
printf 'jq.test#a\tinterp\n' > "$repo/ratchet/oracle/jqtest.txt"
check "ticket: step 4 ratchet fails" "$(ticket 25 query-core base)" 1
has "ratchet failure shown" "jq.test#a" "$tmp/out"
hasnt "the ratchet fails: no passed line (#84)" "passed" "$tmp/out"
check "the ratchet fails: its row with rc 1 (#84)" "$(steps)" "verify:0 lf:0 trailers:0 word:0 ratchet:1 "
check "the ratchet fails: the last line names it (#84)" "$(tail -n 1 "$tmp/out")" \
  "gate-ticket: 25 step ratchet failed with exit code 1 (log .logs/25-ratchet.log)"
rm "$repo/ratchet/oracle/jqtest.txt" "$repo/ratchet/oracle-listed.tmp"

# step 1 runs the compile-mode oracle, so step 4 sees a ratcheted compile-mode case that now fails (F-46, #89)
printf 'x\td1\tconservative\n' > "$repo/ratchet/oracle/compile.txt"
export STUB_COMPILE_REPORT='x\tFAIL\tstub: length/0\td1\n'
check "ticket: a ratcheted compile-mode case that fails fails the gate" "$(ticket 25 query-core base)" 1
has "the failing compile-mode case is named" "compile: x conservative is FAIL" "$tmp/out"
hasnt "the compile-mode oracle is not NOT_RUN" "NOT_RUN compile" "$tmp/out"
export STUB_COMPILE_REPORT='x\tPASS\t\td1\n'
check "ticket: a ratcheted compile-mode case that passes" "$(ticket 25 query-core base)" 0
hasnt "the passing compile-mode oracle is not NOT_RUN" "NOT_RUN compile" "$tmp/out"
# step 1 had the profile but wrote no compile report: the ticket gate requires it, so the listed id is missing
unset STUB_COMPILE_REPORT
rm -rf "$repo/oracle/target"
check "ticket: the profile without a compile report fails the gate" "$(ticket 25 query-core base)" 1
has "step 1 had the profile" "mvnw -B -Pbench,compile-oracle verify" "$STUB_CALLS"
has "the compile-mode case missing from the report is named" "compile: x conservative is missing from the report" "$tmp/out"
hasnt "no NOT_RUN compile in the ticket gate" "NOT_RUN compile" "$tmp/out"
rm -rf "$repo/ratchet/oracle/compile.txt" "$repo/oracle/target"

# step 3 record: the recordings changed since ref are verified (ADR-0020)
mkdir -p "$repo/oracle/src/test/resources/diff"
echo r > "$repo/oracle/src/test/resources/diff/x.rec"
g add -A
g commit -q -m rec
check "ticket: a changed recording" "$(ticket 25 query-core HEAD~1)" 0
has "the changed recording is verified" "oracle-record.sh --verify oracle/src/test/resources/diff/x.rec" "$STUB_CALLS"
hasnt "an unchanged recording is not verified by the ticket gate" "old.rec" "$STUB_CALLS"
check "ticket: no changed recording" "$(ticket 25 query-core HEAD)" 0
hasnt "no changed recording, no record call" "oracle-record" "$STUB_CALLS"
chmod -x "$repo/scripts/oracle-record.sh"
check "ticket: a recorder without its exec bit fails the gate" "$(ticket 25 query-core HEAD~1)" 126
has "the record step is named" "step record failed" "$tmp/out"
chmod +x "$repo/scripts/oracle-record.sh"

# --- spec gate ---
spec() { : > "$STUB_CALLS"; (cd "$repo" && scripts/gate-spec.sh "$@" > "$tmp/out" 2>&1; echo $?); }
check "spec: usage" "$(spec)" 2

rm -rf "$repo/.logs"; mkdir -p "$repo/.logs"
printf 'verify\t50000\t0\npit\t200000\t0\nlf\t100\t0\n' > "$repo/.logs/31-steps.tsv"
printf 'verify\t70000\t0\npit\t90000\t0\n' > "$repo/.logs/32-steps.tsv"
mkdir -p "$repo/bench/jmh/target"
# every report regen --all needs
mkdir -p "$repo/oracle/target/site/jacoco-aggregate" "$repo/jq-cli/target/site/jacoco" "$repo/query-core/target/pit-reports" "$repo/jq-core/target/pit-reports"
hdr=GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED
printf "%s\noracle/query-core,p,A,0,0,5,95,5,95\noracle/jq-core,p,B,0,0,5,95,5,95\n" "$hdr" > "$repo/oracle/target/site/jacoco-aggregate/jacoco.csv"
printf "%s\nj,p,C,0,0,0,0,5,95\n" "$hdr" > "$repo/jq-cli/target/site/jacoco/jacoco.csv"
for m in query-core jq-core; do echo "<mutations><mutation status='KILLED'/></mutations>" > "$repo/$m/target/pit-reports/mutations.xml"; done
# the census of #57: spec 1 (#22) has one case, ratcheted and passing
mkdir -p "$repo/oracle/src/test/resources/census" "$repo/oracle/target/oracle-reports" "$repo/ratchet/oracle"
printf '# census\njq.test#a\t1\t-\tjq.test:1\t-\t-\njq.test#c\t4\t-\tjq.test:9\t-\ttest\n' > "$repo/oracle/src/test/resources/census/jqtest.tsv"
printf 'jq.test#a\tinterpreter\n' > "$repo/ratchet/oracle/jqtest.txt"
printf 'jq.test#a\tPASS\t\n' > "$repo/oracle/target/oracle-reports/jqtest-interpreter.tsv"
check "spec: green" "$(spec 22)" 0
has "slowest step 1" "slowest step 1 (verify): 32 70.0 s" "$tmp/out"
has "step 1 over its target" "over target 60 s" "$tmp/out"
has "step 1 over its target goes to the owner (F-27)" "over target 60 s: report it to the owner in the spec PR (F-27)" "$tmp/out"
hasnt "no fallback named for step 1 (F-27)" "fallback" "$tmp/out"
hasnt "no ticket-gate PIT report (F-27)" "slowest step 2" "$tmp/out"
has "spec step 1 clean verify, with the compile-mode oracle (ADR-0020 amendment for #60)" "mvnw -B clean verify -Pbench,compile-oracle$" "$STUB_CALLS"
has "spec step 2 PIT on whole class-C modules and oracle's crossModule run (ADR-0014)" "mvnw -B -pl query-core,jq-core,oracle -am test org.pitest:pitest-maven:mutationCoverage" "$STUB_CALLS"
# The property would replace every module's POM list (observed, #45): each module keeps its own list in its POM (#47).
has "spec step 2 ends with the PIT goal" "mutationCoverage$" "$STUB_CALLS"
hasnt "no excludedTestClasses property on the PIT call (#47)" "excludedTestClasses" "$STUB_CALLS"
has "spec verifies every recording (ADR-0020)" "oracle-record.sh --verify oracle/src/test/resources/diff/x.rec oracle/src/test/resources/json/old.rec" "$STUB_CALLS"
order=$(grep -o 'clean verify\|oracle-record\|mutationCoverage' "$STUB_CALLS" | tr '\n' ' ')
check "spec record step after clean-verify, before PIT" "$order" "clean verify oracle-record mutationCoverage "
has "spec step 3 JMH" "java -jar bench/jmh/target/benchmarks.jar -prof gc -rf json" "$STUB_CALLS"
# JMH matches the include regex anywhere in <class>.<method> (observed with -l, #102): only Gates reaches bench.tsv.
gates='java -jar bench/jmh/target/benchmarks.jar -prof gc -rf json -rff .logs/spec22-jmh.json \.Gates\.'
check "spec step 3 JMH runs only the Gates class, no diagnostic (ADR-0024, ADR-0019)" "$(grep '^java -jar' "$STUB_CALLS")" "$gates"
has "spec step 4 regen --all" "regen: ok" "$tmp/out"
has "spec step 5 the census gate (#57)" "census gate: spec 1 (#22): 1 cases, 0 missing" "$tmp/out"
order=$(grep -o '== [a-z-]*' "$tmp/out" | tr '\n' ' ')
check "spec census step last" "$order" "== clean-verify == record == pit == jmh == regen == census "
has "spec logs" "regen" "$(ls "$repo/.logs" | tr '\n' ' ' > "$tmp/ls"; echo "$tmp/ls")"
(export JMH_JAR=other/benchmarks.jar; spec 22 > /dev/null)
check "spec: JMH_JAR names the jar, the Gates include stays" "$(grep '^java -jar' "$STUB_CALLS")" "${gates/bench\/jmh\/target/other}"

printf 'verify\t50000\t0\n' > "$repo/.logs/31-steps.tsv"; rm "$repo/.logs/32-steps.tsv"
spec 22 > /dev/null
hasnt "no 'over target' within the target" "over target" "$tmp/out"

# a case the census gives the spec, missing from the ratchet, fails the spec gate (#57)
printf 'jq.test#b\t1\t-\tjq.test:5\t-\t-\n' >> "$repo/oracle/src/test/resources/census/jqtest.tsv"
check "spec: a census case missing from the ratchet fails" "$(spec 22)" 1
has "the missing case is named" "missing jq.test#b" "$tmp/out"
has "the failing step is the census" "step census failed" "$tmp/out"
printf '# census\njq.test#a\t1\t-\tjq.test:1\t-\t-\n' > "$repo/oracle/src/test/resources/census/jqtest.tsv"

export STUB_FAIL=clean:5
check "spec: step 1 fails, its exit code" "$(spec 22)" 5
hasnt "spec stops after step 1" "pitest" "$STUB_CALLS"
hasnt "no record step after a failed step 1" "oracle-record" "$STUB_CALLS"
export STUB_FAIL=oracle-record:3
check "spec: a recording differs, its exit code" "$(spec 22)" 3
hasnt "spec stops after the record step" "pitest" "$STUB_CALLS"
export STUB_FAIL=jmh:6
check "spec: JMH fails, its exit code" "$(spec 22)" 6
hasnt "regen not reached after JMH" "regen" "$tmp/out"
unset STUB_FAIL

rm -rf "$repo/oracle/target" "$repo/jq-cli/target"
# regen --all refuses without reports: the gate fails with ratchet's code
rm -rf "$repo/.logs"; mkdir -p "$repo/.logs"
check "spec: regen --all has no coverage report" "$(spec 22)" 1
has "regen failure shown" "no JaCoCo report" "$tmp/out"

exit $fails
