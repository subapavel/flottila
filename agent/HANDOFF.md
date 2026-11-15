# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BRAND-1, BRAND-4 (done), BRAND-3, MAIN-1, OPS-1 - BACKLOG "priority 0".
last commit: BRAND-4 - `ci/name_guard.py` + allow-list + its test, a step in ci.yml; clean on the tree; review PASS (r2).
in flight: BRAND-3, nothing of it committed. A new file that holds the old name needs an entry in ci/name-allowlist.txt (the guard is in CI).
next exact action: BRAND-3 step 1 - commit the DCO `--first-parent` change with D-151 and agent/packets/BRAND-3.md, then the import merge.
OPS-1 IS DRAFTED, UNCOMMITTED: agent/work/OPS-1.diff (base c017e9c; `git apply -3`), agent/work/OPS-1-decided.md, OPS-1-coverage.md. To do: fix
  round, reviewer, D-152, rows OPS-12 + FIX-220, F-38, ISSUE-8. Its CLAUDE.md and .claude/agents changes stay a patch for the owner, never applied.
Verify mode: `mvn -B -ntp clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); compare with agent/work/BASE-1-counts.txt.
MAIN-1: CI is red on main since 2026-11-02 - FIX-217 (SamplePipelineTest 100 ms wall-clock bound) stops it in testkit: agent/work/MAIN-1-ci.md.
  Then FIX-212..216, 218, 219; facts in agent/work/MAIN-1-scout.md (LedgerIT first). FIX-195 IS IN THE TREE BUT UNREVIEWED - cite D-146, NEVER D-104.
FIX-196: not started, nothing parked; agent/packets/FIX-196.md stands. FIX-201 audit unreviewed (MAIN-1).
BRAND-3 (D-150, D-151): inventory agent/work/BRAND-3-scout.md. Step 0 first: the mapping's own baseline (agent/work/BRAND-3-base*). The clone
  of `subapavel/flottila-mapping` beside this checkout has branch `rail8-import` (cf970caf, all under mapping/), unpushed; redo it if gone.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; domains (ISSUE-7: `io.rail8` is a draft, publish nothing).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents; the reviewer runs out of turns on a big diff - ask it to hand back by turn 45.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - recommend leaving it); ISSUE-7 (namespace); ISSUE-2..5 answered.
next free ids: D-152 (kept for OPS-1), FIX-221 (FIX-220 kept for OPS-1), CONSIDER-24, F-38 and ISSUE-8 (kept for OPS-1), then F-54 (D-151).
