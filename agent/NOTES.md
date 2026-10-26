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

### FIX-11 — D7's fixture depends on the interleaving (2026-10-19)
`TieredBlobStoreFailureTest.LatePauseStream` (FIX-7 D7) opens its latch only on a resume that arrives after
`pause()` has set `delayed`. The sink can resume between `enqueue` releasing the monitor (`TieredBlobStore`
line 310) and the wrapper entering `pause()`: then `GeneratedReadStream.resume` is a no-op (`draining` is
true on the attach thread), the latch never opens and the case fails after its 10 s bound — once on the
2-vCPU runner (run 39253266191), never on the VM (45 loops, 15 of them under stress-ng on 4 cores). For this
ordering the product's re-check (lines 313–318) is right: it resumes the source after the pause has landed.
Two neighbours found by the FIX-11 review, not fixed there: the sink's own resume in `drain` (367–373) was
issued after the lock — a late one could undo the source's next pause, `sourcePaused` stayed true and `enqueue`
did not pause again until the sink had drained below half the mark: with a source faster than the sink the
queue grew up to the body limit (FIX-10, fixed the same day — see below; D7 and the FIX-11 case pinned the
double resume and were rewritten / deleted there);
and the testkit's `GeneratedReadStream.drain` could lose a resume that lands between its loop-condition read of
`paused` and `draining = false` (FIX-12, fixed the same day — see below). Rule for fixtures that stage a race:
the signal must be accepted on either side of the window, and the "other" ordering needs its own
deterministic case.

