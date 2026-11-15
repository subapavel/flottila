# BRAND-2 — designer's draft (2026-11-15, read-only; no file written, no build run)

(a) Layout, old -> new. The platform's modules go under one area directory, `platform/`.
- pom.xml -> platform/pom.xml (today's parent, moved down a level); the new root pom.xml is a thin aggregator of `platform`, `bench` and `mapping`
- flottila-{api,core,runtime,testkit,schemas,cli,sim,archtest} -> platform/{api,core,...}; flottila-components/{http,mock,sftp} -> platform/components/...; flottila-steps/{validate,xslt} -> platform/steps/...
- flottila-bench/{pom.xml,src} -> bench/, beside run.sh, bench.py, check_regression.py and RESULTS.md (one bench directory)
- packages/demo -> examples/demo; prompts/ -> agent/prompts/; deploy/claude-cloud/ -> agent/cloud/; root PROGRESS.md (ends 2026-11-03) -> docs/archive/ unedited (OPS-1's merge; docs/PROGRESS.md is the live one)
- Stay where they are: agent/ issues/ docs/ ci/ bin/ deploy/{compose,quickstart,postgres} docker-compose.yml HANDOVER.md README LICENSE NOTICE AGENTS.md CLAUDE.md .github/ .claude/
- BRAND-3: the mapping repo's {pom.xml,engine,service,ui,contract,corpus,scripts,bench/jmh} -> mapping/...; its agent/ docs/ ratchet/ deploy/ .claude/ .github/ CLAUDE.md AGENTS.md HANDOVER.md GLOSSARY.md are folded in or rowed
- Later: jq/ as a sibling of mapping/; the platform web UI in platform/control/ (Quarkus admin API + React UI, as ARCHITECTURE §12:517 has it); platform/sap (§12:513)
- Resulting root: 10 directories (agent bench bin ci deploy docs examples issues mapping platform), the same count as Iggy's.

(b) Coordinates

| pom | groupId | artifactId | Java packages / JPMS |
|---|---|---|---|
| root aggregator | io.rail8 | rail8 (today's root id, kept) | — |
| platform/pom.xml | io.rail8 | rail8-parent | — |
| platform + bench | io.rail8 | rail8-api, -core, -runtime, -testkit, -schemas, -cli, -sim, -archtest, -bench, -components, -steps, -component-http/mock/sftp, -step-xslt/validate | io.rail8.<module>, e.g. io.rail8.components.http |
| mapping (its own parent) | io.rail8.mapping | rail8-mapping-parent, -engine, -api, -ir, -mapping, -oracle, -runtime, -serializer, -structure, -structure-import, -udf, -udf-api, -xdm, -xslt-export, -service, -bench | io.rail8.mapping.*; JPMS io.rail8.mapping.* |

Build properties flottila.coverage.*, flottila.mutation.threshold and flottila.it.postgres become rail8.*; the PIT targetClasses become io.rail8.*.

(c) Decisions
1. **Grouped, under `platform/`.** A flat root would have 19 directories, with product modules sorted in among agent/, ci/ and docs/. Grouped, `platform/api` sits opposite `mapping/engine/api`, and `cd platform && mvn verify` builds the platform on its own.
   - Cost: the deeper path breaks 25 `Path.of("..")` sites in 21 test files (e.g. CliRuns.java:20-21, ComposeStackTest.java:26,311,378, BenchRunScriptTest.java:28) and flottila-runtime/pom.xml:25.
   - Moving the root pom down keeps all 15 `<relativePath>..</relativePath>` lines valid. ArchUnit keys on packages, so it is not affected.
   - Names rejected: core (the owner's rule), rail8 (the product name, which Iggy avoids in directories), engine (clashes with mapping/engine), server (cli, testkit and schemas are not a server), modules or java (say nothing).
2. **The mapping keeps its own parent; the root aggregates it but it does not inherit.** Its build differs: JUnit 6.1.3 vs 5.14.4, Saxon 12.5 in test scope only vs 12.4, -Werror, dependencyConvergence, spotless, the licence plugin, floors 0.90/0.80, a Quarkus BOM (mapping pom.xml:32-67,162-314). Inheriting would change behaviour, which is not layout work. Converging the two builds is a later row.
3. **Artifact ids keep the `rail8-` prefix although the directories drop it.**
   - The jars in `target/lib` and in the image (deploy/quickstart/Dockerfile:10-11) are named `artifactId-version.jar`, so `rail8-api` and `rail8-mapping-api` must never both become `api-0.1.0.jar`.
   - Commands switch from `-pl flottila-x` (a path) to `-pl :rail8-x` (an id; Maven knowledge, unverified) at ci.yml:125,203 and bench/run.sh:79, so later moves do not touch them.
4. **One bench directory at the root.** CLAUDE.md, §12:519, ci.yml:5,177-185 and WORKFLOW.md:93 all cite `bench/` already. The mapping's JMH stays in mapping/bench/jmh, because its paths are wired to its own depth (bench/jmh/pom.xml:54,73). Its RESULTS.md is folded into bench/RESULTS.md.
5. **Support directories.**
   - The ledgers stay: ci/ledger_check.py:28, CLAUDE.md and the hooks cite agent/, docs/ and issues/.
   - prompts/ and deploy/claude-cloud/ are agent tooling: only claude-cloud/README.md:72-85 cites prompts/, and setup.sh is pasted by the owner, not referenced by path (README.md:29).
   - examples/ follows Iggy; "packages/" reads like the module directory of a JS monorepo. The test edits are already paid for by decision 1. Optional and easy to undo.
   - docker-compose.yml stays at the root (the quickstart's one command; ComposeStackTest.java:311 reads it). HANDOVER.md stays: WORKFLOW.md:54,137,150 keep it in use until the next gate.
   - To archive or delete once their rows are closed: agent/patches/P2-10b-wip.patch and agent/wip/*.patch. They hold 82, 12 and 8 old paths and cannot be applied after the move.
6. **Order of the moves.**
   - (0) P2-10b has landed, the BASE-1 baseline is known, and the scout's list from (d)4 exists.
   - (1) Directories only: git mv, the new root pom, and the path strings. These are in bin/flottila:2-15, the Dockerfile and its .dockerignore:3-7, deploy/compose/docker-compose.yml:216-217, ci.yml:73,76, bench/run.sh:79-81, LauncherIT.java:92, the 25 test paths and runtime/pom.xml:25, plus the keys in ci/coverage-baseline.json.
   - (2) Coordinates, build properties and jar names.
   - (3) Packages: git mv of src/*/java/io/flottila, the constants in Rules.java:25-32, PIT, the manifest's Main, run.sh:115,160,171. Then BRAND-1's steps (2)-(4), then BRAND-3.
   - Why directories first: the coverage keys are directory paths (coverage_check.py:51), so only commit 1 changes them, and no moved file is also edited. Moving the root pom in that same commit looks to git like pom.xml modified plus platform/pom.xml added, so `--follow` on the parent pom may not cross the move (unverified).
   - Each commit must pass `mvn -B -ntp verify -Pit` and the stack job; `git log --follow` must cross the move for a moved class.

(d) Risks
1. ci/coverage_check.py:82-84 skips any module missing from the baseline, so commit 1's no-decrease check passes without checking anything. The verifier has to compare old vs new key by key, by hand.
2. **Conflict:** BuildHygieneTest.java:21,33-37 walks every pom up to 4 levels below `..` and fails on `io.smallrye` or `mutiny`. The mapping's pom.xml:203 matches. Flat, the test turns red; grouped, it silently shrinks to platform/. The Quarkus `control` in §12 will turn it red later either way. The architect has to decide its scope explicitly.
3. `${maven.multiModuleProjectDirectory}` resolves to the first parent directory with a `.mvn/` (verified in /opt/apache-maven-3.9.11/bin/mvn:124-142). Built from the repo root, it points to the root, which breaks service/pom.xml:91,132,162, engine/{mapping:41,70; runtime:52; xslt-export:56; oracle:61; structure-import:61} and bench/jmh:102. BRAND-3 fix: one root `.mvn/` with mvnw 3.9.11 (the mapping requires [3.9.11,4), pom.xml:192) and `/mapping/...` in those paths.
4. Persisted and wire names for the scout; renaming them loses data or splits a cluster:
   - RecordFormat.java:43 "flottila-journal-v1", SpoolCipher.java:49 "flottila-spool-v1", PeerHandshake.java:54 "flottila-peer-v1"
   - the JFR event io.flottila.Step (D-094) and the header X-Flottila-Actor (V16)
   - the volumes flottila-pg and flottila-data (docker-compose.yml:96-97), the bucket flottila (deploy/compose:153), the database and role flottila, flottila_bench, /var/lib/flottila
   - applied migrations stay untouched, comments included (the checksum; from memory)
5. **Conflicts:**
   - §12:502-519 names the flottila-* modules and has no mapping row; it needs an edit with a changelog entry (D-148).
   - CLAUDE.md's licence list vs the mapping's EDL-1.0, ISC, 0BSD and MIT-0 (pom.xml:304-314).
   - The single bench/RESULTS.md and ci/coverage-baseline.json vs mapping/bench/RESULTS.md and mapping/ratchet/.
   - The depth fix edits tests that other agents wrote; the packet should say so.

Hard to undo, but undoable until a release (D-148): the coordinates, the package root and the JPMS names — mark `draft — review at hand-over`. Open for the architect: a later steps/map depending on io.rail8.mapping.api breaks STEPS_DEPEND_ON_API_ONLY (Rules.java:90), which needs a finding. For the BRAND-3 import: `git subtree add` keeps the SHAs, but `--follow` stops at the merge. `filter-repo --to-subdirectory-filter` gives continuous paths but rewrites the mapping's SHAs, so the SHAs its ADRs cite would point nowhere. Also open: one version for both parents?

(e) Not checked: Iggy's repo itself, ARCHITECTURE beyond §12, the mapping's .github workflows, its ui/ and CLAUDE.md, the Maven version on ubuntu-latest, the remaining ~458 "flottila" strings in src/main (scout), whether the agent/wip patches are still in use.
