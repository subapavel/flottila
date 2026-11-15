# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done BRAND-1/3/4/5, OPS-1, FIX-168, 195, 212-219, 221, 222, 224-227, 229, 231. Open: MAIN-1
last commit: FIX-195 - the late review's four findings fixed (two assertions, one count package-private, one comment); PASS. Before: FIX-224.
WIP, UNCOMMITTED in the main tree: FIX-183 (PeerServer serves a link's READs in arrival order; packet agent/packets/FIX-183.md; red
  tests in PeerProtocolTest approved, red 20 of 20; the implementer at work) - then verifier, reviewer, commit.
next exact action: MAIN-1's close - (1) FIX-183 (above), then FIX-234 (packet agent/packets/FIX-234.md: staged red, the fixture
  sends a deploy refused by the pool again) - the faults that failed `ci` 218 and 219; (2) a reviewer pass on the FIX-201 audit
  (claims per commit: agent/work/FIX-201-audit.md); (3) then three green whole-reactor runs in a row - count 0 - and a green `ci`.
  After MAIN-1: FIX-235 [C], 228, 230, 232, 233 (no packets), 223 (list, first pass), 205, 196.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-236, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
