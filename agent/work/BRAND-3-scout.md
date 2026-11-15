# BRAND-3 — scout's inventory of the mapping repository (2026-11-15, read-only)

Read at /home/user/subapavel/flottila-mapping, branch `rail8-import` (every file already under `mapping/`). Paths below are relative to
`mapping/`. Counts are grep counts. Not read: Java and TS bodies, corpus contents, ratchet rows, ADR and finding bodies, skills.

## 1. The old name, by class
(a) Maven: pom.xml:8-9 `io.flottila.mapping:flottila-mapping-parent`, name "Flottila Mapping" (:12); modules :22-23 `engine`, `service`;
    profile `bench` (:418-423) adds `bench/jmh`. engine/pom.xml:13 `flottila-mapping-engine`, modules :18-29 xdm, structure,
    structure-import, ir, serializer, udf-api, udf, runtime, mapping, xslt-export, api, oracle (12). Children `flottila-mapping-<module>`;
    service/pom.xml:12 `flottila-mapping-service`; bench/jmh/pom.xml:18 `flottila-mapping-bench`. 15 artifactIds plus the parent.
(b) Java: one root, `io.flottila.mapping` (subpackages xdm, mapping, runtime, oracle, api, service, bench, …); named in module-info
    (engine/xdm/src/main/java/module-info.java:2-3).
(c) Properties: service/src/main/resources/application.properties:6 `%dev.flottila.mapping.workspace`, :19 `flottila.mapping.test-timeout`,
    :21 `flottila.mapping.trace-limit`, :23 `flottila.mapping.time-zone`. System property `flottila.corpus`: bench/jmh/pom.xml:102,
    engine/oracle/pom.xml:61, engine/structure-import/pom.xml:61; read in bench/jmh/src/test/java/…/OrdersInputTest.java:82 (and two
    siblings). No `FLOTTILA_*` env key.
(d) ui/package.json:2 `"name": "flottila-mapping-ui"`. No Dockerfile, no compose. deploy/ holds only deploy/claude-cloud/setup.sh.
(e) WIRE / PERSISTED: contract/mapping-model.schema.json:3 `"$id": "urn:flottila:mapping-model:1"` (:5 description);
    contract/function-catalogue.schema.json:3 `"$id": "urn:flottila:function-catalogue:1"`; contract/openapi.json:1190 title
    "flottila-mapping-service API"; the generated XSLT's namespace `xmlns:po="urn:flottila:mapping:po"` and
    `QName('urn:flottila:mapping:po', …)` in engine/xslt-export/src/main/xsl/po-runtime/core.xsl:12, :60 and 10 po-runtime .xsl files,
    engine/xslt-export/src/test/resources/pinned-71/po-runtime.xsl, scripts/test/function-families/po-runtime/*.xsl; the error code
    `flottila:TIMEOUT` (application.properties:18, comment); test-model QNames `urn:flottila:test:*`.
(f) GOLDEN: corpus/ has 467 hits in 200 files; `urn:flottila:…` forms in 175 files (e.g. corpus/mappings/order-id-copy.mapping.json:9-25
    `Q{urn:flottila:corpus:skeleton}Order`; corpus/structures/wsdl/order-11.wsdl:9-17). 7 `corpus/cases/**/expected*` files hold a hit.
    Hard-coded 64-hex literals, dependence on a name not checked: engine/api/src/test/java/…/api/FingerprintPinTest.java (24 hits),
    service …/Fixtures.java (14), MappingModelWriterTest.java (13).
(g) HISTORY: docs/adr/ 30 files (0001-0030); docs/findings/ F-1..F-53 + TEMPLATE.md; agent/{HANDOFF.md,NOTES.md,prompts/…};
    bench/RESULTS.md; ratchet/ 6 files; HANDOVER.md (3 hits: :62, :117, :221).
(h) GitHub URLs: 57 in 17 files (40 in ui/package-lock.json). `#N` issue references: CLAUDE.md:37, pom.xml:394, ci.yml:22, :99, HANDOFF.

## 2. Build
- Wrapper 3.3.4, Maven 3.9.11; enforcer (pom.xml:189-192) JDK [25,), Maven [3.9.11,4). `.mvn/maven.config`: the two aether file-lock flags.
- `.mvn/licence-filters.txt` is read through pom.xml:303 `artifactFiltersUrl ${licence.filters.uri}`, set by two profiles (pom.xml:395-416)
  as `${project.basedir}/../../.mvn/licence-filters.txt` (:399, :403) and `../.mvn/…` (:410, :414). bench/jmh has its own
  licence-filters.txt.
- Root-path references that break when the Maven root moves: pom.xml:399, 403, 410, 414; bench/jmh/pom.xml:16 (`../../pom.xml`), :54, :73
  (`${project.basedir}/../../corpus/bench`), :102; service/pom.xml:91, :132, :162 (`${maven.multiModuleProjectDirectory}/ui/dist`);
  engine/mapping/pom.xml:41, :70; engine/runtime/pom.xml:52; engine/xslt-export/pom.xml:56; engine/oracle/pom.xml:61;
  engine/structure-import/pom.xml:61. AGENTS.md:63 names .mvn/maven.config.
