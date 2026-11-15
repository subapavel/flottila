# Notes

Module notes by section, read on demand.

## Cloud environment (observed 2026-10-30)

- `java -version`: 25.0.5.0.3 LTS (Oracle HotSpot). `JAVA_TOOL_OPTIONS` sets proxy + truststore.
- `node --version`: v22.22.0.
- `mvn -v`: Apache Maven 3.9.11 (`/opt/maven`).
- `gh` 2.89.0 (< 2.94: no `--blocked-by` / `--add-sub-issue` flags; use REST anyway).
- `gh issue list`: EXECUTED_FAIL — HTTP 403, GraphQL is blocked from Claude Code sessions.
- `gh api repos/{owner}/{repo}/issues`: VERIFIED_PASS — REST works. Use the
  "Cloud VM: REST only" table in `docs/agents/issue-tracker.md` for every issue operation.
- `gh auth status` reports the GH_TOKEN invalid; ignore it, the proxy authenticates REST calls.
- Labels from `scripts/bootstrap-github.sh` exist on `subapavel/flottila-mapping`.
- Context7, first check: without a key `resolve-library-id` returned "Monthly quota exceeded"
  → ENVIRONMENT_BLOCKED. Fixed by the owner (env key) and fe59d68 (`.mcp.json` header).
- Context7, re-check 2026-10-30:
  - `CONTEXT7_API_KEY` in the environment: VERIFIED_PASS (set; value never printed).
  - Direct POST `resolve-library-id` "vert.x" with the key header: VERIFIED_PASS — returns the
    library list (`/eclipse-vertx/vert.x`, versions 5.1.8), no quota error.
  - Context7 MCP tools: earlier sessions ran from `/home/user` and the server sat at "Pending
    approval"; fixed by 90ffa60 (`deploy/claude-cloud/setup.sh` writes `enabledMcpjsonServers`
    into `~/.claude/settings.json`). `.mcp.json` carries the key header (fe59d68).
