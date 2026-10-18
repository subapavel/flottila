package io.flottila.bench;

import io.flottila.api.Hasher;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.runtime.batch.PgBatchSink;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.time.VertxTimer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code bench/run.sh} entry point: migrates the bench database, then for each configuration
 * truncates the tables, warms up and runs {@link BenchRun} against native Postgres through
 * {@link PgBatchSink}; writes one JSON document to {@code --out}.
 * <p>Arguments: {@code --host --port --db --user --password --messages --warmup --concurrency
 * --batch-size --window-ms --commit-concurrency --out --sync-commit on|off --name}.
 *
 * @since 0.1
 */
public final class BenchMain {

    private BenchMain() {}

    public static void main(String[] args) throws IOException {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /** The whole run; returns the exit code (0 ok, 2 row-count mismatch, 3 failed saves) so tests can call it. */
    public static int run(String[] args) throws IOException {
        Map<String, String> a = parse(args);
        String name = a.getOrDefault("name", "batched-persistence");
        int messages = Integer.parseInt(a.getOrDefault("messages", "20000"));
        int warmup = Integer.parseInt(a.getOrDefault("warmup", "1000"));
        int concurrency = Integer.parseInt(a.getOrDefault("concurrency", "64"));
        BatchingWriter.Options batch = new BatchingWriter.Options(
                Integer.parseInt(a.getOrDefault("batch-size", "100")),
                Duration.ofMillis(Long.parseLong(a.getOrDefault("window-ms", "10"))),
                Integer.parseInt(a.getOrDefault("commit-concurrency", "4")),
                Integer.parseInt(a.getOrDefault("max-backlog", "6400")));
        boolean syncCommit = !"off".equalsIgnoreCase(a.getOrDefault("sync-commit", "on"));
        String host = a.getOrDefault("host", "localhost");
        int port = Integer.parseInt(a.getOrDefault("port", "5432"));
        String db = a.getOrDefault("db", "flottila_bench");
        String user = a.getOrDefault("user", "flottila");
        String password = a.getOrDefault("password", "flottila");
        Path out = Path.of(a.getOrDefault("out", "bench/out/" + name + ".json"));

        Migrations.Result migrated = Migrations.migrate("jdbc:postgresql://" + host + ":" + port + "/" + db, user, password);
        Vertx vertx = Vertx.vertx();
        try {
            PgConnectOptions connect = new PgConnectOptions().setHost(host).setPort(port).setDatabase(db).setUser(user)
                    .setPassword(password).setCachePreparedStatements(true);
            Pool pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(batch.commitConcurrency() + 2))
                    .withConnectHandler(conn -> conn.query("set synchronous_commit = " + (syncCommit ? "on" : "off")).execute()
                            .onComplete(ar -> conn.close()))
                    .using(vertx).build();
            try {
                pool.query("truncate messages, payloads").execute().await();
                PgBatchSink<BenchMessage> sink = new PgBatchSink<>(pool, V1MessageStatements.all());
                VertxTimer timer = new VertxTimer(vertx);
                Buffer body = Buffer.buffer("<invoice id=\"4711\"><customer>ACME</customer><amount currency=\"EUR\">1234.50</amount>"
                        + "<lines><line sku=\"A-1\" qty=\"2\"/><line sku=\"B-7\" qty=\"1\"/></lines></invoice>");
                Hasher hasher = Hasher.sha256();
                if (warmup > 0) {
                    BenchRun.run(new BenchRun.Config(name + "-warmup", warmup, concurrency, batch), sink, timer,
                            i -> BenchMessage.of("w-" + i, 0, "invoice", body, hasher));
                    pool.query("truncate messages, payloads").execute().await();
                }
                BenchRun.Report report = BenchRun.run(new BenchRun.Config(name, messages, concurrency, batch), sink, timer,
                        i -> BenchMessage.of("m-" + i, 0, "invoice", body, hasher));
                long rows = pool.query("select count(*) from messages").execute().await().iterator().next().getLong(0);
                // observed server state, not the flag: the connect handler must really have applied synchronous_commit
                String syncObserved = pool.query("show synchronous_commit").execute().await().iterator().next().getString(0);
                String pgVersion = pool.query("show server_version").execute().await().iterator().next().getString(0);
                JsonObject json = report.toJson()
                        .put("rowsInMessages", rows)
                        .put("syncCommit", "on".equals(syncObserved))
                        .put("postgres", pgVersion)
                        .put("schemaVersion", migrated.schemaVersion())
                        .put("bodyBytes", body.length())
                        .put("machine", machine())
                        .put("date", Instant.now().toString())
                        .put("commit", a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, json.encodePrettily() + "\n");
                System.out.println(json.encodePrettily());
                if (rows != report.saved()) { System.err.println("ROW COUNT MISMATCH: " + rows + " rows for " + report.saved() + " saves"); return 2; }
                if (report.failed() > 0) { System.err.println(report.failed() + " saves failed; first cause: " + report.firstFailure()); return 3; }
                return 0;
            } finally {
                pool.close().await();
            }
        } finally {
            vertx.close().await();
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException("missing value for --" + key);
            m.put(key, args[++i]);
        }
        return m;
    }

    /** The host's name from the environment (no DNS lookup, no failure path); only the fallback label nothing is compared against. */
    static String hostname() {
        return System.getenv().getOrDefault("HOSTNAME", "unknown-host");
    }

    static JsonObject machine() {
        return new JsonObject()
                .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .put("arch", System.getProperty("os.arch"))
                .put("cpus", Runtime.getRuntime().availableProcessors())
                .put("java", System.getProperty("java.version"))
                // the label picks the baseline a run is judged against (D-037): never a borrowed one, so the default is this host's name
                .put("label", System.getenv().getOrDefault("BENCH_MACHINE", "host:" + hostname()));
    }
}
