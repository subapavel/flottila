# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BASE-1, BRAND-2, BRAND-1, BRAND-3, MAIN-1, OPS-1 - BACKLOG section "priority 0".
last commit: BRAND-2 decided - D-149 (layout, coordinates, three commits A/B/C), D-150 (mapping import with history), ISSUE-7. No src change.
in flight: BASE-1 run 3 (whole reactor, failures ignored to reach every module). Runs 1-2: StepBodiesIT; PeerProtocolTest+ReplicaStartTest flaky.
next exact action: record BASE-1 run 3 (agent/work/BASE-1-verify-all.log, BASE-1-red-set.txt); then BRAND-1 commit A by agent/packets/BRAND-1.md.
The brief wins over docs/ARCHITECTURE.md until OPS-1 rewrites it. Its [inferred] items are confirmed with the owner before anything is built on them.
FIX-195 IS IN THE TREE BUT UNREVIEWED (BatchingWriter, D-146 policy). Its review belongs to MAIN-1. Cite D-146, NEVER D-104/D-144/D-145.
FIX-196: its only work is agent/work/FIX-196-partial-lane3.diff; agent/packets/FIX-196.md stands. FIX-212: StepBodiesIT spin-waits 20 s, main's own.
FIX-201 audit is done but unreviewed (MAIN-1). Ledger guard: 9 shape problems are PRE-EXISTING (FIX-197); it is not wired into CI (FIX-199).
Rename rules (D-148): old env keys and CLI name are dropped, not aliased; applied Flyway migrations untouched; history files keep the old name.
Mapping: subapavel/flottila-mapping, HEAD 1818a43 - Maven `engine` + `service`, npm `ui`. BRAND-3 imports it with history, after the rename.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS after BRAND-1, never a session's: rename the GitHub repository; domains.
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on flottila-runtime OOMs - run the whole module; never two Maven reactors at once;
  dockerd dies silently - start it with `sudo -n setsid nohup dockerd`; `python3 -I -m unittest ci.X` cannot import ci.
Journals: docs/PROGRESS.md (newest first) is the live one; the root PROGRESS.md ends 2026-11-03. OPS-1 makes them one.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - recommend leaving it); ISSUE-2..5 answered.
next free ids: D-151, FIX-213, CONSIDER-24, F-37, ISSUE-8. PRE-RENAME BASE for a parked diff: the commit before BRAND-1 A (see git log).
