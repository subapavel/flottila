#!/usr/bin/env bash
# Script test for scripts/ratchet-baseline.sh and scripts/ratchet-ci.sh (MAP-12b, D-164 (2), (3); the contract is
# agent/packets/MAP-12b.md §1-§3). A scratch git repository holds mapping/ratchet/ over several commits and one merge:
#   c0 (no ratchet/) - c1 (coverage.tsv, mutation.tsv only) - c2 - c3 ------- m (merge: c3 first, b1 second) - h (HEAD)
#                                                                \- b1 (side) -/
#                                                                \- x1 (stray: no ancestor of HEAD)
#                                                                \- four commits (gaps), each without one of the names
# Every ratchet file of commit <c> holds the one line `<file> of <c>`. The scripts under test, pit-summary.sh, the date
# file and ratchet.java are copied into the scratch mapping/scripts/ when they exist; a missing one fails the cases
# that run it on what they assert. Stubs, first on PATH, at the boundaries only:
# - `gh`: `gh api <path>` prints the fixture written for that exact path (a runs page, a jobs response: JSON in the
#   fields GitHub's REST lists carry, with the scratch commits' shas) and logs the call; a path without a fixture, or
#   any other call, exits 1 and logs `NO FIXTURE <path>` / `STUB REFUSED <args>`, so that a wrong query is seen;
# - `java`: logs its arguments, keeps the coverage.tsv of the tree given after --baseline, prints a canned check
#   output and exits with a canned code (the mode itself is ratchet-baseline-test.sh's).
# RATCHET_TODAY drives the date; the date of these cases is 2026-11-25. Annotations are read from stdout. An absence
# (no annotation, no call) is always asserted beside a line that must be there. One `ok`/`FAIL` line per assertion; a
# FAIL line shows newlines as \n (no line of this test starts with `::`); exit 1 on any FAIL. Each section ends with
# its second round: the cases of the packet's "Added on the implementer's hand-back", marked `added <sentence>`,
# then its third round: the cases of the packet's "Round 3" (sentences 13-18), marked the same way. The third round
# adds a PATH of symlinks without gh or jq (§1) and a pit-summary.sh wrapper that logs its calls (§3).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
unset GITHUB_STEP_SUMMARY RATCHET_TODAY PIT_ROOT PIT_MVN GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE
export GITHUB_REPOSITORY=example/scratch GITHUB_RUN_ID=990001 GH_TOKEN=scratch-token RATCHET_BASELINE_SHA=
REPO=$GITHUB_REPOSITORY
DATE=2026-11-25
BEFORE=2026-11-24
BACKEND="mapping · backend"
BOOT="(bootstrap: no push run of mapping-ci on main has a green backend job among HEAD's ancestors)"

# ---- assertions: rc, out (stdout), err (stderr) are the last run's ----

