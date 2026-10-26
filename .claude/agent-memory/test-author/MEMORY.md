# test-author — durable facts (seeded at the role split; prune when you add)

- Testkit helpers: FakeRuntime, FakeClock, FakeTimer (`nextDelay()` drives backoffs — never a
  sleep), FakeIds, InMemoryBlobStore, InMemoryMessageStore (unbounded, tests only),
  BlobStoreContract, ComponentContract (9 cases + exact-set self-test; `producerEndpoint(started)` hook for
  addresses assigned at start — testkit test `EphemeralAddressComponent(stopKeepsTheAddressRegistered)`, http contract on
  port 0; the self-test runs `failing(Class)` on two ephemeral configurations too), RecordingComponent,
  GeneratedReadStream, BlockedPlatformThreadDetector / `NoBlockedPlatformThreads`.
- End-to-end reference: flottila-runtime `flow/InvoiceSyncFlowTest` (demo package → FlowDefinition
  → FlowBuilder → FakeRuntime). Reference package: `packages/demo` (flows, fixtures, `*.test.yaml`).
- Every new schema rule needs a negative fixture under `flottila-schemas/src/test/resources/invalid/<kind>/`; every ArchUnit rule a
  fixture. Jackson YAML reads `off`/`yes`/`no` as booleans: quote them in fixtures.
- Two fresh id generators both mint `m-1`: an id-equality assertion can pass by accident — assert the id the component recorded.
- A timing assertion observed against wall time passes for the wrong reason (P0-04 round 1):
  drive the window from the fake clock, assert the probe saw the threshold.
