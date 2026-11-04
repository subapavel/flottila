# implementer — durable facts (seeded from agent/HANDOFF.md at the role split; prune when you add)
## Build
- Agent files (logs, diffs, scratch harnesses, the batch marker) live in `agent/work/` (FIX-111), outside every Maven
  `target/`, so any `mvn clean` leaves them; P1-08 r2 lost them when they lived in the root `target/`.
- Always `-am`: `-pl flottila-runtime` alone resolves a stale core jar from ~/.m2 (NoClassDefFoundError, P1-09c2); steps and
  components need only `-pl flottila-api,flottila-testkit,<module>`.
- Full check: `mvn -B -ntp -q verify -Pit > agent/work/<task>-verify.log 2>&1`; `-Pit` needs Docker (`sudo dockerd &`) and native
  Postgres (`sudo pg_ctlcluster 16 main start`); both die between calls: start detached (NOTES "Build and VM"), check
  `docker info` first — ~31 IT errors "Previous attempts to find a Docker environment failed" mean dockerd is down.
- Coverage: `python3 ci/coverage_check.py` (band 2 pp, D-029); new code must reach the module's level (FIX-3); JaCoCo counts only a module's own tests.
- `-q` prints no "Tests run:" lines: count `**/target/{surefire,failsafe}-reports/TEST-*.xml` RECURSIVELY (components/steps are
  nested: `*/target` misses 26 files), sum `<testsuite>` tests/failures/errors/skipped, check mtimes ≥ the run's start.
- A red core test stops the reactor; for the full picture add a `-Dmaven.test.failure.ignore=true` run (separate log), report both.
  `-Dtest='Foo*'` also selects nested classes (an EngineTestKit fixture built to fail): use `-Dtest=FooTest,FooConfigTest`.
- flottila-runtime `verify` without `-Pit` passed its floor at CORE-4c1 (failed ~73 % before); runtime figures need `-Pit`; a `-Dtest=` verify fails the floor: read surefire XML.
- Packets miss: Flyway migration → MigrationsIT, PgMessageStoreIT:69, BenchMainIT:36 (schema count); public api type → PublicTypesHaveSinceTest:105; new api Component default → api ComponentTest case (P2-09a);
  a new required node config (CORE-4c1 key): MainIT/MainProcessIT env(), DeployCommandIT, bench mains, both compose files start nodes too.
  Flyway 13.10.3 (javap): PG `supportsDdlTransactions` true = one tx per migration; V1's FK was `messages_body_hash_fkey` (dropped V11).
- Mutant loop (`-Dit.test=X` runs X in BOTH failsafe executions, the 2nd's XML wins): `-Dsurefire.timeout=150` kills a hang; always cp + touch + cmp restore; a surefire kill skips failsafe: ITs via `-Dtest=NoSuchTest`. Pass `-Dsurefire.timeout` on hand runs too (CORE-4c1 r1: a hung @AfterEach close blocked a run 15 min).
- Failsafe `-Dit.test=A,B` with `-am` fails upstream ("No tests matching pattern"): add `-Dfailsafe.failIfNoSpecifiedTests=false` (and
  `-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`); `-o verify -Pit -Djacoco.skip=true -pl flottila-runtime -am` ~17-55 s/mutant.
  After the loop target/ holds the LAST mutant's classes (restore touches sources only): `-o package -DskipTests -Djacoco.skip=true -pl <m> -am`.
- JaCoCo: a no-op lambda on its field's line counts covered, on its own line it is missed; in a child JVM a `System.exit` line is
  always missed and lines after a shutdown hook's blocking join are not reliably recorded (the agent's dump hook races it).
- Mutants offline (`-o`): ~13 s core, ~20 s runtime unit (75 s first), ~50 s Pg ITs. Scratch: `tar --exclude='*/target' --exclude=./.git -cf - . | tar -x -C <s>`;
  HEAD's figures: `git archive HEAD | tar -x -C <s>` + `-o verify`. Wait on a build: `timeout N tail -f --pid=<pid> /dev/null` (pid by hand).
