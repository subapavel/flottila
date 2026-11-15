# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, -3, -4, -5, OPS-1, FIX-212, -213, -215 (+184), -216, -217. In flight: MAIN-1.
last commit: FIX-216 - NodeBenchMainWarmupIT compares the journal delta in whole bytes; review PASS. Before it: BRAND-5 (dev.rail8).
WIP, UNCOMMITTED (FIX-214): FrameRelay.java + new FrameRelayTest.java (runtime tests, journal/peer); red seen; verifier (3 runs) + reviewer running; marker set.
next exact action: MAIN-1 - (1) FIX-214: verdicts, ledgers, commit; (2) FIX-221 next - it reddened `ci` run 209 (no packet yet:
  scout the variant "acknowledged before the handler answered" and the case aSendCarriesTheMessageIdToThePartner); then 219, 222,
  218, 223, 168; (3) two more green whole-reactor runs in a row and a green `ci` on the last commit. Worktree: scratchpad/main2.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min, 4580 tests); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1 (agent/work/MAIN-1-ci.md): packets agent/packets/FIX-214, 218, 219, 222. FIX-195 IN THE TREE, UNREVIEWED - cite D-146,
  NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed. PeerReadHeapTest:281 failed once (FIX-214-red.log): needs a row.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner, agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-224, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
