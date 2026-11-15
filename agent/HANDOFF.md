# Handoff

state: CONSOLIDATION ON MAIN (owner 2026-11-15, D-148). Done: BRAND-1, BRAND-4, OPS-1. In flight: BRAND-3 (step 3 left). Then MAIN-1.
last commit: BRAND-3 step 2 - `mapping/` carries the Rail8 names and is in the root reactor; review PASS (r2). Before it: FIX-217.
next exact action: BRAND-3 step 3 - implementer, agent/packets/BRAND-3.md "Step 3" + its three "Rulings" blocks: workflows ported, the
  mapping's CLAUDE.md and docs/WORKFLOW.md trimmed, archives by `git mv` under mapping/docs/archive/, NOTICE merged, README note,
  allow-list, bench/run.sh:79. Architect: ISSUE-9 + agent/patches/BRAND-3-steering.patch (drafts: agent/work/BRAND-3-ISSUE-9-draft.md,
  BRAND-3-steering.patch.draft), D-153 (root design rules bind platform/), ARCHITECTURE 12, MAP rows (agent/work/BRAND-3-scout-2.md D).
Build, wrapper at the root. Mapping: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` (2.5 min, 17 822 tests). Platform:
  `mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true` (26 min); one module: `-pl :rail8-runtime -am`.
OPS-1, OWNER: answer ISSUE-8 (every line has a default); `git apply agent/patches/OPS-1-steering.patch` - never a session.
MAIN-1: `ci` run 201 (agent/work/MAIN-1-ci.md): testkit passes; runtime stops on FIX-213 (unit), then FIX-212 (IT). Packets written:
  agent/packets/FIX-212, 213, 214, 215, 216, 218, 219. Order: 213, 212, 215 (closes FIX-184), 214, 216, 218, 219; then three green
  reactor runs + a green `ci`. FIX-195 IN THE TREE, UNREVIEWED - cite D-146, NEVER D-104. FIX-196 not started; FIX-201 audit unreviewed.
OWNER, 3 OPEN (not for the advisor), agent/work/CONSIDER-15-designer.md section 5: scoping; N5 retention of HANDLED; N7 D-107 vs D-136/D-142.
OWNER ACTIONS, never a session's: rename the GitHub repository; archive `subapavel/flottila-mapping`; domains (ISSUE-7: `io.rail8` is a draft).
TRAPS (agent/NOTES.md "Build and VM"): `-Dtest=` on the runtime module OOMs; never two Maven reactors at once; a container restart kills
  dockerd, Postgres and background agents; the reviewer runs out of turns on a big diff - ask it to hand back by turn 40.
open issues: ISSUE-1 (Context7 quota); ISSUE-6 (commits unsigned - leave it); ISSUE-7 (namespace); ISSUE-8 (the brief's open items).
next free ids: D-153, FIX-221, OPS-13, CONSIDER-24, ISSUE-9 (licences and rules, step 3), F-54 (F-39..F-53: the mapping's, D-151), MAP-1.
