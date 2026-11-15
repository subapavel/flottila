# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, -3, -4, OPS-1, FIX-212, -213, -215 (+184), -217. In flight: MAIN-1, BRAND-5.
last commit: FIX-215 - LedgerIT's takeover check allows a re-run and bounds it (closes FIX-184); review PASS. Before it: FIX-212.
WIP, UNCOMMITTED (BRAND-5): agent/packets/BRAND-5.md; red guard tests in ci/test_name_guard.py (approved); docs/ARCHITECTURE.md 3.27. Next: implementer.
next exact action: (1) BRAND-5: implementer, then the verifier (platform reactor -Pit vs agent/work/BASE-1-counts.txt, mapping
  17 822), reviewer, one commit; then the pending packets' paths (FIX-214, 216, 218, 219, 222) follow the namespace by hand;
  (2) MAIN-1: 214, 216, 218, 219, 222, 223, 221, 168. Read the next `ci` run into agent/work/MAIN-1-ci.md.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build, wrapper at the root. Mapping: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests). Platform:
  `mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1 (agent/work/MAIN-1-ci.md): packets agent/packets/FIX-214, 216, 218, 219, 222; then three green reactor runs + a green `ci`.
  FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner, agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-224, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
