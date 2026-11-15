# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-3, BRAND-4, OPS-1, FIX-217, FIX-213. In flight: MAIN-1.
last commit: the owner's answer to ISSUE-7 recorded - the namespace is `dev.rail8` (D-154), row BRAND-5. Before it: BRAND-3 step 3.
WIP, UNCOMMITTED: FIX-212 here (agent/work/FIX-212.diff; review PASS, verifier owed); FIX-215 in the 2nd worktree (`git worktree list`), test-author at work.
next exact action: (1) commit FIX-212, then FIX-215 (apply its diff here when no writer is active); (2) BRAND-5: scout what carries
  `io.rail8` beyond packages and poms, packet, implementer extends agent/tools/rail8_rename.py, both suites, review; (3) the rest of
  MAIN-1: 214, 216, 218, 219, 222, 221, 168. Read `ci` run 203 and `mapping-ci` run 1 into agent/work/MAIN-1-ci.md (MAP-6).
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build, wrapper at the root. Mapping: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests). Platform:
  `mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1 (agent/work/MAIN-1-ci.md): packets agent/packets/FIX-212, 214, 215, 216, 218, 219; then three green reactor runs + a green
  `ci`. FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner, agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-223, BRAND-6, OPS-13, CONSIDER-24, ISSUE-10, F-55, MAP-9.
