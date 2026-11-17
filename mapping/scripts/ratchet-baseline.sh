#!/usr/bin/env bash
# The ratchet's baseline (MAP-12b, D-164 (2); the contract is agent/packets/MAP-12b.md §1): finds the commit whose
# ratchet/ rows `java scripts/ratchet.java check --baseline <dir>` holds HEAD's rows against, and writes that commit's
# whole mapping/ratchet/ tree (coverage.tsv, mutation.tsv, bench.tsv, oracle/) into <dir>.
# - RATCHET_BASELINE_SHA set (a pull request's base): that commit; gh is never called.
# - Otherwise the closest strict ancestor of HEAD (git rev-list --topo-order: children before parents, both sides of a
#   merge) with a completed push run of mapping-ci on main, other than the run GITHUB_RUN_ID names, whose job
#   `mapping · backend` ended with success. The runs list is read page by page to its end and its order is not relied
#   on; the run's own conclusion is not read. An ancestor's runs are asked newest (highest id) first, and the walk
#   stops at the first green backend job.
# - No ancestor has one (a fork's first run, a renamed job): HEAD's first parent, and the BASELINE line says so.
# For the record it prints `LOOKUP pages <n> runs <count>` and, for every run whose jobs it asks, a `LOOKUP run` and a
# `LOOKUP job` line; its last line is `BASELINE <sha> ...`, exit 0. Any failure is one line `LOOKUP FAILED: <reason>`,
# exit 1, and <dir> is not to be used. Usage: exit 2.
#
#   scripts/ratchet-baseline.sh <dir>        (from the mapping's root; <dir> is created when absent, given empty)
#
# Reads GITHUB_REPOSITORY, GITHUB_RUN_ID and RATCHET_BASELINE_SHA; gh reads GH_TOKEN itself and is only called as
# `gh api <path>`. gh and jq are looked up on PATH before the first call (`LOOKUP FAILED: gh is not installed`, the
# same for jq); the pull-request path needs neither. What the API answers is data: a run id reaches gh only
# as digits, and a sha reaches git only as 40 hex digits (the shas this script hands to git come from git itself or
# from RATCHET_BASELINE_SHA, checked). A status or a conclusion is judged inside jq as the one JSON string it is
# (null, or exactly ^[a-z_]+$), before any line is split; anything else reaches the shell as `-`, which is refused.
set -uo pipefail

BACKEND="mapping · backend"
PER_PAGE=100
MAX_PAGES=100   # 10 000 runs; GitHub answers at most 1 000 for a query filtered by branch and event
BOOTSTRAP="(bootstrap: no push run of mapping-ci on main has a green backend job among HEAD's ancestors)"

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "usage: scripts/ratchet-baseline.sh <dir>" >&2
  exit 2
fi
dir=$1

failed() { echo "LOOKUP FAILED: $1"; exit 1; }
is_sha() { [[ $1 =~ ^[0-9a-f]{40}$ ]]; }
is_word() { [[ $1 =~ ^[a-z_]+$ ]]; }   # a status or a conclusion as WORD hands it on: `-` is none

# one JSON value on stdin, or an error: an empty answer is no answer
ONE='def one: [inputs] | if length == 1 then .[0] else error("not one JSON value") end;'
# a status or a conclusion (a string, or null) judged whole: null is "null", exactly ^[a-z_]+$ stays as it is, any
# other string is "-". \A and \z: jq's $ also matches before a final newline. Nothing is trimmed, nothing split.
WORD='def word: if . == null then "null" elif test("\\A[a-z_]+\\z") then . else "-" end;'
# a runs page: one `<id> TAB <head_sha> TAB <status> TAB <conclusion>` line per run. @tsv escapes a tab or a newline
# inside a value; the status and the conclusion are never empty, so the tab-split (which merges two tabs) loses
# neither. An empty head_sha it does lose: the status moves into its place, and is_sha refuses it.
RUNS_JQ=$ONE$WORD'one
  | if type == "object" and (.workflow_runs | type) == "array" then .workflow_runs[] else error("no workflow_runs") end
  | if (.id | type) == "number" and (.head_sha | type) == "string" and (.status | type) == "string"
       and ((.conclusion | type) == "string" or .conclusion == null)
    then [(.id | tostring), .head_sha, (.status | word), (.conclusion | word)] | @tsv
    else error("a run without its id, head_sha, status or conclusion") end'
# a jobs response: the conclusion of every job named $name, judged by word, one per line
JOBS_JQ=$ONE$WORD'one
  | if type == "object" and (.jobs | type) == "array" then .jobs[] else error("no jobs") end
  | if (.name | type) == "string" and ((.conclusion | type) == "string" or .conclusion == null)
    then select(.name == $name) | .conclusion | word
    else error("a job without its name or conclusion") end'

top=$(git rev-parse --show-toplevel 2> /dev/null) || failed "the working directory is not inside a git checkout"