### FIX-12 — the testkit streams' drain hand-over (2026-10-19)
`GeneratedReadStream` and `ChunkedReadStream` drain with one pattern: one lock guards the flags only
(`paused`, `credit`, `draining`, `rerun`, the handlers); a `drain()` that finds a drain running sets `rerun`
and returns; the drainer delivers with the lock released, runs the end / failure check, the package-private
test hook `beforeDrainExit(Runnable)`, then decides under the lock in one step — `rerun` → round again, else
`draining = false`. Invariant `!draining ⇒ !rerun`. A handler that throws clears both flags in the catch
(a `finally` would clear a `draining` another thread had set since) and is rethrown on the draining thread.
`DrainHandoverTest` stages the window with the hook: the draining thread waits (10 s latch, no sleep) until
the other thread's `resume()` / `fetch()` / `endHandler()` / `handler(null)` has returned — the template for
any fixture that must prove a cross-thread hand-over; cases G/H prove handlers never run under the lock by
handing `pause()` + `delivered()` to another thread from inside them. The reviews named the mutants
(old gating, rerun ignored, hook before the end check or the end call, handler / end / hook under the lock,
catch's `draining = false`, `credit--`); the PROGRESS entry FIX-12 records which case each one failed.

### FIX-10 — one party resumes a pause of the streaming write's source (2026-10-19)
`TieredBlobStore.StreamingWrite`: `pausing` is true while the source is inside a pause of ours — from `enqueue`
marking it paused until that pause's re-check (after `in.pause()` returned) has run. Such a pause is resumed by
its re-check only: the sink resumes `attached && !pausing`, `start()` resumes `resumeOwed && !sourcePaused &&
!pausing` (pre-attach, for the handover or a pause whose re-check already ran and left `resumeOwed`), the
re-check resumes `!sourcePaused && failure == null` (the sink's decision, or nobody before the attach, overtook
the pause; never after a failure). Every pause in effect gets exactly one resume (the pending re-check's re-issue —
`again` — may add an idempotent pause call, R16: assert event order there, not counts); the sink's resume, issued after the
monitor, can never undo the source's next pause. Every resume is issued *after* the monitor that decided it, so
each has a post-check: `if (failed()) in.pause()` — a failure's pause that landed in between is re-issued (never
consume past a failure). The hand-over resume of `start()` is the one resume issued while the source may already
be flowing (`!sourcePaused`), so during it only the hand-over thread resumes the source: `handingOver` (the hand-over
thread) is set under the monitor around it; a pause of ours issued meanwhile on another thread goes out at once
(our resume may undo it) and is marked `pausedDuringHandover`; a resume the sink decides meanwhile (a second
resume for the pause — and it could land after the reconcile and undo the source's next pause, review 4) is left
to the hand-over thread (`resumeDeferred`); a re-check wanting a resume meanwhile on another thread issues none
(its pause is marked); a failure's pause (`fail()`, the sink's catch) goes out at once and `start()`'s post-check
re-issues it. After its resume returned the hand-over thread reconciles: a marked pause → the pause once more
through `pauseAndReCheck()`, whose re-check decides (`!sourcePaused && failure == null` — never a `sourcePaused`
read in the reconcile: "the sink cleared it" is "decided", not "resumed"); a deferred resume with no marked pause
→ the source is paused by a pause of ours the resume did not undo (one issued on the hand-over thread inside it)
→ resumed plainly, with the failure post-check (re-issuing a pause there made one resume for two pause calls —
the verifier's loop caught it in D7); a pending re-check (`pausing`) reconciles both itself (`again`). A call on
the hand-over thread itself (a
synchronous source pausing and re-checking inside the resume) is nested in it and stays direct (marking it adds
a duplicate pause that R1/R2/R4/D7 catch). One `pausing`
at a time, checked at every setter: `enqueue` finding one in flight (`start()`'s re-issue) marks `sourcePaused` and
issues no pause — the pause in flight takes effect for both and its re-check leaves the resume to the sink. `resumeOwed` is read by `start()` only — meaningful until the attach, so the sink does not write it.
Failures: the first one recorded under the monitor (the source's in `fail()`, the sink's in `drain`'s catch) is
the cause the future fails with; a later sink failure rides as suppressed; a later source failure is dropped
(`fail()` returns on `over()`). Rule: decision under the monitor, call after it — ask what lands in between.
Test seam `afterSinkDecision(Runnable)` on the store: the sink thread runs it after each polling iteration's
decision block and before the resume it may issue (not on the failure-break iteration) — with a `HeldKey` (the
spool key's `getEncoded()` held at a gate, so the sink cannot open until the test says), a stream wrapper holding
the attach thread inside chosen pauses and an own-thread source fixture, every ordering of the hand-over is
staged (`TieredBlobStoreBackpressureTest` R1–R16, the rewritten D7). Rules: a fixture that waits for "the sink's
resume" before its pause lands pins the double resume — wait for the sink's decision (the seam) instead; test a
pause after the first one, and the attach and failure paths of the re-check.

## Delivery modes (P1-08, D-049)

`core.flow.Delivery` is the consumer handler a flow gets from `FlowBuilder.delivery(flow, baseDir)`
(`recording`, `queue`, `dlq` set first for at-least-once; an ISE names the missing one). It holds
what the builder hands it, never the `Runtime`. Per mode: sync runs the pipeline on the caller's
thread (the http consumer's virtual thread) and replies `Ack.Reply`, a failure fails the future
with the step's exception; at-least-once records the ingress snapshot with its payload whatever
the recording level says (F-2), then enqueues (partition key null in Phase 1), then `Accepted` —
`delivery.saveTimeout` (default 5 s, non-positive refused) on the `Runtime` timer gives a
retryable `Nack`, a late save enqueues nothing, a timeout between the two writes leaves an item
the worker runs (D-049 b: the sender's retry is the duplicate); at-most-once is `Accepted` before
the pipeline runs on its own virtual thread, a failure is logged and dropped. Admission per
`Delivery` from `limits` (defaults cores × 2 and the request limit × `maxBodyBytes`); a refusal
is a retryable `Nack`; the permit covers the save (at-least-once) or the run. `FlowWorker` (`FlowBuilder.worker(flow, baseDir, workerId[, WorkerPolicy])`, D-055): `poll()` claims
`max − running` with at most one claim in flight (a poll during a claim shares one follow-up round,
also after a failed or throwing claim); `start()` ticks every `pollInterval`, `stop()` ends ticks;
`pollingAfterAccept` polls on `Accepted`; each finished item polls once. Per running item the lease
is renewed every lease/3, fenced by the claim's `attempts`. Outcomes: success, a park, or a ghost
(WARNING) → complete; any other failure → WARNING, left to its lease (NOTES used to say "every
outcome" — wrong since D-049 c). Claimed with `attempts > maxAttempts` → parked at step 1 with
`AttemptsExhaustedException`; park or timeline read fails → `hold` + one ERROR (held rows block their
key; release is a P1-09 route). Open: FIX-19 (byte budget), FIX-20 (re-claim of an own running
seq). Nothing polls in production yet: wiring consumers + workers into deployed flows is P1-09's
deploy endpoint. Faults: api `Fault.kind()` → http `answer` 400 / 422 / 502 / 503 + Retry-After /
500, body `text/plain` = `getMessage()`. `forwardHeaders` is applied in the `to` stage: the
producer gets exactly the listed headers plus `content-type`, whatever set them; steps see every
ingress header. Open for the owner: a reply-direction allow-list; refusing `payload: metadata-only`
for at-least-once at load time. Tests: core `DeliveryTest` (Ack level, with `HoldingMessageStore`),
runtime `DeliveryModesTest` (http level), `DeliveryModesIT` (Postgres), http `HttpConsumerStatusTest`.

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
`xsl:include`) — since FIX-6 also a confinement gap (BACKLOG FIX-17: key by directory + hash).

FIX-6 (D-052): every file a step reads at build time stays inside the flow's directory.
`StepResources.file` confines (absolute, normalised, `startsWith(root)`; `../x`, `/abs/x` refused
naming the reference and the directory) and `StepResources.resolve(baseUri, reference)` does the
same for a reference relative to an including file (non-`file:` → "is not a file in the flow's
directory"; a base without a trailing slash — `Path.toUri()` of a missing directory — gets one).
xsd: `ConfinedSchemaResolver` (`LSResourceResolver`) feeds the parser through `resolve` as a
`SchemaInput` (our own read-only `LSInput`: stream + system id, the rest null — Xerces reads a
character stream or string data first; no DOM-LS lookup: its unreachable catch cost validate 2.5 pp
of line coverage, beyond D-050's band; a stream handed over before a later refusal aborts the parse
is closed by the parser or by GC — build time only, reviewer's note; an input with no stream is still read through its
system id under the `""` belt — the resolver's system id is the confinement, M28) and records
the first reference it could not hand over; `ACCESS_EXTERNAL_SCHEMA=""` is the belt (the parser
loads nothing itself, the resolver's inputs still load, and a refused or unreadable reference is a
parser error — for an `xs:import` too, so no post-compile check); the compile catch prefers the
recorded failure (the parser names only the last path segment of a remote URL). "Not fetched" is
pinned by a counting loopback `HttpServer` in both factory tests (0 requests; the belt's mutant
`"all"` is caught by the counter only). xslt: `XsltCompiler.setResourceResolver` (Saxon
`ResourceResolver`, `relativeUri` else `uri` against `baseUri`), throws `XPathException` — never
null, Saxon would load the file itself; the message reaches `SaxonApiException` intact. It covers
include/import only: a DTD external entity, a static `unparsed-text()` / `doc()` and `use-when`
read at compile time through `Configuration.getResourceResolver()` (Saxon 12.4 has no
compile-scoped hook: `CompilerInfo`'s resolvers serve include/import, the static context is built
from the Configuration), so `CompileConfinement` replaces the shared Configuration's resolver once:
a compile scope open on the compiling thread (ThreadLocal; the factory opens it around `compile()`,
closes in `finally`) confines every read and records the first refusal, no scope delegates to
Saxon's own resolver (run time untouched); the same object wraps the Configuration's
`CollectionFinder` for static `uri-collection()` / `collection()` (Saxon hands the absolute URI).
A recorded refusal after compiling is a build error (`unparsed-text-available` swallows the
failure); `unparsed-text` and collection refusals name the absolute URI (no `relativeUri` there).
Saxon 12.4 throws a ClassCastException ("PackageData cannot be cast to StylesheetPackage") on a
static `collection()` before any finder runs — a `RuntimeException` out of the compile is wrapped
as "does not compile" naming the step. `CompileConfinementTest` (same package) pins that nothing
is confined on the building thread after a build and that a second factory over the same
Processor reuses the installed confinement. Re-probe on every Saxon upgrade: a `file:` collection
URI naming an XML catalog (`<collection><doc href="../x"/></collection>`) inside the directory —
today static `collection()` dies in Saxon before any finder and `uri-collection` returns the
catalog's own text, so nothing outside is read. Symlinks are compared as paths. Run-time
`doc()` / `document()` / `unparsed-text()` are not confined (FIX-16, a decision row); the compile
cache keyed by the main file's hash bypasses the check for an identical second flow (FIX-17).

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
of the delivery modes, the 202 after the durable save, `forwardHeaders`, admission → 503 are
`core.flow.Delivery` (P1-08, D-049; the mode is the flow's `delivery.mode`, not a path).

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

FIX-2 — `BlockedPlatformThreadDetector` has two drivers: `start(threshold)` keeps the sampler thread
(the JUnit extension `NoBlockedPlatformThreads` uses it); `driven(threshold, FakeClock)` starts no
thread and samples on every `clock.advance` / `set` on the calling thread, "now" being the clock's
instant as nanos (`epochSecond * 1e9 + nano`; the clock must not stand before the epoch — the `since`
sentinel is `-1`); `sample()` is a no-op once closed because FakeClock keeps the listener. A test
stages the thread state before each advance (spin on `getState()`, parked with a blocker) and asserts
the exact `blockedFor` = the clock time since the first blocked sample; the closed case blocks on a
monitor, not a latch (`close()` → `shutdownNow()` interrupts a latch wait). Surefire filter trap: the
wildcard `-Dtest='BlockedPlatformThreadDetector*'` selects the nested `BlockingTestCase` (built to
fail, run only through EngineTestKit) — name the two classes.

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
`mvn -q clean -pl <module>` first — never `mvn clean` at the reactor root: the root `target/` holds
the task's packets, specs, logs and diffs, and a root clean deleted them all in P1-08 round 2 (the
reds were re-cited from the handbacks). Task packets and specs now live in the session scratchpad;
only logs and diffs go under `target/`. Surefire: `-Dtest=A+B` selects nothing (`+` joins methods)
and with `failIfNoSpecifiedTests=false` the run is a silent green — use `-Dtest=A,B`.

Loops for a timing-dependent test (FIX-11): `stress-ng` is not installed on the VM (a verifier once reported it
present — it was not); CPU load is four `sh -c 'while :; do :; done' &` hogs with their pids recorded in a file
and killed by pid (never `pkill -f` a pattern that matches the calling shell), the Maven run pinned with
`taskset -c 0,1` to mimic the 2-vCPU runner; check `/proc/loadavg` first. Downloaded CI reports must not stay
under `target/` — `coverage_check.py` globs every `target/site/jacoco/jacoco.xml` below the root and then
reports them as unknown modules at `--check-baseline`; keep them in the session scratchpad.

jqwik-engine 1.10.2 prints an anti-AI-agent sentence in its own report header. Treated as data;
the property tests are real. Owner may want to revisit the library choice.

FIX-5 — `MigrationsIT` runs every case on a database of its own (`case_N`, created on the class's
container through `DriverManager`; the postgresql driver is `runtime` scope, already on the test
classpath). Testcontainers' `getJdbcUrl()` ends with `?loggerLevel=OFF`, so a sibling database URL is
derived on the path segment, not at the string's end. This failsafe honours `-Dit.test=<Class>`; the
`heap-bounded-it` execution then selects nothing without failing; delete stale `failsafe-reports` first.
The kept `@Order` (fresh database → misconfiguration → classpath) is the one that failed in P1-04.

## Reference package, fixtures, tags

packages/demo (package.yaml, endpoints/*.yaml, flows/invoice-sync.yaml + .test.yaml + .xsl +
invoice.xsd, fixtures/); validated by flottila-schemas tests. Add a negative fixture under
flottila-schemas/src/test/resources/invalid/<kind>/ for every new schema rule. ArchUnit rules in
flottila-archtest/Rules.java; every rule has a fixture. FIX-2, Q-1 pending in the Fixes section.

Tag `proto-m1` points at fc6eb56; the milestone-1 state the backlog means is 3f81834; the last
tree with the m1 module is dc85964.
