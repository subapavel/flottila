# BRAND-3 step 3 — the opponent's points on F-54, D-153, §12, ISSUE-9 and the owner's patch (D-137)

The architecture-advisor's hand-back of 2026-11-15, shortened by the architect; the advisor writes no file. Verdict: SOUND WITH
CHANGES — the separation matches the tree; six blocking points (B), the rest notes (N). After each point: what the architect did.

## Weak points
- W1 [B] ISSUE-9 item 2 misstated the GPL fact: `jdk-classfile-backport`, both graalvm artifacts, `aopalliance` and
  `javax.annotation-api` are banned by the mapping's enforcer at compile, runtime and provided scope (`mapping/pom.xml:207-222`,
  `mapping/licence-filters.txt`, ADR-0017) — test scope only, not "in the service's tree outside test scope". The cited
  `.mvn/licence-filters.txt` does not exist. → item 2 rewritten from the POM; the path corrected.
- W2 [B] "bind `platform/` and `bench/`" is a positive list: `deploy/`, `ci/`, `agent/tools/`, `examples/`, a later `jq/` would be
  bound by nothing. → the scope is stated by exclusion: the whole repository except `mapping/`.
- W3 [B] The "working rules bind both" list differed in each text and read as exhaustive. → by exclusion: Tests, Working rules and
  Context hygiene bind both areas except the lines of Tests that name platform modules or tools; the existing `assumeTrue` cases stay,
  no new skipped case.
- W4 [B] The patch said "also follows `mapping/CLAUDE.md`", which leaves the root's design rules binding `mapping/` and pulls in the
  per-artifact exceptions by reference. → the patch paragraph rewritten: the Design rules do not apply under `mapping/` until MAP-1;
  the exceptions are ISSUE-9 item 2 and not ruled there. Until the owner applies it, D-153 is the rule: HANDOFF and the MAP section
  point to it.
- W5 [B for the rows, N for the guard] Nothing encodes the separation: OPS-4 and OPS-6 reach into the engine and depended on
  "OPS-2, BRAND-3" only; no guard fails when a platform pom names a mapping artifact. → OPS-4 and OPS-6 depend on MAP-1; row MAP-8
  (a failing check, red first); the sentence widened to "depends on, packages or starts".
- W6 [B, wording] D-153 stated three things the tree contradicts: the name guard and the DCO job do cover `mapping/`; only
  `mapping-ci.yml` runs on changes under `mapping/` (bench on request, nightly by cron); "built apart" held for CI only — root-level
  commands without `-f` build both. → D-153 corrected; the platform's documented commands gained `-f platform/pom.xml`
  (`bin/rail8`, the compose files, the quickstart, `docs/WORKFLOW.md` §1, `HANDOVER.md`); the agent definition's line is ISSUE-9 item 7.
- W7 [B, wording] D-153 read as a licence ruling in the architect's voice. → "left as imported — neither accepted nor removed by a
  session — for `mapping/` only, and frozen", as the default until ISSUE-9 A.

## Missed alternatives
- The engine's modules under the root licence list now, the wider list for service, UI and bench only: the closure MAP-1 pulls onto
  the platform's class path would already be clean; whether it passes today is unchecked. → named in ISSUE-9 item 3 as the owner's
  option and as MAP-1's first step.
- Two reactors (take `mapping` out of the root modules): makes "built apart" true for every command, gives up the one-command build.
  Worse than fixing the commands. → rejected in D-153.
- A rule-by-rule table in §12: most precise, drifts. → rejected. No §12 paragraph at all: sessions would have no rule. → rejected.

## Risks
- "Costs nothing to reverse" stops being true with every feature built under the mapping's rules. → the standstill in D-153: until
  MAP-1 the mapping closes, fixes and observes, and adds no dependency, licence exception, listener or endpoint.
- The scope is prose; only MAP-8 is executable. The mapping's licence check had not been cited as observed here. → ISSUE-9 item 2
  cites the build log; MAP-6 observes the workflows.
- F-54 lacked the template's Evidence, Change made, ARCHITECTURE edit and PROGRESS lines. → added. `mapping/CLAUDE.md:19` cited a
  bare `docs/WORKFLOW.md` §6. → full path.

## Open questions, with the advisor's answers
- Severity: major, not minor (shipped code contradicts five rules; the draft follows the major procedure). → F-54 is major.
- Marking: `draft — review at hand-over` for the scope with ISSUE-9 item 3; the licence lines are the owner's alone. → so marked.
- Licence default: defensible once W1 and W7 are fixed; nothing running has to stop; add "nothing built from `mapping/` is
  published, attached to a release or started from `deploy/` while ISSUE-9 A is open". → added to D-153 and ISSUE-9.
- Its recommendation to the owner on item 3: accept the default with the standstill; decide per sub-area at MAP-1.
