# Handoff

state: D-148's plan is complete. The owner's answers of 2026-11-15 are on main (D-156 to D-159). The repository is renamed
  `subapavel/rail8` (the remote here names the old one; GitHub redirects); `rail8.dev` is registered. last commit: FIX-260.
WIP: FIX-235 [C]: review PASS (round 3), uncommitted in this tree, commits next (packet, evidence: agent/work/FIX-235-*). Rows
  FIX-257..259 reserved for it (texts in agent/packets/FIX-235.md). The reactor ran once over both fixes. BRAND-6 [B] (the website,
  agent/packets/BRAND-6.md): red tests approved, implementer in worktree scratchpad/wsite; then reviewer, commit, the owner's steps.
next exact action: commit FIX-235, read `ci`. Then BRAND-6, FIX-262 [C] (replay out, D-156), CONSIDER-24 (the re-run window's
  cause), MAP-9 [C] (test rules: measure, propose), MAP-1 first step, BRAND-7 [A], FIX-236, 237, 228, 230, 232, 233, 223 (list), 205,
  196, 255, 256, 257-259, 261, then the audit's rows FIX-238 to 253. Whole reactor green before each commit (26 min).
MAPPING: D-153 stands until MAP-1's design is accepted - root Design rules do not bind mapping/; Tests, Working rules, Context
  hygiene do; nothing outside mapping/ depends on it; only closes, fixes, observes. Licences: D-159 (one list).
Build: mapping `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests); platform `mvn -B -ntp -f
  platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OWNER: applies agent/patches/BRAND-3-steering.patch (OPS-1's is applied, 25484e4f; guide given in session) - never a session;
  reads ISSUE-8; ISSUE-9 rest (items 5-7, the frozen exceptions); sessions wanted: UI earlier (CONSIDER-26), MAP-5. D-110 open.
OWNER ACTIONS, never a session's: GitHub's About text (the line chosen 2026-11-15, in BRAND-6's packet); Pages on and DNS for
  `rail8.dev` (after BRAND-6); archive `subapavel/flottila-mapping` (after MAP-3). TRAPS: `-Dtest=` on runtime OOMs; one Maven at a
  time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres, agents. The owner may push to `main`: fetch first.
  open issues: ISSUE-1, 6, 8, 9. next free ids: D-160, FIX-263, BRAND-8, OPS-13, CONSIDER-27, ISSUE-10, F-55, MAP-10.
