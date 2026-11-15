# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, -3, -4, -5, OPS-1, FIX-212 to -218, -221, -222 (+170, +184). In flight: MAIN-1.
last commit: FIX-218 - known answers for the node file's magic and the retention lock key; review PASS. Before it: FIX-222.
WIP, UNCOMMITTED: in the worktree scratchpad/main2 only - FIX-168 (test-author, class B; packet agent/packets/FIX-168.md).
next exact action: MAIN-1 - (1) FIX-168 hand-back, verifier (clean run, also on one CPU), reviewer (opus high), commit;
  (2) FIX-219 (ClickHouse harness, packet; worktree), then 225, 224, 223, 226 (no packets yet);
  (3) three green whole-reactor runs in a row (the count restarted at FIX-221's run) and a green `ci` on the last commit;
  read the `ci` runs of FIX-221 and later into agent/work/MAIN-1-ci.md (runs 209 and 211 were red on FIX-221 only).
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1: packets FIX-168, 219; CI notes agent/work/MAIN-1-ci.md. FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196, FIX-201 audit: open.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-227, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
