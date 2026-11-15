# Handoff

state: CONSOLIDATION ON MAIN (D-148). Done BRAND-1/3/4/5, OPS-1, FIX-149, 168, 183, 195, 201, 212-219, 221, 222, 224-227, 229, 231, 234, 254. Open: MAIN-1
last commit: FIX-254 - the flow-test runner's quiet sample also counts running carrier threads (D-155). Before it: its stage 1.
WIP: none. MAIN-1 has two conditions left, both a matter of running and reading, nothing to write under src/.
next exact action: MAIN-1's close - (1) whole-reactor run 3 of 3 on FIX-254's commit (the verifier; agent/work/MAIN-1-ci.md has
  the rule and runs 1, 2); (2) read the `ci` run of that commit - 225, 226, 227 were green; then MAIN-1 to its reviewer [B].
  After MAIN-1: FIX-235 [C], 236, 237, 228, 230, 232, 233, 223 (list), 205, 196, 255, 256, then the audit's rows FIX-238 to 253.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief), ISSUE-9 (mapping licences, rule scope), CONSIDER-25 (the branch's decisions of 2026-11-14, one against
  D-136); `git apply` agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; required checks for `mapping · …`.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-156, FIX-257, BRAND-6, OPS-13, CONSIDER-26, ISSUE-10, F-55, MAP-9.
