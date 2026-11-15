# BRAND-3 — scout 2: the mapping's living documents against the root rules (2026-11-15, read-only; during step 2's rename)

Paths relative to `mapping/` unless they start with `/`. Old and new names are the same thing here.

## A. What it is
- Mapping engine + Quarkus service + React UI, Java 25, Apache 2.0 (README.md:3-7, CLAUDE.md:3-6).
- Engine modules, JPMS, 12 with oracle/udf: xdm, structure, structure-import, ir, serializer, udf-api, udf, runtime, mapping, xslt-export,
  api (facade), oracle (tests only) — docs/adr/0016:13-27. bench/jmh (profile bench only, 0016:29), service (0016:28), ui/ npm (0016:30).
- `maven.compiler.release` 25 (pom.xml:27). Engine has module-info (0014:11-13); the service is on the class path, no module-info
  (0014:16); Quarkus 3.40.1.1 (pom.xml:46), quarkus-rest-jackson, quarkus-smallrye-openapi (service/pom.xml:43, :47). Quarkus ships
  Vert.x 4.5, not 5 (0014:18-19; agent/NOTES.md:78). UI: React 19, PatternFly 6.7 (0012:77).
- Exposes: a REST service (0015, 0024), generated `contract/openapi.json` (0017:64-65), the Mapping studio UI (0028:3), the engine
  facade `api` — "not the embedding API", no compatibility promise (0016:41-43). No CLI found.
- Persists: no database; a workspace directory of `**/*.mapping.json`, atomic save, ETag = SHA-256 (0014:28-38); browser localStorage
  with a versioned prefix (0013:88); ratchet TSVs under ratchet/.
- No authentication (out of milestone 1: 0014:103-105, 112; GATE.md:124; 0020:107). The service logs one warning
  (service/.../ServiceConfig.java:131); engine logging through System.Logger (0020:42-44). Audit, metrics, health, tenants, roles,
  masking: not found.

