# Handoff

state: branch PUSHED at 2dacd53, which merges main's 4d4c62c. main itself NOT yet advanced. Uncommitted: see `git status`.
next exact action: run `clean verify -Pit` ALONE in this lane, then push main (`git push origin HEAD:main`) BEFORE folding FIX-195 in -
  the owner asked for that window closed, so two gate runs are accepted over one. Then reviewer + commit on FIX-195 and FIX-209.
FIX-195 IS GREEN in lane 2 (/home/user/flottila-lane2, branch fix195, <scratchpad>/m2-lane2), uncommitted: core 756 tests 0 failures,
  BatchingWriter 49, mutation-checked, diff at lane2 agent/work/FIX-195.diff. Owed: the cancel-WARNING throttle the packet now rules on.
  Its decision is D-146. NEVER cite D-104, D-144 or D-145 - each is a different decision on main.
FIX-209 duplicate-id guard: 26/26 green, shape output byte-identical, uncommitted. Two mutant pins in flight in ci/test_ledger_check.py.
  It found a live collision: CONSIDER-11 was the id of two different rows; the later (2026-11-11) is now CONSIDER-18, 4 references repointed.
  Third collision tonight. The cause is an allocation race between parallel sessions, which WORKFLOW 3 does not address.
FIX-201 AUDIT DONE: of 19 code/test commits, 9 CARRIED and 10 LOST - not the 3 the failing tests had shown, because a deleted test cannot
  fail. Rows FIX-203..FIX-208 each cite the commit to restore; the 10th is FIX-132, already rowed as FIX-197. NOTE FIX-204 is commit 0adcbed
  and is NOT FIX-136 (that is the ha profile). agent/work/FIX-201/ is gitignored, so git history is the only durable copy of those commits.
OWNER, 3 OPEN (he is live; deliberately NOT handed to the advisor): scoping, phases vs all-at-once vs option C; N5 retention of HANDLED;
  N7 D-107 vs D-136/D-142. All three are in agent/work/CONSIDER-15-designer.md section 5. Reminder fires 02:05Z.
TRAPS, all four in agent/NOTES.md "Build and VM": `-Dtest=` on flottila-runtime hits the heap-bounded -Xmx48m fork and OOMs, run the whole
  module; `python3 -I -m unittest ci.X` cannot import the ci package; two Maven reactors at once time out StepBodiesIT and ReplicaStartTest,
  which spin-wait 20 s; dockerd dies silently, restart with `nohup dockerd &`, never `service docker start`.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned; the key here is a 0-byte file - recommend leaving it); ISSUE-2..5 answered.
