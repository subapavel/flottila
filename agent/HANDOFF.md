# Handoff

state: D-148's plan is complete. The owner's answers of 2026-11-15 are on main (D-156 to D-159). The repository is renamed
  `subapavel/rail8` (the remote here names the old one; GitHub redirects); `rail8.dev` is registered. last commit: FIX-266.
WIP: CONSIDER-24 [C] (re-delivery at a node's death; packet agent/packets/CONSIDER-24.md; agent/work/CONSIDER-24-*): step 1 done -
  8 green cases in the worktree <scratchpad>/w24 and in agent/work/CONSIDER-24.diff; scout 3 and the designer's draft are in;
  step 2 (the gap measured, `bench/sql/settle-gap.sql`) is with the implementer. Left: verifier's runs, advisor, review, reactor.
next exact action: THE OWNER (2026-11-16): finish what is in flight, start NO new row, then agree the next steps with the owner.
  So: CONSIDER-24 to the owner's decision, then stop. Queue for after that talk: FIX-263 [B] (the per-case test list first),
  FIX-267 [C], FIX-264, 265, 268, MAP-9 [C], MAP-1, BRAND-7 [A], FIX-236, 237, 228, 230, 232, 233, 223, 205, 196, 255-259, 261, 269.
MAPPING: D-153 stands until MAP-1's design is accepted - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; only closes, fixes, observes. Licences: D-159 (one list).
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: applies agent/patches/BRAND-3-steering.patch and FIX-262-claude-replayable.patch (OPS-1's is applied) - never a session;
  reads ISSUE-8, ISSUE-10 (seven items after "no replay"), ISSUE-9 rest; sessions wanted: UI earlier (CONSIDER-26), MAP-5. D-110 open.
OWNER ACTIONS, never a session's: GitHub's About text (the line chosen 2026-11-15, in BRAND-6's packet); Pages on and DNS for
  `rail8.dev` (after BRAND-6); archive `subapavel/flottila-mapping` (after MAP-3). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a
  time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents. The owner may push to `main`: fetch first.
  open issues: ISSUE-1, 6, 8, 9, 10. next free ids: D-160, FIX-270, BRAND-9, OPS-13, CONSIDER-27, ISSUE-11, F-56, MAP-10.