rc=""; out=""; err=""; dir=""; runs=0
flat() { local s=$1; s=${s//$'\t'/\\t}; s=${s//$'\n'/\\n}; printf '%s' "$s"; }
ctx() {
  local s="exit $rc, stdout '$(flat "$out")', stderr '$(flat "$err")', gh '$(flat "$(cat "$tmp/gh.log" 2>/dev/null)")'"
  printf '%.700s' "$s"
}
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1: $2"; fails=$((fails + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$(flat "$3")' got '$(flat "$2")'"; fi; }
exit_is() { if [ "$rc" = "$2" ]; then pass "$1"; else fail "$1" "expected exit $2; $(ctx)"; fi; }
lines_x() { printf '%s\n' "$out" | grep -cxF -- "$1"; }
lines_re() { printf '%s\n' "$out" | grep -cE -- "$1"; }
# has <name> <line>: stdout holds <line> exactly once
has() {
  local n; n=$(lines_x "$2")
  if [ "$n" = 1 ]; then pass "$1"; else fail "$1" "expected the line '$(flat "$2")' once, seen $n; $(ctx)"; fi
}
# has_re <name> <ERE>: exactly one stdout line matches
has_re() {
  local n; n=$(lines_re "$2")
  if [ "$n" = 1 ]; then pass "$1"; else fail "$1" "expected one line matching '$2', seen $n; $(ctx)"; fi
}
# last_is <name> <line>: the last stdout line is <line>
last_is() {
  local l; l=$(printf '%s\n' "$out" | tail -n 1)
  if [ "$l" = "$2" ]; then pass "$1"; else fail "$1" "expected the last line '$(flat "$2")'; $(ctx)"; fi
}
# absent <name> <ERE> <line>: no stdout line matches <ERE>, beside <line> once
absent() {
  local n a; n=$(lines_re "$2"); a=$(lines_x "$3")
  if [ "$n" = 0 ] && [ "$a" = 1 ]; then pass "$1"
  else fail "$1" "expected no line matching '$2' beside the line '$(flat "$3")'; seen $n and $a; $(ctx)"; fi
}
# absent_re <name> <ERE> <anchor ERE>: as absent, the anchor a pattern
absent_re() {
  local n a; n=$(lines_re "$2"); a=$(lines_re "$3")
  if [ "$n" = 0 ] && [ "$a" = 1 ]; then pass "$1"
  else fail "$1" "expected no line matching '$2' beside one matching '$3'; seen $n and $a; $(ctx)"; fi
}
gh_calls() { grep -c '^gh ' "$tmp/gh.log"; }
java_calls() { wc -l < "$tmp/java.log"; }
# no_calls <name> <anchor ERE>: neither stub was called, beside one stdout line matching the anchor
no_calls() {
  check "$1" "gh calls $(gh_calls), java calls $(java_calls), anchor $(lines_re "$2")" "gh calls 0, java calls 0, anchor 1"
}
# gh_none <name> <line>: the stub gh was never called, beside <line> once in stdout
gh_none() { check "$1" "gh calls $(gh_calls), the line $(lines_x "$2")" "gh calls 0, the line 1"; }
# not_asked <name> <run id> <line>: no call names the run, beside <line> once in stdout
not_asked() {
  check "$1" "calls for run $2: $(grep -cF -- "/actions/runs/$2/" "$tmp/gh.log"), the line $(lines_x "$3")" \
    "calls for run $2: 0, the line 1"
}
usage_seen() { if printf '%s\n' "$err" | grep -qi usage; then echo yes; else echo no; fi; }

# ---- the stubs ----

mkdir -p "$tmp/bin" "$tmp/fixtures"
: > "$tmp/gh.log"; : > "$tmp/java.log"
cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
# stub gh: `gh api <path>` prints fixtures/<key>.json, then fixtures/<key>.err on stderr and exits fixtures/<key>.exit
d=$(cd "$(dirname "$0")/.." && pwd)
echo "gh $*" >> "$d/gh.log"
if [ $# -ne 2 ] || [ "$1" != api ]; then
  echo "STUB REFUSED $*" >> "$d/gh.log"; echo "stub gh: only 'gh api <path>' is served: $*" >&2; exit 1
fi
f=$d/fixtures/$(printf '%s' "$2" | tr -c 'A-Za-z0-9.-' '_')
if [ ! -f "$f.json" ]; then
  echo "NO FIXTURE $2" >> "$d/gh.log"; echo "stub gh: no fixture for $2" >&2; exit 1
fi
cat "$f.json"
if [ -f "$f.exit" ]; then cat "$f.err" >&2; exit "$(cat "$f.exit")"; fi
exit 0
STUB
cat > "$tmp/bin/java" <<'STUB'
#!/usr/bin/env bash
# stub java: logs its arguments, keeps the coverage.tsv of the --baseline tree, prints java.out, exits java.rc
d=$(cd "$(dirname "$0")/.." && pwd)
echo "$*" >> "$d/java.log"
prev=""
for a in "$@"; do
  if [ "$prev" = --baseline ]; then cat "$a/coverage.tsv" > "$d/java.saw" 2>&1; fi
  prev=$a
done
cat "$d/java.out"
cat "$d/java.err" >&2
exit "$(cat "$d/java.rc")"
STUB
chmod +x "$tmp/bin/gh" "$tmp/bin/java"
export PATH="$tmp/bin:$PATH"
# the harness's own check: both stubs are the ones found, and gh refuses an unserved path loudly
gh api repos/example/none > /dev/null 2>&1; self=$?
if [ "$(command -v gh)" != "$tmp/bin/gh" ] || [ "$(command -v java)" != "$tmp/bin/java" ] || [ "$self" != 1 ] \
  || [ "$(cat "$tmp/gh.log")" != $'gh api repos/example/none\nNO FIXTURE repos/example/none' ]; then
  echo "FAIL harness: the stubs are not first on PATH, or gh does not refuse a path without a fixture"; exit 1
fi

# ---- fixtures: one helper writes a runs page, one a jobs response ----

key() { printf '%s' "$1" | tr -c 'A-Za-z0-9.-' '_'; }
# fixture <path> <body> [<exit> <stderr>]: what the stub gh answers to `gh api <path>`
fixture() {
  local f; f=$tmp/fixtures/$(key "$1")
  printf '%s\n' "$2" > "$f.json"
  if [ $# -ge 3 ]; then printf '%s\n' "$3" > "$f.exit"; printf '%s\n' "$4" > "$f.err"; fi
}
fresh() { rm -f "$tmp/fixtures/"*; }
runs_path() {
  printf 'repos/%s/actions/workflows/mapping-ci.yml/runs?branch=main&event=push&per_page=100&page=%s' "$REPO" "$1"
}
jobs_path() { printf 'repos/%s/actions/runs/%s/jobs?per_page=100' "$REPO" "$1"; }
rid() { echo $((43900000000 + $1)); }   # run ids of the size GitHub gives (43928160498)
# runs_page <page> <total_count> <run>...: one page of mapping-ci's push runs on main; a <run> is
# `<id>:<head_sha>:<status>:<conclusion>[:<run_attempt>]`, conclusion `null` while the run is not completed
runs_page() {
  local page=$1 total=$2 r id sha status conclusion attempt num body="" sep=""; shift 2
  for r in "$@"; do
    IFS=: read -r id sha status conclusion attempt <<< "$r"
    [ "$conclusion" = null ] || conclusion="\"$conclusion\""
    body+="$sep"$'\n    '"{\"id\": $id, \"name\": \"mapping-ci\", \"head_branch\": \"main\", \"head_sha\": \"$sha\","
    body+=" \"event\": \"push\", \"status\": \"$status\", \"conclusion\": $conclusion,"
    num=${id//[!0-9]/}   # a run whose id is no number (packet, added 5) still gets a run_number
    body+=" \"run_number\": $((${num:-0} % 10000)), \"run_attempt\": ${attempt:-1}}"
    sep=","
  done
  fixture "$(runs_path "$page")" "{\"total_count\": $total, \"workflow_runs\": [$body]}"
}
# jobs_response <run id> <head_sha> <job name>=<conclusion>...: the run's jobs (the default filter: its latest
# attempt); conclusion `null` is a job still in progress
jobs_response() {
  local id=$1 sha=$2 j name conclusion status body="" sep="" k=0; shift 2
  for j in "$@"; do
    name=${j%=*}; conclusion=${j##*=}; k=$((k + 1)); status=completed
    if [ "$conclusion" = null ]; then status=in_progress; else conclusion="\"$conclusion\""; fi
    body+="$sep"$'\n    '"{\"id\": $((id * 10 + k)), \"run_id\": $id, \"name\": \"$name\", \"status\": \"$status\","
    body+=" \"conclusion\": $conclusion, \"head_sha\": \"$sha\", \"run_attempt\": 1}"
    sep=","
  done
  fixture "$(jobs_path "$id")" "{\"total_count\": $#, \"jobs\": [$body]}"
}
green() {   # green <run id> <head_sha>: the four jobs of a run, all success
  jobs_response "$1" "$2" "$BACKEND=success" "mapping · ui=success" "mapping · windows=success" "mapping · stack=success"
}

# ---- the scratch repository ----

repo=$tmp/repo
night=$tmp/night/mapping
gitc() {
  local w=$1; shift
  git -C "$w" -c user.name=scratch -c user.email=scratch@example.invalid -c commit.gpgsign=false "$@"
}
g() { gitc "$repo" "$@"; }
# ratchet_tree <c> [partial]: mapping/ratchet/ as commit <c> holds it (partial: coverage.tsv and mutation.tsv only)
ratchet_tree() {
  local r=$repo/mapping/ratchet
  rm -rf "$r"; mkdir -p "$r"
  printf 'coverage.tsv of %s\n' "$1" > "$r/coverage.tsv"
  printf 'mutation.tsv of %s\n' "$1" > "$r/mutation.tsv"
  [ "${2:-}" = partial ] && return 0
  printf 'bench.tsv of %s\n' "$1" > "$r/bench.tsv"
  mkdir -p "$r/oracle"; printf 'oracle/corpus.txt of %s\n' "$1" > "$r/oracle/corpus.txt"
}
commit() { g add -A mapping && g commit -q -m "$1" && g rev-parse HEAD; }
install_scripts() {   # install_scripts <mapping dir>: the scripts under test, each only when it exists
  local f; mkdir -p "$1/scripts"
  for f in ratchet-baseline.sh ratchet-ci.sh pit-summary.sh ratchet-enforced-from ratchet.java; do
    if [ -e "$here/../$f" ]; then cp -p "$here/../$f" "$1/scripts/$f"; fi
  done
}
git init -q -b main "$repo"
mkdir -p "$repo/mapping"; echo "scratch mapping" > "$repo/mapping/README.md"
C0=$(commit c0)
ratchet_tree c1 partial; C1=$(commit c1)
ratchet_tree c2; C2=$(commit c2)
g checkout -q -b side; ratchet_tree b1; B1=$(commit b1)
g checkout -q -b stray "$C2"; ratchet_tree x1; X1=$(commit x1)
declare -A GAP   # branch gaps, from c2: a commit whose ratchet/ lacks one of the four names (a pull request's base)
g checkout -q -b gaps "$C2"
for gap in coverage.tsv mutation.tsv bench.tsv oracle; do
  ratchet_tree "no-$gap"; rm -rf "$repo/mapping/ratchet/$gap"; GAP[$gap]=$(commit "no $gap")
done
g checkout -q main; ratchet_tree c3; C3=$(commit c3)
g merge -q --no-ff --no-commit -s ours side > /dev/null 2>&1
ratchet_tree m; M=$(commit m)
ratchet_tree h; printf 'oracle/w3c-a.txt of h\n' > "$repo/mapping/ratchet/oracle/w3c-a.txt"; H=$(commit h)
if [ "$(g rev-list --parents -n 1 "$M")" != "$M $C3 $B1" ] || [ "$(g rev-parse HEAD)" != "$H" ] \
  || g merge-base --is-ancestor "$X1" "$H"; then
  echo "FAIL harness: the scratch history is not the one drawn in the header"; exit 1
fi
install_scripts "$repo/mapping"
lone=$tmp/lone
git init -q -b main "$lone"
mkdir -p "$lone/mapping/ratchet/oracle"
for f in coverage.tsv mutation.tsv bench.tsv oracle/corpus.txt; do echo "$f of lone" > "$lone/mapping/ratchet/$f"; done
gitc "$lone" add -A mapping && gitc "$lone" commit -q -m lone
install_scripts "$lone/mapping"
mkdir -p "$night/ratchet"
install_scripts "$night"

# dir_rows: the tree the lookup wrote into <dir>, as `<file> of <c>` lines, and whether HEAD's own file came along
dir_rows() {
  local f
  for f in coverage.tsv mutation.tsv bench.tsv oracle/corpus.txt; do
    if [ -f "$dir/$f" ]; then cat "$dir/$f"; else echo "$f missing"; fi
  done
  if [ -e "$dir/oracle/w3c-a.txt" ]; then echo "oracle/w3c-a.txt present"; else echo "oracle/w3c-a.txt absent"; fi
}
rows_of() {   # rows_of <c>: what dir_rows shows for commit <c>'s tree
  printf '%s of %s\n' coverage.tsv "$1" mutation.tsv "$1" bench.tsv "$1" oracle/corpus.txt "$1"
  echo "oracle/w3c-a.txt absent"
}
# lookup_in <mapping dir> [<VAR=value>...]: scripts/ratchet-baseline.sh <a fresh empty dir>, cwd <mapping dir>
lookup_in() {
  local where=$1; shift
  runs=$((runs + 1)); dir=$tmp/baseline.$runs; mkdir -p "$dir"
  : > "$tmp/gh.log"
  (cd "$where" && env "$@" scripts/ratchet-baseline.sh "$dir" > "$tmp/out" 2> "$tmp/err"); rc=$?
  out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
}
lookup() { lookup_in "$repo/mapping" "$@"; }

# ==== §1 the lookup ====

fresh
runs_page 1 3 "$(rid 11):$C2:completed:success" "$(rid 12):$B1:completed:success" "$(rid 10):$C1:completed:success"
green "$(rid 11)" "$C2"; green "$(rid 12)" "$B1"
lookup
exit_is "lookup, across a merge: exit 0" 0
last_is "lookup, across a merge: the nearest green ancestor is the merge's second parent" "BASELINE $B1 run $(rid 12)"
not_asked "lookup, across a merge: the walk stops at the baseline" "$(rid 11)" "BASELINE $B1 run $(rid 12)"
check "lookup: <dir> holds the baseline commit's four names with that commit's content, not HEAD's" "$(dir_rows)" \
  "$(rows_of b1)"

fresh   # a re-run of HEAD's push run, green: HEAD is no strict ancestor of itself
runs_page 1 2 "$(rid 21):$H:completed:success:2" "$(rid 20):$M:completed:success"
green "$(rid 21)" "$H"; green "$(rid 20)" "$M"
lookup
last_is "lookup: HEAD's own green push run is never the baseline" "BASELINE $M run $(rid 20)"
not_asked "lookup: HEAD's own run is never asked for its jobs" "$(rid 21)" "BASELINE $M run $(rid 20)"

fresh
runs_page 1 3 "$(rid 31):$M:completed:failure" "$(rid 32):$C3:completed:cancelled" "$(rid 30):$C2:completed:success"
jobs_response "$(rid 31)" "$M" "$BACKEND=failure" "mapping · ui=success" "mapping · windows=success" \
  "mapping · stack=skipped"
jobs_response "$(rid 32)" "$C3" "$BACKEND=cancelled" "mapping · ui=cancelled" "mapping · windows=success" \
  "mapping · stack=skipped"
green "$(rid 30)" "$C2"
lookup
last_is "lookup: a red backend job and a cancelled run are skipped" "BASELINE $C2 run $(rid 30)"
has "lookup: LOOKUP pages counts the pages and the runs read" "LOOKUP pages 1 runs 3"
has "lookup: LOOKUP run for the red run asked" "LOOKUP run $(rid 31) sha $M status completed conclusion failure"
has "lookup: LOOKUP job for the red run's backend job" "LOOKUP job $(rid 31) name $BACKEND conclusion failure"
has "lookup: LOOKUP run for the cancelled run asked" \
  "LOOKUP run $(rid 32) sha $C3 status completed conclusion cancelled"
has "lookup: LOOKUP job for the cancelled run's backend job" "LOOKUP job $(rid 32) name $BACKEND conclusion cancelled"
has "lookup: LOOKUP run for the baseline's run" "LOOKUP run $(rid 30) sha $C2 status completed conclusion success"
has "lookup: LOOKUP job for the baseline's backend job" "LOOKUP job $(rid 30) name $BACKEND conclusion success"

fresh   # the backend job is done and green, the run is not completed yet
runs_page 1 2 "$(rid 41):$M:in_progress:null" "$(rid 40):$C2:completed:success"
jobs_response "$(rid 41)" "$M" "$BACKEND=success" "mapping · ui=null" "mapping · windows=success" "mapping · stack=null"
green "$(rid 40)" "$C2"
lookup
last_is "lookup: a run not completed is no candidate, even with a green backend job" "BASELINE $C2 run $(rid 40)"
not_asked "lookup: a run not completed is never asked for its jobs" "$(rid 41)" "BASELINE $C2 run $(rid 40)"

fresh
runs_page 1 2 "$(rid 51):$M:completed:failure" "$(rid 50):$C2:completed:success"
jobs_response "$(rid 51)" "$M" "$BACKEND=success" "mapping · ui=failure" "mapping · windows=success" \
  "mapping · stack=skipped"
lookup
last_is "lookup: a run red on another job alone is taken (the run's own conclusion is not read)" \
  "BASELINE $M run $(rid 51)"

fresh
runs_page 1 2 "$(rid 61):$X1:completed:success" "$(rid 60):$C2:completed:success"
green "$(rid 61)" "$X1"; green "$(rid 60)" "$C2"
lookup
last_is "lookup: a run of a commit that is no ancestor of HEAD is skipped" "BASELINE $C2 run $(rid 60)"
not_asked "lookup: a run of a commit that is no ancestor is never asked for its jobs" "$(rid 61)" \
  "BASELINE $C2 run $(rid 60)"

fresh   # the running run, put on an ancestor and completed so that only its id excludes it
runs_page 1 2 "$(rid 71):$M:completed:success" "$(rid 70):$C2:completed:success"
green "$(rid 71)" "$M"; green "$(rid 70)" "$C2"
lookup GITHUB_RUN_ID="$(rid 71)"
last_is "lookup: the run with GITHUB_RUN_ID's id is no candidate" "BASELINE $C2 run $(rid 70)"
not_asked "lookup: the run with GITHUB_RUN_ID's id is never asked for its jobs" "$(rid 71)" "BASELINE $C2 run $(rid 70)"

fresh   # page 1: 99 runs of a stray commit and a farther green ancestor; page 2: the nearer one
page1=(); for ((i = 0; i < 99; i++)); do page1+=("$(rid $((1000 + i))):$X1:completed:success"); done
runs_page 1 101 "${page1[@]}" "$(rid 80):$C2:completed:success"
runs_page 2 101 "$(rid 81):$M:completed:success"
green "$(rid 80)" "$C2"; green "$(rid 81)" "$M"
lookup
last_is "lookup: page 2 of a list whose page 1 holds 100 runs is read, the nearer green ancestor on it taken" \
  "BASELINE $M run $(rid 81)"
has "lookup: LOOKUP pages counts both pages and every run" "LOOKUP pages 2 runs 101"
check "lookup: the runs list is asked page by page, no further than the short page" \
  "$(grep -F 'mapping-ci.yml/runs?' "$tmp/gh.log")" "gh api $(runs_path 1)
gh api $(runs_path 2)"

fresh   # exactly 100 runs: the next page is asked, and it is empty
page1=(); for ((i = 0; i < 99; i++)); do page1+=("$(rid $((2000 + i))):$X1:completed:success"); done
runs_page 1 100 "${page1[@]}" "$(rid 90):$M:completed:success"
runs_page 2 100
green "$(rid 90)" "$M"
lookup
last_is "lookup: a full page is followed by the next one, even when that is empty" "BASELINE $M run $(rid 90)"
has "lookup: LOOKUP pages counts the empty page" "LOOKUP pages 2 runs 100"

# the order of the list: the farther ancestor has the higher id, listed first, then last
for order in first last; do
  fresh
  if [ "$order" = first ]; then
    runs_page 1 2 "$(rid 101):$C2:completed:success" "$(rid 100):$M:completed:success"
  else
    runs_page 1 2 "$(rid 100):$M:completed:success" "$(rid 101):$C2:completed:success"
  fi
  green "$(rid 100)" "$M"; green "$(rid 101)" "$C2"
  lookup
  last_is "lookup: the list's order does not matter (the farther ancestor listed $order)" "BASELINE $M run $(rid 100)"
done

fresh   # a renamed job: no run has a job named mapping · backend
runs_page 1 2 "$(rid 111):$M:completed:success" "$(rid 110):$C2:completed:success"
jobs_response "$(rid 111)" "$M" "mapping · build=success" "mapping · ui=success"
jobs_response "$(rid 110)" "$C2" "mapping · build=success" "mapping · ui=success"
lookup
exit_is "lookup, a renamed job: exit 0" 0
last_is "lookup, a renamed job: the bootstrap line names HEAD's first parent" "BASELINE $M $BOOT"
has "lookup, a renamed job: LOOKUP job says none is named mapping · backend" \
  "LOOKUP job $(rid 111) none named $BACKEND"
has "lookup, a renamed job: the walk goes on to the next ancestor with candidates" \
  "LOOKUP job $(rid 110) none named $BACKEND"
check "lookup, bootstrap: <dir> holds the first parent's tree" "$(dir_rows)" "$(rows_of m)"

fresh   # a fork's first run: the list is empty
runs_page 1 0
lookup
exit_is "lookup, no push run at all: exit 0" 0
last_is "lookup, no push run at all: the bootstrap line" "BASELINE $M $BOOT"
has "lookup, no push run at all: LOOKUP pages" "LOOKUP pages 1 runs 0"

fresh
lookup RATCHET_BASELINE_SHA="$C3"
exit_is "lookup, a pull request: exit 0" 0
last_is "lookup, a pull request: its base is the baseline" "BASELINE $C3 (pull request base)"
gh_none "lookup, a pull request: gh is never called" "BASELINE $C3 (pull request base)"
check "lookup, a pull request: <dir> holds the base's tree" "$(dir_rows)" "$(rows_of c3)"

fresh
fixture "$(runs_path 1)" '{"message": "Bad credentials", "status": "401"}' 1 'gh: Bad credentials (HTTP 401)'
lookup
exit_is "lookup, gh failing: exit 1" 1
has_re "lookup, gh failing: one LOOKUP FAILED line with its reason" '^LOOKUP FAILED: .+'

fresh
fixture "$(runs_path 1)" '<html><body>Rate limited</body></html>'
lookup
exit_is "lookup, a runs page that is not JSON: exit 1" 1
has_re "lookup, a runs page that is not JSON: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'

fresh
runs_page 1 1 "$(rid 121):$M:completed:success"
fixture "$(jobs_path "$(rid 121)")" '{"message": "Not Found", "documentation_url": "https://docs.github.com/rest"}'
lookup
exit_is "lookup, a jobs response without its jobs list: exit 1" 1
has_re "lookup, a jobs response without its jobs list: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'

fresh
runs_page 1 1 "$(rid 131):$C0:completed:success"
green "$(rid 131)" "$C0"
lookup
exit_is "lookup, a baseline without mapping/ratchet/: exit 1" 1
has_re "lookup, a baseline without mapping/ratchet/: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'

fresh
runs_page 1 1 "$(rid 141):$C1:completed:success"
green "$(rid 141)" "$C1"
lookup
exit_is "lookup, a baseline whose ratchet/ lacks bench.tsv and oracle/: exit 1" 1
has_re "lookup, a baseline whose ratchet/ lacks bench.tsv and oracle/: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'

for gap in coverage.tsv mutation.tsv bench.tsv oracle; do
  fresh
  lookup RATCHET_BASELINE_SHA="${GAP[$gap]}"
  exit_is "lookup, a base whose ratchet/ lacks $gap alone: exit 1" 1
  has_re "lookup, a base whose ratchet/ lacks $gap alone: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'
done

fresh
runs_page 1 0
lookup_in "$lone/mapping"
exit_is "lookup, HEAD without a parent: exit 1" 1
has_re "lookup, HEAD without a parent: one LOOKUP FAILED line" '^LOOKUP FAILED: .+'

fresh; : > "$tmp/gh.log"
(cd "$repo/mapping" && scripts/ratchet-baseline.sh > "$tmp/out" 2> "$tmp/err"); rc=$?
out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
check "lookup without <dir>: usage on stderr, nothing on stdout, exit 2, no call" \
  "exit $rc, usage on stderr $(usage_seen), stdout '$(flat "$out")', gh calls $(gh_calls)" \
  "exit 2, usage on stderr yes, stdout '', gh calls 0"

# ---- §1, second round: the packet's "Added on the implementer's hand-back", sentences 2-6 and 9-11 ----

FAILED_RE='^LOOKUP FAILED: .+'
# exits <path> <code> <stderr>: the stub gh prints <path>'s fixture, then exits <code> with <stderr>
exits() { local f; f=$tmp/fixtures/$(key "$1"); printf '%s\n' "$2" > "$f.exit"; printf '%s\n' "$3" > "$f.err"; }
# failed_quiet <name> <run id>: one LOOKUP FAILED line, no BASELINE line, and no call names the run
failed_quiet() {
  check "$1" "LOOKUP FAILED $(lines_re "$FAILED_RE"), BASELINE $(lines_re '^BASELINE '), calls for run $2: \
$(grep -cF -- "/actions/runs/$2/" "$tmp/gh.log")" "LOOKUP FAILED 1, BASELINE 0, calls for run $2: 0"
}

# added 2: gh exiting non-zero is a failure also when its stdout is a well-formed answer
fresh
runs_page 1 1 "$(rid 301):$M:completed:success"; green "$(rid 301)" "$M"
exits "$(runs_path 1)" 1 'gh: Server Error (HTTP 502)'
lookup
exit_is "lookup, gh exits 1 after a well-formed runs page: exit 1" 1
absent_re "lookup, gh exits 1 after a well-formed runs page: one LOOKUP FAILED line, no BASELINE" '^BASELINE ' "$FAILED_RE"
fresh
runs_page 1 1 "$(rid 302):$M:completed:success"; green "$(rid 302)" "$M"
exits "$(jobs_path "$(rid 302)")" 1 'gh: Server Error (HTTP 502)'
lookup
exit_is "lookup, gh exits 1 after a well-formed jobs response: exit 1" 1
absent_re "lookup, gh exits 1 after a well-formed jobs response: one LOOKUP FAILED line, no BASELINE" '^BASELINE ' \
  "$FAILED_RE"

# added 3: a shallow checkout. Depth 1 is the checkout's default; depth 2 keeps HEAD's parent m, so that a lookup
# without the guard would name a wrong baseline (m, by the bootstrap) instead of failing on a missing parent.
for depth in 1 2; do
  shallow=$tmp/shallow$depth
  git clone -q --depth "$depth" "file://$repo" "$shallow" 2> /dev/null
  install_scripts "$shallow/mapping"
  fresh
  runs_page 1 1 "$(rid 310):$C2:completed:success"; green "$(rid 310)" "$C2"
  lookup_in "$shallow/mapping"
  exit_is "lookup in a shallow clone (depth $depth): exit 1" 1
  absent_re "lookup in a shallow clone (depth $depth): one LOOKUP FAILED line, no BASELINE" '^BASELINE ' "$FAILED_RE"
done

# added 4: RATCHET_BASELINE_SHA that is not a full lowercase hex sha
for base in main --help "${C3:0:7}" "${C3^^}"; do
  fresh
  lookup RATCHET_BASELINE_SHA="$base"
  exit_is "lookup, RATCHET_BASELINE_SHA '$base': exit 1" 1
  check "lookup, RATCHET_BASELINE_SHA '$base': LOOKUP FAILED, no call to gh, nothing in <dir>" \
    "LOOKUP FAILED $(lines_re "$FAILED_RE"), gh calls $(gh_calls), files in <dir> $(find "$dir" -type f | wc -l)" \
    "LOOKUP FAILED 1, gh calls 0, files in <dir> 0"
done

# added 5: what the API answers is data. Each bad row sits beside a green run, so that a lookup that let the row
# through would name a baseline; every bad run has a green jobs response a wrong call would be served.
fresh   # head_sha is a branch name
runs_page 1 2 "$(rid 321):main:completed:success" "$(rid 320):$M:completed:success"
green "$(rid 321)" "$M"; green "$(rid 320)" "$M"
lookup
exit_is "lookup, a run whose head_sha is 'main': exit 1" 1
failed_quiet "lookup, a run whose head_sha is 'main': LOOKUP FAILED, its jobs never asked" "$(rid 321)"
fresh   # head_sha is an option
runs_page 1 2 "$(rid 323):--output=x:completed:success" "$(rid 322):$M:completed:success"
green "$(rid 323)" "$M"; green "$(rid 322)" "$M"
lookup
exit_is "lookup, a run whose head_sha is '--output=x': exit 1" 1
failed_quiet "lookup, a run whose head_sha is '--output=x': LOOKUP FAILED, its jobs never asked" "$(rid 323)"
fresh   # the id is a string (of digits)
runs_page 1 2 "\"$(rid 325)\":$M:completed:success" "$(rid 324):$C2:completed:success"
green "$(rid 325)" "$M"; green "$(rid 324)" "$C2"
lookup
exit_is "lookup, a run whose id is a string: exit 1" 1
failed_quiet "lookup, a run whose id is a string: LOOKUP FAILED, its jobs never asked" "$(rid 325)"
fresh   # the id is a number but not digits: a run id reaches gh only as digits
runs_page 1 2 "-7:$M:completed:success" "$(rid 326):$C2:completed:success"
green -7 "$M"; green "$(rid 326)" "$C2"
lookup
exit_is "lookup, a run whose id is negative: exit 1" 1
failed_quiet "lookup, a run whose id is negative: LOOKUP FAILED, its jobs never asked" -7
fresh   # the status is no word
runs_page 1 2 "$(rid 328):$M:completed; x:success" "$(rid 327):$C2:completed:success"
green "$(rid 328)" "$M"; green "$(rid 327)" "$C2"
lookup
exit_is "lookup, a run whose status is 'completed; x': exit 1" 1
failed_quiet "lookup, a run whose status is 'completed; x': LOOKUP FAILED, its jobs never asked" "$(rid 328)"
fresh   # the backend job's conclusion is no word
runs_page 1 2 "$(rid 330):$M:completed:success" "$(rid 329):$C2:completed:success"
jobs_response "$(rid 330)" "$M" "$BACKEND=suc cess" "mapping · ui=success"; green "$(rid 329)" "$C2"
lookup
exit_is "lookup, a backend job whose conclusion is 'suc cess': exit 1" 1
absent_re "lookup, a backend job whose conclusion is 'suc cess': one LOOKUP FAILED line, no BASELINE" '^BASELINE ' \
  "$FAILED_RE"

# added 6: GITHUB_REPOSITORY that is not <owner>/<repository>, before any gh call
for repository in scratch a/b/c 'a/b?x=1'; do
  fresh
  lookup GITHUB_REPOSITORY="$repository"
  exit_is "lookup, GITHUB_REPOSITORY '$repository': exit 1" 1
  check "lookup, GITHUB_REPOSITORY '$repository': LOOKUP FAILED before any call to gh" \
    "LOOKUP FAILED $(lines_re "$FAILED_RE"), gh calls $(gh_calls)" "LOOKUP FAILED 1, gh calls 0"
done

# added 9: two completed push runs of the same ancestor m; the newest green one is named
fresh   # the older red, the newer green
runs_page 1 2 "$(rid 340):$M:completed:failure" "$(rid 341):$M:completed:success"
jobs_response "$(rid 340)" "$M" "$BACKEND=failure"; green "$(rid 341)" "$M"
lookup
last_is "lookup, two runs of one ancestor, the older red and the newer green: the newer" "BASELINE $M run $(rid 341)"
fresh   # the older green, the newer red
runs_page 1 2 "$(rid 343):$M:completed:failure" "$(rid 342):$M:completed:success"
jobs_response "$(rid 343)" "$M" "$BACKEND=failure"; green "$(rid 342)" "$M"
lookup
last_is "lookup, two runs of one ancestor, the older green and the newer red: the older" "BASELINE $M run $(rid 342)"
fresh   # both green
runs_page 1 2 "$(rid 344):$M:completed:success" "$(rid 345):$M:completed:success"
green "$(rid 344)" "$M"; green "$(rid 345)" "$M"
lookup
last_is "lookup, two green runs of one ancestor: the newer is named" "BASELINE $M run $(rid 345)"

# added 10: a list that has not ended after 100 pages (one full page of the stray commit, served as pages 1..100)
fresh
full=(); for ((i = 0; i < 100; i++)); do full+=("$(rid $((3000 + i))):$X1:completed:success"); done
runs_page 1 10000 "${full[@]}"
for ((p = 2; p <= 100; p++)); do
  cp "$tmp/fixtures/$(key "$(runs_path 1)").json" "$tmp/fixtures/$(key "$(runs_path "$p")").json"
done
lookup
exit_is "lookup, 100 full pages: exit 1" 1
has_re "lookup, 100 full pages: one LOOKUP FAILED line" "$FAILED_RE"
check "lookup, 100 full pages: pages 1 to 100 asked, page 101 never, no call without its fixture" \
  "runs pages asked $(grep -cF 'mapping-ci.yml/runs?' "$tmp/gh.log"), page 101 $(grep -cxF "gh api $(runs_path 101)" \
"$tmp/gh.log"), NO FIXTURE $(grep -c '^NO FIXTURE' "$tmp/gh.log")" "runs pages asked 100, page 101 0, NO FIXTURE 0"

# added 11: a run listed on two pages (the list moved between the two requests) is asked for its jobs once
twice() {   # twice <conclusion of m's backend job>: m's run on a full page 1 and again on page 2, c2's green run on 2
  fresh
  full=(); for ((i = 0; i < 99; i++)); do full+=("$(rid $((4000 + i))):$X1:completed:success"); done
  runs_page 1 102 "${full[@]}" "$(rid 350):$M:completed:$1"
  runs_page 2 102 "$(rid 350):$M:completed:$1" "$(rid 351):$C2:completed:success"
  jobs_response "$(rid 350)" "$M" "$BACKEND=$1"; green "$(rid 351)" "$C2"
  lookup
}
asked_once() {   # asked_once <name>: run 350's LOOKUP run line once and one call for its jobs
  check "$1" "LOOKUP run lines $(lines_re "^LOOKUP run $(rid 350) "), jobs calls \
$(grep -cxF "gh api $(jobs_path "$(rid 350)")" "$tmp/gh.log")" "LOOKUP run lines 1, jobs calls 1"
}
twice success
last_is "lookup, a green run listed on two pages: its ancestor is the baseline" "BASELINE $M run $(rid 350)"
asked_once "lookup, a green run listed on two pages: asked for its jobs once"
twice failure   # red: a lookup that kept both listings would ask the run twice before walking on
last_is "lookup, a red run listed on two pages: the walk goes on to c2" "BASELINE $C2 run $(rid 351)"
asked_once "lookup, a red run listed on two pages: asked for its jobs once"

# ---- §1, third round: the packet's "Round 3", sentences 13, 14 and 16 ----

# ends_failed <name> <line>: the last stdout line is <line>, it is the only LOOKUP FAILED line, and no BASELINE line
ends_failed() {
  local l; l=$(printf '%s\n' "$out" | tail -n 1)
  check "$1" "last '$l', LOOKUP FAILED lines $(lines_re "$FAILED_RE"), BASELINE lines $(lines_re '^BASELINE ')" \
    "last '$2', LOOKUP FAILED lines 1, BASELINE lines 0"
}
# raw <path> <body>: the stub gh answers <body> exactly as given (no newline added), exit 0
raw() { printf '%s' "$2" > "$tmp/fixtures/$(key "$1").json"; }
body_of() { cat "$tmp/fixtures/$(key "$1").json"; }   # body_of <path>: the fixture written for <path>

# added 13: an answer with exit 0 that is not exactly one JSON value of the shape expected is a failed lookup,
# never "no runs" (the bootstrap) and never "no jobs" (the walk going on). Two values each hold a green run or job,
# so that a lookup that read them one by one would name a baseline.
for answer in empty two-values total_count-only workflow_runs-7; do
  fresh
  case $answer in
    empty) raw "$(runs_path 1)" ''; what="an empty runs page" ;;
    two-values)
      runs_page 1 1 "$(rid 401):$M:completed:success"; green "$(rid 401)" "$M"
      raw "$(runs_path 1)" "$(body_of "$(runs_path 1)")"$'\n''{"total_count": 0, "workflow_runs": []}'$'\n'
      what="a runs page of two JSON values" ;;
    total_count-only) raw "$(runs_path 1)" '{"total_count": 0}'$'\n'; what='a runs page {"total_count": 0}' ;;
    workflow_runs-7) raw "$(runs_path 1)" '{"workflow_runs": 7}'$'\n'; what='a runs page {"workflow_runs": 7}' ;;
  esac
  lookup
  exit_is "lookup, $what with exit 0: exit 1" 1
  ends_failed "lookup, $what with exit 0: LOOKUP FAILED, never no runs" \
    "LOOKUP FAILED: runs page 1 is not the JSON expected"
done
fresh   # a full page 1 (99 runs of the stray commit and c2's green run), then page 2 empty: not the list's end
full=(); for ((i = 0; i < 99; i++)); do full+=("$(rid $((4100 + i))):$X1:completed:success"); done
runs_page 1 101 "${full[@]}" "$(rid 405):$C2:completed:success"; green "$(rid 405)" "$C2"
raw "$(runs_path 2)" ''
lookup
exit_is "lookup, page 2 empty with exit 0 after a full page 1: exit 1" 1
ends_failed "lookup, page 2 empty with exit 0 after a full page 1: LOOKUP FAILED names page 2" \
  "LOOKUP FAILED: runs page 2 is not the JSON expected"
for answer in empty two-values; do   # m's run's jobs; c2's run is green, where a walk that went on would stop
  fresh
  runs_page 1 2 "$(rid 411):$M:completed:success" "$(rid 410):$C2:completed:success"; green "$(rid 410)" "$C2"
  if [ "$answer" = empty ]; then
    raw "$(jobs_path "$(rid 411)")" ''; what="empty"
  else   # the backend job failed in the first value and succeeded in the second
    jobs_response "$(rid 411)" "$M" "$BACKEND=failure"; first=$(body_of "$(jobs_path "$(rid 411)")")
    jobs_response "$(rid 411)" "$M" "$BACKEND=success"
    raw "$(jobs_path "$(rid 411)")" "$first"$'\n'"$(body_of "$(jobs_path "$(rid 411)")")"$'\n'
    what="two JSON values"
  fi
  lookup
  exit_is "lookup, a run's jobs answered $what with exit 0: exit 1" 1
  ends_failed "lookup, a run's jobs answered $what with exit 0: LOOKUP FAILED, never no jobs" \
    "LOOKUP FAILED: the jobs of run $(rid 411) are not the JSON expected"
done

# added 14: a conclusion or a status is judged as the one JSON string it is, never trimmed into a word and never
# split into two. Each value is written as its JSON string: \n and \t are JSON escapes, so jq reads a newline and a
# tab. The bad job or run is m's; c2's run is green, so that a lookup that skipped the bad one would name c2.
# "success\n" ends with a newline: a regex anchored with jq's `$` (it matches before a final newline) takes it.
VALUES=('success ' ' success' 'failure\nsuccess' 'success\tx' '' 'success\n')
for v in "${VALUES[@]}"; do
  fresh
  runs_page 1 2 "$(rid 421):$M:completed:success" "$(rid 420):$C2:completed:success"
  jobs_response "$(rid 421)" "$M" "$BACKEND=$v" "mapping · ui=success"; green "$(rid 420)" "$C2"
  lookup
  exit_is "lookup, a backend job whose conclusion is \"$v\": exit 1" 1
  ends_failed "lookup, a backend job whose conclusion is \"$v\": LOOKUP FAILED, never trimmed or split" \
    "LOOKUP FAILED: the jobs of run $(rid 421) hold a conclusion that is not the JSON expected"
done
for v in "${VALUES[@]}"; do
  for field in status conclusion; do
    fresh
    if [ "$field" = status ]; then bad="$(rid 431):$M:$v:success"; else bad="$(rid 431):$M:completed:$v"; fi
    runs_page 1 2 "$bad" "$(rid 430):$C2:completed:success"; green "$(rid 431)" "$M"; green "$(rid 430)" "$C2"
    lookup
    exit_is "lookup, a run on a runs page whose $field is \"$v\": exit 1" 1
    ends_failed "lookup, a run on a runs page whose $field is \"$v\": LOOKUP FAILED, the run refused" \
      "LOOKUP FAILED: runs page 1 holds a run that is not the JSON expected"
  done
done

# added 16: the lookup's own tools. A PATH of symlinks to every command of /usr/bin (and /bin when it is another
# directory) but gh and jq, then the stub gh (a wrapper: the stub finds its fixtures beside itself) or the harness's
# jq, as each case needs them.
toolpath() {   # toolpath <name> <gh: yes|no> <jq: yes|no>: the directory $tmp/path-<name>
  local p=$tmp/path-$1 f
  mkdir -p "$p"
  ln -s /usr/bin/* "$p/"
  if ! [ /bin -ef /usr/bin ]; then
    for f in /bin/*; do [ -e "$p/${f##*/}" ] || [ -L "$p/${f##*/}" ] || ln -s "$f" "$p/"; done
  fi
  rm -f "$p/gh" "$p/jq"
  if [ "$2" = yes ]; then printf '#!/usr/bin/env bash\nexec %q "$@"\n' "$tmp/bin/gh" > "$p/gh"; chmod +x "$p/gh"; fi
  if [ "$3" = yes ]; then ln -s "$(command -v jq)" "$p/jq"; fi
}
tools_on() {   # tools_on <dir>: what a shell with PATH=<dir> finds
  env PATH="$1" bash -c 'for c in bash git tar mkdir sed sort tr; do
      command -v "$c" > /dev/null || printf "no %s, " "$c"
    done; printf "gh %s, jq %s" "$(command -v gh || echo none)" "$(command -v jq || echo none)"'
}
toolpath nojq yes no; toolpath nogh no yes; toolpath neither no no
if [ "$(tools_on "$tmp/path-nojq")" != "gh $tmp/path-nojq/gh, jq none" ] \
  || [ "$(tools_on "$tmp/path-nogh")" != "gh none, jq $tmp/path-nogh/jq" ] \
  || [ "$(tools_on "$tmp/path-neither")" != "gh none, jq none" ] \
  || [ "$(env PATH="$tmp/path-nojq" gh api repos/example/none > /dev/null 2>&1; tail -n 1 "$tmp/gh.log")" \
    != "NO FIXTURE repos/example/none" ]; then
  echo "FAIL harness: a PATH without gh or jq does not hold what its name says, or the stub gh is not reached on it"
  exit 1
fi
fresh; runs_page 1 1 "$(rid 441):$M:completed:success"; green "$(rid 441)" "$M"
lookup PATH="$tmp/path-nojq"
exit_is "lookup without jq on PATH: exit 1" 1
ends_failed "lookup without jq on PATH: LOOKUP FAILED, jq is not installed" "LOOKUP FAILED: jq is not installed"
check "lookup without jq on PATH: no call is made (the stub gh on that PATH logs nothing)" \
  "gh log lines $(wc -l < "$tmp/gh.log"), the line $(lines_x 'LOOKUP FAILED: jq is not installed')" \
  "gh log lines 0, the line 1"
lookup PATH="$tmp/path-nogh"
exit_is "lookup without gh on PATH: exit 1" 1
ends_failed "lookup without gh on PATH: LOOKUP FAILED, gh is not installed" "LOOKUP FAILED: gh is not installed"
fresh
lookup PATH="$tmp/path-neither" RATCHET_BASELINE_SHA="$C3"
exit_is "lookup, a pull request without gh and jq on PATH: exit 0" 0
last_is "lookup, a pull request without gh and jq on PATH: its base is the baseline" "BASELINE $C3 (pull request base)"
check "lookup, a pull request without gh and jq on PATH: <dir> holds the base's tree" "$(dir_rows)" "$(rows_of c3)"

# ==== §2 scripts/ratchet-ci.sh push ====

datefile() { local t; for t in "$repo/mapping" "$night"; do printf '%b' "$1" > "$t/scripts/ratchet-enforced-from"; done; }
nodatefile() { rm -f "$repo/mapping/scripts/ratchet-enforced-from" "$night/scripts/ratchet-enforced-from"; }
# java_says <exit> [<stdout line>...]: what the stub check prints and returns (stderr empty)
java_says() {
  local code=$1; shift
  if [ $# -gt 0 ]; then printf '%s\n' "$@" > "$tmp/java.out"; else : > "$tmp/java.out"; fi
  echo "$code" > "$tmp/java.rc"; : > "$tmp/java.err"
}
lookup_green() { fresh; runs_page 1 1 "$(rid 201):$M:completed:success"; green "$(rid 201)" "$M"; }
lookup_broken() {
  fresh; fixture "$(runs_path 1)" '{"message": "Bad credentials", "status": "401"}' 1 'gh: Bad credentials (HTTP 401)'
}
GREEN="BASELINE $M run $(rid 201)"
MEASURED="MEASURED coverage engine/xdm lines 9/10 branches 1/2"
ANNOT='^::(error|warning|notice)'
# ci <mode> [<VAR=value>...]: scripts/ratchet-ci.sh <mode>, cwd the repository's mapping/ (nightly: the nightly tree)
ci() {
  local mode=$1 where=$repo/mapping; shift
  [ "$mode" = nightly ] && where=$night
  : > "$tmp/gh.log"; : > "$tmp/java.log"; rm -f "$tmp/java.saw"
  (cd "$where" && env "$@" scripts/ratchet-ci.sh "$mode" > "$tmp/out" 2> "$tmp/err"); rc=$?
  out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
}
reason() { printf '%s\n' "$out" | sed -n 's/^LOOKUP FAILED: //p' | head -n 1; }
java_args() { sed -E 's/ --baseline .+$/ --baseline <dir>/' "$tmp/java.log"; }

# the shipped date file (copied above when it exists) is one the script reads
lookup_green; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$BEFORE
check "push, the shipped date file: read without a date-file error, exit 0" \
  "exit $rc, date-file errors $(lines_re '^::error::ratchet: the date file')" "exit 0, date-file errors 0"

datefile "$DATE\n"
for day in $BEFORE $DATE; do
  lookup_green; java_says 0 "$MEASURED" "RATCHET CHECK OK"
  ci push RATCHET_TODAY=$day
  exit_is "push on $day, the check OK: exit 0" 0
  absent "push on $day, the check OK: no annotation" "$ANNOT" "RATCHET CHECK OK"
done
has "push: the lookup's stdout is passed through" "$GREEN"
has "push: the check's stdout is passed through" "$MEASURED"
check "push: the check ran with the baseline" "$(java_args)" "scripts/ratchet.java check --baseline <dir>"
check "push: the check was given the baseline commit's tree" "$(cat "$tmp/java.saw" 2>&1)" "coverage.tsv of m"

lookup_green; java_says 1 "FAIL coverage engine/xdm lines: 92 % is below the ratchet's 95 %" "RATCHET FAIL: 1 problem(s)"
WOULD="::warning::ratchet would fail from $DATE: RATCHET FAIL: 1 problem(s)"
FAILED="::error::ratchet failed: RATCHET FAIL: 1 problem(s)"
ci push RATCHET_TODAY=$BEFORE
exit_is "push the day before, a failing check: exit 0" 0
has "push the day before, a failing check: the would-fail warning" "$WOULD"
absent "push the day before, a failing check: no error" '^::error::' "$WOULD"
ci push RATCHET_TODAY=$DATE
exit_is "push on the date, a failing check: exit 1" 1
has "push on the date, a failing check: the error" "$FAILED"
absent "push on the date, a failing check: no would-fail warning" 'would fail' "$FAILED"
ci push RATCHET_TODAY=2026-12-01
exit_is "push a later month, a failing check: exit 1" 1
has "push a later month, a failing check: the error" "$FAILED"

lookup_broken; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$BEFORE
exit_is "push the day before, a failed lookup: exit 0" 0
has_re "push, a failed lookup: the lookup's LOOKUP FAILED line is passed through" '^LOOKUP FAILED: .+'
warn="::warning::ratchet: LOOKUP FAILED - $(reason) (reported until $DATE)"
has "push the day before, a failed lookup: its own warning, with the lookup's reason" "$warn"
absent "push the day before, a failed lookup: never worded as a failing check" 'would fail|ratchet failed' "$warn"
check "push the day before, a failed lookup: plain check ran, without --baseline" "$(cat "$tmp/java.log")" \
  "scripts/ratchet.java check"
ci push RATCHET_TODAY=$DATE
exit_is "push on the date, a failed lookup: exit 1" 1
error="::error::ratchet: LOOKUP FAILED - $(reason)"
has "push on the date, a failed lookup: its own error, with the lookup's reason" "$error"
absent "push on the date, a failed lookup: never worded as a failing check" 'would fail|ratchet failed' "$error"
check "push on the date, a failed lookup: plain check ran, without --baseline" "$(cat "$tmp/java.log")" \
  "scripts/ratchet.java check"

lookup_broken; java_says 1 "RATCHET FAIL: 2 problem(s)"
ci push RATCHET_TODAY=$BEFORE
exit_is "push the day before, a failed lookup and a failing plain check: exit 0" 0
has "push the day before, a failed lookup and a failing plain check: the lookup's warning" \
  "::warning::ratchet: LOOKUP FAILED - $(reason) (reported until $DATE)"
has "push the day before, a failed lookup and a failing plain check: the would-fail warning" \
  "::warning::ratchet would fail from $DATE: RATCHET FAIL: 2 problem(s)"
ci push RATCHET_TODAY=$DATE
exit_is "push on the date, a failed lookup and a failing plain check: exit 1" 1
has "push on the date, a failed lookup and a failing plain check: the lookup's error" \
  "::error::ratchet: LOOKUP FAILED - $(reason)"
has "push on the date, a failed lookup and a failing plain check: the check's error" \
  "::error::ratchet failed: RATCHET FAIL: 2 problem(s)"

# no verdict: exit 2 on any day, and nothing is reported as a would-fail or a failed check
noverdict() { echo "::error::ratchet: the check wrote no verdict (exit $1)"; }
for day in $BEFORE $DATE; do
  lookup_green; java_says 2; echo "usage: java scripts/ratchet.java check [--baseline <dir>]" > "$tmp/java.err"
  ci push RATCHET_TODAY=$day
  exit_is "push on $day, the check refused its command line (exit 2): exit 2" 2
  has "push on $day, the check refused its command line: no verdict" "$(noverdict 2)"
  lookup_green; java_says 1 "$MEASURED"
  echo 'Exception in thread "main" java.lang.IllegalStateException: scratch' > "$tmp/java.err"
  ci push RATCHET_TODAY=$day
  exit_is "push on $day, the check's exit 1 without a verdict line: exit 2" 2
  has "push on $day, the check's exit 1 without a verdict line: no verdict" "$(noverdict 1)"
done
lookup_green; java_says 1 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$DATE
exit_is "push, an OK line with exit 1: exit 2" 2
absent "push, an OK line with exit 1: no verdict, nothing else reported" 'would fail|ratchet failed' "$(noverdict 1)"
lookup_green; java_says 0 "RATCHET FAIL: 1 problem(s)"
ci push RATCHET_TODAY=$BEFORE
exit_is "push, a FAIL line with exit 0: exit 2" 2
absent "push, a FAIL line with exit 0: no verdict, nothing else reported" 'would fail|ratchet failed' "$(noverdict 0)"
lookup_green; java_says 137
ci push RATCHET_TODAY=$DATE
exit_is "push, the check killed (exit 137): exit 2" 2
has "push, the check killed: no verdict" "$(noverdict 137)"

for day in $BEFORE $DATE; do
  lookup_green
  java_says 0 "RISE mutation engine/ir: 3 -> 2 surviving mutants" "RISE coverage engine/xdm lines: 91 % -> 93 %" \
    "RATCHET CHECK OK"
  ci push RATCHET_TODAY=$day
  exit_is "push on $day, RISE lines and the check OK: exit 0" 0
  has "push on $day: a RISE line becomes a warning" \
    "::warning::ratchet: RISE mutation engine/ir: 3 -> 2 surviving mutants - regenerate ratchet/"
  has "push on $day: every RISE line becomes a warning" \
    "::warning::ratchet: RISE coverage engine/xdm lines: 91 % -> 93 % - regenerate ratchet/"
done

for fault in missing empty two-lines malformed; do
  case $fault in
    missing) nodatefile ;;
    empty) datefile '' ;;
    two-lines) datefile "$DATE\n2026-12-01\n" ;;
    malformed) datefile '25.11.2026\n' ;;
  esac
  for day in $BEFORE $DATE; do
    lookup_green; java_says 0 "RATCHET CHECK OK"
    ci push RATCHET_TODAY=$day
    exit_is "push on $day, the date file $fault: exit 2" 2
    has_re "push on $day, the date file $fault: its error" '^::error::ratchet: the date file .+'
    no_calls "push on $day, the date file $fault: before gh or java is called" '^::error::ratchet: the date file .+'
  done
