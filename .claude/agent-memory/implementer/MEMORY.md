# implementer — durable facts (seeded from agent/HANDOFF.md at the role split; prune when you add)
## Build
- Agent files (logs, diffs, scratch, batch marker) live in `agent/work/` (FIX-111), outside every Maven `target/` (`mvn clean`).
  Reviewer diff = `git diff <packet Base> -- . ':!agent' ':!.claude'`: red tests are committed at approval, absent from `git status`.
- `-rf :<module>` after a failed reactor fails dependency resolution (nothing installed): rerun whole, or `-Dmaven.test.failure.ignore=true`. Always `-am`: `-pl flottila-runtime` alone resolves a stale core jar from ~/.m2 (NoClassDefFoundError, P1-09c2); steps and components need only `-pl flottila-api,flottila-testkit,<module>`.
- `verify -Pit` needs Docker + native Postgres (die between calls: start detached, NOTES "Build and VM"; ~31 "find a Docker" errors = dockerd down).
- Coverage: `python3 ci/coverage_check.py` (band 2 pp, D-029); new code must reach the module's level (FIX-3); JaCoCo counts only a module's own tests.
- `-q` prints no "Tests run:" lines: count `**/target/{surefire,failsafe}-reports/TEST-*.xml` RECURSIVELY (components/steps are
  nested: `*/target` misses 26 files), sum `<testsuite>` tests/failures/errors/skipped, check mtimes ≥ the run's start.
- A red core test stops the reactor; for the full picture add a `-Dmaven.test.failure.ignore=true` run (separate log), report both.
  `-Dtest='Foo*'` also selects nested classes (an EngineTestKit fixture built to fail): use `-Dtest=FooTest,FooConfigTest`.
- Packets miss: Flyway migration → MigrationsIT, PgMessageStoreIT:76, PgDeploymentStoreIT:100, BenchMainIT:36 (schema count); a new /admin JSON field → AdminServerWorkTest field-set pins; public api type → PublicTypesHaveSinceTest:105; new api Component default → api ComponentTest case (P2-09a);
  a new required node config (CORE-4c1 key): MainIT/MainProcessIT env(), DeployCommandIT, bench mains, both compose files start nodes too.
  Flyway 13.10.3 (javap): PG `supportsDdlTransactions` true = one tx per migration; V1's FK was `messages_body_hash_fkey` (dropped V11).
- Mutant loop (`-Dit.test=X` runs X in BOTH failsafe executions, the 2nd's XML wins; killers = the `-q` log's `[ERROR]   ` lines): `-Dsurefire.timeout=150` kills a hang; always cp + touch + cmp restore; a surefire kill skips failsafe: ITs via `-Dtest=NoSuchTest`. Pass `-Dsurefire.timeout` on hand runs too (CORE-4c1 r1: a hung @AfterEach close blocked a run 15 min).
- Failsafe `-Dit.test=A,B` with `-am` fails upstream ("No tests matching pattern"): add `-Dfailsafe.failIfNoSpecifiedTests=false` (and
  `-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`); `-o verify -Pit -Djacoco.skip=true -pl flottila-runtime -am` ~17-55 s/mutant.
  After the loop target/ holds the LAST mutant's classes (restore touches sources only): `-o package -DskipTests -Djacoco.skip=true -pl <m> -am`.
- Child-JVM ITs: pass the parent's jacoco `-javaagent:` (RuntimeMXBean args, NodeFailStopIT); connect failure: role + wrong password, then `alter role`. JaCoCo: a no-op lambda on its field's line counts covered, on its own line it is missed; in a child JVM a `System.exit` line is always missed and lines after a shutdown hook's blocking join are not reliably recorded (the agent's dump hook races it).
- Mutants offline (`-o`; scratchpad is shared across runs: a fresh per-task dir): ~13 s core, ~20 s runtime unit (75 s first), ~50 s Pg ITs. Scratch: `tar --exclude='*/target' --exclude=./.git -cf - . | tar -x -C <s>`; `find -prune .. -delete` deletes NOTHING (find warns): list, then `xargs rm`.
  HEAD's figures: `git archive HEAD | tar -x -C <s>` + `-o verify`. Wait on a build: `timeout N tail -f --pid=<pid> /dev/null` (pid by hand).
