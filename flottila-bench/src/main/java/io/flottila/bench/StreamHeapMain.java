package io.flottila.bench;

import io.flottila.runtime.db.Migrations;
import io.flottila.testkit.GeneratedReadStream;
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
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GATE 9 "a 50 MB body through a stream step without heap growth" (P1-10c, ARCHITECTURE §13 Phase 1). The node runs as a child
 * JVM at {@code -Xmx<--xmx>} ({@link StreamHeapNode} on this JVM's classpath, its configuration in a cleared environment, its
 * stderr in {@code <out>.node.log}); the run PUTs a package through its admin API whose sync flow takes HTTP on 127.0.0.1, runs
 * the bench's stream step ({@link StreamCopy}, named {@value #STEP}) and sends the copy {@code to partner}, a Vert.x echo in
 * this JVM that hashes (SHA-256) what it reads while it streams it back. The flow's and the partner's max body is the body's
 * size. One {@value #WARMUP_BYTES}-byte message warms the flow first (first-use allocations are not growth); then the child's
 * heap is sampled after a forced GC ({@link StreamHeapNode#sample}, asked and answered over the child's stdin and stdout), one
 * {@code --mb} MiB body ({@link GeneratedReadStream}: never in memory at once, here or in the child) is sent and its reply
 * drained, and the heap is sampled again. Closing the child's stdin closes its node, its recording flushed, before the JVM
 * exits; the run waits for that, fails when it took longer than {@link #STOP} (the child is killed whenever the run ends),
 * and writes the record to {@code --out}.
 *
 * <p>The record: {@code mb}, {@code xmx}, {@code maxHeapBytes} (the child's heap max), {@code heapBeforeBytes},
 * {@code heapAfterBytes}, {@code heapGrowthBytes} (after − before), {@code sentSha256}, {@code partnerSha256} (hex; null when
 * the partner did not read a whole body), {@code oom} (the child's log names an {@code OutOfMemoryError}: it runs with
 * {@code -XX:+ExitOnOutOfMemoryError}, so its heap samples are -1 after one), {@code replyStatus} and {@code replyBytes} (the
 * sync reply; -1 and 0 when the request failed), {@code elapsedMs} (the body's request to its reply's end), {@code machine},
 * {@code commit}, {@code date}. Exit 0 only when the partner read the bytes sent, the node did not run out of memory and the
 * heap grew less than {@value #MAX_GROWTH_BYTES} bytes; else 3 ({@link #exitCode}).
 *
 * <p>Arguments: {@code --mb} (50, at least 1), {@code --xmx} (64m), {@code --host --port --db --user --password} as the node
 * bench ({@code --db} must name a bench database: the run truncates every table in it), {@code --out}
 * (bench/out/stream-heap.json), {@code --commit}. The options are refused before anything connects.
 *
 * @since 0.1
 */
public final class StreamHeapMain {

    /** One run's command line. */
    public record Options(int mb, String xmx, String host, int port, String db, String user, String password, Path out, String commit) {}

    static final long MIB = 1024L * 1024;
    /** The growth of the heap after a forced GC the run tolerates: below it. */
    static final long MAX_GROWTH_BYTES = 8 * MIB;
    /** The warm-up message's size. */
    static final long WARMUP_BYTES = 64 * 1024;
    /** The sent body's chunks. */
    static final int CHUNK = 64 * 1024;
    /** The flow's stream step's name: its snapshot rows carry it. */
    static final String STEP = "copy";
    /** How long the node may take to serve {@code /health}. */
    static final Duration START = Duration.ofSeconds(60);
    /** How long the node may take to close and exit once its stdin ended. */
    static final Duration STOP = Duration.ofMinutes(2);

    private StreamHeapMain() {}

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /**
     * The whole run on a virtual thread of its own (Vert.x futures are awaited); returns the exit code and rethrows what the run
     * threw. The options are refused before anything connects.
     */
    public static int run(String[] args) throws Exception {
        Options o = options(args);
        FutureTask<Integer> run = new FutureTask<>(() -> measure(o));
        Thread.ofVirtual().name("stream-heap").start(run).join();
        try {
            return run.get();
        } catch (ExecutionException failed) {
            throw NodeBenchMain.<RuntimeException>sneaky(failed.getCause());
        }
    }

    /** {@code args} as options; an {@code --mb} below 1 and a database that is no bench one are refused. */
    public static Options options(String[] args) {
        Map<String, String> a = BenchMain.parse(args);
        int mb = Integer.parseInt(a.getOrDefault("mb", "50"));
        if (mb < 1) throw new IllegalArgumentException("--mb must be at least 1 (the body's MiB), was " + mb);
        String db = a.getOrDefault("db", "flottila_bench");
        if (!db.contains("bench")) {
            throw new IllegalArgumentException("--db '" + db + "' does not name a bench database (its name contains 'bench'): the run truncates every table in it");
        }
        return new Options(mb, a.getOrDefault("xmx", "64m"), a.getOrDefault("host", "localhost"), Integer.parseInt(a.getOrDefault("port", "5432")),
                db, a.getOrDefault("user", "flottila"), a.getOrDefault("password", "flottila"),
                Path.of(a.getOrDefault("out", "bench/out/stream-heap.json")), a.getOrDefault("commit", System.getenv().getOrDefault("GIT_COMMIT", "unknown")));
    }

    /**
     * 0 when the partner read the bytes sent ({@code partnerSha256 == sentSha256}), the node did not run out of memory and the
     * heap after a forced GC grew less than {@value #MAX_GROWTH_BYTES} bytes; else 3, each failed condition named on stderr.
     */
    static int exitCode(JsonObject record) {
        List<String> failed = new ArrayList<>();
        String sent = record.getString("sentSha256");
        String partner = record.getString("partnerSha256");
        if (!sent.equals(partner)) failed.add("the partner read sha256 " + partner + ", not the sent " + sent);
        if (record.getBoolean("oom")) failed.add("the node ran out of memory");
        long growth = record.getLong("heapGrowthBytes");
        if (growth >= MAX_GROWTH_BYTES) failed.add("the heap after a forced GC grew " + growth + " bytes, not below " + MAX_GROWTH_BYTES);
        if (failed.isEmpty()) return 0;
        System.err.println("the stream heap run does not hold: " + String.join("; ", failed));
        return 3;
    }

    /** The node bench's options for this run's database: its pool and its harness (health, deploy). */
    static NodeBenchMain.Options bench(Options o) {
        return new NodeBenchMain.Options(NodeBenchMain.Scenario.SYNC, NodeBenchMain.Recording.KEEP, NodeBenchMain.Step.PASSTHROUGH, 1, 1, 1,
                "stream-heap", o.out(), true, o.host(), o.port(), o.db(), o.user(), o.password(), o.commit());
    }

    static int measure(Options o) throws Exception {
        Path run = Files.createTempDirectory("flottila-stream-heap-");
        try {
            Migrations.migrate("jdbc:postgresql://" + o.host() + ":" + o.port() + "/" + o.db(), o.user(), o.password());
            Vertx vertx = Vertx.vertx();
            try {
                Pool setup = NodeBenchMain.pool(vertx, bench(o), 1);
                try {
                    setup.query(NodeBenchMain.PREPARE).execute().await();
                } finally {
                    setup.close().await();
                }
                return pass(o, vertx, run);
            } finally {
                vertx.close().await();
            }
        } finally {
            NodeBenchMain.delete(run);
        }
    }

    private static int pass(Options o, Vertx vertx, Path run) throws Exception {
        Files.createDirectories(o.out().toAbsolutePath().getParent());
        long size = o.mb() * MIB;
        String sent = sha256(size);
        Partner partner = new Partner();
        HttpServer echo = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await();
        HttpClientAgent client = vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(1));
        int in = NodeBenchMain.freePort();
        int admin = NodeBenchMain.freePort();
        Path log = o.out().resolveSibling(o.out().getFileName() + ".node.log");
        JsonObject json = new JsonObject();
        Process node = startNode(command(o.xmx()), nodeEnv(o, Files.createDirectories(run.resolve("node")), admin), log);
        try {
            NodeBenchMain.Harness h = new NodeBenchMain.Harness(bench(o), vertx, null, client, Buffer.buffer(), in, echo.actualPort());
            LedgerMain.awaitServing(vertx, h, node, admin, START, log);
            h.deploy(admin, streamPackage(size, in, echo.actualPort()));
            PrintStream asks = new PrintStream(node.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader answers = node.inputReader(StandardCharsets.UTF_8);
            send(client, in, WARMUP_BYTES);
            StreamHeapNode.Heap before = heap(asks, answers);
            long started = System.nanoTime();
            Reply reply = send(client, in, size);
            json.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0).put("replyStatus", reply.status()).put("replyBytes", reply.bytes());
            StreamHeapNode.Heap after = heap(asks, answers);
            json.put("maxHeapBytes", before.max()).put("heapBeforeBytes", before.used()).put("heapAfterBytes", after.used())
                    .put("heapGrowthBytes", after.used() - before.used());
            stop(asks, node, STOP, log);
        } finally {
            node.destroyForcibly().waitFor();   // nothing when it exited; else dead before its data directory is deleted
            client.close().await();
            echo.close().await();
        }
        json.put("mb", o.mb()).put("xmx", o.xmx()).put("sentSha256", sent).put("partnerSha256", partner.sha256.get())
                .put("oom", oom(log)).put("machine", BenchMain.machine())
                .put("date", Instant.now().toString()).put("commit", o.commit());
        Files.writeString(o.out(), json.encodePrettily() + "\n");
        System.out.println(json.encodePrettily());
        return exitCode(json);
    }

    /** The child's command: this JVM's java and classpath (the test class path under surefire / failsafe), {@code -Xmx<xmx>}, {@link StreamHeapNode}. */
    static List<String> command(String xmx) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx" + xmx, "-XX:+ExitOnOutOfMemoryError",
                "-XX:+DisplayVMOutputToStderr", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                StreamHeapNode.class.getName());
    }

    /** The node's environment: env dev, the run's database, admin on 127.0.0.1:{@code admin}, its data directory. */
    static Map<String, String> nodeEnv(Options o, Path data, int admin) {
        return Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "stream-heap-node", "FLOTTILA_ADMIN_PORT", String.valueOf(admin),
                "FLOTTILA_DATA_DIR", data.toString(), "FLOTTILA_PG_HOST", o.host(), "FLOTTILA_PG_PORT", String.valueOf(o.port()),
                "FLOTTILA_PG_DATABASE", o.db(), "FLOTTILA_PG_USER", o.user(), "FLOTTILA_PG_PASSWORD", o.password());
    }

    /**
     * Closes the child's stdin, {@code asks} — it closes its node, its recording flushed, and exits — and waits for it to exit;
     * refuses a child that did not exit within {@code within}: its recording may not have landed. A child that exited already
     * (an OutOfMemoryError ended it) is stopped too: {@link PrintStream#close} keeps the failure of a write still buffered for
     * it to itself.
     */
    static void stop(PrintStream asks, Process node, Duration within, Path log) throws Exception {
        asks.close();
        if (!node.waitFor(within.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("the node did not exit within " + within + " of the end of its stdin; its output: " + log);
        }
    }

    /** Whether the child's log names an {@code OutOfMemoryError} (with {@code -XX:+ExitOnOutOfMemoryError} the JVM's own last line). */
    static boolean oom(Path log) throws IOException {
        return Files.readString(log).contains("OutOfMemoryError");
    }

    /** {@code command} with its environment {@code env} only, stdin and stdout piped to this JVM, stderr to {@code log}. */
    static Process startNode(List<String> command, Map<String, String> env, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(log.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        return builder.start();
    }

    /**
     * The package: the in endpoint on 127.0.0.1:{@code in}, the partner at the echo, and the sync flow {@code stream-heap},
     * every step recorded with its payload, {@code [stream-copy copy, to partner]}; the flow's and the partner's max body
     * {@code maxBodyBytes}.
     */
    static byte[] streamPackage(long maxBodyBytes, int in, int echo) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("package.yaml", NodeBenchMain.yaml(NodeBenchMain.doc("package").put("name", "bench").put("version", "0.1.0")));
        files.put("endpoints/in.yaml", NodeBenchMain.yaml(NodeBenchMain.doc("endpoint").put("id", "in").put("scheme", "http").put("direction", "in")
                .put("config", new JsonObject().put("host", "127.0.0.1").put("port", in).put("path", "/in"))));
        files.put("endpoints/partner.yaml", NodeBenchMain.yaml(NodeBenchMain.doc("endpoint").put("id", "partner").put("scheme", "http")
                .put("direction", "out").put("config", new JsonObject().put("url", "http://127.0.0.1:" + echo + "/echo")
                        .put("maxBodyBytes", maxBodyBytes).put("poolSize", 1))));
        files.put("flows/stream-heap.yaml", NodeBenchMain.yaml(NodeBenchMain.doc("flow").put("id", "stream-heap").put("version", "0.1.0")
                .put("from", new JsonObject().put("endpoint", "in")).put("delivery", new JsonObject().put("mode", "sync"))
                .put("recording", new JsonObject().put("level", "all").put("payload", "keep"))
                .put("limits", new JsonObject().put("maxBodyBytes", maxBodyBytes).put("maxInFlight", 4))
                .put("steps", new JsonArray().add(new JsonObject().put("type", StreamCopy.TYPE).put("name", STEP))
                        .add(new JsonObject().put("type", "to").put("name", "partner").put("endpoint", "partner")))));
        return NodeBenchMain.zip(files);
    }

    /** Asks the child for a heap sample (one line out) and reads its answer; -1 and -1 when it answered nothing (it exited). */
    static StreamHeapNode.Heap heap(PrintStream asks, BufferedReader answers) throws IOException {
        asks.println("heap");
        String answer = answers.readLine();
        if (answer == null) return new StreamHeapNode.Heap(-1, -1);
        String[] fields = answer.split(" ");
        return new StreamHeapNode.Heap(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
    }

    /** The sent body's SHA-256 (hex): the {@link GeneratedReadStream} bytes of a {@code size}-byte body, chunk by chunk. */
    static String sha256(long size) {
        MessageDigest digest = sha256Digest();
        byte[] chunk = new byte[CHUNK];
        for (long offset = 0; offset < size; offset += CHUNK) {
            int n = (int) Math.min(CHUNK, size - offset);
            for (int i = 0; i < n; i++) chunk[i] = GeneratedReadStream.byteAt(offset + i);
            digest.update(chunk, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** A fresh SHA-256 digest. */
    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {   // every JDK has SHA-256 (MessageDigest's required algorithms)
            throw new IllegalStateException(absent);
        }
    }

    /** A status and the reply's length. */
    record Reply(int status, long bytes) {}

    /**
     * POSTs a {@code size}-byte {@link GeneratedReadStream} body to 127.0.0.1:{@code port}/in and drains the reply, counting
     * its bytes; status -1 and 0 bytes when the request failed (a refused or cut connection).
     */
    static Reply send(HttpClientAgent client, int port, long size) {
        try {
            return client.request(new RequestOptions().setMethod(HttpMethod.POST).setHost("127.0.0.1").setPort(port).setURI("/in")).compose(req -> {
                req.response().onSuccess(HttpClientResponse::pause);   // FIX-21: the body cannot end before it is attached
                return req.putHeader("content-type", "application/octet-stream").putHeader("content-length", String.valueOf(size))
                        .send(new GeneratedReadStream(size, CHUNK));
            }).compose(res -> {
                AtomicLong bytes = new AtomicLong();
                res.handler(b -> bytes.addAndGet(b.length()));
                Future<Void> end = res.end();
                res.resume();
                return end.map(v -> new Reply(res.statusCode(), bytes.get()));
            }).await();
        } catch (Exception failed) {   // await rethrows a cut or refused connection (checked) sneakily
            return new Reply(-1, 0);
        }
    }

    /**
     * The partner: streams every request back as it reads it (paused while its answer's write queue is full) and hashes it;
     * {@link #sha256} is the hex SHA-256 of the last request once it was read to its end, null while it is read.
     */
    static final class Partner implements Handler<HttpServerRequest> {
        final AtomicReference<String> sha256 = new AtomicReference<>();

        @Override
        public void handle(HttpServerRequest req) {
            sha256.set(null);
            MessageDigest digest = sha256Digest();
            HttpServerResponse res = req.response().setChunked(true);
            req.handler(chunk -> {
                digest.update(chunk.getBytes());
                res.write(chunk);
                if (res.writeQueueFull()) {
                    req.pause();
                    res.drainHandler(v -> req.resume());
                }
            });
            req.endHandler(v -> {
                sha256.set(HexFormat.of().formatHex(digest.digest()));
                res.end();
            });
        }
    }
}
