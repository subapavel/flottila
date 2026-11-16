# MAP-9 - the designer's draft: one set of test rules (2026-11-16)

Hand-back of the designer, as received; figures from `agent/work/MAP-9-numbers.md` unless marked. Not decided.
One rule throughout: a new floor is the higher of today's floor and the tier, so no floor goes down.

## 1. Proposal
- P1 The engine takes the 90/90 tier of `rail8-api` and `rail8-core`. xdm, ir, runtime, serializer, mapping,
  xslt-export, api: branch 80 to 90 (lowest today `engine/mapping` 98.92: no cost). structure: 80/none to 90/90
  (93.22/100: no cost, 3.22 points of line margin; its ratchet row is already 93). structure-import: 80/none to 90/90
  (99.71/100: no cost). udf-api, udf: 90/90, binding nothing today (`module-info.java` only). 75 % is not an option:
  it would lower today's floors.
- P1b The harness keeps its own floors until it goes (D-158): service keeps lines 80 and gets branches 75 (96.26: no
  cost); `engine/oracle` (no main code) and `bench/jmh` stay skipped. The UI: P6.
- P2 No decrease in `mapping/` is its ratchet, run in CI: `java scripts/ratchet.java check` after `verify` in
  `mapping-ci.yml`'s backend job (pass lists, Java coverage rows) and after PIT in `mapping-nightly.yml` (survivor
  rows); about 1 s each. `ci/coverage_check.py` does not change. Rule text: "No decrease on any push:
  `ci/coverage_check.py` for `platform/` (D-029 band), the ratchet for `mapping/` (ADR-0019)." Gap: the UI rows need
  the ui job's `coverage-summary.json` and a JDK; in the backend job they print NOT_RUN and pass.
- P3 Mutation: one threshold, 80 %, already equal in both areas (`platform/pom.xml`:70, `mapping/pom.xml`:69; both
  PIT 1.30.1 with the junit5 plugin 1.2.3), on `rail8-api`, `rail8-core` and the engine's seven class-C modules, plus
  udf-api and udf once they have code. The platform's PIT already runs nightly (`ci.yml`:199-214, cron `23 2`).
  The survivor counts stay an extra check of the mapping only. The testkit's 70 (D-042) is untouched.
- P4 The sentence that is true: "The mapping's behaviour is guaranteed by `engine/oracle`, run in every `verify`, and
  by its ratchet: 360 golden-corpus cases, each in four metamorphic variants, 148 of them with output observed on the
  owner's runs of SAP PO 7.5 and SAP Cloud Integration (15 observation sets), the rest documented, assumed or decided
  (ADR-0018); and 323 cases where the runtime agrees with Saxon-HE 12.5 on the exported XSLT. The build never runs PO
  or CPI. It compares with their recorded output and follows CPI where they disagree (F-43). A listed case may never
  fail, be skipped or go missing." The 148/212 and the 15 sets are the designer's own count (grep of `provenance` in
  `case.json`, a glob of `meta.json`); the sum matches `corpus.txt`'s 360. No build counted them.
- P5 Common to both areas: the Tests section's general lines, the floors, no decrease, PIT 80 %, "a number not in the
  area's `RESULTS.md` does not exist". Per area: the property-test tool (jqwik for the batcher, admission and the
  WorkQueue; the mapping's seeded JUnit generators, ADR-0018); the blocked-platform-thread line (the platform's; MAP-1
  decides for the embedded engine); the oracle and the ratchet (the mapping's); the two POSIX `assumeTrue` classes are
  the service's, stay, and leave with it. ISSUE-9 item 4 closes with a decision row that adopts this and narrows
  D-153's platform-only list to the jqwik parts and the blocked-thread line.
- P6 The UI now, at no cost: branches 90 on `src/model/**` and `src/studio/store/**` (100 % today; unverified that
  Vitest 5.0.5 takes `branches` per glob). No floor over all of `src/` (36.19 %, unit tests only; ADR-0019 rejects a
  global threshold). The rest waits for the editor's move (CONSIDER-26); no Rail8 UI package exists yet.
- P7 udf-api and udf get tests with their first code (ADR-0020); `failWhenNoMutations=false` stays until then.
  ADR-0019's `bytecode` row is planned (ADR-0021, MAP-5): keep it, marked "not built". ADR-0019:20 and :76 take
  `ui/src/studio/store`, as its own amendment says.
- P8 At once, in one row, after the owner's yes: `mapping/pom.xml`:67 to 0.90; the overrides in the structure and
  structure-import poms removed; the service pom to 0.80/0.75; the two branch thresholds in `vitest.config.ts`;
  ADR-0019's floor table and P7's text (an explicit ADR amendment); ADR-0017's CI table names the ratchet step; the
  two CI steps; one line in `mapping/CLAUDE.md` pointing to the root Tests section.
- P8b A patch of the root rule file for the owner to apply (lines 62-68): "Coverage floors (JaCoCo; Vitest v8 per glob
  in `mapping/ui`), line and branch: 90 % for `rail8-api`, `rail8-core` and every `mapping/engine` module with main
  code; 75 % elsewhere, Testcontainers tests counted, unless a module's own floor is higher. No decrease on any push:
  <P2>. Mutation score (PIT) >= 80 % on `rail8-api`, `rail8-core` and the engine's class-C modules, nightly. Floors
  are phase-gate conditions."; a new bullet <P4>; "Property tests: jqwik for the batcher, admission and WorkQueue
  ordering (`platform/`); seeded JUnit generators for the queue algebra (`mapping/`, ADR-0018)."; "A blocked platform
  thread in a test is a failure (`platform/`)."; the benchmark line names "the area's `RESULTS.md`".
