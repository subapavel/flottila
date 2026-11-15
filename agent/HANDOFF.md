# Handoff

state: CONSOLIDATION ON MAIN (D-148) is complete. Owner answered 2026-11-15: D-156 (no replay), D-157 (handled = success, log only).
last commit: the owner's answers recorded (documents only). Before it: MAIN-1 closed; FIX-254, the flow-test clock (D-155).
WIP: FIX-235 [C]: green, PIT ok; review round 1 FAIL (2 doc claims) - fix packet at the packet's end: tests done, Javadoc, re-review.
  `ci` run 229 (efb9d4ae) RED: NodePoolWaitIT, one IT, 18.6 only (agent/work/MAIN-1-ci.md). FIX-260 [B]: fixture-only retry of a
  refused start, diff applied to this tree, mutants 5 of 6 killed, with its reviewer. Rows FIX-257..261: reserved, not yet written.
next exact action: finish FIX-260 and FIX-235 (one whole-reactor run over both, then two commits), read `ci`. Then FIX-262 [C] (replay
  out, D-156), CONSIDER-24 (the re-run window's cause), FIX-236, 237, 228, 230, 232, 233, 223 (list), 205, 196, 255, 256, 257-259,
  261, then the audit's rows FIX-238 to 253. Each needs the whole reactor green before its commit (26 min; counts: PROGRESS).
MAPPING: D-153 is the rule until the owner applies the patch - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; the mapping only closes, fixes, observes until MAP-1; licence list frozen.
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: reads ISSUE-8; ISSUE-9 - two yes/no asked (4 licences repo-wide; GPL+CPE/CDDL/UPL test and bench only), then the BRAND-3 patch
  is rewritten; applies agent/patches/OPS-1-steering.patch himself now (guide given) - never a session. D-110 (branch) unanswered.
OWNER ACTIONS, never a session's: rename the GitHub repository (told: after FIX-260/235 are pushed; say when); archive
  `subapavel/flottila-mapping` (after MAP-3). `rail8.dev` is registered. TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents. The owner may push to `main`: fetch first.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-158, FIX-263, BRAND-6, OPS-13, CONSIDER-26, ISSUE-10, F-55, MAP-9.
