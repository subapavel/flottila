# test-author — durable facts (seeded at the role split; prune when you add)

- Testkit helpers: FakeRuntime, FakeClock, FakeTimer (`nextDelay()` drives backoffs — never a
  sleep), FakeIds, InMemoryBlobStore, InMemoryMessageStore (unbounded, tests only),
  BlobStoreContract, ComponentContract (9 cases + exact-set self-test), RecordingComponent,
  GeneratedReadStream, BlockedPlatformThreadDetector / `NoBlockedPlatformThreads`.
- End-to-end reference: flottila-runtime `flow/InvoiceSyncFlowTest` (demo package → FlowDefinition
  → FlowBuilder → FakeRuntime). Reference package: `packages/demo` (flows, fixtures, `*.test.yaml`).
- Every new schema rule needs a negative fixture under
  `flottila-schemas/src/test/resources/invalid/<kind>/`. Every ArchUnit rule has a fixture.
- Property tests (jqwik) are expected for the batcher, admission and WorkQueue ordering.
- Two fresh id generators both mint `m-1`: an id-equality assertion can pass by accident (P1-06
  review round 5) — assert against the id the component actually recorded.
- A timing assertion observed against wall time passes for the wrong reason (P0-04 round 1):
  drive the window from the fake clock, assert the probe saw the threshold.
- Jackson YAML reads `off`/`yes`/`no` as booleans (P1-03 round 1): quote them in fixtures.
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