- ArchUnit alone: `verify -pl flottila-archtest -am -Dtest=BuildHygieneTest,ModuleBoundaryTest,ModuleBoundaryViolationTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- Scoped PIT `-DtargetClasses=..` (no -q; total `^>> Generated N mutations`, per class pit-reports/mutations.xml) needs `-DfailWhenNoMutations=false`; a narrow `-DtargetTests` score is not the module's. Full ~10 min. PIT never mutates `instanceof`: hand mutant (FIX-107).
- Mutant killers: grep the `-q` log's `[ERROR]   ` summary lines; an inherited contract case reads `Impl>Contract.method:NN` (regex
  `^\[ERROR\]   \w+(>\w+)?\.\w+`; TEST-*.xml only with mtime ≥ the run's start). jqwik's banner to "AI agents" is build output: ignore.
- Maven's stale check is mtime-based: never edit a source during a verify, else `touch` it and rebuild before quoting JaCoCo/PIT (P2-01). Stop a loop: `kill -INT <python pid>`.
- coverage_check after `-pl <m> -am -Pit` flags modules outside that reactor ("has classes but no JaCoCo report": cli, components/mock) and
  scans a parallel agent's `.claude/worktrees/*`: clean figures need `verify -Pit -pl '!flottila-bench'` (whole reactor, ~7 min, CORE-4a).
## APIs
- JDK 25 `FileChannel.write(ByteBuffer[],off,len)` on Linux writes at most IOV_MAX (1024) buffers per call: loop to write all (CORE-4a, I01 mutant).
- `/usr/bin/javap` is JDK 21: JDK 22+ API (e.g. `InetAddress.ofLiteral`) only shows with `/opt/temurin-25/bin/javap`. ofLiteral (probe): takes
  `[::1]`, canonicalises `127.1`/`::ffff:127.0.0.1` to Inet4, IAE on names and blanks. Vert.x 5.1.10 NetServerImpl shares a port>0 server by ServerID(port, host string).
- vertx-web 5.1.10 (javap, P1-09a; fetch with `mvn dependency:get -Dartifact=io.vertx:vertx-web:5.1.10` if ~/.m2 lacks it):
  `Router.router(Vertx)`, `get(String)`/`route(String)` → `Route.handler(Handler<RoutingContext>)`, `Router.errorHandler(int, Handler)`
  answers an unmatched path (404) and a wrong method (405, observed); `RoutingContext.pathParam`, `request().getParam`.
  Vert.x 5 `VerticleBase` (`Future<?> start()/stop()`, fields `vertx`, `context`), `Vertx.deployVerticle(Deployable)`.
- vertx-web 5.1.10 `Router.post(String)`→Route; RouterImpl never pauses (javap). Pg `UPDATE…RETURNING` has no order: CLAIM sorts in a CTE (P1-09c1).
- `ReadStream.pipeTo(res)` ends the destination when the source fails (a truncated 200): use `pipe().endOnFailure(false).to(res)` and
  answer 500 / `res.reset()` by `res.headWritten()`. `HttpHeaders.headers().set(..)` validates a value as `putHeader` does (CRLF → IAE, probe).
- vertx-sql-client 5.1.10 (javap -c Pool): `Pool`, `SqlConnection` extend `SqlClient`; `withTransaction(fn)` = getConnection → begin →
  fn.apply(conn) called directly (a sync throw skips the rollback: start fn's chain from a completed future) → commit, or rollback
  then fail with fn's cause → close. A statement on the pool is autocommit; on the tx conn it commits with the tx.
- vertx-pg-client 5.1.10 (javap -c, no source jar): `PgBuilder.pool()..build()` opens no connection (SqlConnectionPool keeps a
  connect lambda, used on acquire); a query's future fires in `PgCommandMessage.handleReadyForQuery`, i.e. after the Sync that
  commits an autocommit statement. `show synchronous_commit` answered `off`, `local`, `remote_write` as set by ALTER DATABASE (NodeIT).
- runtime System.Logger → JUL: capture with a JUL Handler on `Logger.getLogger(<class>)`. Vert.x 5.1.10: `Promise.tryComplete(T)`; `complete(T,Throwable)`/`handle` THROW when already done (race a
  bound with `f.onComplete(p::tryComplete, p::tryFail)`); `Future.join` fails after all settle with the first failure in INDEX order; an `eventually` supplier throw is lost
  (completes with the ORIGINAL outcome); `compose`/`transform`/`Future.future` fail on any Throwable, a listener's throw is lost.
- Saxon-HE 12.4 (javap + probe, FIX-6): `XsltCompiler.setResourceResolver` sees only xsl:include/import; DTD entities,
  static `doc()`/`unparsed-text()`/`use-when` read via `Configuration.getResourceResolver()` (UseWhenFilter builds
  `new Controller(config)`); default is `CatalogResourceResolver`; resolvers may return `StreamSource`.
- Vert.x 5.1.10 source: `Future.toCompletionStage()` is a fresh CompletableFuture completed by onComplete; `.toCompletableFuture()
  .join()` waits uninterruptibly (no catch), for a never-failing close in a finally (P1-09k2). `andThen(Handler<AsyncResult>)` completes after
  the handler ran; `Future.await()` works on a virtual thread, returns at once when complete, sneaky-throws the cause.
- AWS SDK 2.56.0 (javap): `RequestBody.fromFile(Path)` sizes the file at once (missing → UncheckedIOException); a client built without a credentialsProvider
  resolves `DefaultCredentialsProvider.builder()..build()` (`create()` is a static singleton); `serviceClientConfiguration()` shows region/endpoint/credentials.
- JDK 25 AQS (P2-09a): parked in `CountDownLatch.await`, interrupt + countDown before it runs → IE; `CompletableFuture.get` returns the value, flag set.
- JDK 25 (source, P2-03b1): a virtual thread blocked on a monitor leaves its carrier (JEP 491) and, from the release until the
  `VirtualThread-unblocker` platform thread queues it, is neither mounted nor queued: VirtualThreadSchedulerMXBean misses it.
  FlowWorker.pollingAfterAccept (wired by Deployments) runs an at-least-once message right after its 202, not at the first tick.
- Vert.x 5.1.10 client (source jar in ~/.m2, FIX-21): off a Vert.x thread every `getOrCreateContext()` is a NEW context (sticky
  loop); a future listener on another context object runs as a deferred EmitResultTask, so a response handed on through
  `compose` can end unheard. `req.response().onSuccess(HttpClientResponse::pause)` registered BEFORE `send` runs inline in the head emit.
- BlobStore contract: a stream may be handed over paused; the store resumes it once attached and never resumes one it paused
  or failed itself (`resumeOwed` / `attached` / `pausing` in `StreamingWrite`; one-resume-per-pause: agent/NOTES.md "FIX-10").
- Vert.x 5.1.10 source: `Future.future(h)` fails its promise with what `h` throws (null answer included); `compose` with a
  mapper that returns null throws NPE inside a listener and the composed future NEVER completes. `VertxOptions.setUseDaemonThread`
  (default null: Vert.x threads inherit the daemon flag of the thread that first runs them — a virtual thread's is true).
- Vert.x 5.1.10 HTTP/1 server upload (source + tests, P1-09d1b): a hang-up mid-body reaches `req.exceptionHandler` only if it is
  set when the close lands — set handlers on the route while paused (the vertx-web Router never resumes a paused request);
  `isEnded()` flips when the end is delivered from the paused queue; `req.handler(null)` drops queued chunks; `AsyncFile.write`
  after `close()` throws IllegalStateException synchronously, `close()` waits for writes in flight; `res.reset()` is a Future.
  Netty answers 400 before routing for Content-Length `abc`, `-1`, overflow, `5, 6` (observed). An answer before the request's
  end must close the connection (`!req.isEnded()` → `connection().close()`), or the client waits on a paused socket.
- TieredBlobStore spool files are per write since FIX-35: `<id>/<step>-<part>-<n>.blob` (n per store); a write that replaces a
  spool entry unlinks its own file off-thread; a missing file reads the row only for a `Stored` persist flagged durable, else
  ISE (tier) / NoSuchFileException (none, pinned by TieredBlobStoreOpenedStreamTest). `SqlResult.rowCount()` is int (javap).
- vertx-pg-client 5.1.10 jsonb (javap + IT, P2-02b): a JsonArray Tuple param goes through ParamExtractor.prepareJson (Json.encode);
  textDecodeJSONB answers a JsonArray, `Row.getJsonArray(String)` exists. `dirUri.relativize(u).equals(u)` = "u not below dir", no scheme branch.
- Apache MINA SSHD 2.20.0 (javap, P2-02): SFTP answers wait CoreModuleProperties.IDLE_TIMEOUT (also closes an idle session);
  NIO2 pools "-nio2" and "-nio2-resume" both sized by NIO_WORKERS; setUpDefaultClient reads ~/.ssh/config unless
  HostConfigEntryResolver.EMPTY; a client session can still be isOpen() after the server counted it closed (idle DISCONNECT race).
  loadKeyPairIdentities: garbage → null, PKCS8 with an unknown OID → NoSuchAlgorithmException (a GSE). rename never overwrites.
- vertx-redis-client 5.1.10 (javap, P2-02L; Apache-2.0/EPL-1.0, no NOTICE): `Redis.createClient(Vertx, String)` lazy, `close()` → Future;
  `RedisAPI.api(Redis).eval(List)` = [script, numkeys, keys.., args..]; `RedisAPI.close()` is void. Valkey IT image valkey/valkey:8.
- `-pl flottila-cli` alone compiles against ~/.m2 jars: `-o install -DskipTests -Djacoco.skip=true -pl flottila-cli -am` first (P2-03b2). Full `verify -Pit` ~10 min (P2-08a).
- P2-03a: `install -DskipTests` needs `-Djacoco.skip=true` (check-floors reads a stale exec) and fails on archtest (no artifact: leave it out); Vert.x 5.1.10 `Future.await()` on a plain platform thread blocks on a latch, an interrupt sneaky-throws InterruptedException (source, FIX-108); Deployments.stop(Flow) arms a consumer-stop bound on the timer per flow even with no consumer.
- vertx-sql-client 5.1.10 (javap -c TransactionImpl, P2-02D): `Transaction.completion()` is a fresh flatMap future completed on the conn's
  context (`isComplete()` lags off-context); a second rollback/commit fails "Transaction already complete". withTransaction fails with fn's own
  cause even when its rollback fails: a private marker exception carries a rolled-back outcome out (PgIntake.Duplicate).
- JFR, JDK 25.0.5 (FIX-108): `jdk.CPUTimeSample` throttle `"<n>ms"`; live RecordingStream vthread `getThread()` null, a whole dump resolves all (F-22); `dump(p)` truncates p, works on STOPPED; one dump per comparison; cp: `dependency:build-classpath`.
## Findings that came back in review
- Journal SegmentRows without rows (NO_ROWS, RecordingRows) answer journalOf null for every node: a rule on "no row" checks rows kept (`segments()` non-null); a seam hold armed before an async first try is taken by it (CORE-4c1 r1).
- A fake-timer round-end wait `pending() > 0` is fooled by an at-least-once save timeout on the same timer (P2-02D SftpIngressDedupeIT): wait for the answer too.
- Tests outside allowed paths: prove a guard's test in a scratch copy, hand its path back (FIX-66); a subclass of the packet's test class
  reuses its inner fixtures (P2-02b). RestoreIT logs 5 Trail "was not recorded (writer closed)" WARNINGs: expected.
- Packet red tests can carry fixture bugs no implementation fixes (P1-09k2): check the fixture first, fix with a one-line reason
  (FIX-109: an assert after a hold's `release()` in `finally` sees the released chain land).
- Core mutants: `-pl flottila-api,flottila-testkit,flottila-core -o test -Djacoco.skip=true`; runtime (CORE-4b, ~13 s): `-pl flottila-runtime -am -o
  test-compile surefire:test@default-test -Djacoco.skip=true -Dtest=...` skips heap-bounded-test; a double guard (two never-fail transforms)
  makes each single-drop mutant equivalent: drop the redundant one instead (P1-09k2).
- A "stopping" boolean ANDed into a completion check survives the drop-the-term mutant when no test completes an answer
  before the stop; a hold count (the owner holds 1 until stop, released through a Promise's tryComplete) has no such term (P1-08c).
- JaCoCo marks a line whose call throws as missed (`config = NodeConfig.from(env)`), a `throw sneaky(cause)` catch line always; measured
  lines (P2-08a): core ~1560, runtime ~2200. Hand mutants: python runner + cp/touch/cmp (P2-02a, P2-08a, P2-08b).
- Known flakes (never verify beside a mutant loop): FIX-30 RecordingComponent.send; P2-02L TwoNodeSftpLeaseTest:192, LeasedConsumerTest#aHangingRenew.
- A multi-step bookkeeping path over SPI calls that catches only RuntimeException leaves state half-written on an Error (P1-09d2c r1,
  rollBack): write the safe state first (stopped flows FAILED), overwrite on success, one catch (RuntimeException | Error) → suppressed.
- NodeConfig carries `Secrets` (P2-04): tests compare `from(env)` to the 11-arg ctor: a new component needs equality + a defaulting overload.
- Compose (P2-05a/06): native PG holds :5432 → `FLOTTILA_PG_HOST_PORT=55432`; MINA 2.20 offers no ssh-ed25519 (pin RSA); no rsync on the VM: cp. PG 17 (P2-07): `on conflict … do update … where <false>` returns 0 rows yet row-locks; `withTransaction` COMMITs a succeeded fn.
- JaCoCo: an arrow switch STATEMENT over an enum leaves a synthetic default branch missed: index arrays by ordinal (P2-07).
- Node bench by hand: `NodeBenchMain --scenario ack --concurrency 8`, --db must contain "bench", a fresh DB per side; before side in
  `git worktree add --detach <scratch> HEAD` + `-o package -DskipTests -Djacoco.skip=true -pl flottila-bench -am` (P2-07).
- PG 17 + vertx-pg (P2-07b2): `insert … select $1..$n from <cte>` types unknown params from the target columns; `$1::text[]` takes `Tuple.addArrayOfString`; a `for key share` CTE waiting on a row deleted+committed returns 0 rows (READ COMMITTED).