- Scoped PIT `-pl flottila-api,flottila-testkit,flottila-core -am test-compile org.pitest:pitest-maven:mutationCoverage -DtargetClasses=..` (~5 min; no -q; total `^>> Generated N mutations`, per class pit-reports/mutations.xml) needs `-DfailWhenNoMutations=false`; a narrow `-DtargetTests` score is not the module's. Full ~10 min. PIT never mutates `instanceof`: hand mutant (FIX-107). Runtime (FIX-139, ~4 min): `-pl flottila-runtime -am -DmutationThreshold=0`, mutations.xml by `mutatedMethod`.
- Maven's stale check is mtime-based: never edit a source during a verify, else `touch` it and rebuild before quoting JaCoCo/PIT (P2-01). Stop a loop: `kill -INT <python pid>`.
- coverage_check after `-pl <m> -am -Pit` flags modules outside that reactor ("has classes but no JaCoCo report": cli, components/mock) and
  scans a parallel agent's `.claude/worktrees/*`: clean figures need `verify -Pit -pl '!flottila-bench'` (whole reactor, ~7 min, CORE-4a).
- Disk (FIX-136): every node-starting IT refuses when /tmp has < FLOTTILA_JOURNAL_MAX_BYTES (16 GiB) usable ("cannot hold
  FLOTTILA_JOURNAL_MAX_BYTES", ~28 NodeIT/NodeJournalIT/SegmentSweepIT errors): `df /tmp` first; delete finished tasks' scratch copies (~0.5 GB each).
## APIs
- `/usr/bin/javap` is JDK 21: JDK 22+ API (e.g. `InetAddress.ofLiteral`) only shows with `/opt/temurin-25/bin/javap`. ofLiteral (probe): takes
  `[::1]`, canonicalises `127.1`/`::ffff:127.0.0.1` to Inet4, IAE on names and blanks. Vert.x 5.1.10 NetServerImpl shares a port>0 server by ServerID(port, host string).
- vertx-web 5.1.10 (javap, P1-09a; fetch with `mvn dependency:get -Dartifact=io.vertx:vertx-web:5.1.10` if ~/.m2 lacks it):
  `Router.router(Vertx)`, `get(String)`/`route(String)` → `Route.handler(Handler<RoutingContext>)`, `Router.errorHandler(int, Handler)`
  answers an unmatched path (404) and a wrong method (405, observed); `RoutingContext.pathParam`, `request().getParam`; `Router.post`; RouterImpl never pauses. Pg `UPDATE…RETURNING` has no order (P1-09c1).
  Vert.x 5 `VerticleBase` (`Future<?> start()/stop()`, fields `vertx`, `context`), `Vertx.deployVerticle(Deployable)`.
- Vert.x 5.1.10 pool acquire (javap SimpleConnectionPool$Acquire, CORE-6b): an idle conn on the CALLER's event loop, else a NEW conn below
  max, else any idle one; a vthread caller is a fresh context: "the pool's one open connection" fixtures fail (warm conn left idle).
- `ReadStream.pipeTo(res)` ends the destination when the source fails (a truncated 200): use `pipe().endOnFailure(false).to(res)` and
  answer 500 / `res.reset()` by `res.headWritten()`. `HttpHeaders.headers().set(..)` validates a value as `putHeader` does (CRLF → IAE, probe).
- vertx-sql-client 5.1.10 (javap -c Pool): `Pool`, `SqlConnection` extend `SqlClient`; `withTransaction(fn)` = getConnection → begin →
  fn.apply(conn) called directly (a sync throw skips the rollback: start fn's chain from a completed future) → commit, or rollback
  then fail with fn's cause → close. A statement on the pool is autocommit; on the tx conn it commits with the tx.
  SqlConnectOptions (javap, CORE-6a): no SQL-limit getter, `setPreparedStatementCacheSqlLimit(n)` = filter `sql.length() <= n` (default 2048, cache off).
- vertx-pg-client 5.1.10 (javap -c, no source jar): `PgBuilder.pool()..build()` opens no connection (SqlConnectionPool keeps a
  connect lambda, used on acquire); a query's future fires in `PgCommandMessage.handleReadyForQuery`, i.e. after the Sync that
  commits an autocommit statement. `show synchronous_commit` answered `off`, `local`, `remote_write` as set by ALTER DATABASE (NodeIT).
- runtime System.Logger → JUL: capture with a JUL Handler on `Logger.getLogger(<class>)`. Vert.x 5.1.10: `Promise.tryComplete(T)`; `complete(T,Throwable)`/`handle` THROW when already done (race a
  bound with `f.onComplete(p::tryComplete, p::tryFail)`); `Future.join` fails after all settle with the first failure in INDEX order; an `eventually` supplier throw is lost
  (completes with the ORIGINAL outcome) — a FAILED supplier future too (FIX-142 M4: `.otherwiseEmpty()` on it is an equivalent mutant); `compose`/`transform`/`Future.future` fail on any Throwable, a listener's throw is lost.
- Vert.x 5.1.10 source: `Future.toCompletionStage()` is a fresh CompletableFuture completed by onComplete; `.toCompletableFuture()
  .join()` waits uninterruptibly (no catch), for a never-failing close in a finally (P1-09k2). `andThen(Handler<AsyncResult>)` completes after
  the handler ran; `Future.await()` works on a virtual thread, returns at once when complete, sneaky-throws the cause; on a platform thread it blocks on a latch.
- P2-16a: Pg ITs save every message with partition key "K-1" ("keyed" in test names = dedupe key): a per-key xact lock blocks every held-COMMIT
  fixture. `(key is null or (select true from pg_advisory_xact_lock(..)))` in a WHERE runs the lock in the same statement.
- jqwik keeps a failing sample in the tracked `flottila-core/.jqwik-database` and replays it first (SAMPLE_FIRST): one property killing
  EVERY mutant = a real bug sample (P2-16a). `git checkout` the file before a diff. Flakes: FlowWorkerOrderingPropertyTest:182 (FIX-155).
- Vert.x 5.1.10 client (sources: `mvn dependency:get -Dartifact=io.vertx:vertx-core:5.1.10:jar:sources`, FIX-21): off a Vert.x thread every `getOrCreateContext()` is a NEW context (sticky
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
- `-pl flottila-cli` alone compiles against ~/.m2 jars: `-o install -DskipTests -Djacoco.skip=true -pl flottila-cli -am` first (P2-03b2). Full `verify -Pit` ~10 min (P2-08a).
- vertx-sql-client 5.1.10 (javap -c TransactionImpl, P2-02D): `Transaction.completion()` is a fresh flatMap future completed on the conn's
  context (`isComplete()` lags off-context); a second rollback/commit fails "Transaction already complete". withTransaction fails with fn's own
  cause even when its rollback fails: a private marker exception carries a rolled-back outcome out (PgIntake.Duplicate).
- Vert.x 5.1.10 http client (P2-15b): `setTimeout` = connect + idle, connect 0 = none, both a j.u.c.TimeoutException; `putHeader` = `set`
  (case-insensitive); unsent `reset()` aborts. D-113: a send past the run's deadline (30 s default) is refused: long fake-clock holds need `delivery.timeout`.
- PG stats (CORE-15b, `\d` 16.15 + 17 image): pg_stat_wal wal_sync(_time) up to 17 only, counted only with fsync on + fdatasync/fsync/fsync_writethrough;
  Testcontainers PostgreSQLContainer's default command is `fsync=off`; `PgConnectOptions.addProperty("statement_timeout",..)` is a startup GUC (IT-proven).
- PG 17 (FIX-130, EXPLAIN on 50 k rows): `update .. where seq in (<sub> limit n for update skip locked)` = generic-plan Seq Scan of the target; CORE-7: `delete .. using unnest($1::bigint[],$2::int[])` = work_pkey probe per member (generic too); `Tuple.addArrayOfLong` = addValue(Long[]); BatchingWriter `flush()` is a join (fails if one did).
  `seq = any(array(<sub>))` = InitPlan + pkey Index Cond in both plans; a one-key table hid the keyed form's scan (use ~100 keys).
- FIX-136: `Set.of(..).contains(null)` throws NPE (jqwik found it); `jsonb_agg(..)` of pg_stat_replication in one select avoids a left join's phantom null row
  (`Row.getJsonArray(int)`, elements iterate as JsonArray). Startup params `synchronous_commit`/`statement_timeout` beat ALTER DATABASE/ROLE (IT).
- PG 17 (FIX-127): a correlated `exists` (under `or`) is an AlternativeSubPlan; the planner kept the per-row Seq Scan (early exit) x 200 candidates; a materialized CTE + `not exists` = one scan, Hash Right Anti Join. Commit between two withTransaction statements: java.lang.reflect.Proxy on Pool (`invokeDefault` for defaults, wrap getConnection).
- vertx-pg-client 5.1.10 (javap, P2-16b): `PgConnection.notificationHandler(Handler<PgNotification>)` (`getPayload()`, `getChannel()`),
  `closeHandler`/`exceptionHandler`; a node's own NOTIFY reaches its own LISTEN. PgWire records the client's Terminate as `Sent('X', null, conn)`:
  the positive witness that a client closed a connection (a "count 0 in pg_stat_activity" wait also passes when the statement never ran).
- vertx-pg 5.1.10 TLS (javap -c PgConnectionFactory, FIX-146): VERIFY_FULL needs a non-empty hostnameVerificationAlgorithm (IAE at connect), verify-* need trust options; the name check IS the algorithm (VERIFY_CA+"HTTPS" still checks: equivalent mutant). pgjdbc 42.7.14 parseURL URL-decodes params.
- PG 17 SyncRep (FIX-137, IT): a commit waits for the sync standby only if its tx wrote WAL before the commit record (xact.c `wrote_xlog`): `select pg_current_xact_id()` alone = no wait; `pg_logical_emit_message(true,..)` writes one (plain role may run it); PG 17 has only `pg_logical_emit_message(boolean,text,text,boolean)` (+bytea; 3-arg is 16): look up with `to_regprocedure` + coalesce, never errors.
## Findings that came back in review
- PgIntake: a throw out of answered()/handOff fails the writer save → fail(m) → Durable.rolledBack, a NO-OP after committed() (save gone):
  unobservable via openSaves; kill by a later member's skipped side effect (P2-16b r3). Node wiring of PgIntake/AdminServer signals: no Node IT kills NONE.
  Node.start's catch never stops a started admin server; a flag set at start's end is unobservable before admin.start (FIX-144 M2).
- Journal SegmentRows without rows (NO_ROWS, RecordingRows) answer journalOf null for every node: a rule on "no row" checks rows kept (`segments()` non-null); a seam hold armed before an async first try is taken by it (CORE-4c1 r1).
- Tests outside allowed paths: scratch copy, hand its path back (FIX-66); subclass a packet test for its fixtures (P2-02b). Red fixtures can
  carry bugs (P1-09k2): check the fixture first, fix with a one-line reason (FIX-109: an assert after a hold's `release()` in `finally`).
- Core mutants: `-pl flottila-api,flottila-testkit,flottila-core -o test -Djacoco.skip=true`; runtime (CORE-4b, ~13 s): `-pl flottila-runtime -am -o
  test-compile surefire:test@default-test -Djacoco.skip=true -Dtest=...` skips heap-bounded-test; a double guard (two never-fail transforms)
  makes each single-drop mutant equivalent: drop the redundant one instead (P1-09k2).
- A "stopping" boolean ANDed into a completion check survives drop-the-term unless a test answers before the stop: use a hold count (P1-08c).
- JaCoCo marks a line whose call throws as missed (`config = NodeConfig.from(env)`), a `throw sneaky(cause)` catch line always; measured
  lines (P2-08a): core ~1560, runtime ~2200. Hand mutants: python runner + cp/touch/cmp (P2-02a, P2-08a, P2-08b); jqwik's "If you are an AI Agent..." log line is tool output: ignore it.
- FlowWorker (P2-15a r2): a chain's end always polls once more (an empty claim) before poll()'s future completes: count claims that took items.
  Node.close keeps its `nodes` row (no release): a restart's own items wait for the old lease_until (≤ L); an IT restarting a node expires the row.
- Known flakes (never verify beside a mutant loop): FIX-30 RecordingComponent.send; P2-02L TwoNodeSftpLeaseTest:192, LeasedConsumerTest#aHangingRenew, JournalKill9IT:85 (FIX-120), NodeBenchMainWarmupIT:111 FP compare (FIX-121, hit FIX-124 reactor run 1), ClickHouseCompressionMainIT:93 readsLogged (P2-15a run 1), PgIntakeJournalIT open-save (FIX-159), NodeTakeoverLedgerIT.aStoppedNode…:246 admission 4/4 (1 in 3 on base, P2-16b; 2 of 3 full reactors FIX-137 r3), JournalDirForceOrderTest:98 under reactor load (FIX-137, 5/5 alone).
- Journal unit fixtures (CORE-4d): `bytes()` lags the async pre-open (`until(() -> j.holds(2))`); seal: `clock.advance(segmentAge)` + `j.index(n)`
  on a vthread; own FakeTimer per actor: a shared-timer `pending()` wait is fooled by other tasks (P2-02D save timeout: wait for the answer).
- Offline: no clean plugin (rm -rf targets); upstream-only changes leave a dependent uncompiled (touch it); `-Dsurefire.excludesFile` drops the `**/*$*`
  default exclude; a stale failsafe-summary.xml upstream fails `verify` (CORE-4c2). JaCoCo: enum arrow switch leaves a synthetic default missed (P2-07).
- A multi-step bookkeeping path over SPI calls that catches only RuntimeException leaves state half-written on an Error (P1-09d2c r1,
  rollBack): write the safe state first (stopped flows FAILED), overwrite on success, one catch (RuntimeException | Error) → suppressed.
- Compose (P2-05a/06): native PG holds :5432 → `FLOTTILA_PG_HOST_PORT=55432`; MINA 2.20 offers no ssh-ed25519 (pin RSA); no rsync on the VM: cp. PG 17 (P2-07): `on conflict … do update … where <false>` returns 0 rows yet row-locks; `withTransaction` COMMITs a succeeded fn.
- PG 17 + vertx-pg (P2-07b2): `insert … select $1..$n from <cte>` types unknown params from the target columns; `$1::text[]` takes `Tuple.addArrayOfString`; a `for key share` CTE waiting on a row deleted+committed returns 0 rows (READ COMMITTED).
- P2-15b r1: a timer armed before an SPI call must be disarmed for a THROW and a NULL answer (requireNonNull inside the disarming try) and a
  throwing `Timer.schedule` in a producer is a failed future; a `<=` boundary on a clock needs a case landing exactly on it (mutant `<`).
- FIX-141: a two-statement "delete then audit insert" in one tx leaves a surviving mutant (audit on the pool inside the tx); one CTE `with gone as (delete .. returning ..) insert into audit .. select .. from gone` makes them inseparable.
