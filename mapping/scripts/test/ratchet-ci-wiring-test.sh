#!/usr/bin/env bash
# A guard of text, not a test of behaviour (MAP-12b; since 17. 11. 2026 the ratchet runs only in the
# nightly workflow's coverage job, on demand, as a report that blocks nothing): it reads
# .github/workflows/mapping-ci.yml, .github/workflows/mapping-nightly.yml, scripts/ratchet-enforced-from,
# MUTATION_MODULES of scripts/ratchet.java and the job name in scripts/ratchet-baseline.sh, and asserts how the ratchet
# check is wired. It runs nothing. The workflows are read as text in the layout they have (two-space YAML, block
# style, a job's steps under `    steps:`); a change of layout that keeps the meaning is this file's to follow. An
# absence (no `secrets.`, no `if:` on the step) is asserted beside a presence, so that a missing step never passes.
# One `ok`/`FAIL` line per assertion (newlines shown as \n); exit 1 on any FAIL.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mapping=$(cd "$here/../.." && pwd)
ci=$mapping/../.github/workflows/mapping-ci.yml
nightly=$mapping/../.github/workflows/mapping-nightly.yml
datefile=$mapping/scripts/ratchet-enforced-from
lookup=$mapping/scripts/ratchet-baseline.sh
java_src=$mapping/scripts/ratchet.java
fails=0
flat() { local s=$1; s=${s//$'\t'/\\t}; s=${s//$'\n'/\\n}; printf '%s' "$s"; }
check() {
  if [ "$2" = "$3" ]; then echo "ok   $1"
  else echo "FAIL $1: expected '$(flat "$3")' got '$(flat "$2")'"; fails=$((fails + 1)); fi
}
yes_no() { if "$@"; then echo yes; else echo no; fi; }
in_lookup() { [ -f "$lookup" ] && grep -qF -- "$1" "$lookup"; }
names() { grep -qE -- "$1"; }   # names <ERE>: some line of stdin matches
count() { grep -cE -- "$1"; }   # count <ERE>: the lines of stdin that match

# block <file> <key>: the lines under a top-level key (`on`, `concurrency`), comments dropped
block() { awk -v k="$2:" '$0 == k {b = 1; next} b && /^[^ #]/ {b = 0} b && !/^ *#/' "$1"; }
# job <file> <id>: the lines of a job, comments dropped
job() { awk -v k="  $2:" '$0 == k {b = 1; next} b && (/^  [^ #]/ || /^[^ #]/) {b = 0} b && !/^ *#/' "$1"; }
# steps: on stdin a job's lines; each line of its steps, prefixed by the step's number and a tab
steps() { awk '/^    steps:/ {s = 1; next} s && /^    [^ ]/ {s = 0} s && /^      - / {n++} s && n {print n "\t" $0}'; }
# step_no <ERE>: on stdin numbered step lines; the number of the first step with a line matching <ERE>
step_no() { grep -E -- "$1" | head -n 1 | cut -f1; }
# step_text <n>: on stdin numbered step lines; the lines of step <n>
step_text() { awk -F'\t' -v n="$1" 'n != "" && $1 == n' | cut -f2-; }
# header <file>: the comment lines before `on:`
header() { awk '/^on:/ {exit} /^#/' "$1"; }
# forbidden <file>: the lines that name a secret, continue-on-error, RATCHET_TODAY or an ISO date
forbidden() { grep -nE 'secrets\.|continue-on-error|RATCHET_TODAY|[0-9]{4}-[0-9]{2}-[0-9]{2}' "$1"; }

# ---- mapping-nightly.yml, the coverage job: the ratchet check runs there, once a night ----

coverage=$(job "$nightly" coverage)
csteps=$(printf '%s\n' "$coverage" | steps)
last=$(printf '%s\n' "$csteps" | tail -n 1 | cut -f1)
rstep=$(printf '%s\n' "$csteps" | step_no $'\t'' +(- )?run: scripts/ratchet-ci\.sh push *$')
build=$(printf '%s\n' "$csteps" | step_no $'\t'' +run: \.\./mvnw -B -Pbench verify *$')
rtext=$(printf '%s\n' "$csteps" | step_text "$rstep")
check "nightly: scripts/ratchet-ci.sh push is the coverage job's last step" "step ${rstep:-none} of $last" "step $last of $last"
check "nightly: the ratchet step comes after the build step" \
  "$(if [ -n "$rstep" ] && [ -n "$build" ] && [ "$rstep" -gt "$build" ]; then echo after
     else echo "build step ${build:-none}, ratchet step ${rstep:-none}"; fi)" after
check "nightly: the coverage job's permissions are contents: read and actions: read, nothing else" \
  "$(printf '%s\n' "$coverage" | awk '/^    permissions:/ {p = 1; next} p && /^      / {print; next} {p = 0}' \
     | sed 's/^ *//; s/ *$//' | sort)" "actions: read
contents: read"
check "nightly: the step carries the run's token and the pull request's base sha" \
  "GH_TOKEN $(printf '%s\n' "$rtext" | count '^ +GH_TOKEN: \$\{\{ github\.token \}\} *$'), RATCHET_BASELINE_SHA \
$(printf '%s\n' "$rtext" | count '^ +RATCHET_BASELINE_SHA: \$\{\{ github\.event\.pull_request\.base\.sha \}\} *$')" \
  "GH_TOKEN 1, RATCHET_BASELINE_SHA 1"
check "nightly: the ratchet step has no if:" \
  "step $(yes_no [ -n "$rstep" ]), if: $(printf '%s\n' "$rtext" | count '^ +(- )?if:')" "step yes, if: 0"
co=$(printf '%s\n' "$csteps" | step_no 'uses: actions/checkout@')
check "nightly: the coverage job's checkout takes fetch-depth: 0" \
  "$(printf '%s\n' "$csteps" | step_text "$co" | count '^ +fetch-depth: 0 *$')" 1

# ---- mapping-ci.yml: the checks on every change ----

backend=$(job "$ci" backend)
bsteps=$(printf '%s\n' "$backend" | steps)
check "ci: the backend job builds with coverage skipped and runs no ratchet step" \
  "build $(printf '%s\n' "$bsteps" | count '^[0-9]+'$'\t'' +run: \.\./mvnw -B -Djacoco\.skip=true verify *$'), \
ratchet $(printf '%s\n' "$bsteps" | count 'ratchet-ci\.sh (push|nightly)')" "build 1, ratchet 0"
nojdk=$(printf '%s\n' "$bsteps" | step_no 'name: .*need no JDK')
check "ci: the three tests of MAP-12b run beside the script tests that need no JDK" \
  "$(for t in ratchet-ci-test.sh ratchet-ci-wiring-test.sh pit-summary-test.sh; do
       printf '%s %s; ' "$t" "$(printf '%s\n' "$bsteps" | step_text "$nojdk" | count "^ +scripts/test/${t//./\\.} *$")"
     done)" "ratchet-ci-test.sh 1; ratchet-ci-wiring-test.sh 1; pit-summary-test.sh 1; "
name=$(printf '%s\n' "$backend" | sed -n 's/^    name: *//p' | head -n 1)
check "ci: the backend job's name is the one scripts/ratchet-baseline.sh looks up" \
  "name '$name', in the lookup $(yes_no in_lookup "$name")" "name 'mapping · backend', in the lookup yes"

on=$(block "$ci" on)
under() { printf '%s\n' "$on" | awk -v k="  $1:" '$0 == k {s = 1; next} /^  [^ ]/ {s = 0} s'; }   # under <trigger>
nightly_path="^ +- ['\"]?\.github/workflows/mapping-nightly\.yml['\"]? *\$"
check "ci: only the push path list names the nightly workflow, and there is no pull_request trigger" \
  "pull_request $(printf '%s\n' "$on" | count '^  pull_request:'), push $(under push | count "$nightly_path")" \
  "pull_request 0, push 1"
check "ci: the header comment says the nightly workflow's file starts it too" \
  "$(header "$ci" | yes_no names 'mapping-nightly\.yml')" yes
check "ci: mapping-ci.yml runs on a schedule and on demand and has no windows job; the nightly has the windows job, on demand only" \
  "ci schedule $(printf '%s\n' "$on" | count '^  schedule:'), ci dispatch $(printf '%s\n' "$on" | count '^  workflow_dispatch:'), \
ci windows $(job "$ci" windows | count 'runs-on'), nightly windows $(job "$nightly" windows | count '^    runs-on: windows-latest *$'), \
nightly schedule $(block "$nightly" on | count '^  schedule:'), nightly dispatch $(block "$nightly" on | count '^  workflow_dispatch:')" \
  "ci schedule 1, ci dispatch 1, ci windows 0, nightly windows 1, nightly schedule 0, nightly dispatch 1"
check "ci: the concurrency group carries the event's name" \
  "$(block "$ci" concurrency | count '^  group: mapping-ci-\$\{\{ github\.event_name \}\}-\$\{\{ github\.ref \}\} *$')" 1
check "ci: no secrets., continue-on-error, RATCHET_TODAY or ISO date anywhere in mapping-ci.yml" \
  "$(forbidden "$ci" || echo none)" "none"

# ---- mapping-nightly.yml ----

mutation=$(job "$nightly" mutation)
nsteps=$(printf '%s\n' "$mutation" | steps)
pit=$(printf '%s\n' "$nsteps" | step_no $'\t'' +(- )?name: PIT *$')
nstep=$(printf '%s\n' "$nsteps" | step_no $'\t'' +(- )?run: scripts/ratchet-ci\.sh nightly *$')
ntext=$(printf '%s\n' "$nsteps" | step_text "$nstep")
check "nightly: scripts/ratchet-ci.sh nightly runs after the PIT step" \
  "$(if [ -n "$pit" ] && [ -n "$nstep" ] && [ "$nstep" -gt "$pit" ]; then echo after
     else echo "PIT step ${pit:-none}, ratchet step ${nstep:-none}"; fi)" after
