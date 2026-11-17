#!/usr/bin/env bash
# One-time GitHub setup for this repo: labels used by the skills and the workflow.
# Run from the repo root after the first push:  bash scripts/bootstrap-github.sh
# REST only (`gh api`): the cloud VM blocks GitHub GraphQL, so `gh repo view` and `gh label create`
# are not used (agent/NOTES.md). `{owner}/{repo}` is filled in by `gh api` from the clone's remote.
# Idempotent: an existing label is updated in place.
set -uo pipefail

echo "Creating labels in $(git remote get-url origin)"
rc=0

label() {
  local name=$1 color=$2 description=$3
  if gh api "repos/{owner}/{repo}/labels" -f name="$name" -f color="$color" -f description="$description" > /dev/null 2>&1; then
    echo "  created  $name"
  elif gh api -X PATCH "repos/{owner}/{repo}/labels/${name//:/%3A}" -f color="$color" -f description="$description" > /dev/null 2>&1; then
    echo "  updated  $name"
  else
    echo "  FAILED   $name" >&2
    rc=1
  fi
}

label needs-triage     "ededed" "Maintainer needs to evaluate this issue"
label needs-info       "ededed" "Waiting on reporter for more information"
label ready-for-agent  "0e8a16" "Fully specified, ready for an AFK agent"
label ready-for-human  "d93f0b" "Requires the owner"
label wontfix          "ffffff" "Will not be actioned"

label spec             "5319e7" "A deliverable; parent of its tickets"
label class:A          "c2e0c6" "Mechanical ticket: Sonnet medium, automatic gate only"
label class:B          "7fd1a8" "Standard ticket: Opus high, automatic gate, spec-level review"
label class:C          "1d7a4a" "Hard path: designer, test-author, Opus xhigh, per-ticket review"
label class:G          "000000" "Milestone gate"
label oracle:jqtest       "fbca04" "Acceptance: cases of jq's own test files"
label oracle:differential "fbca04" "Acceptance: our output vs the reference jq binary"
label oracle:backend      "fbca04" "Acceptance: interpreter and bytecode backends agree"
label oracle:json         "fbca04" "Acceptance: JSON parsing conformance cases"

label wayfinder:map       "0052cc" "Wayfinder map"
label wayfinder:research  "c5def5" "Decision ticket: research (AFK)"
label wayfinder:prototype "c5def5" "Decision ticket: prototype (HITL)"
label wayfinder:grilling  "c5def5" "Decision ticket: grilling (HITL)"
label wayfinder:task      "c5def5" "Decision ticket: task"

echo "Remaining by hand: Settings → General → enable Issues; Branches → protect main (block force pushes and deletion; no PR requirement — Phase 0 commits ADRs to main, specs land by PR)."
exit "$rc"
