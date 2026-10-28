package io.flottila.bench;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The node bench (P1-10a, {@code bench/run.sh} with {@code BENCH_SCENARIOS}): an in-process {@link Node} (env dev, the dir
 * archive store in a temp data directory) over the given Postgres, a bench package PUT through its admin API and a raw Vert.x
 * HTTP echo on 127.0.0.1 as the partner (it answers every request with the request's bytes). One scenario per run, one JSON
 * document to {@code --out}:
 * <ul>
 *   <li>{@code sync}: HTTP → the sync flow → the echo → the reply, against {@code raw} (the same client straight to the echo,
 *       measured first); the record carries both distributions and {@code overheadP50Ms = p50(sync) − p50(raw)};</li>
 *   <li>{@code ack}: the at-least-once flow's 202 latency (the durable save, the {@code bodies} insert included, D-061);</li>
 *   <li>{@code worker}: the same flow's items, queued through the ingress and run by its worker: the distribution is each
 *       item's claim → complete, from triggers on {@code work} into an unlogged {@code bench_work_log}; the window runs from
 *       the first request until the queue is empty, and the record adds the ingress' own distribution ({@code ingress}) and
 *       {@code drainTailMs} (the last 202 → the queue empty).</li>
 * </ul>
 * The flow is {@code [xslt?, to partner]} ({@code --step}; xslt sends a ~100 KB generated invoice, passthrough a one-line one)
 * under {@code --recording}. Its {@code maxInFlight} is {@code --concurrency} for at-least-once (the ingress permit is back when
 * the 202 is decided, and the worker runs at most that many items) and {@value #SYNC_PERMITS_PER_CLIENT} times it for sync: a
 * sync message holds its permit until its recording closed, after the reply (D-064), so a client's next request comes while
 * earlier ones still hold theirs; a refusal counts as failed. The partner's pool has {@code --concurrency} connections. Every
 * record carries
 * {@code walBytesPerSecond} ({@code pg_current_wal_lsn} delta), {@code bodiesBytesPerSecond} ({@code pg_total_relation_size('bodies')}
 * delta) — both taken once what the window accepted landed (recorded, the work queue empty) and divided by the window's
 * {@code elapsedMs} — and {@code bodiesGrowthPerHour} (the latter × 3600).
 *
 * <p>Before the node starts the run migrates the database, sets {@code synchronous_commit = on} on it (ALTER DATABASE: the
 * node's sessions commit durably whatever the database's default, and a node refuses off, D-061), truncates every table but
 * Flyway's history and, for {@code worker} only, installs the two triggers (any other run drops them). Arguments: BenchMain's
 * {@code --host --port --db --user --password --messages --warmup --concurrency --name --out --sync-commit --commit} plus
 * {@code --scenario sync|ack|worker} (required), {@code --recording keep|metadata-only} (keep), {@code --step passthrough|xslt}
 * (passthrough). {@code --sync-commit} is on or off, and off is refused by the run; a {@code --db} whose name lacks "bench" is
 * refused before anything connects.
 *
 * @since 0.1
 */
public final class NodeBenchMain {

    /** What a run measures; the CLI name is the lower-case name. */
    public enum Scenario { SYNC, ACK, WORKER }

    /** The flow's {@code recording.payload}; the CLI name is the lower-case name with a dash. */
    public enum Recording { KEEP, METADATA_ONLY }

    /** The flow's step before {@code to partner}. */
    public enum Step { PASSTHROUGH, XSLT }

    /** One run's command line. */
    public record Options(Scenario scenario, Recording recording, Step step, int messages, int warmup, int concurrency, String name,
                          Path out, boolean syncCommit, String host, int port, String db, String user, String password, String commit) {}

    /** The flow's and the partner's max body. */
    static final long MAX_BODY_BYTES = 1024 * 1024;
    /** The size the xslt step's generated invoice grows to. */
    static final int XSLT_BODY_BYTES = 100_000;
    /** A sync flow's permits per client request in flight (see the class comment). */
    static final int SYNC_PERMITS_PER_CLIENT = 64;
    /** How long a run waits for what a window accepted to land (recorded, the work queue empty) before it gives up. */
    static final Duration DRAIN = Duration.ofMinutes(10);

    static final String STYLESHEET = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
              <xsl:template match="/Invoice">
                <PartnerInvoice number="{Id}" currency="{Currency}">
                  <Total><xsl:value-of select="sum(Lines/Line/(Qty * Price))"/></Total>
                  <xsl:for-each select="Lines/Line"><Item sku="{Sku}" amount="{Qty * Price}"/></xsl:for-each>
                </PartnerInvoice>
              </xsl:template>
            </xsl:stylesheet>
            """;

    /** Run before the node starts, on a session of its own (ALTER DATABASE reaches new sessions only). */
    static final String PREPARE = """
            do $$ begin execute format('alter database %I set synchronous_commit = on', current_database()); end $$;
            create unlogged table if not exists bench_work_log (seq bigint not null, event text not null, at timestamptz not null);
            create or replace function bench_work_logged() returns trigger language plpgsql as $f$ begin
              if tg_op = 'DELETE' or new.attempts > old.attempts then
                insert into bench_work_log(seq, event, at) values (old.seq, lower(tg_op), clock_timestamp());
              end if;
              return null;
            end $f$;
            drop trigger if exists bench_claim on work;
            drop trigger if exists bench_complete on work;
            do $$ begin execute (select 'truncate ' || string_agg(format('%I', tablename), ', ') from pg_tables
              where schemaname = current_schema() and tablename <> 'flyway_schema_history'); end $$;
            """;

    /**
     * A claim raises {@code attempts} (the function logs only those updates); a complete deletes the row. The triggers name no
     * column, so they never hold a migration of {@code work} back; dropping the table drops them.
     */
    static final String WORK_LOG_TRIGGERS = """
            create trigger bench_claim after update on work for each row execute function bench_work_logged();
            create trigger bench_complete after delete on work for each row execute function bench_work_logged();
            """;

    /** Each completed item's last claim → its complete, in nanoseconds (the server's clock, microsecond resolution). */
    static final String CLAIM_TO_COMPLETE = "select (extract(epoch from done.at - max(claim.at)) * 1000000000)::bigint from bench_work_log done"
            + " join bench_work_log claim on claim.seq = done.seq and claim.event = 'update' where done.event = 'delete' group by done.seq, done.at";

    private NodeBenchMain() {}

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /**
     * The whole run on a virtual thread of its own ({@link Node#start} blocks on Vert.x futures); returns the exit code (0 ok,
     * 3 a request or an item failed) and rethrows what the run threw. The options are refused before anything connects.
     */
    public static int run(String[] args) throws Exception {
        Options o = options(args);
        if (!o.syncCommit()) {
            throw new IllegalArgumentException("--sync-commit off: a node refuses synchronous_commit off (D-061); the node bench runs with on");
        }
        FutureTask<Integer> run = new FutureTask<>(() -> measure(o));
        Thread.ofVirtual().name("node-bench").start(run).join();
        try {
            return run.get();
        } catch (ExecutionException failed) {
            throw NodeBenchMain.<RuntimeException>sneaky(failed.getCause());
        }
    }

    /** {@code args} as options; an unknown or missing scenario, an unknown recording or step is refused naming the value and the known ones. */
    public static Options options(String[] args) {
        Map<String, String> a = BenchMain.parse(args);
        Scenario scenario = choice(a, "scenario", Scenario.class, null);
        Recording recording = choice(a, "recording", Recording.class, Recording.KEEP);
        Step step = choice(a, "step", Step.class, Step.PASSTHROUGH);
        String name = a.getOrDefault("name", "node-" + cli(scenario));
        String syncCommit = a.getOrDefault("sync-commit", "on");
        if (!syncCommit.equals("on") && !syncCommit.equals("off")) throw new IllegalArgumentException("--sync-commit must be on or off, was '" + syncCommit + "'");
        String db = a.getOrDefault("db", "flottila_bench");
        if (!db.contains("bench")) {
            throw new IllegalArgumentException("--db '" + db + "' does not name a bench database (its name contains 'bench'): the run truncates every table in it");
        }
        return new Options(scenario, recording, step, Integer.parseInt(a.getOrDefault("messages", "20000")),
                Integer.parseInt(a.getOrDefault("warmup", "1000")), Integer.parseInt(a.getOrDefault("concurrency", "64")), name,
                Path.of(a.getOrDefault("out", "bench/out/" + name + ".json")), syncCommit.equals("on"),
                a.getOrDefault("host", "localhost"), Integer.parseInt(a.getOrDefault("port", "5432")), db,
                a.getOrDefault("user", "flottila"), a.getOrDefault("password", "flottila"),
                a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
    }

    /** {@code value}'s CLI name: lower case, dashes for underscores. */
    static String cli(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static <E extends Enum<E>> E choice(Map<String, String> a, String key, Class<E> type, E fallback) {
        String raw = a.get(key);
        List<String> known = Arrays.stream(type.getEnumConstants()).map(NodeBenchMain::cli).toList();
        if (raw == null && fallback == null) throw new IllegalArgumentException("--" + key + " is required: one of " + known);
        if (raw == null) return fallback;
        int index = known.indexOf(raw);
        if (index < 0) throw new IllegalArgumentException("--" + key + " must be one of " + known + ", was '" + raw + "'");
        return type.getEnumConstants()[index];
    }

    static int measure(Options o) throws Exception {
        Migrations.Result migrated = Migrations.migrate("jdbc:postgresql://" + o.host() + ":" + o.port() + "/" + o.db(), o.user(), o.password());
        Vertx vertx = Vertx.vertx();
        Path data = Files.createTempDirectory("flottila-node-bench-");
        try {
            Pool setup = pool(vertx, o, 1);
            try {
                setup.query(PREPARE + (o.scenario() == Scenario.WORKER ? WORK_LOG_TRIGGERS : "")).execute().await();
            } finally {
                setup.close().await();
            }
            Pool pool = pool(vertx, o, 2);   // opened after the ALTER DATABASE: its sessions see what the node's see
            try {
                return report(o, migrated, vertx, pool, data);
            } finally {
                pool.close().await();
            }
        } finally {
            try {
                vertx.close().await();
            } finally {
                delete(data);
            }
        }
    }

    private static int report(Options o, Migrations.Result migrated, Vertx vertx, Pool pool, Path data) throws Exception {
        Buffer body = invoice(o.step() == Step.XSLT ? XSLT_BODY_BYTES : 0);
        int in = freePort();
        HttpServer echo = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(bytes -> req.response().end(bytes)))
                .listen(0, "127.0.0.1").await();
        HttpClientAgent client = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(o.concurrency()));
        Harness h = new Harness(o, vertx, pool, client, body, in, echo.actualPort());
        SaxonXml xml = new SaxonXml();
        JsonObject measured;
        Node node = Node.start(nodeConfig(o, data), vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())), xml.steps(),
                xml.languages());
        try {
            h.deploy(node.adminPort(), benchPackage(o, in, echo.actualPort()));
            int warmedUp = o.warmup() - h.load(in, "/in", o.warmup()).failed();
            h.settle(warmedUp, DRAIN);
            measured = h.measure(warmedUp);   // settled too: the node stops with nothing in flight
        } finally {
            node.close();   // flushes the message store: its rows are in before the record is written
        }
        Row server = pool.query("select current_setting('synchronous_commit'), current_setting('server_version')").execute().await().iterator().next();
        JsonObject json = new JsonObject().put("name", o.name()).put("scenario", cli(o.scenario())).put("recording", cli(o.recording()))
                .put("step", cli(o.step())).put("warmup", o.warmup()).put("concurrency", o.concurrency()).put("maxInFlight", inFlight(o))
                .mergeIn(measured)
                // observed on a session opened as the node's were, not the flag
                .put("syncCommit", "on".equals(server.getString(0))).put("postgres", server.getString(1))
                .put("schemaVersion", migrated.schemaVersion()).put("bodyBytes", body.length()).put("machine", BenchMain.machine())
                .put("date", Instant.now().toString()).put("commit", o.commit());
        Files.createDirectories(o.out().toAbsolutePath().getParent());
        Files.writeString(o.out(), json.encodePrettily() + "\n");
        System.out.println(json.encodePrettily());
        return exitCode(json);
    }

    /** 3 when the record counts a failure (named on stderr with the first one), else 0. */
    static int exitCode(JsonObject record) {
        int failed = record.getInteger("failed");
        if (failed > 0) {
            System.err.println(failed + " of " + record.getInteger("messages") + " failed; first: " + record.getString("firstFailure"));
            return 3;
        }
        return 0;
    }

    /** The flow's in-flight limit (see the class comment). */
    static int inFlight(Options o) {
        return o.scenario() == Scenario.SYNC ? SYNC_PERMITS_PER_CLIENT * o.concurrency() : o.concurrency();
    }

    static Pool pool(Vertx vertx, Options o, int size) {
        return PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(o.host()).setPort(o.port()).setDatabase(o.db()).setUser(o.user())
                .setPassword(o.password())).with(new io.vertx.sqlclient.PoolOptions().setMaxSize(size)).using(vertx).build();
    }

    private static NodeConfig nodeConfig(Options o, Path data) {
        return NodeConfig.from(Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "node-bench", "FLOTTILA_ADMIN_PORT", "0",
                "FLOTTILA_DATA_DIR", data.toString(), "FLOTTILA_PG_HOST", o.host(), "FLOTTILA_PG_PORT", String.valueOf(o.port()),
                "FLOTTILA_PG_DATABASE", o.db(), "FLOTTILA_PG_USER", o.user(), "FLOTTILA_PG_PASSWORD", o.password()));
    }

    /** An invoice of at least one line, grown by lines until it holds {@code atLeast} bytes. */
    static Buffer invoice(int atLeast) {
        StringBuilder xml = new StringBuilder("<Invoice><Id>4711</Id><Currency>EUR</Currency><Lines>");
        int line = 0;
        do {
            xml.append("<Line><Sku>SKU-").append(line).append("</Sku><Qty>").append(1 + line % 5).append("</Qty><Price>")
                    .append(10 + line % 90).append(".50</Price></Line>");
            line++;
        } while (xml.length() < atLeast);
        return Buffer.buffer(xml.append("</Lines></Invoice>").toString());
    }

    /**
     * The bench package as a zip: the in endpoint on 127.0.0.1:{@code in}, the partner at the echo, and the flow {@code bench}
     * — sync for the sync scenario, else at-least-once — with the run's recording payload and steps.
     */
    static byte[] benchPackage(Options o, int in, int echo) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("package.yaml", yaml(doc("package").put("name", "bench").put("version", "0.1.0")));
        files.put("endpoints/in.yaml", yaml(doc("endpoint").put("id", "in").put("scheme", "http").put("direction", "in")
                .put("config", new JsonObject().put("host", "127.0.0.1").put("port", in).put("path", "/in"))));
        files.put("endpoints/partner.yaml", yaml(doc("endpoint").put("id", "partner").put("scheme", "http").put("direction", "out")
                .put("config", new JsonObject().put("url", "http://127.0.0.1:" + echo + "/echo").put("maxBodyBytes", MAX_BODY_BYTES)
                        .put("poolSize", o.concurrency()))));
        JsonArray steps = new JsonArray();
        if (o.step() == Step.XSLT) steps.add(new JsonObject().put("type", "xslt").put("name", "transform").put("stylesheet", "transform.xsl"));
        steps.add(new JsonObject().put("type", "to").put("name", "partner").put("endpoint", "partner"));
        files.put("flows/bench.yaml", yaml(doc("flow").put("id", "bench").put("version", "0.1.0").put("from", new JsonObject().put("endpoint", "in"))
                .put("delivery", new JsonObject().put("mode", o.scenario() == Scenario.SYNC ? "sync" : "at-least-once"))
                .put("recording", new JsonObject().put("level", "all").put("payload", cli(o.recording())))
                .put("limits", new JsonObject().put("maxBodyBytes", MAX_BODY_BYTES).put("maxInFlight", inFlight(o)))
                .put("steps", steps)));
        files.put("flows/transform.xsl", STYLESHEET.getBytes(StandardCharsets.UTF_8));
        return zip(files);
    }

    /** {@code files} (path → content) as a zip. */
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static JsonObject doc(String kind) {
        return new JsonObject().put("schemaVersion", 1).put("kind", kind);
    }

    /** A document as JSON, which every YAML reader reads. */
    static byte[] yaml(JsonObject doc) {
        return doc.encodePrettily().getBytes(StandardCharsets.UTF_8);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static void delete(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    /** One measured window: the latency samples, the failures and the window's length. */
    record Window(LatencyStats latency, int failed, long elapsedNanos, String firstFailure) {
        JsonObject toJson(int messages) {
            return new JsonObject().put("messages", messages).put("failed", failed).put("elapsedMs", elapsedNanos / 1_000_000.0)
                    .put("throughputPerSecond", (messages - failed) / (elapsedNanos / 1_000_000_000.0))
                    .put("p50Ms", latency.percentileMillis(50)).put("p95Ms", latency.percentileMillis(95)).put("p99Ms", latency.percentileMillis(99))
                    .put("maxMs", latency.maxMillis()).put("meanMs", latency.meanMillis()).put("firstFailure", firstFailure);
        }
    }

    /** The database's counters at one instant. */
    record Snapshot(long walBytes, long bodiesBytes, long parked) {}

    /** A status and a body. */
    record Answer(int status, Buffer body) {}

    /** What a run drives: the client, the bench's own pool, the ports and the body. */
    record Harness(Options o, Vertx vertx, Pool pool, HttpClientAgent client, Buffer body, int in, int echo) {

        /** The answer the flow gives a request it took: the reply of a sync flow, else 202. */
        int accepted() {
            return o.scenario() == Scenario.SYNC ? 200 : 202;
        }

        /** The scenario's window, {@code recorded} messages of the node recorded before it (the warmup's). */
        JsonObject measure(int recorded) {
            return switch (o.scenario()) {
                case SYNC -> {
                    JsonObject raw = load(echo, "/echo", o.messages(), 200).toJson(o.messages());
                    JsonObject flow = window(recorded);
                    yield flow.put("raw", raw).put("overheadP50Ms", flow.getDouble("p50Ms") - raw.getDouble("p50Ms"));
                }
                case ACK -> window(recorded);
                case WORKER -> worker(recorded);
            };
        }

        /**
         * The flow's requests, the window their own: its length is the load's, the after-snapshot is taken once what the window
         * accepted landed — a sync message's recording closes after its reply, an ack's later steps are the worker's — so the
         * rates hold the window's bytes, divided by the load's duration.
         */
        private JsonObject window(int recorded) {
            Snapshot before = snapshot();
            Window w = load(in, "/in", o.messages(), accepted());
            settle(recorded + o.messages() - w.failed(), DRAIN);
            Snapshot after = snapshot();
            return w.toJson(o.messages()).mergeIn(rates(before, after, w.elapsedNanos()));
        }

        /**
         * The items queued and run, the window from the first request until they landed (the queue empty); the latency is claim →
         * complete. The record also carries the ingress' own window ({@code ingress}) and {@code drainTailMs}, from the last
         * ingress answer until the queue was empty: which side set the rate.
         */
        private JsonObject worker(int recorded) {
            pool.query("truncate bench_work_log").execute().await();
            Snapshot before = snapshot();
            long started = System.nanoTime();
            Window queued = load(in, "/in", o.messages(), accepted());
            long answered = System.nanoTime();
            settle(recorded + o.messages() - queued.failed(), DRAIN);
            long drained = System.nanoTime();
            Snapshot after = snapshot();
            LatencyStats claimToComplete = new LatencyStats(o.messages());
            for (Row r : pool.query(CLAIM_TO_COMPLETE).execute().await()) claimToComplete.record(r.getLong(0));
            return new Window(claimToComplete, failed(queued, before, after), drained - started, queued.firstFailure()).toJson(o.messages())
                    .mergeIn(rates(before, after, drained - started)).put("ingress", queued.toJson(o.messages()))
                    .put("drainTailMs", (drained - answered) / 1_000_000.0);
        }

        /** A worker window's failures: the requests the ingress did not take, and the items parked in the window. */
        static int failed(Window queued, Snapshot before, Snapshot after) {
            return queued.failed() + Math.toIntExact(after.parked() - before.parked());
        }

        static JsonObject rates(Snapshot before, Snapshot after, long elapsedNanos) {
            double seconds = elapsedNanos / 1_000_000_000.0;
            double bodies = (after.bodiesBytes() - before.bodiesBytes()) / seconds;
            return new JsonObject().put("walBytesPerSecond", (after.walBytes() - before.walBytes()) / seconds).put("bodiesBytesPerSecond", bodies)
                    .put("bodiesGrowthPerHour", bodies * 3600);
        }

        Snapshot snapshot() {
            Row r = pool.query("select pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint, pg_total_relation_size('bodies'), (select count(*) from dlq)")
                    .execute().await().iterator().next();
            return new Snapshot(r.getLong(0), r.getLong(1), r.getLong(2));
        }

        /**
         * Waits until {@code messages} holds at least {@code recorded} messages and the work queue is empty, at most
         * {@code within} (a run waits {@link #DRAIN}).
         */
        void settle(long recorded, Duration within) {
            long deadline = System.nanoTime() + within.toNanos();
            String landed = "select (select count(distinct id) from messages) >= " + recorded + " and not exists (select 1 from work)";
            while (!pool.query(landed).execute().await().iterator().next().getBoolean(0)) {
                if (System.nanoTime() - deadline > 0) {
                    throw new IllegalStateException(recorded + " recorded messages and an empty work queue did not land within " + within);
                }
                vertx.timer(5).await();
            }
        }

        void deploy(int admin, byte[] zip) {
            Answer a = exchange(HttpMethod.PUT, admin, "/admin/packages/bench", "application/zip", Buffer.buffer(zip));
            if (a.status() != 200) throw new IllegalStateException("the node refused the bench package: " + a.status() + " " + a.body());
        }

        /** {@code count} requests of the flow, answered as it takes them. */
        Window load(int port, String path, int count) {
            return load(port, path, count, accepted());
        }

        /**
         * {@code count} POSTs of the body to 127.0.0.1:{@code port}{@code path} from {@code concurrency} virtual threads, each
         * sending its share one after the other; latency = request to the answer's last byte, a failure any other status or a
         * request that failed.
         */
        Window load(int port, String path, int count, int status) {
            LatencyStats latency = new LatencyStats(Math.max(1, count));
            AtomicInteger next = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicReference<String> first = new AtomicReference<>();
            long started = System.nanoTime();
            try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < o.concurrency(); c++) {
                    senders.execute(() -> {
                        for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) {
                            long t0 = System.nanoTime();
                            String failure = send(port, path, status);
                            latency.record(System.nanoTime() - t0);
                            if (failure != null) {
                                failed.incrementAndGet();
                                first.compareAndSet(null, failure);
                            }
                        }
                    });
                }
            }
            return new Window(latency, failed.get(), System.nanoTime() - started, first.get());
        }

        /** Null when the answer has {@code status}, else what went wrong. */
        private String send(int port, String path, int status) {
            try {
                Answer a = exchange(HttpMethod.POST, port, path, "application/xml", body);
                return a.status() == status ? null : "HTTP " + a.status() + ": " + a.body();
            } catch (Exception failure) {   // await rethrows a checked cause (a refused connection) sneakily
                return String.valueOf(failure);
            }
        }

        /**
         * One request and its whole answer. The response is paused in the callback that delivers it, before the send, so its
         * body cannot end before {@code body()} is attached on a later task (FIX-21).
         */
        Answer exchange(HttpMethod method, int port, String path, String contentType, Buffer content) {
            return client.request(method, port, "127.0.0.1", path).compose(req -> {
                req.response().onSuccess(HttpClientResponse::pause);
                return req.putHeader("content-type", contentType).send(content);
            }).compose(res -> {
                Future<Buffer> answer = res.body();
                res.resume();
                return answer.map(bytes -> new Answer(res.statusCode(), bytes));
            }).await();
        }
    }
}
