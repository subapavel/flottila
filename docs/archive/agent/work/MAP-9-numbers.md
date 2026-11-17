# MAP-9 - the mapping's tests, measured (2026-11-16)

Step 1 of MAP-9: what the mapping area's tests reach today, beside the platform's floors. Numbers observed by the
verifier; how the mapping checks them is `agent/work/MAP-9-scout.md`. No rule is changed by this file.

- Commit f1be875c (the tree also held FIX-275's uncommitted change under `platform/`; nothing under `mapping/`).
- Machine: 4 cores, 15 GB, Linux; Java 25.0.5.
- Coverage: `./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify` - exit 0, 3:04 min (`agent/work/MAP-9-verify.log`);
  JaCoCo's per-module CSV summed (`agent/work/MAP-9-jacoco.tsv`).
- Mutation: one call per module, the mapping's own way - `../mvnw -B -ntp -pl <module> -am test-compile
  org.pitest:pitest-maven:mutationCoverage` (`agent/work/MAP-9-pit-<name>.log`, `agent/work/MAP-9-pit.tsv`).
- UI: `npm run test` in `mapping/ui` (`vitest run --coverage`) - exit 0, 674 tests (`agent/work/MAP-9-ui.tsv`).
- `git status --short mapping/` was empty before and after.

## The platform's floors (root `CLAUDE.md`, Tests)

Line and branch: `rail8-api` and `rail8-core` 90 %, every other module 75 %; no decrease on any push. Mutation (PIT):
`api` and `core` at least 80 %.

## The engine and the service

| Module | Tests | Lines | Line % | Branches | Branch % | Its floor today (line / branch) | Mutation: score, survivors / generated | Its mutation floor today |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| engine/xdm | 11 151 | 743 | 100.00 | 432 | 100.00 | 90 / 80 | 100 %, 0 / 509 | 80 %; survivors 0 |
| engine/ir | 91 | 181 | 100.00 | 79 | 100.00 | 90 / 80 | 100 %, 0 / 66 | 80 %; survivors 0 |
| engine/runtime | 717 | 1 142 | 100.00 | 452 | 100.00 | 90 / 80 | 99 %, 5 / 597 | 80 %; survivors 5 |
| engine/serializer | 19 | 117 | 100.00 | 58 | 100.00 | 90 / 80 | 100 %, 0 / 103 | 80 %; survivors 0 |
| engine/mapping | 563 | 1 162 | 99.57 | 461 | 98.92 | 90 / 80 | 98 %, 11 / 525 | 80 %; survivors 11 |
| engine/xslt-export | 53 | 194 | 98.97 | 88 | 100.00 | 90 / 80 | 100 %, 0 / 95 | 80 %; survivors 0 |
| engine/api | 409 | 276 | 99.28 | 83 | 100.00 | 90 / 80 | 100 %, 0 / 109 | 80 %; survivors 0 |
| engine/structure | 412 | 118 | 93.22 | 33 | 100.00 | 80 / none | not run (skipped by its pom) | none |
| engine/structure-import | 211 | 1 727 | 99.71 | 891 | 100.00 | 80 / none | not run (skipped by its pom) | none |
| engine/udf-api | 0 | - | - | - | - | 90 / 80 | none: no tests or no production code | 80 %, not enforced |
| engine/udf | 0 | - | - | - | - | 90 / 80 | none: no tests or no production code | 80 %, not enforced |
| engine/oracle | 2 910 | - | - | - | - | skipped | not run (skipped by its pom) | none |
| service | 1 257 | 416 | 98.56 | 107 | 96.26 | 80 / none | not run (skipped by its pom) | none |
| bench/jmh | 29 | - | - | - | - | skipped | not run (skipped by its pom) | none |

Reading it beside the platform's floors:
- Every measured Maven module is above the platform's strictest floor (90 % line and branch): the lowest line figure
  is `engine/structure` at 93.22 %, the lowest branch figure `service` at 96.26 %.
- Every module that runs mutation testing is above the platform's 80 %: the lowest is `engine/mapping` at 98 %.
- Mutation score is PIT's own: killed plus timed out, over generated. Timed out: xdm 11, runtime 5, mapping 1, api 1.
  No mutant without coverage in any module.
- Today's survivor counts equal the committed rows of `mapping/ratchet/mutation.tsv`; today's coverage, rounded down,
  equals `mapping/ratchet/coverage.tsv`.

## The UI (`mapping/ui`, not a Maven module)

| Scope | Lines | Branches | Functions | Statements | Its floor today |
| --- | --- | --- | --- | --- | --- |
| all of `src/` | 36.19 % (1 153 / 3 186) | 34.73 % (978 / 2 816) | 31.11 % (476 / 1 530) | 36.08 % (1 461 / 4 049) | none |
| `src/model/**` | 100 % (283 / 283) | 100 % (266 / 266) | 100 % (79 / 79) | 100 % (363 / 363) | lines 90 |
| `src/studio/store/**` | 100 % (113 / 113) | 100 % (70 / 70) | 100 % (69 / 69) | 100 % (141 / 141) | lines 90 |

The figure for all of `src/` counts unit tests only: the browser tests and the end-to-end tests were not run and are
not counted by this command.

## Where the two areas differ (facts for the proposal; nothing is proposed here)

1. No decrease. The platform checks it on every push (`ci/coverage_check.py`, in `ci`). The mapping's no-decrease
   rule is its ratchet (`java scripts/ratchet.java check`), which runs only in the mapping's ticket gate: `verify` does
   not run it and **no workflow runs it**. On a push, only the fixed floors stand between the mapping and a drop.
2. Mutation testing. Both areas run PIT every night and fail under 80 %: the platform on `api` and `core`
   (`ci.yml`:199-214, job `mutation`), the mapping on seven modules with code (`mapping-nightly.yml`). The mapping's
   ratchet adds a survivor count per module, checked only in the ticket gate. (Corrected the same day: this line first
   said the platform measures by hand - the designer read `ci.yml`.)
3. Floors lower than the platform's 75 % branch floor exist on paper only: `engine/structure`,
   `engine/structure-import` and `service` have no branch floor, and measure 100 %, 100 % and 96.26 %.
4. Modules outside every number: `engine/oracle` (2 910 tests; the comparison's own harness) and `bench/jmh` skip
   coverage and mutation; `engine/udf-api` and `engine/udf` have no test and no measured code yet.
5. The UI has a floor on two directories only; the rest of `src/` is at about a third, by unit tests alone.
6. The platform's guards skip `mapping/` (`ci/coverage_check.py`; `ci.yml` builds `-f platform/pom.xml`).
7. The ratchet's README and ADR-0019 do not name SAP; the comparison the build runs is with Saxon-HE 12.5
   (the differential oracle), and ADR-0018 says the corpus holds observed cases from SAP PO 7.5 and SAP Cloud
   Integration. What "compared with SAP PO and SAP CPI" means in the build is a question for the proposal.
8. Loose ends in ADR-0019: it lists a `bytecode` module that does not exist, and names `ui/src/editor/store/**` where
   its own amendment says `ui/src/studio/store`.

## Not measured

- PIT wall times: each call's time includes waiting for the shared Maven lock, and `-am` mutates upstream modules
  again in every call (the api call re-ran all seven). No usable duration per module came out of this run; the nine
  calls took about half an hour together, waits included.
- The UI's browser tests and end-to-end tests; the Windows job; the stack job.
- `git diff --exit-code contract/` was not run apart; `git status --short mapping/` was empty after the build.
