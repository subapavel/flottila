# Handoff

state: MAIN IS AT fa1d68e - the branch was pushed to main on 2026-11-15, so main and claude/relaxed-archimedes-q2nb4c agree.
next exact action: fold FIX-195 in - the cancel-WARNING throttle the packet rules on (its last section), then reviewer, then commit.
  After it: dispatch FIX-196 (packet written, agent/packets/FIX-196.md). CONSIDER-15 stays blocked on the owner's three answers.
push gate 2026-11-15: 3213 tests, one failure - StepBodiesIT (spin-waits 20 s, lost at 20.10 s); the branch touches no runtime src, so
  it is main's own. Owner: fix it on main, do not measure further. FIX-212. The bench coverage drop is stale data (bench was SKIPPED).
FIX-195 IS GREEN in lane 2 (/home/user/flottila-lane2, branch fix195, <scratchpad>/m2-lane2), uncommitted: core 756 tests 0 failures,
  BatchingWriter 49, mutation-checked, diff at lane2 agent/work/FIX-195.diff. Its decision is D-146 - NEVER cite D-104, D-144 or
  D-145, each is a different decision on main. D-146's row says "not yet in the tree"; drop that sentence in the landing commit.
ledger guard: its 9 shape problems are PRE-EXISTING (identical on main, rowed as FIX-197); it is NOT wired into CI. Its new
  duplicate-id half already earned itself: CONSIDER-11 named two rows; the later one is now CONSIDER-18.
FIX-201 AUDIT DONE: 19 code/test commits, 9 CARRIED 10 LOST; 16 doc-only, 9 audited, 1 CARRIED 8 LOST. Rows FIX-203..FIX-208, FIX-210,
  FIX-211; FIX-204 is commit 0adcbed, NOT FIX-136 (the ha profile). Open: a reviewer pass on the audit. Owner questions: see below.
OWNER, 3 OPEN (live, deliberately NOT handed to the advisor), all in agent/work/CONSIDER-15-designer.md section 5: scoping (phases vs
  all-at-once vs option C); N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS, all four in agent/NOTES.md "Build and VM": `-Dtest=` on flottila-runtime hits the heap-bounded -Xmx48m fork and OOMs, run the whole
  module; `python3 -I -m unittest ci.X` cannot import the ci package; two Maven reactors at once time out StepBodiesIT and ReplicaStartTest;
  dockerd dies silently, restart with `nohup dockerd &`, never `service docker start`.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned; the key here is a 0-byte file - recommend leaving it); ISSUE-2..5 answered.
