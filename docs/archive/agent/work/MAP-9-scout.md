# MAP-9 - how the mapping checks its tests today (scout, 2026-11-16, read at f1be875c)

Read-only facts; paths under the repository root. Not read: `mapping/ui/src`, the body of the service's `ui` profile,
`mapping-bench.yml`, git history.

## Modules
- `mapping/pom.xml` (`rail8-mapping-parent`): `engine`, `service`; profile `bench` adds `bench/jmh` (:423-428).
- `mapping/engine/pom.xml` (:17-30), twelve modules `rail8-mapping-<dir>`: xdm, structure, structure-import, ir,
  serializer, udf-api, udf, runtime, mapping, xslt-export, api, oracle.
- `mapping/service` (`rail8-mapping-service`); `mapping/ui` is not a Maven module.

## Coverage (JaCoCo 0.8.16, `mapping/pom.xml`:334-378)
- Defaults: lines 0.90 (:66), branches 0.80 (:67); `report` and `check` are bound to `verify` (:345-353); the check is
  on the bundle, LINE and BRANCH covered ratio (:358-374). No class exclusion anywhere.
- Overrides - lines 0.80, branches 0.00: `engine/structure` (pom :16-17), `engine/structure-import` (:16-17),
  `service` (:17-18).
- Skipped (`jacoco.skip`): `engine/oracle` (:17), `bench/jmh` (:23).
- On the defaults: xdm, ir, runtime, serializer, mapping, xslt-export, api, udf-api, udf.

## Mutation (PIT 1.30.1, junit5 plugin 1.2.3)
- Declared under `pluginManagement` only (`mapping/pom.xml`:125-148; `engine/api/pom.xml`:45-55): bound to no phase,
  runs when `org.pitest:pitest-maven:mutationCoverage` is called. ADR-0019:81 "not bound to `verify`".
- Threshold `pit.mutation.threshold` 80 (:69, :138); `failWhenNoMutations` true (:70); 4 threads; no target classes or
  target tests set in any pom.
- `pit.skip` true: structure, structure-import, oracle, service, bench/jmh. `failWhenNoMutations` false: udf-api, udf
  (`engine/udf-api/pom.xml`:18, `engine/udf/pom.xml`:18). api excludes the test class `DiagnosticCatalogueTest`.
- Commands: nightly `mapping-nightly.yml`:48-52 - `../mvnw -B -pl engine/xdm,engine/ir,engine/runtime,
  engine/serializer,engine/mapping,engine/xslt-export,engine/api,engine/udf-api,engine/udf -am test-compile
  org.pitest:pitest-maven:mutationCoverage` (in `mapping/`); the ticket gate runs one module per call
  (`scripts/pit-summary.sh`:28, `scripts/gate-ticket.sh`:57-72).
- A second floor beside the 80 %: the survivor count per module in `ratchet/mutation.tsv` (ADR-0019:86-91).

## The ratchet (`mapping/ratchet/`)
- `oracle/corpus.txt` 360 lines, `oracle/differential.txt` 323 lines, `coverage.tsv` 12 rows, `mutation.tsv` 7 rows
  (api, ir, mapping, runtime, serializer, xdm, xslt-export; no udf-api, no udf), `bench.tsv` 14 rows.
- Rule: "a merge may only keep or raise them" (ADR-0019:5-6); "Only a drop below `main` fails." (:62-63). The floors are
  "fixed minimums in the build, independent of the ratchet. The ratchet keeps what was reached above them." (:67-68).
- Checked by `java scripts/ratchet.java check` (README :53-55), which is the ticket gate's step 5
  (`docs/WORKFLOW.md`:28, `scripts/gate-ticket.sh`:123). **`verify` does not run it and no workflow runs it**;
  `mapping-ci.yml`:70-76 runs only the scripts' own tests.
- SAP: the ratchet's README and ADR-0019 do not name SAP; ADR-0018:7-8 "ADR-0002 adds observed cases from SAP PO 7.5
  and SAP Cloud Integration"; the differential oracle compares with Saxon-HE 12.5 (README :14; ADR-0018:87).

## CI
- `mapping-ci.yml`: `backend` - the scripts' tests, `../mvnw -B -Pbench verify` (:91), `git diff --exit-code contract/`
  (:93); `windows` - `-DskipTests verify`; `ui` - licences, generated types, typecheck, lint, `npm run test`, browser
  tests, e2e, build (:145-157); `stack` - the service packaged with the UI and `npm run e2e:stack`. No PIT, no ratchet.
- `mapping-nightly.yml`: job `mutation`, cron `17 2 * * *` and by hand: the PIT command above. No JaCoCo, no ratchet.

## The UI
- `npm run test` is `vitest run --coverage` (`ui/package.json`:15), provider v8, `src/**/*.{ts,tsx}`; thresholds only
  for two globs, lines 90: `src/model/**` and `src/studio/store/**` (`ui/vitest.config.ts`:17-18). No global and no
  branch threshold.

## Recorded numbers (undated, `mapping/ratchet/`)
- `coverage.tsv`, lines/branches %: api 99/100, ir 100/100, mapping 99/98, runtime 100/100, serializer 100/100,
  structure 93/100, structure-import 99/100, xdm 100/100, xslt-export 98/100, service 98/96, ui/src/model 100/100,
  ui/src/studio/store 100/100.
- `mutation.tsv`, survivors/generated: api 0/109, ir 0/66, mapping 11/525, runtime 5/597, serializer 0/103, xdm 0/509,
  xslt-export 0/95.

## Loose ends the scout saw
- ADR-0019:70-74 lists a `bytecode` module; there is no such directory under `engine/`.
- ADR-0019:76 still names `ui/src/editor/store/**`; its amendment (:3) says the store moved to `ui/src/studio/store`.
- No table of the mapping's numbers exists under `agent/work/`, in `mapping/HANDOVER.md` or in `issues/ISSUE-9.md`.
