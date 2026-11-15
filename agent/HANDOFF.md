# Handoff

state: MAIN AND THE BRANCH AGREE. Owner 2026-11-15, twice: everything goes to main even against the rules, then "zadne testy, kasli na
  to, poradek si udelame na main". So this tree holds work that did NOT pass the normal gate - read the next two lines before trusting it.
FIX-195 IS IN THE TREE BUT UNREVIEWED: BatchingWriter's D-146 policy, three widened catch sites, contained cancels, and the refused-cancel
  episode throttle with ONE FLAG PER SITE. The implementer reported the module green and 7/7 mutants killed; the architect ran no build of
  its own and no reviewer saw it. BACKLOG says `landed, unreviewed`. Cite D-146, NEVER D-104/D-144/D-145 (different decisions on main).
FIX-196: agent/work/FIX-196-partial-lane3.diff holds its only work; agent/packets/FIX-196.md is complete and still stands.
next exact action: settle what "poradek na main" means - at minimum a full `clean verify -Pit` (none has run since FIX-195 landed) and a
  reviewer pass on FIX-195 and on the FIX-201 audit. FIX-196 NOT STARTED past one partial test file, parked at the diff named above.
push gate 2026-11-15, BEFORE FIX-195 landed: 3213 tests, one failure - StepBodiesIT, which spin-waits 20 s and lost at 20.10 s. The branch
  touched no runtime src, so it is main's own; owner: fix on main, do not measure further. FIX-212. The bench coverage drop is stale data.
durability, done: agent/work/*.md and *.diff force-added past the ignore rule; scratchpad design extracts in agent/archive/branch-design/;
  FIX-195's two intermediate states on remote branch wip/fix195-snapshot (f546913, 65403e1). Both lanes (lane2 fix195, lane3) are CLEAN.
FIX-201 AUDIT DONE: 19 code/test commits 9 CARRIED 10 LOST, 16 doc-only 9 audited 1 CARRIED 8 LOST; rows FIX-203..FIX-208, FIX-210..212;
  FIX-204 is 0adcbed, NOT FIX-136. Ledger guard: its 9 shape problems are PRE-EXISTING (FIX-197) and it is NOT wired into CI.
OWNER, 3 OPEN (live, NOT for the advisor), all in agent/work/CONSIDER-15-designer.md section 5: scoping (phases vs all-at-once vs option
  C); N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS, all four in agent/NOTES.md "Build and VM": `-Dtest=` on flottila-runtime hits the heap-bounded -Xmx48m fork and OOMs, run the whole
  module; `python3 -I -m unittest ci.X` cannot import ci; two Maven reactors time out StepBodiesIT/ReplicaStartTest; dockerd dies silently.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned; the key here is a 0-byte file - recommend leaving it); ISSUE-2..5 answered.