## B. Use by the platform, as the mapping's documents foresee it
- "Separate repo until it is embedded ... as its mapping engine and mapping UI" (FOUNDING-ARCHITECTURE.md:21); the service is "the same
  as [the platform's] future backend" (0014:6); a ReferenceResolver is supplied by the host (0014:52-53, 73-92); "the engine never
  opens a file" (0014:60).
- Library call through the facade `api`; the embedding API is deferred (0016:38-43; 0014:112-113). The REST service is not a host
  contract (0014:16-17).
- Generated XSLT run elsewhere: the portable export writes one stylesheet importing po-runtime.xsl (0023:1); "Export to readable XSLT
  3.0" (FOUNDING:121). No XSLT export endpoint and no transport export in M1 (GATE.md:125-126; 0015:36).
- Artifact names carry a prefix "so the jars do not collide inside a host application" (0016:32); no engine module may depend on
  service, io.quarkus, jakarta or com.fasterxml (0014:22; 0016:70). Nothing is published in M1 (0017:12-13).
- Root /docs/ARCHITECTURE.md §12 (line 664): no platform module depends on the mapping at first; "a mapping step needs a finding first".

## C. Where the mapping's documents meet or contradict the root rules
1. Framework: adr/0014:17-19 "uses no Vert.x API directly", Quarkus on Vert.x 4.5; adr/0024:92 "Vert.x's 413" — root: Vert.x 5.1.
2. Size: service/.../MappingsResource.java:312, 423, 473 `body.readAllBytes()`; Workspace.java:149, 228 whole-file reads; adr/0024:92
   a body above 10 MB is refused — root: no message refused for its size, never a whole-body buffer.
3. I/O: Workspace.java:114, 148, 338-348 file I/O in the service; HTTP listener on 127.0.0.1 (0014:103) — root: nothing but the
   Component SPI opens a socket. The mapping has no SPI.
4. Threads: adr/0014:106 "worker threads, never the event loop"; adr/0020:98 "a test run runs on a platform thread, never a virtual
   one" — root: one virtual thread per message, two executors.
5. Tests: engine/xdm/.../BooleanFunctionsTest.java:199 `pairs().skip(10)` (a stream skip, not a skipped test); WorkspaceTest.java:600-602
   and ObservedFileSystemTest.java:25-27 `assumeTrue` (POSIX) — root: no skipped cases. No @Disabled, Thread.sleep or excludedGroups.
6. adr/0017:59 "jqwik is not used (ADR-0018)" — root: jqwik property tests (for platform parts).
7. adr/0017:58-59 no mocking library; ports have hand-written fakes (no conflict).
8. Licences: block E — root: "GPL ... ISSUE, never linked" against 0017:40-43.
9. Logging: 0020:42-44 System.Logger and run.log. Payload logging, payload-view audit, masking, alerts, monitoring: not found — the
   operations brief's sections 4-6 are undecided for the mapping.
10. Statements about the platform: CLAUDE.md:4-6 "separate repo with no dependency on [the platform]"; FOUNDING-ARCHITECTURE.md:21;
    root ARCHITECTURE.md:647 "mapping/ ... moves in ... (planned: BRAND-3)"; agent/HANDOFF.md:44 planned the rename with rail8.dev,
    "packages at embedding (ADR)".
11. Release: 0017:12-13 nothing published in M1; pom.xml:10 `0.1.0-SNAPSHOT`; 0017:21-23 the Google mirror of Central.

## D. Open work in the mapping's own records
- Findings: F-51 open (F-51.md:3); F-38 open, deferred past milestone 1 (#220) (F-38.md:3); F-13, F-16, F-18, F-27, F-28, F-30, F-31,
  F-32 open or deferred per HANDOVER.md:186-190 (those files have no Status line).
- Tracked as GitHub issues of the old repository (docs/agents/issue-tracker.md; CLAUDE.md:20 — no backlog file): #222 observation
  request deferred (HANDOVER.md:196; GATE.md:39-40); #220 (F-38); #221 (F-51's fix, HANDOVER.md:193-194); #191 optional, #129 ADR
  wording (agent/HANDOFF.md:39); #165, #167, #168, #169, #171 wait for the data model (GATE.md:117-119); a cleanup ticket
  (agent/HANDOFF.md:43); F-53 point 4, the ADR-0026 re-pin (agent/HANDOFF.md:18; HANDOVER.md:204-205); F-40's bench uncommitted
  (agent/HANDOFF.md:19).
- Next specs: JSON output (0008), the bytecode tier (0021), formatVersion 2 and the PO importer (0010), then the XSLT front end
  (GATE.md:111-115; HANDOVER.md:210-221).
- No ADR is proposed or draft; docs/wayfinder/phase-0-map.md:3 is a draft. GitHub was not queried.

## E. Licence exceptions (ADR-0017 and the filters)
- Java, `.mvn/licence-filters.txt:5-9`, ADR-0017:40-43, enforcer excludes pom.xml:202-217, pom.xml:303:
  io.smallrye.classfile:jdk-classfile-backport (GPL-2.0 with Classpath Exception; outside test scope); org.graalvm.sdk:nativeimage and
  :word (UPL-1.0); aopalliance:aopalliance (public domain); javax.annotation:javax.annotation-api (CDDL + GPL-2.0 with CPE). No ruling
  number in ADR-0017; the filter file's line 2 says "the owner accepted".
- JMH org.openjdk.jmh:* (GPL-2.0 with CPE): bench/jmh/licence-filters.txt:3, bench scope, ADR-0017:44, pom.xml:234-236.
- Saxon-HE (MPL 2.0): ADR-0017:44-45 "needs no exception"; pom.xml:198-201 excludes it outside test; CLAUDE.md:35-36 calls it an
  exception at test scope.
- The Quarkus test framework's dependencies: CLAUDE.md:36 only; not in ADR-0017 or the filter file.
- npm, dev-only (ui/scripts/licence-exceptions.json): argparse (Python-2.0), caniuse-lite (CC-BY-4.0), minimatch (BlueOak-1.0.0) —
  owner ruling #39 (ADR-0017:48-56).
- ISC, 0BSD: ADR-0012:78-79 (d3-*, tslib); ADR-0013:156 production licences MIT, ISC, BSD-3-Clause, 0BSD.
- MIT-0 (reactive-streams), EDL 1.0 (jakarta.activation, jakarta.xml.bind): ADR-0014:140-143, "the owner added both", no number.

## F. mapping/CLAUDE.md by line range
- Process: 8-21 (read order, GitHub Issues, tracker); 27-28, 31-32, 39 (evidence words, worktrees, Context7, findings); 41-47 (context
  hygiene); 51-57 (issue tracker, triage labels).
- Technical: 13 (glossary); 17-18 (ADRs, the CODING_STANDARDS pointer); 25-26 and 29-30 (red first, oracle and ratchet, no weakening,
  ratchet files); 33-37 (licence list); 38 (no secrets or payloads); 59-61 (domain docs, single context).
