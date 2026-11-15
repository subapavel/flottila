# Handoff

state: CONSOLIDATION ON MAIN (D-148). Done BRAND-1/3/4/5, OPS-1, FIX-149, 168, 183, 195, 201, 212-219, 221, 222, 224-227, 229, 231, 234. Open: MAIN-1
last commit: FIX-149 - test ports come from `dev.rail8.testkit.Ports`, outside the kernel's ephemeral range. Before it: FIX-201.
WIP: FIX-254 (the flow time that failed `ci` run 224) - stage 1 of agent/packets/FIX-254.md, a measurement, started 18:34Z in the
  scratch worktree: agent/work/FIX-254-probe/probe.sh runs the demo under CPU pins and load; nothing under src/ changed.
next exact action: MAIN-1's close - (1) FIX-254: read the measurement, show the cause, then the remedy; (2) whole-reactor runs
  green in a row: 1 of 3 (the rule and the runs are in agent/work/MAIN-1-ci.md); (3) a green `ci` on the last commit - 225 was.
  After MAIN-1: FIX-235 [C], 236, 237, 228, 230, 232, 233, 223 (list), 205, 196, 255, then the audit's rows FIX-238 to FIX-253.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief), ISSUE-9 (mapping licences, rule scope), CONSIDER-25 (the branch's decisions of 2026-11-14, one against
  D-136); `git apply` agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; required checks for `mapping · …`.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-256, BRAND-6, OPS-13, CONSIDER-26, ISSUE-10, F-55, MAP-9.
