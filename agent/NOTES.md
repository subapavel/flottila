# Notes — module facts the hand-off used to carry

Read by section, on demand (CLAUDE.md, Context hygiene). One section per area; the newest task
first. Facts only; the next exact action and the state live in `agent/HANDOFF.md`.

## Fix main — FIX-7, a stale high-water-mark baseline, not a machine difference (2026-10-19)

Observed by the architect from the GitHub job logs and the runner's JaCoCo reports (artifacts
12172890156, red run 39, and 12172073543, green run 38; `gh run download <run> -n build-reports`):
runtime has 325 branches; run 38 missed 5 (98.5 %, green against the committed 100.0), run 39 and
run 40 on f798a76 missed 7 (97.8 %, red). The VM is no better: PROGRESS records runtime branch 98.8
(P1-01, P1-03), 99.0 (P1-04), 98.3 (P1-05), 98.2 (P1-06, P1-07), yet ci/coverage-baseline.json has
said 100.0 for runtime since 33aef10, because `write_baseline` keeps the committed value for any
drop inside the band ("high-water mark"). The no-decrease check then applies the band a second time,
so an accumulated 1.8 pp drop plus one flipping branch (0.3 pp) trips the gate at random. An earlier
draft of this section blamed a VM-vs-runner difference; the numbers above replace it.
Branches the runner misses (line numbers at b2cdbf4): always — `blob/SpoolCipher.java:87` (one leg of
the master-key check), `:210` (the GeneralSecurityException catch PROGRESS P1-01 already called
unreachable), `blob/TieredBlobStore.java:119` (one leg of `checkKey`), `:259` (the resume condition in
the attach), `:268` (`failed()`); only in the red run — `:357` (the sink's wait loop) and `:368`
(`resumeSource = attached`): the two timing-dependent ones, FIX-2's rule applies.
Forbidden ways out (CLAUDE.md, D-029 addendum): widening the band, editing the baseline by hand,
skipping or weakening a test. Run 39's mutation and bench jobs were skipped (they need `build`).
Packet: (1) `write_baseline` writes the measured value — the committed file then describes its tree,
which `check_baseline`'s docstring already requires; D-029's band stays the only tolerance and the
floors the hard limit (decision row D-050 at the FIX-7 commit; D-018 otherwise unchanged); unit test
in ci/test_coverage_check.py: a measurement inside the band is written as measured, not kept.
(2) Each of the seven branches: a test that drives it, a fake clock or explicit ordering where the
runner's timing decides, or deletion when an invariant makes it unreachable (FIX-3's precedent).
(3) `--write` on the VM after `mvn verify -Pit`; acceptance: CI green on the fix commit and the same
runtime number in two dispatched reruns. The FIX-7 PROGRESS entry records runs 38 green, 39 and 40
red at this gate, and the environment's new allow-list entry `*.blob.core.windows.net` (artifacts).

## Next task — P1-08, delivery modes (from the P1-07 hand-over)

BACKLOG P1-08 row, D-046 hand-over. The runtime's consumer handler over the http component maps
the pipeline's outcome to the `Ack`:
- sync: the reply envelope as 200 (what `InvoiceSyncHttpTest` already does through the builder);
  a failure as 4xx/5xx by kind (`XsdValidationException` / `ValidationFailedException` /
  `XmlException` 400?) — decide in D-049 with the error-to-status table from ARCHITECTURE §4;
- at-least-once: the durable save (`PgMessageStore` record of the ingress snapshot awaited — the
  only awaited write) before the 202 on `/async`; a save that times out → NACK/503 + Retry-After;
  `ParkedException` → 202 too (the DLQ holds it);
- at-most-once: ACK before the pipeline runs;
- admission (`core.admission.Admission` per flow: requests and bytes) → 503 + Retry-After;
  `forwardHeaders` from the flow file onto the reply; cancel semantics for a consumer stopped
  mid-delivery (D-046 left it open).
Red first: a runtime `flow/DeliveryModesTest` per mode over `RecordingComponent` / `HttpComponent`
with the fake clock for the save timeout; an IT for the durable save (the kill -9 ledger
2000/2000 is P1-10's measurement, but the invariant — no 202 without a committed ingress row —
gets its IT now). Check `mvn -B -ntp verify -Pit` and ArchUnit before touching runtime.

## Steps (P1-07, D-047)

