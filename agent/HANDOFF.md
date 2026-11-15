# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-4, OPS-1. In flight: BRAND-3. Then MAIN-1 - BACKLOG "priority 0".
last commit: OPS-1 - ARCHITECTURE 3.25 and the living documents follow docs/OPERATIONS-BRIEF.md; D-152, F-38, ISSUE-8; review PASS.
in flight: BRAND-3 (clone of `subapavel/flottila-mapping` beside this checkout, branch `rail8-import` cf970caf, unpushed). A new file that
  holds the old name needs an entry in ci/name-allowlist.txt (the guard is in CI).
next exact action: the import merge ON `main` - packet step 1.2-1.3: `git merge --allow-unrelated-histories --no-commit FETCH_HEAD`, add
  the `mapping/** | * | ...` allow-list line, hand-written message; before the push `git rev-list --first-parent <before>..HEAD` = signed only.
  Then step 2 (implementer; rulings in the packet), step 3 (root CLAUDE.md is NOT edited: licences -> an issue + a patch for the owner).
OPS-1, OWNER: answer ISSUE-8 (every line has a default); `git apply agent/patches/OPS-1-steering.patch` - never a session. After BRAND-3: scout mapping/ docs.
Verify mode: `mvn -B -ntp clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); compare with agent/work/BASE-1-counts.txt.
MAIN-1: CI is red on main since 2026-11-02 - FIX-217 (packet agent/packets/FIX-217.md; test-author only) stops it in testkit.
  Then FIX-212..216, 218, 219 (agent/work/MAIN-1-scout.md, LedgerIT first). FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104.
  FIX-196 not started (its packet stands); FIX-201 audit unreviewed.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; domains (ISSUE-7: `io.rail8` is a draft, publish nothing).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents; the reviewer runs out of turns on a big diff - ask it to hand back by turn 40.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - leave it); ISSUE-7 (namespace); ISSUE-8 (the brief's open items); ISSUE-2..5 answered.
next free ids: D-153, FIX-221, OPS-13, CONSIDER-24, ISSUE-9 (licences, BRAND-3 step 3), F-54 (F-39..F-53 stay free: the mapping has them, D-151).
