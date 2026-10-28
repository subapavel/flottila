package io.flottila.bench;

import io.flottila.components.http.HttpComponent;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.runtime.Main;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The kill -9 ledger (P1-10b, GATE 9, ARCHITECTURE §11: sent = final-step rows, DLQ 0, work 0). Node 1 runs as a child JVM
 * ({@link Main} on this JVM's classpath, its configuration in a cleared environment, its output in {@code <out>.node-1.log})
 * with the node bench's at-least-once package ({@link NodeBenchMain#benchPackage}: {@code to partner}, every step recorded,
 * PUT through its admin API); the partner is a Vert.x echo in this JVM. The driver sends {@code --messages} one-line invoices
 * from {@value #CONCURRENCY} virtual threads and, once node 1 ACKed {@code --kill-after} of them (202, {@code x-message-id}),
 * kills it with {@link Process#destroyForcibly()} (SIGKILL). A message whose 202 did not arrive — another status, a cut or refused
 * connection, no answer within {@value #ACK_TIMEOUT_MS} ms — is re-sent until it is ACKed: to node 1 until the kill, then to
 * node 2, so the ledger reads N/N over all N messages. The in-flight work at the kill is made certain, not left to timing:
 * the partner answers the first {@code --kill-after / 2} calls and holds every later one until node 1 is dead, so at least
 * {@code --kill-after − --kill-after / 2} ACKed messages are queued or leased, unfinished. Node 2 then starts in this JVM on an
 * empty data directory and restores the package from the deployment record and the archive store (P1-09c3b, no PUT): S3 when
 * the {@code --s3-*} options are given, else a directory both nodes share. The rest is sent to node 2, which also re-runs what
 * node 1 left leased once the lease ({@link WorkerPolicy#DEFAULT}) ran out; each load gives up after {@link #SEND}, the run
 * waits for the work queue to be empty, at most {@link #DRAIN}, closes node 2 and writes the record to {@code --out}.
 *
 * <p>The record: {@code messages}, {@code sent} (messages sent at least once), {@code resent} (attempts beyond the first per
 * message), {@code acked} and {@code ackedIds} (each message's one 202), {@code finalRows} (ACKed ids with a final-step row:
 * {@value #FINAL}), {@code savedUnacked} (final-step ids that are no ACKed id: an attempt saved whose 202 was cut, its message
 * re-sent under a new id), {@code missing} (ACKed ids without a final-step row), {@code dlq}, {@code work},
 * {@code partnerCalls}, {@code duplicates} ({@code partnerCalls − finalRows}), {@code killedAfter} (node 1's ACKs),
 * {@code node1ExitValue}, {@code killedAt} (the database's clock once node 1 died, before node 2 started), {@code workAtKill}
 * and {@code unfinishedAtKill} (work rows, and ACKed ids without a final-step row, then), {@code heldToKillMs} (the partner's
 * first held call to the kill, -1 when it held none), {@code machine}, {@code commit}, {@code date}. Exit 0 only when every
 * message was ACKed, none is missing its final-step row, the DLQ and the work queue are empty and the kill tested something:
 * node 1 exited 137 (SIGKILL), an ACKed message was unfinished at the kill and node 2 ACKed one; else 3. Re-sends,
 * saved-but-unACKed ids, duplicates and {@code heldToKillMs} are reported, never a failure.
 *
 * <p>Arguments: {@code --messages} (2000), {@code --kill-after} ({@code messages / 2}; at least 1 and below {@code --messages}),
 * {@code --host --port --db --user --password} as the node bench ({@code --db} must name a bench database: the run truncates
 * every table in it), {@code --out} (bench/out/ledger.json), {@code --commit}, and {@code --s3-endpoint --s3-region
 * --s3-bucket --s3-prefix --s3-access-key --s3-secret-key} for an S3 archive store (path-style addressing). The options are
 * refused before anything connects; so is a node configuration {@link NodeConfig#from} refuses.
 *
 * @since 0.1
 */
public final class LedgerMain {

    /**
     * One run's command line.
     *
     * @param s3 the {@code FLOTTILA_S3_*} keys the {@code --s3-*} options give; empty: the dir archive store
     */
    public record Options(int messages, int killAfter, String host, int port, String db, String user, String password, Path out,
                          String commit, Map<String, String> s3) {}

    /** The senders, the flow's in-flight limit and the partner's pool. */
    static final int CONCURRENCY = 8;
    /** A final-step row: the bench flow's last step, {@code to partner}, recorded as done. */
    static final String FINAL = "step_name = 'partner' and outcome = 'ok'";
    /** How long node 1 may take to serve {@code /health}. */
    static final Duration START = Duration.ofSeconds(60);
    /** How long node 2 may take to empty the work queue: what node 1 left leased waits for its lease first. */
    static final Duration DRAIN = WorkerPolicy.DEFAULT.lease().plus(Duration.ofMinutes(5));
    /** How long one load of messages may take, re-sends included. */
    static final Duration SEND = Duration.ofMinutes(5);
    /** The pause between two looks at a condition {@link #until} waits for, and after an attempt without a 202. */
    static final long POLL_MS = 50;
    /** How long an attempt may wait for its connection, and then between two pieces of its answer, before it is re-sent. */
    static final long ACK_TIMEOUT_MS = 30_000;
    /** The exit status of a JVM SIGKILL ended: 128 + 9. */
    static final int SIGKILLED = 137;
    static final String NODE_1 = "ledger-node-1";
    static final String NODE_2 = "ledger-node-2";
    /** The {@code --s3-*} options and the node keys they set. */
    static final Map<String, String> S3_OPTIONS = Map.of("s3-endpoint", "FLOTTILA_S3_ENDPOINT", "s3-region", "FLOTTILA_S3_REGION",
            "s3-bucket", "FLOTTILA_S3_BUCKET", "s3-prefix", "FLOTTILA_S3_PREFIX", "s3-access-key", "FLOTTILA_S3_ACCESS_KEY",
            "s3-secret-key", "FLOTTILA_S3_SECRET_KEY");
    /** The ACKed ids ($1) with a final-step row, the final-step ids that are none of them, the DLQ and the work queue. */
    static final String LEDGER = "select (select count(distinct id) from messages where " + FINAL + " and id = any($1::text[])),"
            + " (select count(distinct id) from messages where " + FINAL + " and not (id = any($1::text[]))),"
            + " (select count(*) from dlq), (select count(*) from work)";
    static final String MISSING = "select a.id from unnest($1::text[]) a(id) where not exists (select 1 from messages m where m.id = a.id and m."
            + FINAL + ")";

    private LedgerMain() {}

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /**
     * The whole run on a virtual thread of its own ({@link Node#start} blocks on Vert.x futures); returns the exit code and
     * rethrows what the run threw. The options are refused before anything connects.
     */
    public static int run(String[] args) throws Exception {
        Options o = options(args);
        FutureTask<Integer> run = new FutureTask<>(() -> measure(o));
        Thread.ofVirtual().name("ledger").start(run).join();
        try {
            return run.get();
        } catch (ExecutionException failed) {
            throw NodeBenchMain.<RuntimeException>sneaky(failed.getCause());
        }
    }

    /** {@code args} as options; a kill-after outside [1, messages) and a database that is no bench one are refused. */
    public static Options options(String[] args) {
        Map<String, String> a = BenchMain.parse(args);
        int messages = Integer.parseInt(a.getOrDefault("messages", "2000"));
        int killAfter = Integer.parseInt(a.getOrDefault("kill-after", String.valueOf(messages / 2)));
        if (killAfter < 1 || killAfter >= messages) {
            throw new IllegalArgumentException("--kill-after must be at least 1 and below --messages " + messages
                    + " (node 1 ACKs that many, node 2 takes the rest), was " + killAfter);
        }
        String db = a.getOrDefault("db", "flottila_bench");
        if (!db.contains("bench")) {
            throw new IllegalArgumentException("--db '" + db + "' does not name a bench database (its name contains 'bench'): the run truncates every table in it");
        }
        Map<String, String> s3 = new TreeMap<>();
        for (Map.Entry<String, String> option : S3_OPTIONS.entrySet()) {
            String value = a.get(option.getKey());
            if (value != null) s3.put(option.getValue(), value);
        }
        return new Options(messages, killAfter, a.getOrDefault("host", "localhost"), Integer.parseInt(a.getOrDefault("port", "5432")), db,
                a.getOrDefault("user", "flottila"), a.getOrDefault("password", "flottila"), Path.of(a.getOrDefault("out", "bench/out/ledger.json")),
                a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")), s3);
    }

    /**
     * 0 when every message was ACKed, no ACKed id is missing its final-step row, the DLQ and the work queue are empty, node 1
     * exited 137 (SIGKILL), an ACKed message was unfinished at the kill and node 2 ACKed one; else 3, each failed condition
     * named on stderr. Re-sends, saved-but-unACKed ids, duplicates and the hold-to-kill time never count.
     */
    static int exitCode(JsonObject record) {
        List<String> failed = new ArrayList<>();
        int acked = record.getInteger("acked");
        if (acked != record.getInteger("messages")) failed.add(acked + " of " + record.getInteger("messages") + " messages ACKed");
        JsonArray missing = record.getJsonArray("missing");
        if (!missing.isEmpty()) failed.add(missing.size() + " ACKed ids without a final-step row " + missing);
        if (record.getInteger("dlq") != 0) failed.add("dlq " + record.getInteger("dlq"));
        if (record.getInteger("work") != 0) failed.add("work " + record.getInteger("work"));
        if (record.getInteger("node1ExitValue") != SIGKILLED) failed.add("node 1 exited " + record.getInteger("node1ExitValue") + ", not " + SIGKILLED + " (SIGKILL)");
        if (record.getInteger("unfinishedAtKill") <= 0) failed.add("no ACKed message was unfinished at the kill");
        if (acked <= record.getInteger("killedAfter")) failed.add("node 2 ACKed nothing (node 1 ACKed " + record.getInteger("killedAfter") + ")");
        if (failed.isEmpty()) return 0;
        System.err.println("the ledger does not hold: " + String.join("; ", failed));
        return 3;
    }

    /**
     * A node's environment: env dev, the run's database, admin on 127.0.0.1:{@code adminPort} (0: any free port) and its data
     * directory; the archive store is S3 (path-style) when the run has S3 options, else the directory {@code archives}, which
     * both nodes are given.
     */
    static Map<String, String> nodeEnv(Options o, String nodeId, Path data, Path archives, int adminPort) {
        Map<String, String> env = new HashMap<>(Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", nodeId, "FLOTTILA_ADMIN_PORT", String.valueOf(adminPort),
                "FLOTTILA_DATA_DIR", data.toString(), "FLOTTILA_PG_HOST", o.host(), "FLOTTILA_PG_PORT", String.valueOf(o.port()),
                "FLOTTILA_PG_DATABASE", o.db(), "FLOTTILA_PG_USER", o.user(), "FLOTTILA_PG_PASSWORD", o.password()));
        if (o.s3().isEmpty()) {
            env.put("FLOTTILA_ARCHIVE_DIR", archives.toString());
        } else {
            env.put("FLOTTILA_ARCHIVE_STORE", "s3");
            env.put("FLOTTILA_S3_PATH_STYLE", "true");
            env.putAll(o.s3());
        }
        return env;
    }

    /** The node bench's options for the ledger: the at-least-once (ack) package, passthrough, every payload kept. */
    static NodeBenchMain.Options bench(Options o) {
        return new NodeBenchMain.Options(NodeBenchMain.Scenario.ACK, NodeBenchMain.Recording.KEEP, NodeBenchMain.Step.PASSTHROUGH, o.messages(), 0,
                CONCURRENCY, "ledger", o.out(), true, o.host(), o.port(), o.db(), o.user(), o.password(), o.commit());
    }

    static int measure(Options o) throws Exception {
        Path run = Files.createTempDirectory("flottila-ledger-");
        try {
            int admin1 = NodeBenchMain.freePort();
            Map<String, String> env1 = nodeEnv(o, NODE_1, Files.createDirectories(run.resolve(NODE_1)), run.resolve("archives"), admin1);
            NodeConfig.from(env1);   // a configuration the node refuses fails the run here, before anything connects
            Migrations.migrate("jdbc:postgresql://" + o.host() + ":" + o.port() + "/" + o.db(), o.user(), o.password());
            Vertx vertx = Vertx.vertx();
            try {
                Pool setup = NodeBenchMain.pool(vertx, bench(o), 1);
                try {
                    setup.query(NodeBenchMain.PREPARE).execute().await();
                } finally {
                    setup.close().await();
                }
                Pool pool = NodeBenchMain.pool(vertx, bench(o), 2);   // opened after the ALTER DATABASE, as the nodes' are
                try {
                    return ledger(o, vertx, pool, run, env1, admin1);
                } finally {
                    pool.close().await();
                }
            } finally {
                vertx.close().await();
            }
        } finally {
            NodeBenchMain.delete(run);
        }
    }

    private static int ledger(Options o, Vertx vertx, Pool pool, Path run, Map<String, String> env1, int admin1) throws Exception {
        Files.createDirectories(o.out().toAbsolutePath().getParent());
        Partner partner = new Partner(o.killAfter() / 2);
        HttpServer echo = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await();
        int in = NodeBenchMain.freePort();
        Buffer body = NodeBenchMain.invoice(0);
        Sending sending = new Sending(vertx, o.messages(), body, in, ACK_TIMEOUT_MS);
        JsonObject json = new JsonObject();

        Path log = o.out().resolveSibling(o.out().getFileName() + ".node-1.log");
        Process node1 = startNode(env1, log);
        try {
            HttpClientAgent client = client(vertx);
            NodeBenchMain.Harness h = new NodeBenchMain.Harness(bench(o), vertx, pool, client, body, in, echo.actualPort());
            awaitServing(vertx, h, node1, admin1, START, log);
            h.deploy(admin1, NodeBenchMain.benchPackage(bench(o), in, echo.actualPort()));
            CompletableFuture<Void> reached = new CompletableFuture<>();
            CompletableFuture<Void> load = CompletableFuture.runAsync(() -> sending.load(client, o.killAfter(), reached, SEND),
                    task -> Thread.ofVirtual().name("ledger-load-1").start(task));
            CompletableFuture.anyOf(reached, load).join();
            json.put("heldToKillMs", partner.heldToKillMs());   // read at the kill: the first held call came before it
            node1.destroyForcibly();
            json.put("node1ExitValue", node1.waitFor());
            load.join();   // what was in flight to node 1 failed with it, or was ACKed before it died
            client.close().await();
        } finally {
            node1.destroyForcibly().waitFor();   // nothing when it is dead already; else dead before its data directory is deleted
        }
        List<String> ackedAtKill = List.copyOf(sending.acked);
        Row atKill = pool.query("select clock_timestamp(), (select count(*) from work)").execute().await().iterator().next();
        json.put("killedAfter", ackedAtKill.size()).put("killedAt", atKill.getOffsetDateTime(0).toInstant().toString())
                .put("workAtKill", atKill.getLong(1)).put("unfinishedAtKill", missing(pool, ackedAtKill).size());
        partner.release();

        SaxonXml xml = new SaxonXml();
        Node node2 = Node.start(NodeConfig.from(nodeEnv(o, NODE_2, Files.createDirectories(run.resolve(NODE_2)), run.resolve("archives"), 0)), vertx,
                ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())), xml.steps(), xml.languages());
        try {
            HttpClientAgent client = client(vertx);   // a fresh pool: none of node 1's dead connections
            sending.load(client, Integer.MAX_VALUE, new CompletableFuture<>(), SEND);   // the rest, and node 1's cut 202s again
            client.close().await();
            until(vertx, DRAIN, () -> !pool.query("select exists (select 1 from work)").execute().await().iterator().next().getBoolean(0));
        } finally {
            node2.close();   // the drain already implies the final-step rows: an item is completed only after its trail closed
        }
        return record(o, pool, json, sending, partner, env1);
    }

    /** The ledger read from the database once node 2 closed, the record written to {@code --out}; {@link #exitCode} of it. */
    private static int record(Options o, Pool pool, JsonObject json, Sending sending, Partner partner, Map<String, String> env1) throws IOException {
        List<String> acked = List.copyOf(sending.acked);
        Row r = pool.preparedQuery(LEDGER).execute(Tuple.tuple().addArrayOfString(acked.toArray(String[]::new))).await().iterator().next();
        long finalRows = r.getLong(0);
        json.put("sent", sending.sent.get()).put("resent", sending.resent.get()).put("acked", acked.size())
                .put("ackedIds", new JsonArray(new ArrayList<Object>(acked))).put("finalRows", finalRows).put("savedUnacked", r.getLong(1))
                .put("missing", new JsonArray(new ArrayList<Object>(missing(pool, acked)))).put("dlq", r.getLong(2))
                .put("work", r.getLong(3)).put("partnerCalls", partner.calls()).put("duplicates", partner.calls() - finalRows)
                .put("messages", o.messages()).put("killAfter", o.killAfter()).put("concurrency", CONCURRENCY)
                .put("archiveStore", env1.getOrDefault("FLOTTILA_ARCHIVE_STORE", "dir")).put("machine", BenchMain.machine()).put("date", Instant.now().toString())
                .put("commit", o.commit());
        Files.writeString(o.out(), json.encodePrettily() + "\n");
        System.out.println(json.encodePrettily());
        return exitCode(json);
    }

    /** The ids of {@code ids} without a final-step row. */
    static List<String> missing(Pool pool, Collection<String> ids) {
        List<String> missing = new ArrayList<>();
        for (Row r : pool.preparedQuery(MISSING).execute(Tuple.tuple().addArrayOfString(ids.toArray(String[]::new))).await()) {
            missing.add(r.getString(0));
        }
        return missing;
    }

    /** Node 1: {@link Main} in a JVM of its own on this JVM's classpath (the test class path under surefire / failsafe), its environment {@code env} only. */
    static Process startNode(Map<String, String> env, Path log) throws IOException {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), Main.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        return builder.start();
    }

    /** Waits until {@code node} serves {@code /health} 200 on 127.0.0.1:{@code admin}; refuses a node that exited first or did not serve within {@code within}. */
    static void awaitServing(Vertx vertx, NodeBenchMain.Harness h, Process node, int admin, Duration within, Path log) {
        boolean served = until(vertx, within, () -> !node.isAlive() || health(h, admin) == 200);
        if (!node.isAlive()) throw new IllegalStateException("node 1 exited with " + node.exitValue() + " before it served; its output: " + log);
        if (!served) throw new IllegalStateException("node 1 did not serve /health 200 on port " + admin + " within " + within + "; its output: " + log);
    }

    /** The status of {@code GET /health} on 127.0.0.1:{@code port}; -1 when nothing answered (the node not listening yet). */
    static int health(NodeBenchMain.Harness h, int port) {
        try {
            return h.exchange(HttpMethod.GET, port, "/health", "text/plain", Buffer.buffer()).status();
        } catch (Exception notYet) {   // await rethrows a refused connection (checked) sneakily
            return -1;
        }
    }

    /** Looks at {@code condition} every {@value #POLL_MS} ms on a Vert.x timer until it holds (true) or {@code within} passed (false). */
    static boolean until(Vertx vertx, Duration within, BooleanSupplier condition) {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) return false;
            vertx.timer(POLL_MS).await();
        }
        return true;
    }

    /** A client of {@value #CONCURRENCY} connections. */
    static HttpClientAgent client(Vertx vertx) {
        return vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(CONCURRENCY));
    }

    /**
     * POSTs {@code body} to 127.0.0.1:{@code port}/in; the {@code x-message-id} of a 202, else null (another status, or a
     * request that failed — node 1 killed under it, or no connection or no answer within {@code timeoutMs}).
     */
    static String ack(HttpClientAgent client, int port, Buffer body, long timeoutMs) {
        try {
            return client.request(new RequestOptions().setMethod(HttpMethod.POST).setHost("127.0.0.1").setPort(port).setURI("/in")
                    .setTimeout(timeoutMs)).compose(req -> {
                req.response().onSuccess(HttpClientResponse::pause);   // FIX-21: the body cannot end before it is attached
                return req.putHeader("content-type", "application/xml").send(body);
            }).compose(res -> {
                Future<Buffer> all = res.body();
                res.resume();
                return all.map(ignored -> res.statusCode() == 202 ? res.getHeader(HttpComponent.H_MESSAGE_ID) : null);
            }).await();
        } catch (Exception failed) {   // await rethrows a cut or refused connection (checked) sneakily
            return null;
        }
    }

    /**
     * The messages of one run, shared by node 1's load and node 2's: how many are not sent yet, how many are owed a re-send (an
     * attempt without a 202), the attempts made and the ids ACKed.
     */
    static final class Sending {
        final Vertx vertx;
        final int messages;
        final Buffer body;
        final int in;
        final long timeoutMs;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger owed = new AtomicInteger();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger resent = new AtomicInteger();
        final ConcurrentLinkedQueue<String> acked = new ConcurrentLinkedQueue<>();
        private final AtomicInteger ackCount = new AtomicInteger();

        Sending(Vertx vertx, int messages, Buffer body, int in, long timeoutMs) {
            this.vertx = vertx;
            this.messages = messages;
            this.body = body;
            this.in = in;
            this.timeoutMs = timeoutMs;
        }

        /**
         * Sends from {@value #CONCURRENCY} virtual threads, each one attempt after the other — a message owed a re-send first,
         * else one not sent yet — until every message was ACKed, {@code stop} is done or {@code within} passed; an attempt
         * without a 202 is owed again and its sender pauses {@value #POLL_MS} ms. Completes {@code stop} once {@code stopAt}
         * ACKs were counted. Returns when every sender did.
         */
        void load(HttpClientAgent client, int stopAt, CompletableFuture<Void> stop, Duration within) {
            long deadline = System.nanoTime() + within.toNanos();
            try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < CONCURRENCY; c++) {
                    senders.execute(() -> {
                        while (!stop.isDone() && System.nanoTime() - deadline < 0) {
                            if (owed.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                                resent.incrementAndGet();
                            } else if (next.getAndIncrement() < messages) {
                                sent.incrementAndGet();
                            } else {
                                return;   // nothing left to send; a sender whose attempt fails later re-sends it itself
                            }
                            String id = ack(client, in, body, timeoutMs);
                            if (id == null) {
                                owed.incrementAndGet();
                                vertx.timer(POLL_MS).await();
                            } else {
                                acked.add(id);
                                if (ackCount.incrementAndGet() >= stopAt) stop.complete(null);
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * The partner: echoes the first {@code answered} calls at once and holds every later one — never answered — until
     * {@link #release()}, from which on it echoes every call. Counts every call it read.
     */
    static final class Partner implements Handler<HttpServerRequest> {
        private final int answered;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean released;
        private final AtomicReference<Long> firstHeld = new AtomicReference<>();   // System.nanoTime() of the first call held

        Partner(int answered) {
            this.answered = answered;
        }

        @Override
        public void handle(HttpServerRequest req) {
            req.body().onSuccess(bytes -> {
                if (calls.incrementAndGet() <= answered || released) {
                    req.response().end(bytes);
                } else {
                    firstHeld.compareAndSet(null, System.nanoTime());
                }
            });
        }

        void release() {
            released = true;
        }

        /** Milliseconds from the first call held until now; -1 when none was held. */
        long heldToKillMs() {
            Long first = firstHeld.get();
            return first == null ? -1 : (System.nanoTime() - first) / 1_000_000;
        }

        int calls() {
            return calls.get();
        }
    }
}
