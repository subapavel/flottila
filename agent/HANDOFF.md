# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, -3, -4, -5, OPS-1, FIX-212 to -217, -221 (+170, +184). In flight: MAIN-1.
last commit: FIX-221 - the contract case tells two deliveries by body, not order (the flake of `ci` runs 209, 211); review PASS.
WIP, UNCOMMITTED in the main tree: FIX-222 (ReplicaStartTest.java; review PASS, a verifier run owed) and FIX-218 (test-author writing).
next exact action: MAIN-1 - (1) FIX-218 hand-back, one verifier run for 222+218 (runtime module, RetentionIT), review 218, two commits
  (FIX-222's adds row FIX-225: the same pre-open race in JournalSpillBoundTest and LargeBodyCrashTest, agent/work/FIX-222-demo-old.log);
  (2) FIX-168 (third sighting, it broke FIX-221's reactor run): scout, packet; then FIX-219 (worktree scratchpad/main2), 224, 223;
  (3) three green whole-reactor runs in a row (the count restarted) and a green `ci` on the last commit.
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min, 4594 tests); one module: `-pl :rail8-runtime -am`.
OWNER: answer ISSUE-8 (brief) and ISSUE-9 (mapping licences, rule scope) - every item has a default; `git apply` the two patches
  agent/patches/OPS-1-steering.patch and BRAND-3-steering.patch - never a session; set required checks for the `mapping · …` jobs.
MAIN-1: packets FIX-218, 219, 222; CI notes agent/work/MAIN-1-ci.md. FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196, FIX-201 audit: open.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping` (after MAP-3); register `rail8.dev`.
  3 OPEN for the owner (agent/work/CONSIDER-15-designer.md section 5). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-155, FIX-225, BRAND-6, OPS-13, CONSIDER-25, ISSUE-10, F-55, MAP-9.
