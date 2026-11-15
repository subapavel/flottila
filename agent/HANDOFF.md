# Handoff

state: branch MERGED with the core rework, pushed to the branch at 1fbd093 (on 5b5946d, on main's 542087b). NOT yet pushed to main.
uncommitted (see `git status`): HANDOFF, NOTES docker lesson, DECISIONS D-144, BACKLOG CONSIDER-17, packets/FIX-196.md, the designer note.
IN FLIGHT lane main: verifier, `clean verify -Pit` -> agent/work/push-gate3.log, then a runtime-only repeat, then coverage --check-baseline.
IN FLIGHT lane 2: test-author on FIX-195 reds in /home/user/flottila-lane2, branch fix195, -Dmaven.repo.local=<scratchpad>/m2-lane2.
gate run 2: 3208 tests, ONLY ReplicaStartTest:159 failed (FIX-194, a flake main already had); run 3 decides. bench needs -Pit or it fails.
next exact action: on the verifier's hand-back, if green or FIX-194 only -> push to main (`git push origin HEAD:main`), PROGRESS, reviewer.
FIX-195 (FIX-71's policy, lost by the rework): packet agent/packets/FIX-195.md, decision D-144 is WRITTEN in docs/DECISIONS.md.
  Do NOT cite D-104: on main that is a different decision, and that mis-citation is the F-27 defect class.
FIX-196 (FIX-59/60, lost): packet written, re-measured - 22 construction sites, 3 in src/main, 19 in tests. FIX-201 replays the other
  32 branch commits against main: all three losses surfaced only because one of my own tests failed, so the check must be per commit.
CONSIDER-15 (owner TOP PRIORITY): designer + advisor done, agent/work/CONSIDER-15-designer.md. Ids D-145<-D-102, D-146<-D-103,
  D-147<-D-105, D-148<-D-106, F-37<-F-26, F-38<-F-27. The design exists on this branch only (a0b3f74, 585b6f4, 94fceab).
OWNER, 3 OPEN, he is live and said "jeste nevim" at 00:30Z, so NOT handed to the advisor: (1) scoping, phases vs all at once vs
  option C (handled still leaves a visible record); (2) N5 retention of a HANDLED message; (3) N7 D-107 vs D-136/D-142. Reminder 02:05Z.
CONSIDER-17 new: the `dlq` name against the `park` verb, decide before /admin/dlq is public. Owner's question of 2026-11-15.
TRAP, paid for once: `-Dtest=` on flottila-runtime overrides the heap-bounded execution's `**/*HeapTest.java` includes and runs an
  18 MB-streaming class under -Xmx48m, which OOMs. An artefact of the command. Run the whole module. dockerd dies: see NOTES "Build and VM".
open issues: ISSUE-1 (Context7 quota); ISSUE-2..5 answered. Ledger guard: 9 malformed rows, all pre-existing on main (FIX-197).
