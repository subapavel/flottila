# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BRAND-1 (A done; B, C), BRAND-4, BRAND-3, MAIN-1, OPS-1 - BACKLOG "priority 0".
last commit: BRAND-1 A - modules under platform/, bench module in bench/, root pom = aggregator `flottila-all`; counts = BASE-1; review PASS r3.
in flight: BRAND-1 B (identifiers), prepared but NOT verified. Parked: agent/work/BRAND-1-B-tool.patch (the tool) and BRAND-1-B-hand.patch.
next exact action: `git apply agent/work/BRAND-1-B-tool.patch`; `python3 agent/tools/rail8_rename.py identifiers`; `git apply` the hand patch;
  reword "Rail8 is a working name" (README, ARCHITECTURE x2); verify the whole reactor against BASE-1 counts; review; commit. Then C, BRAND-4.
B rulings: wire literals stay for C (BRAND-1-B-report.txt = what must remain); thread/temp prefixes and schema titles are B; `$id`, JFR name are C.
C: wire list, golden values and rulings in agent/work/BRAND-1-scout.md; expectations recomputed outside the code; migrations byte-identical.
Verify mode: `mvn -B -ntp clean verify -Pit -Dmaven.test.failure.ignore=true` (27 min); compare agent/work/BASE-1-counts.txt and -red-set.txt.
The brief wins over docs/ARCHITECTURE.md until OPS-1 rewrites it (map: agent/work/OPS-1-scout.md). Its [inferred] items go to the owner first.
MAIN-1: facts on the red tests in agent/work/MAIN-1-scout.md (LedgerIT first). FIX-195 IS IN THE TREE BUT UNREVIEWED - cite D-146, NEVER D-104.
FIX-196: not started, nothing parked (the diff an earlier handoff named never existed); agent/packets/FIX-196.md stands. FIX-201 audit unreviewed.
Mapping: clone has branch `rail8-import` (cf970caf, all under mapping/), unpushed; if the clone is gone redo the move commit. BRAND-3 per D-150.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS after BRAND-1, never a session's: rename the GitHub repository; domains (ISSUE-7: `io.rail8` is a draft, publish nothing).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents - restart both with `sudo -n setsid ...`; ledger guard: 9 shape problems are PRE-EXISTING (FIX-197).
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - recommend leaving it); ISSUE-7 (namespace); ISSUE-2..5 answered.
next free ids: D-151, FIX-217, CONSIDER-24, F-38, ISSUE-8. PRE-RENAME BASE for a parked diff: 3e78044 (`rail8_rename.py translate <file>`).
