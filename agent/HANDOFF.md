# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done BRAND-1, -3, -4, -5, OPS-1, FIX-168, -212 to -219, -221, -222, -225 to -227, -229. Open: MAIN-1
last commit: FIX-227 - the second dir-force case holds its own persist's force (shared fixture OwnDirForce); review PASS. Before it: FIX-225.
WIP, NOT in the main tree: FIX-231 (test-author in a side worktree; packet agent/packets/FIX-231.md; its diff will be
  agent/work/FIX-231.diff) - then verifier, reviewer, apply to the main tree, commit.
next exact action: MAIN-1 - (1) finish FIX-231; FIX-224 (packet), 228, 230, 232 (no packets), 223 (list, first pass); (2) three green
  whole-reactor runs in a row - count 0: FIX-229's run had one IT error (row FIX-231) - and a green `ci` on the last commit;
  `ci` runs 214, 215 green, later ones and 212 not read; notes agent/work/MAIN-1-ci.md.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1: packets FIX-224, 231; CI notes agent/work/MAIN-1-ci.md. FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196, FIX-201 audit: open.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-233, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
