# Handoff

state: D-148's plan is complete. Owner answered 2026-11-15: D-156 no replay; D-157 handled = success; D-158 mapping joins the
  platform, its service was a harness; D-159 one licence list. last commit: those answers (documents only).
WIP: FIX-235 [C]: review PASS (round 3), FIX-260 [B]: review PASS - both uncommitted in this tree; the whole reactor over both is
  running (agent/work/FIX-235-260-*.log). Then two commits: FIX-260 first, then FIX-235. Rows FIX-257..261 reserved (scratchpad
  drafts; texts also in agent/packets/FIX-235.md and FIX-260.md). `ci` run 229 (efb9d4ae) RED: NodePoolWaitIT - FIX-260 answers it.
next exact action: commit FIX-260 and FIX-235 when the reactor is green, read `ci`. Then FIX-262 [C] (replay out, D-156), CONSIDER-24
  (the re-run window's cause), MAP-9 [C] (test rules: measure, propose), MAP-1 first step, FIX-236, 237, 228, 230, 232, 233, 223
  (list), 205, 196, 255, 256, 257-259, 261, then the audit's rows FIX-238 to 253. Whole reactor green before each commit (26 min).
MAPPING: D-153 stands until MAP-1's design is accepted - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; only closes, fixes, observes. Licences: D-159 (one list).
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: applies agent/patches/OPS-1-steering.patch, then BRAND-3-steering.patch (rewritten; guide given in session) - never a
  session; reads ISSUE-8; ISSUE-9 rest (items 5-7, two exceptions); sessions wanted: UI earlier (CONSIDER-26), MAP-5. D-110 open.
OWNER ACTIONS, never a session's: rename the GitHub repository (told: after FIX-260/235 are pushed; say when); archive
  `subapavel/flottila-mapping` (after MAP-3). `rail8.dev` is registered. TRAPS: `-Dtest=` on runtime OOMs; one Maven at a time
  (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents. The owner may push to `main`: fetch first.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-160, FIX-263, BRAND-6, OPS-13, CONSIDER-27, ISSUE-10, F-55, MAP-10.