# take <sha> <how>: <sha>'s mapping/ratchet/ into <dir>, then the BASELINE line. git archive runs from the top level:
# from a subdirectory it would archive that subdirectory of the tree it is given.
take() {
  local sha=$1 how=$2 name
  mkdir -p -- "$dir" || failed "cannot create $dir"
  git -C "$top" archive --format=tar "$sha:mapping/ratchet" | tar -x -f - -C "$dir" \
    || failed "the baseline $sha has no mapping/ratchet/ tree"
  for name in coverage.tsv mutation.tsv bench.tsv; do
    [ -f "$dir/$name" ] || failed "the baseline $sha has no mapping/ratchet/$name"
  done
  [ -d "$dir/oracle" ] || failed "the baseline $sha has no mapping/ratchet/oracle/"
  echo "BASELINE $sha $how"
  exit 0
}

base=${RATCHET_BASELINE_SHA:-}
if [ -n "$base" ]; then
  is_sha "$base" || failed "RATCHET_BASELINE_SHA is not a full hex sha"
  git rev-parse --verify -q "$base^{commit}" > /dev/null \
    || failed "RATCHET_BASELINE_SHA $base is no commit of this repository"
  take "$base" "(pull request base)"
fi

head=$(git rev-parse --verify -q 'HEAD^{commit}') || failed "HEAD is no commit"
[ "$(git rev-parse --is-shallow-repository)" = false ] \
  || failed "the checkout is shallow: the walk needs HEAD's whole history (fetch-depth: 0)"
parent=$(git rev-parse --verify -q 'HEAD^1') || failed "HEAD has no parent"
repo=${GITHUB_REPOSITORY:-}
[[ $repo =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || failed "GITHUB_REPOSITORY is not <owner>/<repository>"
self=${GITHUB_RUN_ID:-}

# ---- the API's tools, before the first call: gh asks, jq reads ----

type -P gh > /dev/null || failed "gh is not installed"
type -P jq > /dev/null || failed "jq is not installed"

# ---- the runs list, to its end: candidates per head_sha ----

declare -A runs_of=() line_of=()
pages=0
count=0
while :; do
  pages=$((pages + 1))
  [ "$pages" -le "$MAX_PAGES" ] || failed "the runs list did not end within $MAX_PAGES pages"
  path="repos/$repo/actions/workflows/mapping-ci.yml/runs?branch=main&event=push&per_page=$PER_PAGE&page=$pages"
  body=$(gh api "$path" < /dev/null) || failed "gh api $path exited $?"
  rows=$(printf '%s' "$body" | jq -n -r "$RUNS_JQ") || failed "runs page $pages is not the JSON expected"
  n=0
  if [ -n "$rows" ]; then
    while IFS=$'\t' read -r id sha status conclusion; do
      if ! [[ $id =~ ^[0-9]+$ ]] || ! is_sha "$sha" || ! is_word "$status" || ! is_word "$conclusion"; then
        failed "runs page $pages holds a run that is not the JSON expected"
      fi
      n=$((n + 1))
      [ "$status" = completed ] && [ "$id" != "$self" ] || continue
      [ -z "${line_of[$id]:-}" ] || continue   # listed twice: the list moved between two pages
      line_of[$id]="LOOKUP run $id sha $sha status $status conclusion $conclusion"
      runs_of[$sha]+=" $id"
    done <<< "$rows"
  fi
  count=$((count + n))
  [ "$n" -ge "$PER_PAGE" ] || break
done
echo "LOOKUP pages $pages runs $count"

# ---- the walk: HEAD's strict ancestors, children before parents ----

listed=$(git rev-list --topo-order HEAD) || failed "git rev-list --topo-order HEAD failed"
mapfile -t ancestors <<< "$listed"
for sha in "${ancestors[@]}"; do
  [ "$sha" != "$head" ] && [ -n "${runs_of[$sha]:-}" ] || continue
  # an ancestor's runs, the newest (highest id) first
  mapfile -t ids < <(tr ' ' '\n' <<< "${runs_of[$sha]}" | sed '/^$/d' | sort -rn)
  for id in "${ids[@]}"; do   # digits: checked when its page was read
    echo "${line_of[$id]}"
    path="repos/$repo/actions/runs/$id/jobs?per_page=100"
    body=$(gh api "$path" < /dev/null) || failed "gh api $path exited $?"
    conclusions=$(printf '%s' "$body" | jq -n -r --arg name "$BACKEND" "$JOBS_JQ") \
      || failed "the jobs of run $id are not the JSON expected"
    if [ -z "$conclusions" ]; then
      echo "LOOKUP job $id none named $BACKEND"
      continue
    fi
    green=no
    while IFS= read -r conclusion; do   # each line one conclusion, as word judged it
      is_word "$conclusion" || failed "the jobs of run $id hold a conclusion that is not the JSON expected"
      echo "LOOKUP job $id name $BACKEND conclusion $conclusion"
      [ "$conclusion" != success ] || green=yes
    done <<< "$conclusions"
    [ "$green" = no ] || take "$sha" "run $id"
  done
done
take "$parent" "$BOOTSTRAP"