- Context7, session check 2026-10-30 (new session on this repo only, issue #20):
  1. `pwd` = `/home/user/flottila-mapping`: VERIFIED_PASS.
  2. `~/.claude/settings.json` `enabledMcpjsonServers` = `['context7']`: VERIFIED_PASS.
  3. `CONTEXT7_API_KEY` set (value never printed): VERIFIED_PASS.
  4. `claude mcp list`: `context7: https://mcp.context7.com/mcp (HTTP) - √ Connected`: VERIFIED_PASS.
  5. ToolSearch "context7" → `resolve-library-id`, `query-docs` attached: VERIFIED_PASS.
     `resolve-library-id` "Saxon-HE" → `/websites/saxonica_documentation12` (Saxon 12 docs);
     `query-docs` "s9api XsltCompiler compile stylesheet" → real snippets, no quota error:
     VERIFIED_PASS. Caveat: that library has only 4 snippets (overview pages), none on s9api;
     for Saxon Java API signatures use `javap` on the test-scope Saxon-HE jar.
- `curl https://help.sap.com/` and `https://community.sap.com/`: ENVIRONMENT_BLOCKED — proxy
  refuses the CONNECT tunnel (403, http_code 000); the hosts are outside the network policy.
- Session check 2026-10-30 (Phase 0 continuation):
  - ToolSearch "context7" → `resolve-library-id`, `query-docs` attached: VERIFIED_PASS.
  - `resolve-library-id` "saxon" → `/websites/saxonica_documentation12` (4 snippets) and
    `/websites/saxonica_saxonjs_documentation2`: VERIFIED_PASS.
  - `query-docs` on the Saxon 12 library ("xsl:iterate and grouping in Saxon-HE") → overview
    snippets only (editions, XSLT 3.0 conformance, HE under MPL 2.0), no quota error:
    VERIFIED_PASS. Same caveat: no element-level Saxon docs in Context7; read the W3C specs or
    saxonica.com directly.
  - `curl https://help.sap.com/` → 301 to `/docs`, then 200 (Akamai): VERIFIED_PASS — the host is
    now inside the network policy.
  - `curl https://community.sap.com/` → CONNECT tunnel established, origin (Cloudflare) answers
    403 to GET (HEAD 200): ENVIRONMENT_BLOCKED by the site's bot protection, not by the proxy.
    Do not work around it; cite community posts only via search excerpts, tagged [COMM].
  - `gh api … -f body=@file` posts the literal text `@file`; only `-F body=@file` reads the file
    (EXECUTED_FAIL then VERIFIED_PASS on #1, #2, #20 — repaired). Fixed in `docs/agents/issue-tracker.md`.
  - Research #15 agent: www.w3.org and saxonica.com: ENVIRONMENT_BLOCKED by the egress proxy;
    W3C sources read from `raw.githubusercontent.com/w3c/qtspecs`. Maven Central reachable:
    Saxon-HE 12.5 and 13.1 fetched into a scratch dir and run — VERIFIED_PASS. learning.sap.com:
    ENVIRONMENT_BLOCKED (proxy). help.sap.com/docs content via its own `/http.svc/*` JSON endpoints.
- UI toolchain trial for #11 (2026-10-30, scratch dir, verifier):
  - Preinstalled `playwright` 1.63.0 resolves from `/opt/node-tools/node_modules` (via `NODE_PATH`);
    browsers in `/opt/pw-browsers` (`chromium-1243`, `PLAYWRIGHT_BROWSERS_PATH` set).
    `@playwright/test` is not global: pin it to exactly 1.63.0 in `ui/` and it finds the browser
    with no install and no `executablePath` (VERIFIED_PASS). Latest 1.64.1 would not match.
  - Vite 8.3.5 `build` and `tsc` (TS 6.0.3) with React 19.3.1 + PF 6.7.0 + React Flow 12.12 +
    Query 5.105 + Zustand 5.0.16 + Immer 11.2.9: VERIFIED_PASS. Side-effect CSS imports need
    `"types": ["vite/client"]` in tsconfig.
  - Vitest 5.0.5 node mode and browser mode (`@vitest/browser-playwright` 5.0.5) on the
    preinstalled Chromium: VERIFIED_PASS. Exclude `e2e/` from Vitest.
  - jsdom 30 needs Node ≥ 22.22.2 (VM: 22.22.0). TypeScript 7 is blocked by typescript-eslint
    8.72 (`typescript <6.1.0`). `@tanstack/react-router` depends on `isbot` (Unlicense).
- Service trial for #12 (2026-10-30, scratch dir, verifier):
  - Maven Central (`repo1.maven.org`, `repo.maven.apache.org`) answered HTTP 429 to the VM:
    ENVIRONMENT_BLOCKED, intermittent. The Google mirror of Central
    (`https://maven-central.storage-download.googleapis.com/maven2`) served every artefact via a
    scratch `settings.xml` mirror (`mirrorOf central`) and `-Dmaven.repo.local=<scratch>/m2`:
    VERIFIED_PASS.
  - quarkus.io: DNS ENOTFOUND from the session; Quarkus guides via Context7
    (`/websites/quarkus_io_guides`) and raw quarkusio.github.io files.
  - Quarkus 3.40.1.1 (LTS 3.40) on Java 25: `mvn verify` with `@QuarkusTest` VERIFIED_PASS
    (test app start 4.1 s); packaged start 0.9 s, RSS 126 MB; `quarkus:dev` listening after
    ~26 s, app RSS ~454 MB (needs the engine SNAPSHOT installed first). Vert.x 4.5.35,
    Jackson 2.21.7, smallrye-open-api 4.3.5, kotlin-stdlib 2.4.10 (BOM) inside.
  - json-sKema 0.32.0 has no module descriptor (derived automatic module). Real API (javap):
    `new SchemaLoader(String).load()`, `Validator.forSchema(s).validate(new JsonParser(doc).parse())`
    → `ValidationFailure` or null; pointer `failure.getInstance().getLocation().getPointer()`.
  - smallrye-openapi: sealed records need explicit `@Schema(oneOf, discriminatorProperty,
    discriminatorMapping)`; required needs `requiredProperties` / `required=true` / `@NotNull`;
    external ref must start with `./`. openapi-typescript 7.13.0 resolves it (ADR-0015).
  - Never `pkill -f` a pattern that matches your own shell (it killed the verifier's shell).
- Build trial for #13 (2026-10-30, scratch reactor, agents; decisions in ADR-0016, ADR-0017):
  - Maven: until the owner pastes the new `deploy/claude-cloud/setup.sh`, use a scratch
    `settings.xml` with the Google mirror and `-Dmaven.repo.local=<scratch>/m2` (never `~/.m2` of
    the VM for trials). `MVNW_REPOURL=<mirror> ./mvnw -v` downloads Maven from the mirror.
    Running `setup.sh` on the VM itself was refused by the session's permission classifier;
    it was checked with a scratch `HOME` (VERIFIED_PASS).
  - Versions observed working on JDK 25: compiler 3.16.0, surefire 3.6.1, enforcer 3.6.3,
    license-maven-plugin 2.7.1, Spotless 3.10.4 + google-java-format 1.36.1 (1.37.0 fails:
    `NoSuchMethodError JavaFormatterOptions$Style.valueOf`), JUnit 6.1.3 (Quarkus 3.40.1.1 BOM),
    AssertJ 3.27.7, ArchUnit 1.5.1, JMH 1.37 + shade 3.6.2, wrapper 3.3.4.
  - Maven 4 is RC only (4.0.0-rc-7); the latest Maven 3 is 3.10.0; we pin 3.9.11.
  - json-sKema: no `--add-modules kotlin.stdlib` under Maven; it breaks compile and surefire
    (F-3). javac lint category for the automatic module is `requires-automatic`; the
    "filename-based automodules" banner is maven-compiler's own, non-fatal.
  - `quarkus:dev` needs `-am` (`-pl service` alone: "Could not find artifact
    …flottila-mapping-xdm:jar:0.1.0-SNAPSHOT"); no `install` needed. `kill <mvn pid>` stops the
    app too. Plain `./mvnw quarkus:dev` from the root needs `quarkus-maven-plugin` in the root
    `pluginManagement`.
  - Two `@QuarkusTest` builds at once clash on the test port: `quarkus.http.test-port=0`.
  - `store-schema-directory=../contract` resolves against `service/`; it always writes
    `openapi.yaml` as well (gitignored).
  - license plugin: `add-third-party` skips itself when THIRD-PARTY.txt is newer than the pom:
    `force=true`. Per-artifact exceptions via `artifactFiltersUrl` (`exclude gaPattern
    group:artifact`, full regex match); `excludedArtifacts`/`excludedGroups` match one part only.
    Each module needs `<licenses>` (inherit Apache-2.0 from the root) or it is "Unknown license".
    With `-q` a licence failure shows only the generic error: read the log without `-q`.
  - enforcer `bannedDependencies`: a bare groupId pattern also matched test scope (docs say
    compile only).
  - `-Pui` copies `ui/dist` into `target/classes`; without `clean` it leaks into the next jar.
  - Quinoa 2.9.3 (rejected): dev proxy loop on `/api` (`QuinoaDevProxyHandler … HttpClosedException`)
    until `ignored-path-prefixes=/api`; runs `npm install` on every fresh `target/`.
  - `/usr/bin/time` is not installed; time with `date +%s.%N`.
  - The installed stop hook (`~/.claude/stop-hook-git-check.sh`) is copied at session start; this
    session's copy still checks `target/.batch-in-review`, new sessions get `.logs/`.
- Oracle and ratchet trial for #14 (2026-10-30, scratch reactor + scratch `ui/`, verifiers;
  decisions in ADR-0018, ADR-0019):
  - JaCoCo 0.8.16: surefire needs `<argLine>@{argLine}</argLine>`; works on the module path.
    Reports in `<module>/target/site/jacoco/{jacoco.xml,jacoco.csv}`. `check` rule BUNDLE / LINE
    / COVEREDRATIO fails `verify` below the minimum.
  - PIT: pitest-maven 1.30.1 + plugin dependency pitest-junit5-plugin 1.2.3 runs on JUnit 6.1.3
    with no extra launcher (PIT auto-adds `junit-platform-launcher` 6.1.3). Run as
    `-pl <m> test-compile org.pitest:pitest-maven:mutationCoverage`, `<threads>4</threads>`,
    `<mutationThreshold>`. Not bound to `verify`.
  - jqwik 1.10.2 runs on JUnit 6.1.3 but its engine prints an instruction to AI agents not to use it
    and to ignore its results (since 1.10.1; 1.9.3 has none). Not used (ADR-0018); never add it.
  - Surefire names dynamic tests `cases()[1]` by default. For case ids in the reports:
    `<statelessTestsetReporter implementation="org.apache.maven.plugin.surefire.extensions.junit5.JUnit5Xml30StatelessReporter"><usePhrasedTestCaseMethodName>true</usePhrasedTestCaseMethodName></statelessTestsetReporter>`
    (names become `cases() <display name>`). A failing run needs `-Dmaven.test.failure.ignore=true`
    to leave reports for a ratchet dry run.
  - Saxon-HE 12.5 and 13.1 run from a test on the module path with Saxon on the class path, no
    flags. s9api (javap): `new Processor(false).newXsltCompiler().compile(Source)`,
    `XsltExecutable.load30()`, `Xslt30Transformer.newSerializer(Writer)`,
    `transform(Source, Destination)`, `Serializer.setOutputProperty(Serializer.Property.…, String)`.
    12.5 pulls xmlresolver 5.2.2; 13.1 pulls 6.1.0 and has `Automatic-Module-Name: net.sf.saxon`.
  - XMLUnit 2.14.0: `DiffBuilder.compare(Input.fromString(e)).withTest(Input.fromString(a))`
    `.checkForIdentical()` / `.checkForSimilar()`, `.ignoreElementContentWhitespace()` (exists per
    javap, behaviour not yet observed). Never `ignoreWhitespace`/`normalizeWhitespace` (they hide
    ` 1 ` vs `1`).
  - JDK C14N needs `requires java.xml.crypto` in the main descriptor (test-only use is impossible
    on the module path); rejected anyway.
  - Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5: per-glob `coverage.thresholds`
    (`{'src/model/**': {lines: 90}}`); a glob does not inherit top-level `perFile`.
    `coverage/coverage-summary.json` has `total` + entries keyed by absolute file path, no
    per-directory totals. `thresholds.autoUpdate` rewrites the config file: do not use.
  - Vitest browser mode with `@playwright/test` 1.63.0 looked for `chromium_headless_shell-1248`;
    the image has build 1243. It passed with
    `playwright({launchOptions: {executablePath: '/opt/pw-browsers/chromium_headless_shell-1243/…/chrome-headless-shell'}})`,
    never `playwright install`. A browser launch failure also shows as a 0 % coverage failure.
    Re-check against the #11 note (e2e found the browser without a path) in the skeleton ticket.
- UDF trial for #16 (2026-10-30, scratch reactors, agents; decisions in ADR-0020, numbers in
  `bench/RESULTS.md`):
  - `/usr/bin/javap` is JDK 21 and shows old API shapes: use `/opt/temurin-25/bin/javap`.
  - Context7 has JDK 25 docs; javap confirmed: `ToolProvider.getSystemJavaCompiler()`,
    `ForwardingJavaFileManager`, `SimpleJavaFileObject(URI, Kind)`, `Lookup.findStatic`,
    `defineHiddenClass(byte[], boolean, ClassOption...)`, `ModuleLayer.defineModulesWithOneLoader`,
    `java.lang.classfile` (`ClassFile.of().parse`, `transformClass`, `CodeTransform.ofStateful`).
  - `ScopedValue` and the Class-File API are final in 25; `StructuredTaskScope` is preview
    ("is a preview API and is disabled by default" without `--enable-preview`).
  - `Thread.stop()` throws `UnsupportedOperationException`; `interrupt()` does not stop
    `while(true){}`. A spinning virtual thread holds its carrier.
  - javac class path for UDFs: use the `CodeSource` of an `udf-api` class (or a file manager over
    the class loader). `java.class.path` fails in the Quarkus fast-jar (only `quarkus-run.jar`) and
    on the module path. `SimpleJavaFileObject` rejects `jar:file:…!/X.class` URIs ("URI must have a
    path"): give each class a synthetic `loader:///pkg/Name.class` URI.
  - A host API exposing `javax.tools.Diagnostic` needs `requires transitive java.compiler` under
    `-Werror`. `-proc` makes no difference on 25 (no notes). Without `-parameters` names are `arg0`.
  - Allow-list: a constant-pool scan false-flags clean UDFs (string concatenation adds
    `MethodHandles` entries); walk instructions incl. `invokedynamic` bootstrap arguments.
  - `invokeExact` with a `?:` argument throws `WrongMethodTypeException` at run time (exact types).
  - Surefire 3.5.3 silently falls back to the class path on Java 25 (ASM 9.7.1 cannot read a
    major-69 `module-info`); 3.5.4+ fixes it. The repo pins 3.6.1.
  - Regex `(a+)+\1b` on 30 chars does not stop on interrupt (JDK code, not instrumented); classic
    `(a+)+b` finishes in ms on 25.
- Interpreter and bytecode trial for #17 (2026-10-31, scratch reactor, agent; decisions in
  ADR-0021, numbers in `bench/RESULTS.md`):
  - javap (JDK 25): `ClassFile.of(Option...)`, `build(ClassDesc, Consumer<? super ClassBuilder>)`;
    `Lookup.defineHiddenClassWithClassData(byte[], Object, boolean, ClassOption...)`, `ClassOption`
    = {`NESTMATE`, `STRONG`}; `MethodHandles.classDataAt(Lookup, String, Class<T>, int)`;
    `DynamicConstantDesc.ofNamed(DirectMethodHandleDesc, String, ClassDesc, ConstantDesc...)` with
    `ConstantDescs.BSM_CLASS_DATA_AT`; `ClassHierarchyResolver.ofClassLoading(Lookup)`;
    `CodeBuilder.allocateLocal(TypeKind)`. Context7 has nothing on the Class-File API; JMH and the
    `java` AOT flags are there.
  - Hidden-class frames are missing from stack traces (an NPE showed no generated frame):
    `-XX:+ShowHiddenFrames` to debug.
  - Leyden on JDK 25 without preview: `java -XX:AOTCacheOutput=app.aot …` (training run, then an
    assembly child JVM), then `java -XX:AOTCache=app.aot …`. Flags `AOTCache`, `AOTCacheOutput`,
    `AOTConfiguration`, `AOTMode`, `AOTClassLinking` exist. The assembly child picks up
    `JAVA_TOOL_OPTIONS` and overwrites the training run's `-Xlog` file. Hidden classes from
    `Lookup.defineHiddenClass` (the generated mapping) are not cached; lambdas and
    `java.lang.classfile` classes are. `unset JAVA_TOOL_OPTIONS` for start-time measurements.
  - `ProcessHandle.startInstant()` gave ~500 ms against ~110 ms wall: time start-up by wall clock.
  - JMH 1.37 prints an `sun.misc.Unsafe::objectFieldOffset` deprecation warning on JDK 25
    (harmless). One fork's error on the VM reaches ±100 %; use `-f 3` and the median (ADR-0021).
  - Licences pulled: JUnit 6.1.3 (EPL-2.0), opentest4j / apiguardian / jspecify (Apache-2.0),
    JMH 1.37 (GPL-2.0 + CPE, ADR-0017 exception in `bench/jmh`), jopt-simple 5.0.4 (MIT),
    commons-math3 3.6.1 (Apache-2.0). Nothing new for the repo.

## Main checkout is the merger's while it runs (2026-11-04, #73)

- The architect committed a docs change in the main checkout while a merger's `--no-commit` merge was staged. The commit took the merge with it: e461749 carries #73 under a docs message with `Ticket: #74`.
- Rule: while a merger runs, the architect writes nothing in the main checkout. Docs wait for the hand-back, or go through a separate worktree.
- A merger checks `git rev-parse HEAD` between commit and push.