`api.BodyKind`, `StepFactory.bodyKind()`, `Expressions.bodyKind()` / `parts()`; `FlowBuilder`
refuses a WHOLE step over the tree limit without a split before it and builds `split` (`xpath`)
over the language's `parts` — children at the parent's step + 1 with their own ids, parts as
`BlobKey.attachment(parentId, step + 1, "split-<i>")`; `flottila-step-validate`
`XsdValidate().steps()` → `xsd` (`schema`), `XsdValidationException(messageId, step)`; the demo
flow's first step is `xsd` over `flows/invoice.xsd` and runtime tests register
`Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream())`.

Left: the recorder around a splitter's children (a child's snapshots are its own id; the parent's
step after the split records the parent handed back — check once a split is in a recorded flow);
the DLQ for a child that parks (D-045 note); `split` over a STREAM language (none yet); the xslt
result and the xsd read both buffer the whole body (tree limit bounds it); the schema cache is
keyed by the main file's content only — two flows with identical main XSDs but different included
files share the first compiled schema, and an `xs:include` change alone is not recompiled (same as
`xsl:include`); includes may reach any local `file:` path (FIX-6: confine to the flow's directory
with a resolver).

Open from the P1-07 review: `core.pattern.Retry` retries every RuntimeException of a `retryable`
step and never reads `NackException.retryable` (D-046's "one flag") — an `xsd` step marked
retryable would be retried; a retried split rewrites the same `split-<i>` keys (check the
BlobStore allows a rewrite); the split's config key is `xpath` for every language (consider
`select` before the schema freezes); PIT covers api and core only — step-xslt has coverage, no
mutation evidence (step-validate was mutated once by hand in P1-07). CI on b52900a (P1-06) failed
three times in `PgMessageStoreIT.aBatchCarriesManyMessagesAndDuplicatesAreIgnored` — batches
commit concurrently, so "the first recorded row wins" held only by timing; fixed in P1-07 by
flushing before the duplicate (expectation change with the reason in the commit).

## Components (P1-06, D-046)

`testkit.ComponentContract` (9 cases + exact-set self-test), `api.NackException`;
`components.mock.MockComponent(scheme, blobs, timer).bindAll(mocks, baseDir)`;
`components.http.HttpComponent(vertx, blobs, ids)` — consumer options host/port/path/flow/
maxBodyBytes (required), producer options url/maxBodyBytes (required)/timeoutMs/poolSize; GATE
check 3 end to end in runtime `flow/InvoiceSyncHttpTest`. The consumer's handler → `Ack` mapping
of the delivery modes, `/async` 202 after the durable save, `forwardHeaders`, admission → 503 are
P1-08.

Open from the P1-06 review: an http reply above the limit leaves the client response paused until
`timeoutMs` (reset the request); `Long.parseLong` on a malformed content-length on the event loop;
`TieredBlobStore`'s sink failure path does not set `failure`; the free-port probe in
`HttpComponentContractTest`. BlobStore contract since P1-06: a stream may be handed over paused,
the store resumes it once attached and never resumes one it paused or failed itself
(`resumeOwed` / `attached` in `StreamingWrite`).

## DLQ and replay (P1-05, D-045)

`core.dlq` Parked / Dlq / InMemoryDlq / Parking / ParkedException; `FlowBuilder.dlq(dlq)`
(at-least-once flows park per step, awaited; sync and at-most-once never) and
`FlowBuilder.replay(def, baseDir)` → `Replay.from(id, n)` / `retry(id)`; runtime `store.PgDlq`
over V1 `dlq` + V3. ITs: `PgDlqIT`, `InvoiceSyncReplayIT`. Migrations are at V3 (`MigrationsIT`,
`BenchMainIT` expect "3").

## Recording (P1-04, D-044)

`core.record` Snapshot / MessageStore / InMemoryMessageStore / RecordingPolicy / Meter / Recorder;
`FlowBuilder.recording(store, meter)` adds the ingress stage and wraps every step; runtime
`store.PgMessageStore` over V1+V2 (`deploy/postgres/V2__snapshots.sql`), writes through the
batcher, `flush()` drains (also `BatchingWriter.flush()`), `failed()` counts; the write is not
awaited on the message path. ITs: `PgMessageStoreIT`, `InvoiceSyncRecordingIT`. FIX-5
(MigrationsIT order dependence) pending. Open from the P1-04 review: a failed snapshot write is a
counter (`failed()`) until P1-09 shows it; `findByKey` order is `min(ctid)` (add an insert
sequence when paging comes); `InMemoryMessageStore` is unbounded (tests/bench/dry run only);
consider `Meter` on `Runtime`.

