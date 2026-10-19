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
