# Handoff

state: CONSOLIDATION ON MAIN (D-148) is complete: BRAND-1/3/4/5, OPS-1, MAIN-1 done. `main` is green: 3 whole-reactor runs, `ci` 225-228.
last commit: MAIN-1 closed (documents only; agent/work/MAIN-1-evidence.txt). Before it: FIX-254, the flow-test clock (D-155).
WIP: none.
next exact action: FIX-235 [C] - BatchingWriter still lets two things out that D-146 says it contains; class C: designer, then
  the architecture-advisor, then the packet. After it: FIX-236, 237, 228, 230, 232, 233, 223 (list), 205, 196, 255, 256, then
  the audit's rows FIX-238 to 253. Each needs the whole reactor green before its commit (26 min; expected counts: PROGRESS).
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