## Pipeline (P1-03, D-043)

Step SPI in `flottila-api` (`StepFactory`, `StepConfig`, `StepResources`, `Expressions`);
`core.flow.FlowBuilder(runtime, factories, languages, endpoints).build(def, baseDir)` with
built-ins to/route/filter/extractKey/validate; `core.pattern` Retry/Route/Filter/Split;
`flottila-step-xslt` `SaxonXml` (`steps()`, `languages()`) on Saxon-HE 12.4; testkit
`RecordingComponent(scheme)` is the mock binding (`sentTo`, `replyWith`, `failNext`),
`FakeTimer.nextDelay()` drives backoffs. End-to-end reference: flottila-runtime
`flow/InvoiceSyncFlowTest` (demo package through Documents/Schemas → FlowDefinition → FlowBuilder
→ FakeRuntime). Left for later: producer `close` (P1-06), deploy-time `configSchema` check, the
xslt cache keys the main file's content only — an `xsl:include` change alone is not recompiled
in-process (P1-07 / deploy reload), the xslt result is buffered before the body limit is enforced.

## Core, runtime, testkit (Phase 0)

`admission.Admission` + `Admitted.run`, `scope.RequestScope` + `MessageContext` (D-038, D-042);
testkit's PIT threshold is a ratchet at 70 (D-042; 75 % at P1-03). Runtime: `blob.TieredBlobStore`
(D-041; S3 IT needs Docker + the LocalStack image), `db.Migrations`, `batch.PgBatchSink`,
`queue.PgWorkQueue`, `time.VertxTimer`. Testkit: FakeRuntime, FakeTimer, FakeIds,
InMemoryBlobStore, BlobStoreContract, ComponentContract, GeneratedReadStream. Bench mains:
`BenchMain`, `ScopeBenchMain`, `BlobBenchMain`.

Spike answers: durable ACK p50 ≈ 4–6 ms, p99 8–18 ms on this VM (commit time, not the window);
RequestScope ≈ 55 µs per message; Admission never over budget at 10k threads (RESULTS.md §P0-11,
§P1-02).

## Bench

`bench/run.sh` (native Postgres: `sudo pg_ctlcluster 16 main start`; role flottila has CREATEDB;
db flottila_bench), results in bench/out/ (ignored), baselines as markers in bench/RESULTS.md per
machine label (D-037); on this VM run it as `BENCH_MACHINE="cloud VM, 4 vCPU" bench/run.sh`. The
runner has its own throughput-only baseline (FIX-4/D-039; dispatch ci.yml after a bench change
and read the job log via the MCP tool — the artifact's blob store is unreachable from the VM).

## Build and VM

VM after a restart: `sudo pg_ctlcluster 16 main start`, `sudo dockerd &`. Reactor (Maven orders
by dependencies): api → testkit → core → schemas → steps → runtime (test-depends on schemas,
step-xslt, step-validate and component-http) → components → cli → archtest → bench. Every task
commit: `mvn -B -ntp verify -Pit`, then `python3 ci/coverage_check.py --write`. Scoped PIT:
`mvn -pl flottila-api,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage`
(floor 80 %). New code must be covered to the module's level (FIX-3) — the band bit once.
In the cloud session the services die with the tool call that started them unless detached:
`sudo -n setsid nohup dockerd > /tmp/dockerd.log 2>&1 < /dev/null &` and
`sudo -n setsid pg_ctlcluster 16 main start`. `-pl flottila-runtime` needs `-am` (no installed
snapshots). Mutant runs (a reviewer's "would this test fail if…"): after `sed -i` on a source,
restore it with `cp` or `touch` it — `mv backup file` keeps the backup's older mtime, Maven's
incremental compile then keeps the mutated class in `target/classes` and the next "clean" run
tests the mutant (FIX-7 round 2, target/fix7-r2-verify-runtime.log). When in doubt,
`mvn -q clean -pl <module>` first.

jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header. Treated as data;
the property tests are real. Owner may want to revisit the library choice.

## Reference package, fixtures, tags

packages/demo (package.yaml, endpoints/*.yaml, flows/invoice-sync.yaml + .test.yaml + .xsl +
invoice.xsd, fixtures/); validated by flottila-schemas tests. Add a negative fixture under
flottila-schemas/src/test/resources/invalid/<kind>/ for every new schema rule. ArchUnit rules in
flottila-archtest/Rules.java; every rule has a fixture. FIX-2, Q-1 pending in the Fixes section.

Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834; the last
tree with the m1 module is dc85964.