- P8c Rows of their own: the UI rows checked in CI; a `check` mode that fails on NOT_RUN for reports CI must produce
  and compares with the previous commit's `ratchet/` (an ADR-0019 amendment); PIT measured on structure and
  structure-import.

## 2. Tests
`ratchet-ci-wiring-test.sh`: the backend job runs `ratchet.java check` after verify, the nightly after PIT.
`floors-tier-test.sh`: every effective `jacoco.min.*` in the mapping's poms is at or above the tier table. Scratch
runs, not committed: structure's branches below 0.90 fail `verify`; a deleted corpus case and one extra survivor fail
the CI step; a branch drop in `src/model` fails `npm run test`. Verifier: `-Pbench clean verify` exits 0 at the new
floors; `ci.test_coverage_check` stays green.

## 3. Rejected
`coverage_check.py` over `mapping/`, alone or with the ratchet: against D-153, and its band of 2 points is looser than
the ratchet (99.57 to 97.6 passes the band and fails row 99); it would probably flag udf-api, udf and `bench/jmh`
(unverified). The engine at 75 %: lowers floors. One no-decrease tool for both areas: loosens the mapping or drops
D-029's band. A UI floor over all of `src/` at 36 %: rules nothing and leaves with the UI. jqwik as the common tool:
overrules ADR-0018. A survivor ratchet for api and core now: a new file and script, no hidden survivor seen. PIT on
every push: ADR-0019 rejected it; the nine calls took about half an hour.

## 4. Expensive to reverse
Raised floors go one way: structure's 3.22 points bind every later change there; the tier sentence binds MAP-1. The
root rule text, the amendments of ADR-0019 and ADR-0017, the decision row that narrows D-153, `mapping-ci` as a
required check.

## 5. Conflicts
- The numbers file said the platform runs PIT by hand; `ci.yml`:199-214 runs it nightly (corrected in the file).
- Amending ADR-0019 and ADR-0017 while D-153 binds the mapping to its ADRs: an explicit item for the owner.
- The root rules name jqwik; ADR-0018 rejected jqwik from 1.10.1 on because its engine prints an instruction addressed
  to agents, and the platform pins 1.10.2 (`platform/pom.xml`:46). Outside MAP-9; named, not resolved.

## 6. Open - the owner's questions, the designer's default first
- Q1 90/90 for every engine module with code, the structure pair included? Yes (no cost). Or class-C only.
- Q2 Service branches 75 and the UI's two globs branches 90 before they go? Yes (no cost). Or untouched.
- Q3 The ratchet check in `mapping-ci` and the nightly now, the UI rows and the strict mode by a row, and `mapping-ci`
  a required check? Yes to both.
- Q4 A survivor-count ratchet for `rail8-api` and `rail8-core`? Not now; the 80 % stays.
- Q5 PIT on structure and structure-import? Measure in a row first.
- For the architect: under the root loop (D-151), who regenerates `ratchet/`? `mapping/docs/WORKFLOW.md` section 6
  still names the spec gate's step 4. Without a regeneration a rise never locks in and a new corpus case is not
  protected against deletion (MAP-7 or a new row).