done
datefile "$DATE enforced\n"
lookup_green; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$DATE
exit_is "push, a date with text after it: exit 2" 2
no_calls "push, a date with text after it: its error, before gh or java" '^::error::ratchet: the date file .+'
datefile "$DATE\n"

lookup_green; java_says 0 "RATCHET CHECK OK"
ci pull RATCHET_TODAY=$DATE
check "an unknown mode: usage on stderr, exit 2, no call" \
  "exit $rc, usage $(usage_seen), gh $(gh_calls), java $(java_calls)" "exit 2, usage yes, gh 0, java 0"
: > "$tmp/gh.log"; : > "$tmp/java.log"
(cd "$repo/mapping" && RATCHET_TODAY=$DATE scripts/ratchet-ci.sh > "$tmp/out" 2> "$tmp/err"); rc=$?
out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
check "no mode: usage on stderr, exit 2, no call" \
  "exit $rc, usage $(usage_seen), gh $(gh_calls), java $(java_calls)" "exit 2, usage yes, gh 0, java 0"

# fenced <file>: the lines of the first fenced block of a step summary; fences <file>: its count of fence lines
fenced() { awk '/^```/ {f++; next} f == 1' "$1"; }
fences() { grep -c '^```' "$1"; }
lookup_green; java_says 0 "$MEASURED" "RATCHET CHECK OK"
printf "the previous step's line\n" > "$tmp/summary.md"
ci push RATCHET_TODAY=$DATE GITHUB_STEP_SUMMARY="$tmp/summary.md"
check "push, the step summary: one fenced block appended after what was there" \
  "first '$(head -n 1 "$tmp/summary.md")', fences $(fences "$tmp/summary.md")" "first 'the previous step's line', fences 2"
