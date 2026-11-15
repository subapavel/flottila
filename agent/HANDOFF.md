# Handoff

state: MAIN AND THE BRANCH AGREE at 6261ba2. Owner 2026-11-15: everything goes to main even against the rules, so agent/work/*.md and
  *.diff are force-added past the ignore rule and the scratchpad design extracts are in agent/archive/branch-design/.
next exact action: fold FIX-195 in - the cancel-WARNING throttle the packet rules on (its last section), then reviewer, then commit.
  After it: dispatch FIX-196 (packet written, agent/packets/FIX-196.md). CONSIDER-15 stays blocked on the owner's three answers.
push gate 2026-11-15: 3213 tests, one failure - StepBodiesIT (spin-waits 20 s, lost at 20.10 s); the branch touches no runtime src, so it
  is main's own - owner: fix on main, do not measure further, FIX-212. The bench coverage drop is stale (bench was SKIPPED), unverified.
FIX-195 IS GREEN in lane 2 (/home/user/flottila-lane2, fix195, <scratchpad>/m2-lane2): core 756 tests 0 failures, BatchingWriter 49,
  mutation-checked. Uncommitted but SNAPSHOTTED to remote branch wip/fix195-snapshot (f546913), so a dead container loses nothing.
  Its decision is D-146 - NEVER D-104/D-144/D-145. D-146's row says "not yet in the tree"; drop that sentence in the landing commit.
ledger guard: its 9 shape problems are PRE-EXISTING (identical on main, FIX-197) and it is NOT in CI; its duplicate-id half found
  CONSIDER-11 naming two rows - the later is now CONSIDER-18. FIX-201 AUDIT DONE: 19 code/test commits, 9 CARRIED 10 LOST; 16 doc-only,
  9 audited, 1 CARRIED 8 LOST. Rows FIX-203..FIX-208, FIX-210, FIX-211; FIX-204 is 0adcbed, NOT FIX-136. Open: a reviewer pass on it.
OWNER, 3 OPEN (live, deliberately NOT handed to the advisor), all in agent/work/CONSIDER-15-designer.md section 5: scoping (phases vs
  all-at-once vs option C); N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS, all four in agent/NOTES.md "Build and VM": `-Dtest=` on flottila-runtime hits the heap-bounded -Xmx48m fork and OOMs, run the whole
  module; `python3 -I -m unittest ci.X` cannot import the ci package; two Maven reactors time out StepBodiesIT and ReplicaStartTest; dockerd
  dies silently - `nohup dockerd &`, never `service docker start`.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned; the key here is a 0-byte file - recommend leaving it); ISSUE-2..5 answered.