- Root-finding code: service/src/test/java/…/service/Fixtures.java:204-212 walks up to a directory holding `mvnw` and `corpus/`;
  scripts/gate-spec.sh:29 `cd "$(dirname "$0")/.."`; ui/src/import-boundaries.test.ts:74 `'../../../../service/pom.xml?raw'`.
- Bound to verify: spotless-check (pom.xml:255-260), corpus-bench-format (:265-270), jacoco report and check (:339-349). Enforcer and the
  licence check (`add-third-party`, :292-300, failOnBlacklist) run without an explicit phase. PIT only by explicit goal (nightly, gate).
- exec of scripts at generate-sources/resources: engine/runtime/pom.xml:45-55, engine/mapping/pom.xml:63-73,
  engine/xslt-export/pom.xml:49-58 (`scripts/function-families.java`), service/pom.xml:121-132 (`src/build/WriteCatalogue.java`).
- Profiles: `bench`, `ui` (service/pom.xml:155), `licence-filters-depth-1/-2`. Node >= 22.12 (ui/package.json:6-7); Playwright 1.63.0.

## 3. CI (mapping/.github/workflows)
- ci.yml (pull_request; push to main and t/**): `trailers` (script tests under scripts/test + scripts/check-trailers.sh);
  `backend` (ubuntu-24.04, Temurin 25, a settings.xml mirror, script tests function-families*, ratchet-*, gate-spec-bench, then
  `./mvnw -B -Pbench verify`, `git diff --exit-code contract/`, scripts/check-lf-checkout.sh); `windows` (`./mvnw.cmd -B -q -DskipTests
  verify`); `ui` (working-directory ui: npm ci, licences, playwright, gen:types + diff, typecheck, lint, test, test:browser, e2e, build,
  upload `ui-dist` from ui/dist); `stack` (needs backend+ui: download ui-dist, `./mvnw -B -Pui -pl service -am package -DskipTests`,
  `npm run e2e:stack`).
- bench.yml: workflow_dispatch; `./mvnw -B -Pbench -pl bench/jmh -am package -DskipTests`, then the JMH jar; artifact `jmh`.
- nightly.yml: cron `17 2 * * *`; PIT `mutationCoverage` over the engine modules; artifact pit-reports.
- scripts/gate-spec.sh: steps 1, 1b, 2 (ui), 5 (JMH), 4 (`java scripts/ratchet.java regen --all`), 3 (`-Pui` package + e2e:stack).

## 4. Process files
- CLAUDE.md (61 lines): intro :1-8; "Read before work" :10-21 [process]; "Non-negotiable" :23-39 [technical at :25, 26, 29, 32, 33-37,
  38; process at :27, 28, 30, 31, 39]; "Context hygiene" :41-47 [process]; "Agent skills" :49-61 [process: issue tracker, labels, docs].
- AGENTS.md (131 lines): roles, ticket classes, concurrency, hand-offs, command rules, evidence vocabulary, memory — all process.
- .claude/: 10 agents (designer, verifier, grilling-adviser, merger, implementer, reviewer-standards, reviewer-spec, scout, test-author,
  gate-reviewer), 1 hook, settings.json (model, SessionStart hook, denies), 7 agent-memory files, 23 skills + UPSTREAM.md + the MIT text.
- .mcp.json: context7 only. Findings F-1..F-53 collide by file name with the platform's F-1..F-37.

## 5. Licences
CLAUDE.md:33-37: "Apache 2.0, MIT, MIT-0, BSD, EDL 1.0, ISC, 0BSD, MPL 2.0, EPL only (ISC, 0BSD: ADR-0012; MIT-0, EDL 1.0: ADR-0014)".
Enforced by license-maven-plugin (pom.xml:289-322, includedLicenses :304-314) and, for npm, `npm run licences`
(ui/scripts/check-licences.mjs, exceptions in ui/scripts/licence-exceptions.json).

## 6. The mapping's own next actions (agent/HANDOFF.md:39-46)
open: #222 (observation request, deferred past milestone 1), #191 (optional), #129 (ADR wording, owner).
1. Owner: merge the `gate/m1` PR. 2. The cleanup ticket (the lists in that file; F-51 first, class B, with a red case).
3. The Rail8 rename (R8, rail8.dev): docs first; packages at embedding (ADR). 4. Grill jq/jqj shared layers: limits/host SPI, the
Class-File tier, the JSON tokenizer, tooling; and where XPath 3.1 lives. 5. Next spec: JSON output (ADR-0008), via grilling.

## 7. Ratchet and bench
ratchet/: README.md, mutation.tsv, coverage.tsv, bench.tsv, oracle/corpus.txt, oracle/differential.txt — keyed by module path
`engine/<name>`, no artifactIds. The JMH class pattern with the package root is in ratchet/README.md:30 and scripts/ratchet.java:518, :523
(`io\.flottila\.mapping\.bench\.(\w+)Bench\.(\w+)`). bench/RESULTS.md: 13 lines with module paths (history).