wanted=("LOOKUP pages 1 runs 1" "LOOKUP run $(rid 201) sha $M status completed conclusion success"
  "LOOKUP job $(rid 201) name $BACKEND conclusion success" "$GREEN" "$MEASURED" "RATCHET CHECK OK")
check "push, the step summary: the lookup's and the check's lines are in the block (in any order)" \
  "$(fenced "$tmp/summary.md" | grep -xF "${wanted[@]/#/-e}" | sort)" "$(printf '%s\n' "${wanted[@]}" | sort)"

# RATCHET_TODAY unset: today is the clock's (`date -u +%F`); a date long past and one far ahead
lookup_green; java_says 1 "RATCHET FAIL: 1 problem(s)"
datefile '2000-01-01\n'
ci push
exit_is "push, RATCHET_TODAY unset, the date long past: enforced, exit 1" 1
has "push, RATCHET_TODAY unset, the date long past: the error" "$FAILED"
datefile '2999-12-31\n'
ci push
exit_is "push, RATCHET_TODAY unset, the date far ahead: the week, exit 0" 0
has "push, RATCHET_TODAY unset, the date far ahead: the would-fail warning" \
  "::warning::ratchet would fail from 2999-12-31: RATCHET FAIL: 1 problem(s)"
datefile "$DATE\n"

fresh; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$DATE RATCHET_BASELINE_SHA="$C3"
exit_is "push, a pull request: exit 0" 0
gh_none "push, a pull request: its base is the baseline and gh is never called" "BASELINE $C3 (pull request base)"
check "push, a pull request: the check was given the base's tree" "$(cat "$tmp/java.saw" 2>&1)" "coverage.tsv of c3"

# ---- §2, second round: the packet's "Added on the implementer's hand-back", sentences 1, 7, 8 and 12 ----

# added 1: RATCHET_TODAY, set and not empty, is one real ISO date; with a failing check, so that a value read as
# "before the date" would show as a would-fail warning and exit 0
NOT_ISO="::error::ratchet: RATCHET_TODAY is not an ISO date"
for today in never 2026-13-01 20261125; do
  lookup_green; java_says 1 "RATCHET FAIL: 1 problem(s)"
  ci push RATCHET_TODAY="$today"
  exit_is "push, RATCHET_TODAY '$today': exit 2" 2
  has "push, RATCHET_TODAY '$today': its error" "$NOT_ISO"
  no_calls "push, RATCHET_TODAY '$today': before gh or java is called" "^$NOT_ISO\$"
done
lookup_green; java_says 1 "RATCHET FAIL: 1 problem(s)"
datefile '2000-01-01\n'
ci push RATCHET_TODAY=
exit_is "push, RATCHET_TODAY empty: today is the clock's, the date long past: exit 1" 1
absent "push, RATCHET_TODAY empty: the check's error, no RATCHET_TODAY error" 'RATCHET_TODAY' "$FAILED"
datefile "$DATE\n"

# added 7: a lookup that ends non-zero without a LOOKUP FAILED line (here: it prints nothing and exits 3)
scratch_lookup=$repo/mapping/scripts/ratchet-baseline.sh real_lookup=$tmp/ratchet-baseline.sh.real
if [ -f "$scratch_lookup" ]; then cp -p "$scratch_lookup" "$real_lookup"; fi
printf '#!/usr/bin/env bash\nexit 3\n' > "$scratch_lookup"; chmod +x "$scratch_lookup"
lookup_green; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$BEFORE
exit_is "push the day before, a lookup that exits 3 silently: exit 0" 0
has_re "push the day before, a lookup that exits 3 silently: its warning, with a reason" \
  '^::warning::ratchet: LOOKUP FAILED - .+ \(reported until 2026-11-25\)$'
check "push the day before, a lookup that exits 3 silently: plain check ran" "$(cat "$tmp/java.log")" \
  "scripts/ratchet.java check"
ci push RATCHET_TODAY=$DATE
exit_is "push on the date, a lookup that exits 3 silently: exit 1" 1
has_re "push on the date, a lookup that exits 3 silently: its error, with a reason" '^::error::ratchet: LOOKUP FAILED - .+'
if [ -f "$real_lookup" ]; then cp -p "$real_lookup" "$scratch_lookup"; else rm -f "$scratch_lookup"; fi

# added 8: a date that is no calendar day, and a date without its final newline
for fault in '2026-02-30\n' "$DATE"; do
  lookup_green; java_says 0 "RATCHET CHECK OK"
  datefile "$fault"
  ci push RATCHET_TODAY=$DATE
  exit_is "push, the date file '$fault': exit 2" 2
  no_calls "push, the date file '$fault': its error, before gh or java" '^::error::ratchet: the date file .+'
done
datefile "$DATE\n"

# added 12: started by its path from another working directory
lookup_green; java_says 0 "RATCHET CHECK OK"
: > "$tmp/gh.log"; : > "$tmp/java.log"; rm -f "$tmp/java.saw"
(cd "$tmp" && env RATCHET_TODAY=$DATE "$repo/mapping/scripts/ratchet-ci.sh" push > "$tmp/out" 2> "$tmp/err"); rc=$?
out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
exit_is "push from another working directory: exit 0" 0
absent "push from another working directory: the check OK passed through, no annotation" "$ANNOT" "RATCHET CHECK OK"
check "push from another working directory: the check ran with the baseline" "$(java_args)" \
  "scripts/ratchet.java check --baseline <dir>"

# ---- §2, third round: the packet's "Round 3", sentences 17 and 18 ----

# added 17: TMPDIR names a directory that does not exist, so that `mktemp -d` cannot make the work directory
NO_TMP="::error::ratchet: cannot create a temporary directory"
for day in $BEFORE $DATE; do
  lookup_green; java_says 0 "RATCHET CHECK OK"
  ci push RATCHET_TODAY=$day TMPDIR="$tmp/no-such-directory"
  exit_is "push on $day, TMPDIR a directory that does not exist: exit 2" 2
  has "push on $day, TMPDIR a directory that does not exist: its error" "$NO_TMP"
  no_calls "push on $day, TMPDIR a directory that does not exist: before gh or java is called" "^$NO_TMP\$"
done

# added 18: the token is never printed. GH_TOKEN is the test's own value (the stub gh does not read it)
TOKEN=map12b-scratch-token-7c41e09d5a
token_seen() { printf 'token in stdout %s, in stderr %s' "$(printf '%s\n' "$out" | grep -cF -- "$TOKEN")" \
  "$(printf '%s\n' "$err" | grep -cF -- "$TOKEN")"; }
lookup_green; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$DATE GH_TOKEN=$TOKEN
check "push, GH_TOKEN set and the lookup green: the token in neither stdout nor stderr" \
  "exit $rc, $(token_seen), the line $(lines_x "$GREEN")" "exit 0, token in stdout 0, in stderr 0, the line 1"
lookup_broken; java_says 0 "RATCHET CHECK OK"
ci push RATCHET_TODAY=$DATE GH_TOKEN=$TOKEN
check "push, GH_TOKEN set and gh failing: the token in neither stdout nor stderr" \
  "exit $rc, $(token_seen), LOOKUP FAILED lines $(lines_re "$FAILED_RE")" \
  "exit 1, token in stdout 0, in stderr 0, LOOKUP FAILED lines 1"

# ==== §3 scripts/ratchet-ci.sh nightly (the real pit-summary.sh, PIT reports under the nightly tree) ====

rows() { printf '%b' "$1" > "$night/ratchet/mutation.tsv"; }
night_fresh() { rm -rf "$night/engine"; : > "$night/ratchet/mutation.tsv"; }
# report <module> <killed> <timed out> <survived> <non-viable>: <module>/target/pit-reports/mutations.xml
report() {
  local d=$night/$1/target/pit-reports i
  mkdir -p "$d"
  {
    echo "<mutations>"
    for ((i = 0; i < $2; i++)); do echo "<mutation detected='true' status='KILLED'><x/></mutation>"; done
    for ((i = 0; i < $3; i++)); do echo "<mutation detected='true' status='TIMED_OUT'><x/></mutation>"; done
    for ((i = 0; i < $4; i++)); do echo "<mutation detected='false' status='SURVIVED'><x/></mutation>"; done
    for ((i = 0; i < $5; i++)); do echo "<mutation detected='false' status='NON_VIABLE'><x/></mutation>"; done
    echo "</mutations>"
  } > "$d/mutations.xml"
}
module_lines() { printf '%s\n' "$out" | awk '$1 ~ /^engine\// && $2 != "timed-out" && $2 != "generated" {print $1}'; }

night_fresh; rows 'engine/ir\t1\t10\nengine/xdm\t0\t5\n'; report engine/ir 9 0 1 0; report engine/xdm 5 0 0 0
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly on the date, no drop: exit 0" 0
has "nightly: pit-summary.sh's lines are passed through" "engine/ir 9/10 survivors 1 floor 1 ok"
absent "nightly on the date, no drop: no annotation" "$ANNOT" "engine/xdm 5/5 survivors 0 floor 0 ok"

night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 8 0 2 0
ci nightly RATCHET_TODAY=$BEFORE
exit_is "nightly the day before, a count above its row: exit 0" 0
has "nightly the day before, a count above its row: the would-fail warning" \
  "::warning::mutation ratchet would fail from $DATE: engine/ir 8/10 survivors 2 floor 1 DROP"
absent "nightly the day before, a count above its row: no error" '^::error::' "engine/ir 8/10 survivors 2 floor 1 DROP"
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly on the date, a count above its row: exit 1" 1
has "nightly on the date, a count above its row: the error" \
  "::error::mutation ratchet failed: engine/ir 8/10 survivors 2 floor 1 DROP"

night_fresh; rows 'engine/ir\t1\t10\nengine/udf\t0\t7\n'; report engine/ir 9 0 1 0
ci nightly RATCHET_TODAY=$BEFORE
exit_is "nightly the day before, a row without a report: exit 0" 0
has "nightly the day before, a row without a report: the would-fail warning" \
  "::warning::mutation ratchet would fail from $DATE: engine/udf no PIT report DROP"
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly on the date, a row without a report: exit 1" 1
has "nightly on the date, a row without a report drops" "::error::mutation ratchet failed: engine/udf no PIT report DROP"

night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 9 0 1 0; report engine/extra 4 0 1 0
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly on the date, a report without a row: exit 0" 0
absent "nightly on the date, a report without a row is printed and drops nothing" "$ANNOT" \
  "engine/extra 4/5 survivors 1 floor none ok"

night_fresh; rows 'engine/xdm\t0\t5\nengine/ir\t1\t10\n'
report engine/ir 9 0 1 0; report engine/xdm 5 0 0 0; report engine/api 3 0 0 0
mkdir -p "$night/engine/serializer/target/pit-reports"   # a report directory without mutations.xml: no module
echo '<html/>' > "$night/engine/serializer/target/pit-reports/index.html"
ci nightly RATCHET_TODAY=$DATE
check "nightly: the modules are the rows' and the reports', sorted, each once" "$(module_lines)" "engine/api
engine/ir
engine/xdm"

night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 7 2 1 0
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly on the date, timed-out mutants within the row: exit 0" 0
absent "nightly: the timed-out line is passed through, not annotated" "$ANNOT" "engine/ir timed-out 2 of 9 killed"

for day in $BEFORE $DATE; do
  night_fresh   # no row and no report: pit-summary.sh is given no module and answers with its usage, exit 2
  ci nightly RATCHET_TODAY=$day
  exit_is "nightly on $day, pit-summary.sh exiting 2: exit 2" 2
  has "nightly on $day, pit-summary.sh exiting 2: no verdict" \
    "::error::ratchet: the mutation summary wrote no verdict (exit 2)"
done

night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 8 0 2 0; nodatefile
ci nightly RATCHET_TODAY=$DATE
exit_is "nightly, no date file: exit 2" 2
absent_re "nightly, no date file: its error, before pit-summary.sh runs" '^engine/' '^::error::ratchet: the date file .+'
datefile "$DATE\n"

night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 8 0 2 0
: > "$tmp/summary.md"
ci nightly RATCHET_TODAY=$DATE GITHUB_STEP_SUMMARY="$tmp/summary.md"
check "nightly, the step summary: pit-summary.sh's lines in one fenced block" \
  "fences $(fences "$tmp/summary.md"), $(fenced "$tmp/summary.md" | grep -xF 'engine/ir 8/10 survivors 2 floor 1 DROP')" \
  "fences 2, engine/ir 8/10 survivors 2 floor 1 DROP"

# ---- §3, second round: the packet's "Added on the implementer's hand-back", sentences 1 and 12 ----

# added 1: with a drop, so that a value read as "before the date" would show as a would-fail warning and exit 0
for today in never 2026-13-01 20261125; do
  night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 8 0 2 0
  ci nightly RATCHET_TODAY="$today"
  exit_is "nightly, RATCHET_TODAY '$today': exit 2" 2
  absent_re "nightly, RATCHET_TODAY '$today': its error, before pit-summary.sh runs" '^engine/' "^$NOT_ISO\$"
done

# added 12: started by its path from another working directory
night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 9 0 1 0
(cd "$tmp" && env RATCHET_TODAY=$DATE "$night/scripts/ratchet-ci.sh" nightly > "$tmp/out" 2> "$tmp/err"); rc=$?
out=$(cat "$tmp/out"); err=$(cat "$tmp/err")
exit_is "nightly from another working directory: exit 0" 0
absent "nightly from another working directory: pit-summary.sh's line passed through, no annotation" "$ANNOT" \
  "engine/ir 9/10 survivors 1 floor 1 ok"

# ---- §3, third round: the packet's "Round 3", sentences 15 and 17 ----

# pit-summary.sh is wrapped for this round: the wrapper logs each call to pit.log, then runs the real one
scratch_pit=$night/scripts/pit-summary.sh real_pit=$tmp/pit-summary.sh.real
if [ -f "$scratch_pit" ]; then cp -p "$scratch_pit" "$real_pit"; fi
printf '#!/usr/bin/env bash\necho "pit-summary.sh $*" >> %q\nexec %q "$@"\n' "$tmp/pit.log" "$real_pit" > "$scratch_pit"
chmod +x "$scratch_pit"
pit_calls() { wc -l < "$tmp/pit.log"; }
ci_night() { : > "$tmp/pit.log"; ci nightly "$@"; }
# the wrapper's own check: with the rows there, the nightly calls it once and its line passes through
night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 9 0 1 0
ci_night RATCHET_TODAY=$DATE
check "nightly, pit-summary.sh wrapped: called once, its line passed through" \
  "exit $rc, pit-summary.sh calls $(pit_calls), the line $(lines_x 'engine/ir 9/10 survivors 1 floor 1 ok')" \
  "exit 0, pit-summary.sh calls 1, the line 1"

# added 15: ratchet/mutation.tsv missing, or a directory (not a readable file); a report beside it, so that a
# pit-summary.sh that ran would print the module's line
NO_TSV="::error::ratchet: ratchet/mutation.tsv is missing"
for fault in missing directory; do
  for day in $BEFORE $DATE; do
    night_fresh; rm -f "$night/ratchet/mutation.tsv"; report engine/ir 9 0 1 0
    if [ "$fault" = directory ]; then mkdir "$night/ratchet/mutation.tsv"; fi
    ci_night RATCHET_TODAY=$day
    exit_is "nightly on $day, ratchet/mutation.tsv $fault: exit 2" 2
    has "nightly on $day, ratchet/mutation.tsv $fault: its error" "$NO_TSV"
    check "nightly on $day, ratchet/mutation.tsv $fault: before pit-summary.sh is called" \
      "pit-summary.sh calls $(pit_calls), engine lines $(lines_re '^engine/'), the error $(lines_x "$NO_TSV")" \
      "pit-summary.sh calls 0, engine lines 0, the error 1"
    rm -rf "$night/ratchet/mutation.tsv"
  done
done

# added 17: TMPDIR names a directory that does not exist; with a drop, so that a run that went on would show it
for day in $BEFORE $DATE; do
  night_fresh; rows 'engine/ir\t1\t10\n'; report engine/ir 8 0 2 0
  ci_night RATCHET_TODAY=$day TMPDIR="$tmp/no-such-directory"
  exit_is "nightly on $day, TMPDIR a directory that does not exist: exit 2" 2
  check "nightly on $day, TMPDIR a directory that does not exist: its error, before pit-summary.sh is called" \
    "the error $(lines_x "$NO_TMP"), pit-summary.sh calls $(pit_calls), engine lines $(lines_re '^engine/')" \
    "the error 1, pit-summary.sh calls 0, engine lines 0"
done
if [ -f "$real_pit" ]; then cp -p "$real_pit" "$scratch_pit"; else rm -f "$scratch_pit"; fi
night_fresh

if [ "$fails" -gt 0 ]; then exit 1; fi
exit 0
