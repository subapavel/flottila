# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BASE-1, BRAND-2, BRAND-1, BRAND-3, MAIN-1, OPS-1 - BACKLOG section "priority 0".
last commit: documents only - docs/OPERATIONS-BRIEF.md (D-147), the plan (D-148), rows OPS-1..11 and CONSIDER-19..23. No src change.
in flight: BASE-1, full `clean verify -Pit` on the a6b1201 tree; logs agent/work/BASE-1-verify.log and -fae.log. Result NOT read yet.
next exact action: read the BASE-1 handback, write its PROGRESS entry (reactor summary, red set), set BASE-1 done; then BRAND-2 (designer, D-149).
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
next free ids: D-149, FIX-213, CONSIDER-24, F-37, ISSUE-7.