- `mvn -pl <module>` alone fails to resolve sibling 0.1.0-SNAPSHOTs (not installed in ~/.m2): add `-am`
  with `-Dsurefire.failIfNoSpecifiedTests=false`. `-q` hides the counts: read target/surefire-reports/*.txt.
- TieredBlobStore choreography tests (runtime blob/TieredBlobStoreHandoverTest): `GatedKey` (spool key whose
  getEncoded() waits on a latch) holds the sink thread at its open; a WatchService on the spool message dir
  observes the sink storing bytes. Every handed-over stream not paused by the store gets one handover resume().
- RecordingComponent.holdNext(n) → CountDownLatch (send recorded at once, answered after countDown, no caller thread
  waits); awaitSent(n) waits on a latch (10 s bound), awaitSent(n, Duration) with its own bound. Count every hold down
  in @AfterEach. Vert.x 5 `Future.await(long, TimeUnit)` works on any thread (latch), rethrows the cause.
- Surefire: `-Dtest=A+B` selects NO class (`+` joins methods: `A#m1+m2`); with failIfNoSpecifiedTests=false the run
  is a silent green with stale reports. Use `-Dtest=A,B` and check the surefire-reports mtime.
- core flow tests without XML: `BuiltInStepsTest.HEADERS` (header expression language, package-visible). Core's
  `flow/HoldingMessageStore` (test tree) holds / releases / fails `record` futures, `throwNext`, `failReads`/`readsOk`,
  `recorded(stepName)`; `flow/FailingDlq` refuses parks (`failParks`/`parksOk`).
- `System.Logger` in core tests resolves to JUL: a `java.util.logging.Handler` on `Logger.getLogger(X.class.getName())`
  sees the records. Acting inside `publish` (it runs on the logging thread, synchronously) pins "X happened before
  the log line" without a race.
- A red upstream test tree blocks `-am` before the module under test compiles: `-fn` (fail never) lets the reactor reach it.
- ScriptedStream records "resume" before its hooks run, so the call order is not the effective state: a pause issued in
  `beforeResume` is undone by that resume. Assert the end state through the private `paused` field (enclosing class sees it).
  A script chunk delivered inside handler() starts the sink before the attach: whether the handover resume is issued races.
- Testkit streams' drain window (FIX-12): `DrainHandoverTest.Window` stages a call from a second thread at the package-private
  `beforeDrainExit(Runnable)` hook of Generated/ChunkedReadStream; `Kind` enum parameterises both streams (one-byte chunks "1".."n").
  `handOff(timeoutMsg, Runnable)` there runs a call on a second platform thread from inside a handler (detects handler-under-lock).
  Window records "<name> returned" on the other thread in `events`: a whole-list assertion must include it.
- Wrapper sources in TieredBlobStore tests: tell the sink's resume from start()'s handover resume by the thread that called
  handler() (capture it there), never by wrapper state set in pause() — the sink can resume before pause() is entered (FIX-11).
- Classpath for scratch checks: `mvn -o -q -pl flottila-runtime -am compile dependency:build-classpath -Dmdep.outputFile=target/x.txt
  -Dmdep.includeScope=test` (without `compile` the sibling SNAPSHOTs do not resolve). Launcher jar: junit-platform-launcher 1.14.4.
- TieredBlobStore streaming write's sink-thread seam `afterSinkDecision(Runnable)` (FIX-10) + `HeldKey` (Backpressure test: spool key
  gated at getEncoded, `reached` latch, optional refusal) stage sink/attach orderings. A sink open failing at the cipher leaves a `.part`.
  Same class: `HeldStream(inner, Set<Integer>)` holds the Nth store pause (counted after handler()) via `pauseEntered(n)`/`pauseMayReturn(n)`
  (pre-open mayReturn = observe only), counts `delivered`; `holdAfterChunk(n)` (chunkDelivered/chunkMayReturn: delivering thread held
  after the store's handler returned for chunk n — a sync source's hand-over resume stays in flight); wrappers' latches opened via
  the test's `wrappers` list (holds armed after construction included); `OwnThreadStream` delivers on its own virtual thread, holds the attach in handler().
  OwnThreadStream (FIX-10 r5): `holdPause(n)` (pauseEntered/pauseMayTakeEffect, before effect), `holdPauseAfterEffect(n)`
  (pauseTookEffect/pauseMayReturn), n = call count over every thread; `nextHold()` opens when the source thread next reaches any
  hold. Events: pause recorded at the call, resume at its effect (under the fixture lock). Also: `holdResume(n)` (n-th resume any thread, held before it takes effect: resumeEntered/resumeMayTakeEffect),
  `holdResumeAfterEffect(n)` (resumeTookEffect/resumeMayReturn: caller held inside resume() after the effect — start() not yet reconciled),
  `holdBeforeChunk(n)` (heldBeforeChunk/chunkMayGo, before the paused check), `failNow`, `isPaused`, `delivered`, `stalled` (first paused wait
  or loop exit — a sync point valid whether or not the source stayed paused), `close()` (paused → leaves; unreleased chunk hold → leaves;
  unpaused → runs to its end, so "delivered after" stays deterministic). Test's `sources` list: closed + `latches()` opened in @AfterEach.
  `Attach` and `settled` are package-private (FailureTest D7 reuses them). The sink drains concurrently: a peak assertion needs the key gate
  shut until the source has stalled.
- A pause a synchronous source issues inside a resume (same thread, nested) is NOT undone by that resume: a "pause during hand-over"
  mark must exclude the hand-over thread, else R1/R2/R4/D7 see an extra pause (checked on a scratch reference, FIX-10 r4).
- `afterSinkDecision` runs once per polled chunk (not on empty waits): with the Backpressure store (spill at chunk 1, empty
  prefix, 60-byte chunks, mark 100) call n = chunk n polled; the sink decides only at queued < 50, i.e. at even chunks.
- Scratch `out` dirs keep stale classes (a stale testkit GeneratedReadStream gave NoSuchMethodError): always a fresh output dir.
  `pgrep -f <pattern>` inside a Bash call matches the calling shell itself — not proof another Maven runs.
- A "next hold" latch must be consumed before the hold signals `reached`: a test that observes `reached` of hold X and arms the latch afterwards expects the hold after X; signalling first leaves a window where X opens it (FIX-10 r9, R12 1/30 under load, `OwnThreadStream.block()`).
- A guard in front of a mutated call (`x == null ? null : check(x)`) lets the mutant survive a test that only exercises
  the null branch (FIX-8 r2): test against the whole expression, or drop a redundant guard so the check stands alone.
- A delete that must fail (root-safe, any OS): make the path a non-empty directory → `Files.deleteIfExists` throws
  `DirectoryNotEmptyException` (FIX-14 r2). `SpoolCipher.encrypting(Path, OutputStream)` is the test seam for the open path.
- A join or latch timeout in a fixture names where the waited thread sits (its stack) — a bare "(not within 10 s)" cost a diagnosis round (FIX-10 r9, `Attach.joined`).
- Staging thread states without sleeps (FIX-2): capture the pool thread from the task body, spin `onSpinWait` on `getState()`; for
  WAITING/TIMED_WAITING also `LockSupport.getBlocker(t) != null` (parked, not transient). A thread that must stay blocked across
  `shutdownNow()` (an interrupt) waits on a `synchronized` monitor entry (BLOCKED), never a latch. `Future.get` returns before `afterExecute`.
- A nanos time source built from `epochSecond * 1e9 + nano` is unpinned unless a measured interval crosses a whole second
  (FIX-2 r2): start the FakeClock at `.850Z`. Mutant runner for a scratch copy: JUnit `LauncherFactory` + `SummaryGeneratingListener`.
- Testcontainers-postgresql 2.0.5 `getJdbcUrl()` ends with `?loggerLevel=OFF` (db name is NOT the URL's tail). A database per case
  (FIX-5 `MigrationsIT`): admin JDBC `CREATE DATABASE`, swap the path segment before `?`. Failsafe honours `-Dit.test=<Class>`.
- XML resolution (FIX-6, JDK 25 / Saxon-HE 12.4): the JDK schema parser's refusal message names only the file part of a remote
  URL ('x.xsd', not the URL); `ACCESS_EXTERNAL_SCHEMA=""` still loads inputs an LSResourceResolver supplies. A Saxon
  ResourceResolver's XPathException message is the SaxonApiException's message at compile(). `Path.toUri()` of a NON-existing
  dir has no trailing slash, so `dirUri.resolve("a")` lands in the parent. Probes: java single-file source + ~/.m2 jars.
  With ACCESS_EXTERNAL_SCHEMA="" a refused import/include/DTD entity is a parser ERROR (stops at the first; a later reference
  never reaches the resolver), not a warning. Saxon-HE 12.4 XsltCompiler.setResourceResolver does NOT cover a stylesheet's
  DTD external entity, static unparsed-text()/doc() or use-when: all read files at compile time (FIX-6 r3). Counting
  loopback `com.sun.net.httpserver.HttpServer` on 127.0.0.1:0 pins "never fetched" (JAVA_TOOL_OPTIONS nonProxyHosts has 127.*).
  Scratch mutant runs of a module test class: relative `../../packages/demo` paths fail outside the module dir (6 cases).
- Saxon-HE 12.4 (FIX-6 r4, probed): the default Configuration resolver (CatalogResourceResolver) answers NULL for a plain file:
  URI (also via `ResourceRequest.resolve`). A static/use-when `collection()` in XSLT throws ClassCastException (PackageData →
  StylesheetPackage, CollectionFn.call) before any CollectionFinder; `uri-collection('..')` lists the directory. A failing static
  `unparsed-text()` stops further static vars; `unparsed-text-available()` swallows and compilation continues.
- Worker fixtures (core flow test tree, P1-08b): `RunGate` (step `gate`: holds each run until `Run.release()`, records thread → `joined()`,
  `overlaps()` per message id, `peakActive()`, `open()`), `RecordingWorkQueue` (decorates InMemoryWorkQueue: claims with max/in-flight/
  running probe, `holdNext`/`releaseHeld`, `failClaims`, `answerRenews`/`failRenews`, renews/holds/completes; one-shot `failNextClaim`,
  `throwOnNextClaim` (sync throw), `failCompletes(n,c)`, `failHolds(n,c)`, `answerHolds(Boolean)`). `FlowWorkerTest.World` = per case/try.
- Mutating a tree production class without touching the tree: copy it to scratch, `javac -d m/out -cp core/target/classes:<cp>`, run the
  launcher with `m/out` ahead of core target/classes + test-classes (a class earlier on the classpath shadows the tree's).
- jqwik in a Jupiter class: @BeforeEach/@AfterEach do not run for @Property and instances may be shared — build all state inside the
  property method; a class-level @Timeout only draws a jqwik warning. `frequencyOf(Tuple.of(n, Arbitraries.just(new R())))` infers R:
  declare each as `Arbitrary<Iface>` first.
- FakeTimer runs due tasks on the thread calling `advance`: after an advance wait `until(renews == k && timer.pending() == 1)` (spin, no sleep).
- Docker works: a Testcontainers IT runs in scratch (runtime cp as above; `mvn -o` may fail on import BOMs: drop -o); an extra `db/migration`
  dir on the cp adds a scratch migration. Ryuk/port-mapping "failed to set up container networking" (500) is a docker flake: rerun.
- Stop/cancel fixtures (P1-08c): core flow test tree `HeldPipeline` (pipeline/step holding each run in `Future.await()` until
  `Run.reply/fail`; `awaiting()`, `awaitingThenThrowing(..)`, `stubborn(replies)` keeps the interrupt flag); testkit src/test `ConsumerDrain`
  (draining stop for fixture components). A draining `stop()` hangs any contract case that leaves a pending answer: complete it in `finally`.
- Never name a test fixture after a production class (core `dlq.Parking` exists). Scratch reference: compile only the copied files into a
  fresh out dir (a stale `ref/` with old sources gave bogus constructor errors). `-Dmaven.compiler.failOnError=false` with `-am` shows a
  downstream module's test compile errors when an upstream test tree does not compile (exit 0: read the ERROR lines).
- Pg lock-order (deadlock) staging without sleeps (P1-09a, PgMessageStoreIT): a holder tx keeps an uncommitted payloads row (plug batch
  waits → BatchingWriter's first save with inFlight 0 goes alone, so the next `batchSize` saves form one batch) and one middle key row;
  poll `pg_stat_activity where wait_event_type='Lock'` for 1/2/3 waiters, then roll back. Unsorted inserts → 40P01, observed in scratch.
- Admin routes: runtime `admin/AdminServerTest` (HttpClientAgent; store fakes record the calling thread; `RecordingStore.hold()`). Scratch
  runtime reference: out-rt + scratch `db/migration` dir ahead of flottila-runtime/target/classes (V1..V4); the cp file lists deps only.
- Rare hang (FIX-21): scratch Launcher loop, 4 JVMs, watchdog `jcmd <pid> Thread.dump_to_file` (shows virtual threads). HttpProducer's
  unpaused reply can end before reply() listens (~1/2000 sends under load); deterministic pin: http `HttpComponentTest.LateListeningStore`.
- P1-09b: testkit test `ConsumerDrain.deliver` (track, then handler on own virtual thread); runtime test `node/OnVirtualThread`, `node/PlainComponent`; ArchUnit DoNotIncludeTests treats a scratch out dir as production.
- Interleaving "send returned, handler thread not yet run": `HeldCarriers.hold()` (testkit + mock src/test) spins P virtual threads; 20/20 deterministic.
- Process tests (P1-09d3 `MainProcessIT`): child cp `surefire.test.class.path`, JaCoCo agent from the test JVM's `-javaagent` arg, env cleared
  (JAVA_TOOL_OPTIONS = stderr line); SIGTERM exit 143; "main returned" = jcmd Thread.print has DestroyJavaVM, no "main"; closed-vs-died: pg debug1 "unexpected EOF" (no Terminate).
