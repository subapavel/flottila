# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-4, OPS-1, FIX-217, FIX-213. In flight: BRAND-3 step 3.
last commit: FIX-213 - `ReplicaStartTest` waits for the pre-opened segment's file; review PASS. Before it: BRAND-3 step 2.
WIP, UNCOMMITTED IN THE TREE - BRAND-3 step 3. Implementer done (agent/work/BRAND-3-s3.diff): workflows ported to .github/workflows/
  mapping-*.yml, the mapping's rule files trimmed, archives under mapping/docs/archive/, NOTICE, platform commands with -f platform/pom.xml.
  Architect's part, the opponent's points accepted: issues/ISSUE-9.md, agent/patches/BRAND-3-steering.patch, docs/findings/F-54.md
  (major), D-153, ARCHITECTURE 12 + changelog 3.26, rows MAP-1..8, NOTES, README - installed from the session's scratchpad drafts.
next exact action: finish step 3 - verifier (mapping suite vs agent/work/BRAND-3-base-counts.txt, script tests, guards, check-lf on the
  staged tree), reviewer (class B), one commit, push; read the first runs of mapping-ci (MAP-6). Then MAIN-1: FIX-212.
Build, wrapper at the root. Mapping: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests). Platform:
  `mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OPS-1, OWNER: answer ISSUE-8 (every line has a default); `git apply agent/patches/OPS-1-steering.patch` - never a session.
MAIN-1 (agent/work/MAIN-1-ci.md): packets agent/packets/FIX-212, 214, 215, 216, 218, 219. Order: 212, 215 (closes FIX-184), 214, 216,
  218, 219, then 221, 222, 168 (no packets yet); then three green reactor runs + a green `ci`. FIX-195 IN THE TREE, UNREVIEWED - cite
  D-146, NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed. A second worktree may hold a test fix: `git worktree list`.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping`; domains (ISSUE-7: `io.rail8` is a draft).
TRAPS: `-Dtest=` on the runtime module OOMs; one Maven at a time (`flock /tmp/rail8-maven.lock`); a container restart kills dockerd, Postgres
  and agents; reviewer: hand back by turn 25. open issues: ISSUE-1, 6, 7, 8. next free ids: D-153, FIX-223, OPS-13, CONSIDER-24, ISSUE-9, F-54, MAP-1.
