# Handoff

state: D-148's plan is complete. The owner's answers of 2026-11-15 are on main (D-156 to D-159). The repository is renamed
  `subapavel/rail8` (the remote here names the old one; GitHub redirects); `rail8.dev` is registered. last commit: ISSUE-12 (D-163).
WIP: FIX-275 [C] - red tests written, observed and approved (uncommitted in the tree; `agent/work/FIX-275-tests.patch`); the implementer
  was running when this was written (uncommitted `src/main`: redo from the packet if lost). F-57 is staged: three ITs red on today's code.
  Cancel is designed, not built: D-162 as D-163 (the owner's answers) amends it. Replay's core half is still in the code. `site` is red.
next exact action: FIX-275 - implementer's hand-back, then verifier (whole reactor, coverage, JaCoCo CSVs to `agent/work/FIX-264-before/`), reviewer [C].
  Order (agreed 2026-11-16; cancel's build at the end of 1): (1) FIX-275, 264 (`agent/work/FIX-264-scout.md`), 265, 268 [G], 269, 276, 277, CONSIDER-17, 278;
  (2) MAP-9 [C], MAP-1; (3) FIX-271 to 273, CONSIDER-28. Then FIX-274 [C], BRAND-7 [A], FIX-236, 237, 228, 230, 232, 233, 223, 205, 196, 255-259, 261, 279.
MAPPING: D-153 stands until MAP-1's design is accepted - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; only closes, fixes, observes. Licences: D-159 (one list).
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: applies agent/patches/BRAND-3-steering.patch and FIX-262-claude-replayable.patch (OPS-1's is applied) - never a session;
  reads ISSUE-8, ISSUE-10 (seven items), ISSUE-11 (2 to 6), ISSUE-9 rest, CONSIDER-27; sessions wanted: UI earlier, MAP-5. D-110 open.
OWNER ACTIONS, never a session's: GitHub's About text (the line chosen 2026-11-15, in BRAND-6's packet); Pages on and DNS for
  `rail8.dev` (after BRAND-6); archive `subapavel/flottila-mapping` (after MAP-3). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a
  time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents. The owner may push to `main`: fetch first.
  open issues: ISSUE-1, 6, 8, 9, 10, 11. next free ids: D-164, FIX-280, BRAND-9, OPS-13, CONSIDER-30, ISSUE-13, F-59, MAP-10.
