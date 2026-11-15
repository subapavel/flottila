# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-4, OPS-1. In flight: BRAND-3. Then MAIN-1 - BACKLOG "priority 0".
last commit: BRAND-3 step 1 - the import merge: `mapping/` holds the mapping engine with its history (1155 commits), unchanged, not built yet.
in flight: BRAND-3 step 2. `ci/name-allowlist.txt` has `mapping/** | *` until step 2 narrows it; a new file with the old name needs an entry.
next exact action: BRAND-3 step 2 - implementer, agent/packets/BRAND-3.md "Step 2" + "Rulings after the review of step 1": rename inside
  `mapping/` with agent/tools/rail8_rename.py (base = the merge commit, full SHA), root reactor builds it; then the verifier: the mapping
  suite vs agent/work/BRAND-3-base-counts.txt, the platform vs BASE-1. Step 3: root CLAUDE.md is NOT edited (licences -> ISSUE-9 + a patch).
The mapping's old clone (`subapavel/flottila-mapping`, beside this checkout) is no longer needed; archiving the repository is the owner's.
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
