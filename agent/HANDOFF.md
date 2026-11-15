# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Order: BRAND-1, BRAND-4 (done), BRAND-3, MAIN-1, OPS-1 - BACKLOG "priority 0".
last commit: BRAND-3 step 1 - the DCO job lists `--first-parent` (D-150 amended), D-151, agent/packets/BRAND-3.md; review PASS (r3).
in flight: BRAND-3 (clone of `subapavel/flottila-mapping` beside this checkout, branch `rail8-import` cf970caf, unpushed). A new file that
  holds the old name needs an entry in ci/name-allowlist.txt (the guard is in CI).
next exact action: the import merge ON `main` - packet step 1.2-1.3: `git merge --allow-unrelated-histories --no-commit FETCH_HEAD`, add
  the `mapping/** | * | ...` allow-list line, hand-written message; before the push `git rev-list --first-parent <before>..HEAD` = signed only.
  Then step 2 (implementer; rulings in the packet), step 3 (root CLAUDE.md is NOT edited: licences -> an issue + a patch for the owner).
OPS-1 IS REVIEWED (part 1 PASS r2, part 2 PASS r2) AND UNCOMMITTED: agent/work/OPS-1.diff (base b702b49) - commit it next: D-152, OPS-12, FIX-220.
Verify mode: `mvn -B -ntp clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); compare with agent/work/BASE-1-counts.txt.
MAIN-1: CI is red on main since 2026-11-02 - FIX-217 (packet agent/packets/FIX-217.md; test-author only) stops it in testkit.
  Then FIX-212..216, 218, 219 (agent/work/MAIN-1-scout.md, LedgerIT first). FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104.
  FIX-196 not started (its packet stands); FIX-201 audit unreviewed.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; domains (ISSUE-7: `io.rail8` is a draft, publish nothing).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents; the reviewer runs out of turns on a big diff - ask it to hand back by turn 40.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - recommend leaving it); ISSUE-7 (namespace); ISSUE-2..5 answered.
next free ids: D-152 (OPS-1's), FIX-221 (FIX-220 is OPS-1's), CONSIDER-24, F-38 and ISSUE-8 (OPS-1's), then F-54, ISSUE-9 (licences, BRAND-3 step 3).
