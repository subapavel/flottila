#!/usr/bin/env bash
# Script test for `check --baseline <dir>` of scripts/ratchet.java (MAP-12a, D-164 (2)): the commit's ratchet/ rows
# held against an earlier commit's ratchet/ tree, the reports the commit must have, the MEASURED sums, the exit-2
# command lines, and the one change of `regen --all` (a code module whose csv holds only its header).
# Every case builds its own scratch tree: <case>/t is the commit's mapping/ (the cwd of the script), <case>/b the
# baseline (a copy of an earlier ratchet/). One line per case: `PASS <case>`, or `FAIL <case>: [<command>] expected
# ...; came exit <n>, stdout '...', stderr '...'` (newlines as \n, tabs as \t). stdout and stderr are read apart
# (an exit 2 prints to stderr only); the JVM's own "Picked up ..._OPTIONS" notice is dropped from stderr.
# Cases marked GREEN TODAY pass on the script before MAP-12a (characterization and a guard); every other case is red
# until the mode exists. Case letters are the packet's (agent/packets/MAP-12a.md, Acceptance 1).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
java_src=$here/../ratchet.java
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
cases=0
expected_cases=47
M=claude-cloud-4vcpu
CORPUS=dev.rail8.mapping.oracle.CorpusTest
DIFFERENTIAL=dev.rail8.mapping.oracle.DifferentialTest
BELOW="FAIL coverage engine/structure lines: 92 % is below the ratchet's 95 %"

# ---- the case harness ----