check "nightly: the ratchet step runs if: always()" "$(printf '%s\n' "$ntext" | count '^ +(- )?if: always\(\) *$')" 1
modules=$(grep -E '^ *PIT_MODULES: ' "$nightly" | sed -E "s/^ *PIT_MODULES: *//; s/^['\"]//; s/['\"] *\$//")
pit_list=$(printf '%s\n' "$modules" | tr ', ' '\n\n' | sed '/^$/d' | sort)
java_list=$(awk '/MUTATION_MODULES =/ {m = 1} m {print} m && /\);/ {exit}' "$java_src" | grep -oE '"[^"]+"' | tr -d '"' \
  | sort)
check "nightly: env PIT_MODULES names MUTATION_MODULES of scripts/ratchet.java, each once" \
  "$(grep -cE '^ *PIT_MODULES: ' "$nightly") definition, $(printf '%s\n' "$pit_list" | grep -c .) modules: $pit_list" \
  "1 definition, 9 modules: $java_list"
check "nightly: the PIT step reads PIT_MODULES and the modules are named once in the file" \
  "PIT step reads it $(printf '%s\n' "$nsteps" | step_text "$pit" | yes_no names 'PIT_MODULES'), lines naming \
engine/xdm $(count 'engine/xdm' < "$nightly")" "PIT step reads it yes, lines naming engine/xdm 1"
artefact=$(printf '%s\n' "$nsteps" | step_no 'uses: actions/upload-artifact@')
check "nightly: the artefact step stays (if: always(), pit-reports) beside the ratchet step" \
  "ratchet step $(yes_no [ -n "$nstep" ]), artefact step \
$(printf '%s\n' "$nsteps" | step_text "$artefact" | count '^ +(if: always\(\)|name: pit-reports) *$')" \
  "ratchet step yes, artefact step 2"
check "nightly: the header comment says the job compares with ratchet/mutation.tsv" \
  "$(header "$nightly" | yes_no names 'mutation\.tsv')" yes
check "nightly: no secrets., continue-on-error, RATCHET_TODAY or ISO date anywhere in mapping-nightly.yml" \
  "ratchet step $(yes_no [ -n "$nstep" ]); $(forbidden "$nightly" || echo none)" "ratchet step yes; none"

# ---- the date file ----

if [ -f "$datefile" ]; then
  content=$(cat "$datefile"); lines=$(wc -l < "$datefile")
else
  content="(no file)"; lines=0
fi
iso=no
if [[ $content =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && [ "$(date -u -d "$content" +%F 2> /dev/null)" = "$content" ]; then
  iso=yes
fi
check "date file: scripts/ratchet-enforced-from exists and holds exactly one line, one ISO date" \
  "lines $lines, ISO date $iso" "lines 1, ISO date yes"
check "date file: the packet's date" "$content" "2026-11-25"

if [ "$fails" -gt 0 ]; then exit 1; fi
exit 0
