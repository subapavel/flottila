package io.flottila.bench;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream-heap run's child node in this JVM on a Postgres container (P1-10c): {@link StreamHeapNode#run} starts a node from
 * its environment that knows the bench's {@code stream-copy} step (the run's package deploys on it), answers a heap sample for
 * a line asked, and closes the node when its stdin ends, before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class StreamHeapNodeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");

    @TempDir Path dir;

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void theNodeServesTheStreamStepAnswersHeapSamplesAndClosesAtTheEndOfItsInput() throws Exception {
        int admin = NodeBenchMain.freePort();
        StreamHeapMain.Options o = StreamHeapMain.options(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)),
                "--db", "x_bench", "--user", pg.getUsername(), "--password", pg.getPassword()});
        Map<String, String> env = new HashMap<>(StreamHeapMain.nodeEnv(o, dir, admin));
        env.put("FLOTTILA_PG_DATABASE", pg.getDatabaseName());
        Pipe in = Pipe.open();
        Pipe out = Pipe.open();
        OutputStream asks = Channels.newOutputStream(in.sink());
        BufferedReader answers = new BufferedReader(new InputStreamReader(Channels.newInputStream(out.source()), StandardCharsets.UTF_8));
        CompletableFuture<Void> node = CompletableFuture.runAsync(() -> {
            try {
                StreamHeapNode.run(env, Channels.newInputStream(in.source()), new PrintStream(Channels.newOutputStream(out.sink()), false, StandardCharsets.UTF_8));
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        Vertx vertx = Vertx.vertx();
        HttpClientAgent client = vertx.createHttpClient();
        try {
            NodeBenchMain.Harness h = new NodeBenchMain.Harness(StreamHeapMain.bench(o), vertx, null, client, Buffer.buffer(), 0, 0);
            assertTrue(LedgerMain.until(vertx, Duration.ofSeconds(60), () -> node.isDone() || LedgerMain.health(h, admin) == 200),
                    "the node served /health");
            h.deploy(admin, StreamHeapMain.streamPackage(1024 * 1024, NodeBenchMain.freePort(), NodeBenchMain.freePort()));   // refused without stream-copy

            asks.write("heap\n".getBytes(StandardCharsets.UTF_8));
            asks.flush();
            String[] answer = answers.readLine().split(" ");
            assertEquals(2, answer.length);
            assertTrue(Long.parseLong(answer[0]) > 0 && Long.parseLong(answer[0]) <= Long.parseLong(answer[1]), String.join(" ", answer));

            asks.close();   // the end of its input
            node.get(120, TimeUnit.SECONDS);
            assertEquals(-1, LedgerMain.health(h, admin), "the node closed before run returned");
        } finally {
            client.close().await();
            vertx.close().await();
        }
    }
}
