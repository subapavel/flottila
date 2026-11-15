# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-3, BRAND-4, OPS-1, FIX-217, FIX-213. In flight: MAIN-1.
last commit: BRAND-3 step 3 - mapping workflows at the root, its rule files trimmed, archives, F-54, D-153, ISSUE-9; review PASS (r2).
next exact action: MAIN-1 - (1) read `ci` run 203 (FIX-213's proof) and the first `mapping-ci` run (MAP-6) into agent/work/MAIN-1-ci.md;
  (2) FIX-212: test-author per agent/packets/FIX-212.md (class A), verifier, reviewer, commit. Then 215, 214, 216, 218, 219.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
  Rows MAP-1..8 (agent/BACKLOG.md); every MAP packet names D-153 and mapping/CLAUDE.md. Notes: agent/NOTES.md "Mapping engine".
Build, wrapper at the root. Mapping: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests). Platform:
  `mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1 (agent/work/MAIN-1-ci.md): packets agent/packets/FIX-212, 214, 215, 216, 218, 219. Order: 212, 215 (closes FIX-184), 214, 216,
  218, 219, then 221, 222, 168 (no packets yet); then three green reactor runs + a green `ci`. FIX-195 IN THE TREE, UNREVIEWED - cite
  D-146, NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed. A second worktree may hold a test fix: `git worktree list`.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); domains (ISSUE-7).
TRAPS: `-Dtest=` on the runtime module OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres
  and agents; reviewer: hand back by turn 25. open issues: ISSUE-1, 6, 7, 8, 9. next free ids: D-154, FIX-223, OPS-13, CONSIDER-24, ISSUE-10, F-55, MAP-9.
