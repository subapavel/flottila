# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BRAND-1 (A, B done; C), BRAND-4, BRAND-3, MAIN-1, OPS-1 - BACKLOG "priority 0".
last commit: BRAND-1 B - identifiers are Rail8 (`io.rail8`, `rail8-*`, `RAIL8_*`, `bin/rail8`); test counts = BASE-1; review PASS.
in flight: BRAND-1 C (persisted and wire names) at the implementer; nothing of C is committed. Work list: agent/work/BRAND-1-B-report.txt.
next exact action: implementer per agent/packets/BRAND-1.md "Commit C" + agent/work/BRAND-1-scout.md (lists 1-2, rulings at its end); then the
  verifier's whole reactor against agent/work/BASE-1-counts.txt, review, commit. After C: BRAND-4 (agent/packets/BRAND-4.md).
Verify mode: `mvn -B -ntp clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); move ~/.m2/repository/io/flottila away first.
The brief wins over docs/ARCHITECTURE.md until OPS-1 rewrites it (map: agent/work/OPS-1-scout.md). Its [inferred] items go to the owner first.
MAIN-1: CI is red on main since 2026-11-02 - FIX-217 (SamplePipelineTest 100 ms wall-clock bound) stops it in testkit: agent/work/MAIN-1-ci.md.
  Then FIX-212..216, facts in agent/work/MAIN-1-scout.md (LedgerIT first). FIX-195 IS IN THE TREE BUT UNREVIEWED - cite D-146, NEVER D-104.
FIX-196: not started, nothing parked; agent/packets/FIX-196.md stands. FIX-201 audit unreviewed (MAIN-1).
BRAND-3 (D-150): inventory agent/work/BRAND-3-scout.md - the mapping has wire names too (`urn:flottila:*` in contract, XSLT, 175 corpus files).
  The clone /home/user/subapavel/flottila-mapping has branch `rail8-import` (cf970caf, all under mapping/), unpushed; redo it if the clone is gone.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS after BRAND-1, never a session's: rename the GitHub repository; domains (ISSUE-7: `io.rail8` is a draft, publish nothing).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents; the reviewer runs out of turns on a big diff - ask it to hand back by turn 45.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - recommend leaving it); ISSUE-7 (namespace); ISSUE-2..5 answered.
next free ids: D-151, FIX-218, CONSIDER-24, F-38, ISSUE-8. PRE-RENAME BASE for a parked diff: 3e78044 (`rail8_rename.py translate <file>`).
