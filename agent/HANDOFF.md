# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done BRAND-1/3/4/5, OPS-1, FIX-168, 183, 195, 212-219, 221, 222, 224-227, 229, 231, 234. Open: MAIN-1
last commit: FIX-234 - NodePoolWaitIT's fixture sends a deploy the pool refused again; a cause staged; PASS. Before it: FIX-183.
WIP: nothing uncommitted. FIX-201's audit was reviewed: FAIL in both halves (agent/work/FIX-201-audit-review.md holds both
  hand-backs: the reviewed verdict per commit, what must become true, what was not checked). No ledger correction made yet.
next exact action: MAIN-1's close - (1) FIX-201: the corrections and new rows the review names, the reviewed tables into
  agent/work/FIX-201-audit.md, a second reviewer pass on what was not checked; (2) FIX-149, the free-port race that failed `ci`
  run 220 (no packet yet); (3) three green whole-reactor runs in a row - count 0 - and a green `ci` on the last commit (222 on
  not read; agent/work/MAIN-1-ci.md). After MAIN-1: FIX-235 [C], 236, 237, 228, 230, 232, 233, 223 (list), 205, 196.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-238, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