name=""; root=""; base=""; problems=""; pending=""; label=""; rc=""; out=""; err=""; outsize=0
flat() { local s=$1; s=${s//$'\t'/\\t}; s=${s//$'\n'/\\n}; printf '%s' "$s"; }
# settle: the failed expectations of the last run, with what that run printed
settle() {
  if [ -n "$pending" ]; then
    problems+="${problems:+ || }[$label] expected${pending} came exit $rc, stdout '$(flat "$out")', stderr '$(flat "$err")'"
  fi
  pending=""
}
begin() {
  [ -z "$name" ] || { echo "FAIL $name: the case did not end"; fails=$((fails + 1)); }
  name=$1; problems=""; pending=""; label=""
  root=$tmp/$name/t; base=$tmp/$name/b
  mkdir -p "$root/engine"
}
end() {
  settle
  cases=$((cases + 1))
  if [ -z "$problems" ]; then echo "PASS $name"; else echo "FAIL $name: $problems"; fails=$((fails + 1)); fi
  name=""
}
# run <args>...: the script from source in the case's tree; sets rc, out (stdout), err (stderr)
run() {
  settle
  label="${*//"$tmp/$name/"/}"
  (cd "$root" && java "$java_src" "$@" >"$tmp/stdout" 2>"$tmp/stderr"); rc=$?
  out=$(cat "$tmp/stdout"); outsize=$(wc -c <"$tmp/stdout")
  err=$(grep -vE '^(NOTE: )?Picked up (JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS):' "$tmp/stderr")
}
exit_is() { [ "$rc" = "$1" ] || pending+=" exit $1;"; }
# line <text>: stdout holds exactly one line equal to <text>
line() {
  local n; n=$(printf '%s\n' "$out" | grep -cxF -- "$1")
  [ "$n" = 1 ] || pending+=" the line '$(flat "$1")' once (seen $n);"
}
# no_line_with <text>: no stdout line contains <text>
no_line_with() {
  local n; n=$(printf '%s\n' "$out" | grep -cF -- "$1")
  [ "$n" = 0 ] || pending+=" no line with '$(flat "$1")' (seen $n);"
}
stdout_is() { [ "$out" = "$1" ] || pending+=" stdout '$(flat "$1")';"; }
stdout_empty() { [ "$outsize" = 0 ] || pending+=" nothing on stdout;"; }
stderr_is() { [ "$err" = "$1" ] || pending+=" stderr '$(flat "$1")';"; }
# stderr_one_line <ERE>: stderr is one line and it matches <ERE> (where the packet leaves part of the text open)
stderr_one_line() {
  if [ -z "$err" ] || [ "$(printf '%s\n' "$err" | wc -l)" != 1 ] || ! printf '%s\n' "$err" | grep -qE -- "$1"; then
    pending+=" stderr one line matching /$(flat "$1")/;"
  fi
}
# same <what> <got> <want>: a value read from the tree after a run
same() { [ "$2" = "$3" ] || pending+=" $1 '$(flat "$3")' (got '$(flat "$2")');"; }
plain_passes() { run check; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL'; }
mode() { run check --baseline "$base"; }

# ---- fixtures ----

# tsv <file under ratchet/> <row>...: a commit's ratchet file, fields given space-separated, written TAB-separated
tsv() { put "$root/ratchet/$1" "${@:2}"; }
# btsv <file under the baseline> <row>...: the same in the baseline
btsv() { put "$base/$1" "${@:2}"; }
put() {
  mkdir -p "$(dirname "$1")"
  if [ $# -eq 1 ]; then : > "$1"; else printf '%s\n' "${@:2}" | tr ' ' '\t' > "$1"; fi
}
# code <module>: production code by the script's rule (a .java under src/main/java other than module-info.java)
code() { mkdir -p "$root/$1/src/main/java/p"; echo "package p; class C {}" > "$root/$1/src/main/java/p/C.java"; }
modinfo() { mkdir -p "$root/$1/src/main/java"; echo "module m {}" > "$root/$1/src/main/java/module-info.java"; }
# csv <module> [<lines covered> <lines missed> <branches covered> <branches missed>]...: the JaCoCo csv, one class row
# per four numbers; no numbers = the header only
csv() {
  local d=$root/$1/target/site/jacoco i=0; shift
  mkdir -p "$d"
  {
    echo "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
    while [ $# -ge 4 ]; do i=$((i + 1)); echo "m,p,C$i,0,0,$4,$3,$2,$1,0,0,0,0"; shift 4; done
  } > "$d/jacoco.csv"
}
# pit <module> <killed> <survived>: target/pit-reports/mutations.xml
pit() {
  local d=$root/$1/target/pit-reports i
  mkdir -p "$d"
  {
    echo "<mutations>"
    for ((i = 0; i < $2; i++)); do echo "<mutation detected='true' status='KILLED'><x/></mutation>"; done
    for ((i = 0; i < $3; i++)); do echo "<mutation detected='false' status='SURVIVED'><x/></mutation>"; done
    echo "</mutations>"
  } > "$d/mutations.xml"
}
# surefire <class> <case id>...: a report of passing oracle cases under engine/oracle/target/surefire-reports
surefire() {
  local d=$root/engine/oracle/target/surefire-reports c=$1 id; shift
  mkdir -p "$d"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"$c\" tests=\"$#\">"
    for id in "$@"; do echo "<testcase name=\"cases() $id\" classname=\"$c\" time=\"0.01\"/>"; done
    echo "</testsuite>"
  } > "$d/TEST-$c.xml"
}
# snapshot: the baseline is a copy of the commit's ratchet/ as it is now
snapshot() { rm -rf "$base"; cp -R "$root/ratchet" "$base"; }
# files_of_both: every file of the commit's ratchet/ and of the baseline with its content hash
files_of_both() { (cd "$tmp/$name" && find t/ratchet b -type f -exec sha256sum {} + | sort -k2); }

# commit_tree: every report present and at its row, so plain `check` prints only its verdict
commit_tree() {
  surefire "$CORPUS" c/001 c/002
  surefire "$DIFFERENTIAL" d/001
  tsv oracle/corpus.txt c/001 c/002
  tsv oracle/differential.txt d/001
  code engine/structure
  csv engine/structure 168 12 60 15 200 20 100 25 # lines 368/400 = 92 %, branches 160/200 = 80 %
  tsv coverage.tsv 'engine/structure 92 80'
  pit engine/mapping 8 2
  tsv mutation.tsv 'engine/mapping 2 10'
  tsv bench.tsv "orders/small/latency $M 166.6 us/op" "ui/tree-20128/scroll $M 16.7 ms"
}
# tree_a: commit_tree with the lines plain `check` prints and passes on (RISE, MOVED, NOT_RUN, NEW)
tree_a() {
  commit_tree
  surefire "$CORPUS" c/001 c/002 c/003            # one passing case more than listed
  code engine/xdm; csv engine/xdm 19 1 10 0        # 95 % / 100 % against the row's 90 / 100
  code engine/udf; csv engine/udf 5 0 0 0          # no row
  tsv coverage.tsv 'engine/structure 92 80' 'engine/xdm 90 100' 'ui/src/editor/store 100 100' # no ui summary
  tsv mutation.tsv 'engine/api 0 4' 'engine/mapping 3 10' # engine/api has no PIT report
}
# tree_o: plain `check` passes with NOT_RUN lines the mode replaces, a module the mode requires, and one baseline
# difference of each kind: an oracle case, a coverage row, a mutation row and a bench row
tree_o() {
  tsv oracle/corpus.txt c/001                       # no surefire reports at all
  tsv oracle/differential.txt d/001
  code engine/api; csv engine/api 19 1 4 0          # 95 % / 100 % against the row's 90 / 100
  code engine/structure                             # a row, no csv
  csv engine/xdm 7 0 2 0                            # no row
  code engine/udf                                   # production code, no row, no csv
  tsv coverage.tsv 'engine/api 90 100' 'engine/structure 92 80' '../ui/src/mapping/model 100 100'
  pit engine/mapping 8 2
  tsv mutation.tsv 'engine/mapping 3 10' 'engine/xdm 0 5'
  tsv bench.tsv "orders/small/latency $M 166.6 us/op" "ui/tree-20128/scroll $M 16.7 ms"
  snapshot
  btsv oracle/corpus.txt c/001 c/002                                          # a case the commit lacks
  btsv coverage.tsv 'engine/api 91 100' 'engine/structure 92 80' '../ui/src/mapping/model 100 100' # lines 91 -> 90
  btsv mutation.tsv 'engine/mapping 2 10' 'engine/xdm 0 5'                    # survivors 2 -> 3
  tsv bench.tsv "orders/small/latency $M 166.6 us/op"                        # a bench row the commit lacks
}
# tree_h: plain `check` prints a FAIL line (a row above its report) and the baseline has a bench row the commit
# lacks: a mode that printed before validating would show lines
tree_h() {
  commit_tree
  tsv coverage.tsv 'engine/structure 95 80'
  snapshot
  tsv bench.tsv "orders/small/latency $M 166.6 us/op"
}

# ---- (a) a row lowered together with its report ----

begin a1-coverage-lines-lowered-with-the-report-fail
tree_a; snapshot
btsv coverage.tsv 'engine/structure 93 80' 'engine/xdm 90 100' 'ui/src/editor/store 100 100'
plain_passes
mode; exit_is 1
line 'FAIL baseline coverage engine/structure lines 93 -> 92'
no_line_with 'FAIL baseline coverage engine/structure branches'
line 'RATCHET FAIL: 1 problem(s)'
end

begin a2-coverage-branches-lowered-with-the-report-fail
tree_a; snapshot
btsv coverage.tsv 'engine/structure 92 81' 'engine/xdm 90 100' 'ui/src/editor/store 100 100'
plain_passes
mode; exit_is 1
line 'FAIL baseline coverage engine/structure branches 81 -> 80'
no_line_with 'FAIL baseline coverage engine/structure lines'
line 'RATCHET FAIL: 1 problem(s)'
end

# CHARACTERIZATION, GREEN TODAY: plain `check` on the tree of case (a) prints what the script before MAP-12a printed
# on it (observed 2026-11-16, the script as at b873eb21), no line with `baseline`, nothing on stderr
begin characterization-plain-check-on-the-a-tree-is-unchanged
tree_a; snapshot
btsv coverage.tsv 'engine/structure 93 80' 'engine/xdm 90 100' 'ui/src/editor/store 100 100'
run check; exit_is 0
stdout_is "$(printf '%s\n' \
  'RISE oracle/corpus.txt: 1 new passing case(s)' \
  'RISE coverage engine/xdm lines: 90 % -> 95 %' \
  'MOVED coverage ui/src/editor/store -> ../ui/src/mapping/studio/store' \
  'NOT_RUN coverage ../ui/src/mapping/studio/store' \
  'NEW  coverage engine/udf 100/100' \
  'NOT_RUN mutation engine/api' \
  'RISE mutation engine/mapping: 3 -> 2 surviving mutants' \
  'RATCHET CHECK OK')"
no_line_with 'baseline'
stderr_is ''
end

# ---- (b) equal, raised and added rows are no problem ----

begin b1-equal-rows-pass-with-measured-and-verdict-only
commit_tree; snapshot
plain_passes
mode; exit_is 0
stdout_is "$(printf '%s\n' 'MEASURED coverage engine/structure lines 368/400 branches 160/200' 'RATCHET CHECK OK')"
stderr_is ''
end

begin b2-raised-coverage-row-passes
commit_tree; snapshot
btsv coverage.tsv 'engine/structure 90 70'
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL baseline'
end

begin b3-rows-and-cases-the-commit-adds-pass
commit_tree; snapshot
code engine/xdm; csv engine/xdm 10 0 4 0
tsv coverage.tsv 'engine/structure 92 80' 'engine/xdm 100 100'
pit engine/api 4 0
tsv mutation.tsv 'engine/api 0 4' 'engine/mapping 2 10'
tsv bench.tsv "compile/orders $M 1.228 ms/op" "orders/small/latency $M 166.6 us/op" "ui/tree-20128/scroll $M 16.7 ms"
surefire "$CORPUS" c/001 c/002 c/003
tsv oracle/corpus.txt c/001 c/002 c/003
tsv oracle/w3c-qt3.txt 'qt3:fn-abs/001'
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL baseline'
end

# ---- (c) removed rows, cases and files ----

begin c1-coverage-row-removed-while-measured-fails
commit_tree
code engine/xdm; csv engine/xdm 19 1 9 1 # 95 % / 90 %, still measured
snapshot
btsv coverage.tsv 'engine/structure 92 80' 'engine/xdm 95 90'
plain_passes; line 'NEW  coverage engine/xdm 95/90'
mode; exit_is 1
line 'FAIL baseline coverage engine/xdm removed'
line 'RATCHET FAIL: 1 problem(s)'
end

begin c2-mutation-row-removed-while-reported-fails
commit_tree
pit engine/api 4 0
snapshot
btsv mutation.tsv 'engine/api 0 4' 'engine/mapping 2 10'
plain_passes; line 'NEW  mutation engine/api 0/4'
mode; exit_is 1
line 'FAIL baseline mutation engine/api removed'
line 'RATCHET FAIL: 1 problem(s)'
end

begin c3-bench-row-removed-fails
commit_tree; snapshot
tsv bench.tsv "orders/small/latency $M 166.6 us/op"
plain_passes
mode; exit_is 1
line "FAIL baseline bench ui/tree-20128/scroll $M removed"
line 'RATCHET FAIL: 1 problem(s)'
end

begin c3-bench-row-only-on-another-machine-is-removed
commit_tree; snapshot
tsv bench.tsv "orders/small/latency other-machine 166.6 us/op" "ui/tree-20128/scroll $M 16.7 ms"
plain_passes
mode; exit_is 1
line "FAIL baseline bench orders/small/latency $M removed"
line 'RATCHET FAIL: 1 problem(s)'
end

begin c3-bench-scores-are-not-compared
commit_tree; snapshot
tsv bench.tsv "orders/small/latency $M 999 us/op" "ui/tree-20128/scroll $M 1.5 ms"
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL baseline'
end

begin c4-corpus-case-removed-fails
commit_tree; snapshot
btsv oracle/corpus.txt c/001 c/002 c/003
plain_passes
mode; exit_is 1
line 'FAIL baseline oracle/corpus.txt c/003 removed'
line 'RATCHET FAIL: 1 problem(s)'
end

begin c4-differential-case-removed-fails
commit_tree; snapshot
btsv oracle/differential.txt d/001 d/002
plain_passes
mode; exit_is 1
line 'FAIL baseline oracle/differential.txt d/002 removed'
line 'RATCHET FAIL: 1 problem(s)'
end

begin c5-oracle-file-removed-fails-once-per-case
commit_tree; snapshot
btsv oracle/w3c-qt3.txt 'qt3:fn-abs/001' 'qt3:fn-abs/002'
plain_passes
mode; exit_is 1
line 'FAIL baseline oracle/w3c-qt3.txt qt3:fn-abs/001 removed'
line 'FAIL baseline oracle/w3c-qt3.txt qt3:fn-abs/002 removed'
line 'RATCHET FAIL: 2 problem(s)'
end

# ---- (d) survivors ----

begin d1-mutation-survivors-raised-0-to-1-fail
commit_tree
pit engine/api 3 1
tsv mutation.tsv 'engine/api 1 4' 'engine/mapping 2 10'
snapshot
btsv mutation.tsv 'engine/api 0 4' 'engine/mapping 2 10'
plain_passes
mode; exit_is 1
line 'FAIL baseline mutation engine/api survivors 0 -> 1'
line 'RATCHET FAIL: 1 problem(s)'
end

begin d2-mutation-survivors-lowered-11-to-10-pass
commit_tree
pit engine/mapping 10 10
tsv mutation.tsv 'engine/mapping 10 20'
snapshot
btsv mutation.tsv 'engine/mapping 11 20'
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL baseline'
end

# ---- (e) the MOVED map on both files ----

begin e1-moved-baseline-key-is-not-a-removal
commit_tree
tsv coverage.tsv 'engine/structure 92 80' 'ui/src/studio/store 100 100'
snapshot
btsv coverage.tsv 'engine/structure 92 80' 'ui/src/editor/store 100 100'
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL baseline'
line 'NOT_RUN coverage ../ui/src/mapping/studio/store'
end

begin e2-moved-pair-lowered-prints-the-new-key
commit_tree
tsv coverage.tsv 'engine/structure 92 80' 'ui/src/studio/store 90 100'
snapshot
btsv coverage.tsv 'engine/structure 92 80' 'ui/src/editor/store 100 100'
plain_passes
mode; exit_is 1
line 'FAIL baseline coverage ../ui/src/mapping/studio/store lines 100 -> 90'
no_line_with 'FAIL baseline coverage ui/src/editor/store'
line 'RATCHET FAIL: 1 problem(s)'
end

begin e3-moved-commit-key-lowered-prints-the-new-key
commit_tree
tsv coverage.tsv 'engine/structure 92 80' 'ui/src/editor/store 90 100'
snapshot
btsv coverage.tsv 'engine/structure 92 80' 'ui/src/studio/store 100 100'
plain_passes
mode; exit_is 1
line 'FAIL baseline coverage ../ui/src/mapping/studio/store lines 100 -> 90'
no_line_with 'FAIL baseline coverage ui/src/editor/store'
no_line_with 'removed'
line 'RATCHET FAIL: 1 problem(s)'
end

begin e4-moved-row-removed-prints-the-new-key
commit_tree; snapshot
btsv coverage.tsv 'engine/structure 92 80' 'ui/src/editor/store 100 100'
plain_passes
mode; exit_is 1
line 'FAIL baseline coverage ../ui/src/mapping/studio/store removed'
no_line_with 'FAIL baseline coverage ui/src/editor/store'
line 'RATCHET FAIL: 1 problem(s)'
end

# ---- (f) reports the commit must have ----

begin f1-oracle-lists-without-surefire-reports-fail-without-not-run
commit_tree; rm -rf "$root/engine/oracle"; snapshot
plain_passes; line 'NOT_RUN oracle/corpus.txt (2 cases)'
mode; exit_is 1
line 'FAIL baseline report oracle/corpus.txt: no surefire reports'
line 'FAIL baseline report oracle/differential.txt: no surefire reports'
no_line_with 'NOT_RUN oracle/'
line 'RATCHET FAIL: 2 problem(s)'
end

# packet :62 "wherever plain `check` prints that line": an empty list has no NOT_RUN line, so no report failure
begin f1-empty-oracle-lists-without-surefire-reports-pass-silently
commit_tree; rm -rf "$root/engine/oracle"
tsv oracle/corpus.txt; tsv oracle/differential.txt
snapshot
plain_passes; no_line_with 'oracle/'
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'oracle/'
end

begin f2-row-without-csv-fails-without-not-run
commit_tree
tsv coverage.tsv 'engine/structure 92 80' 'engine/xdm 95 90' # no engine/xdm at all
snapshot
plain_passes; line 'NOT_RUN coverage engine/xdm'
mode; exit_is 1
line 'FAIL baseline report coverage engine/xdm: no JaCoCo report'
no_line_with 'NOT_RUN coverage engine/xdm'
line 'RATCHET FAIL: 1 problem(s)'
end

begin f2-row-and-code-without-csv-fail-once
commit_tree; rm -rf "$root/engine/structure/target"; snapshot
plain_passes; line 'NOT_RUN coverage engine/structure'
mode; exit_is 1
line 'FAIL baseline report coverage engine/structure: no JaCoCo report'
no_line_with 'NOT_RUN coverage engine/structure'
line 'RATCHET FAIL: 1 problem(s)'
end

begin f3-row-with-header-only-csv-fails-with-no-figures
commit_tree; csv engine/structure; snapshot
plain_passes; line 'NOT_RUN coverage engine/structure'
mode; exit_is 1
line 'FAIL baseline report coverage engine/structure: the report has no figures for its row'
no_line_with 'no JaCoCo report'
no_line_with 'NOT_RUN coverage engine/structure'
line 'RATCHET FAIL: 1 problem(s)'
end

begin f4-code-without-row-or-csv-fails
commit_tree; code engine/udf; snapshot
plain_passes
mode; exit_is 1
line 'FAIL baseline report coverage engine/udf: no JaCoCo report'
line 'RATCHET FAIL: 1 problem(s)'
end

begin f4-service-code-without-csv-fails
commit_tree; code service; snapshot
plain_passes
mode; exit_is 1
line 'FAIL baseline report coverage service: no JaCoCo report'
line 'RATCHET FAIL: 1 problem(s)'
end

begin f4-oracle-module-code-is-not-required
commit_tree; code engine/oracle; snapshot
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'engine/oracle'
end

begin f5-code-without-row-with-header-only-csv-passes-silently
commit_tree; code engine/udf; csv engine/udf; snapshot
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'engine/udf'
end

begin f6-module-info-only-without-csv-passes-silently
commit_tree; modinfo engine/udf-api; snapshot
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'engine/udf-api'
end

begin f7-ui-row-and-mutation-row-without-reports-stay-not-run
commit_tree
mkdir -p "$root/../ui/src/mapping/model"; echo "export const m = 1;" > "$root/../ui/src/mapping/model/m.ts"
tsv coverage.tsv 'engine/structure 92 80' '../ui/src/mapping/model 100 100' # no ../ui/coverage summary
tsv mutation.tsv 'engine/mapping 2 10' 'engine/xdm 0 5'           # no engine/xdm PIT report
snapshot
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'; no_line_with 'FAIL'
line 'NOT_RUN coverage ../ui/src/mapping/model'
line 'NOT_RUN mutation engine/xdm'
end

# ---- (g) MEASURED: the csv's sums, in the order the script lists its modules ----

begin g-measured-lines-carry-the-csv-sums-in-module-order
commit_tree
code engine/api; csv engine/api 30 10 0 0 7 3 0 0 # lines 37/50 = 74 %, no branches = 100 %
csv engine/xdm 9 0 2 0                            # no row
code service; csv service 45 15 3 1              # lines 45/60 = 75 %, branches 3/4 = 75 %
tsv coverage.tsv 'engine/api 74 100' 'engine/structure 92 80' 'service 75 75'
snapshot
plain_passes
mode; exit_is 0; line 'RATCHET CHECK OK'
same "MEASURED lines" "$(printf '%s\n' "$out" | grep '^MEASURED ')" "$(printf '%s\n' \
  'MEASURED coverage engine/api lines 37/50 branches 0/0' \
  'MEASURED coverage engine/structure lines 368/400 branches 160/200' \
  'MEASURED coverage engine/xdm lines 9/9 branches 2/2' \
  'MEASURED coverage service lines 45/60 branches 3/4')"
end

# ---- the order of the mode's output: plain lines with the report failures in place of NOT_RUN, the report
# failures of modules without a row and the MEASURED lines after the coverage block, the baseline comparison after
# the last plain line, the verdict counting every problem ----

begin order-of-plain-report-measured-baseline-and-verdict-lines
tree_o
plain_passes
before=$(files_of_both)
mode; exit_is 1
same "the commit's ratchet/ and the baseline, unwritten" "$(files_of_both)" "$before"
# the first 11 lines in this order, ending with the tree's last plain line (the mutation block's NOT_RUN)
same "stdout up to the last plain line" "$(printf '%s\n' "$out" | head -n 11)" "$(printf '%s\n' \
  'FAIL baseline report oracle/corpus.txt: no surefire reports' \
  'FAIL baseline report oracle/differential.txt: no surefire reports' \
  'RISE coverage engine/api lines: 90 % -> 95 %' \
  'FAIL baseline report coverage engine/structure: no JaCoCo report' \
  'NOT_RUN coverage ../ui/src/mapping/model' \
  'NEW  coverage engine/xdm 100/100' \
  'FAIL baseline report coverage engine/udf: no JaCoCo report' \
  'MEASURED coverage engine/api lines 19/20 branches 4/4' \
  'MEASURED coverage engine/xdm lines 7/7 branches 2/2' \
  'RISE mutation engine/mapping: 3 -> 2 surviving mutants' \
  'NOT_RUN mutation engine/xdm')"
# then the four baseline lines as a set (their own order is not fixed, packet :35-36), then the verdict, last
same "the lines between the last plain line and the verdict, in any order" \
  "$(printf '%s\n' "$out" | sed '1,11d;$d' | LC_ALL=C sort)" "$(printf '%s\n' \
  'FAIL baseline oracle/corpus.txt c/002 removed' \
  'FAIL baseline coverage engine/api lines 91 -> 90' \
  'FAIL baseline mutation engine/mapping survivors 2 -> 3' \
  "FAIL baseline bench ui/tree-20128/scroll $M removed" | LC_ALL=C sort)"
same "the last line" "$(printf '%s\n' "$out" | tail -n 1)" 'RATCHET FAIL: 8 problem(s)'
stderr_is ''
end

# CHARACTERIZATION, GREEN TODAY: plain `check` on the order tree keeps its NOT_RUN lines and prints nothing of the
# mode (observed 2026-11-16, the script as at b873eb21)
begin characterization-plain-check-on-the-order-tree-is-unchanged
tree_o
run check; exit_is 0
stdout_is "$(printf '%s\n' \
  'NOT_RUN oracle/corpus.txt (1 cases)' \
  'NOT_RUN oracle/differential.txt (1 cases)' \
  'RISE coverage engine/api lines: 90 % -> 95 %' \
  'NOT_RUN coverage engine/structure' \
  'NOT_RUN coverage ../ui/src/mapping/model' \
  'NEW  coverage engine/xdm 100/100' \
  'RISE mutation engine/mapping: 3 -> 2 surviving mutants' \
  'NOT_RUN mutation engine/xdm' \
  'RATCHET CHECK OK')"
stderr_is ''
end

# ---- exit 1: n counts plain and baseline problems together ----

begin count-plain-and-baseline-problems-together
tree_h
run check; exit_is 1; line "$BELOW"; line 'RATCHET FAIL: 1 problem(s)'
mode; exit_is 1
line "$BELOW"
line "FAIL baseline bench ui/tree-20128/scroll $M removed"
line 'RATCHET FAIL: 2 problem(s)'
end

# ---- (h) exit 2: the message on stderr, nothing on stdout, on a tree that would otherwise print FAIL lines ----

USAGE='^usage: .*check \[--baseline <dir>\] \| regen'

begin h1-baseline-without-dir-is-the-usage-line
tree_h
run check --baseline; exit_is 2; stdout_empty; stderr_one_line "$USAGE"
end

begin h2-argument-after-dir-is-the-usage-line
tree_h
run check --baseline "$base" extra; exit_is 2; stdout_empty; stderr_one_line "$USAGE"
end

begin h3-missing-dir-is-not-a-directory
tree_h
run check --baseline "$tmp/$name/none"; exit_is 2; stdout_empty; stderr_is "baseline $tmp/$name/none: not a directory"
end

begin h3-file-as-dir-is-not-a-directory
tree_h; : > "$tmp/$name/file"
run check --baseline "$tmp/$name/file"; exit_is 2; stdout_empty; stderr_is "baseline $tmp/$name/file: not a directory"
end

begin h4-dir-without-coverage-tsv
tree_h; rm "$base/coverage.tsv"
mode; exit_is 2; stdout_empty; stderr_is "baseline $base: no coverage.tsv"
end

begin h5-dir-without-mutation-tsv
tree_h; rm "$base/mutation.tsv"
mode; exit_is 2; stdout_empty; stderr_is "baseline $base: no mutation.tsv"
end

begin h6-dir-without-bench-tsv
tree_h; rm "$base/bench.tsv"
mode; exit_is 2; stdout_empty; stderr_is "baseline $base: no bench.tsv"
end

begin h7-dir-without-oracle
tree_h; rm -rf "$base/oracle"
mode; exit_is 2; stdout_empty
# open in the packet: the directory's name with or without its slash
[ "$err" = "baseline $base: no oracle" ] || [ "$err" = "baseline $base: no oracle/" ] ||
  pending+=" stderr 'baseline <dir>: no oracle' (or 'no oracle/');"
end

TWO_COLUMNS='^baseline mode needs three-column mutation rows: engine/mapping[[:space:]]+80$'

begin h8-two-column-mutation-row-in-the-baseline
tree_h; btsv mutation.tsv 'engine/mapping 80'
run check; exit_is 1; line "$BELOW"
mode; exit_is 2; stdout_empty; stderr_one_line "$TWO_COLUMNS"
end

begin h9-two-column-mutation-row-in-the-commit
tree_h; tsv mutation.tsv 'engine/mapping 80' # 8 of 10 killed = 80 %: plain check reads the old row and passes it
run check; exit_is 1; line "$BELOW"; line 'RATCHET FAIL: 1 problem(s)'
mode; exit_is 2; stdout_empty; stderr_one_line "$TWO_COLUMNS"
end

# ---- (i) regen --all: a code module whose csv holds only its header ----

regen_tree() {
  tsv oracle/corpus.txt; tsv oracle/differential.txt; tsv coverage.tsv; tsv mutation.tsv
  code engine/structure; csv engine/structure 168 12 60 15 200 20 100 25 # 92 % / 80 %: regen writes its row
  code engine/udf
}

begin i1-regen-code-module-with-header-only-csv-is-no-failure
regen_tree; csv engine/udf
run regen --all; exit_is 0
line 'RATCHET REGEN OK'
no_line_with 'no JaCoCo report'
same "ratchet/coverage.tsv" "$(cat "$root/ratchet/coverage.tsv")" $'engine/structure\t92\t80'
end

# GREEN TODAY (a guard of the change): a code module with no csv file still fails regen, which writes nothing
begin i2-regen-code-module-without-csv-still-fails
regen_tree
run regen --all; exit_is 1
line 'FAIL regen: no JaCoCo report for engine/udf (it has production code)'
line 'RATCHET FAIL: 1 problem(s)'
same "ratchet/coverage.tsv" "$(cat "$root/ratchet/coverage.tsv")" ""
end

if [ "$cases" != "$expected_cases" ]; then
  echo "FAIL the case count: expected $expected_cases cases, $cases ran"
  fails=$((fails + 1))
fi
echo "ratchet-baseline-test: $cases case(s), $fails failed"
[ "$fails" -eq 0 ]
