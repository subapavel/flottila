#!/usr/bin/env bash
# One-time GitHub setup for this repo: labels used by the skills and the workflow.
# Run from the repo root after the first push:  bash scripts/bootstrap-github.sh
# Needs gh >= 2.94 (sub-issues and issue dependencies) and a logged-in gh.
set -euo pipefail

repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
echo "Creating labels in $repo"

label() { gh label create "$1" --color "$2" --description "$3" --force >/dev/null && echo "  $1"; }

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
label oracle:differential "fbca04" "Acceptance: runtime vs Saxon-HE on exported XSLT"
label oracle:corpus    "fbca04" "Acceptance: golden-corpus cases"
label oracle:w3c       "fbca04" "Acceptance: W3C QT3 / XSLT 3.0 test sets"

label wayfinder:map       "0052cc" "Wayfinder map"
label wayfinder:research  "c5def5" "Decision ticket: research (AFK)"
label wayfinder:prototype "c5def5" "Decision ticket: prototype (HITL)"
label wayfinder:grilling  "c5def5" "Decision ticket: grilling (HITL)"
label wayfinder:task      "c5def5" "Decision ticket: task"

echo "Done. Remaining by hand: Settings → General → enable Issues; Branches → protect main (block force pushes and deletion; no PR requirement — Phase 0 commits ADRs to main, specs land by PR)."
