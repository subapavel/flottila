# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done BRAND-1/3/4/5, OPS-1, FIX-168, 183, 195, 212-219, 221, 222, 224-227, 229, 231. Open: MAIN-1
last commit: FIX-183 - PeerServer serves a link's READs in arrival order (one FIFO per link); PASS. Before it: FIX-195's late review.
WIP, UNCOMMITTED in a second worktree (scratchpad, not durable): FIX-234 (NodePoolWaitIT's fixture; packet agent/packets/FIX-234.md;
  the test-author at work) - redo from the packet if it is lost. Then verifier, reviewer, commit.
next exact action: MAIN-1's close - (1) FIX-234 (above), the second fault that failed `ci` (run 219; 218 was FIX-183, fixed); (2) a
  reviewer pass on the FIX-201 audit (claims per commit: agent/work/FIX-201-audit.md); (3) then three green whole-reactor runs in a
  row - count 0 - and a green `ci` on the last commit (runs 220 on not read; notes agent/work/MAIN-1-ci.md).
  After MAIN-1: FIX-235 [C], 236, 228, 230, 232, 233 (no packets), 223 (list, first pass), 205, 196.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-237, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
