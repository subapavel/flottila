package io.flottila.bench;

import io.flottila.runtime.node.NodeConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream-heap run's parts that need no database and no node (P1-10c): the child's command (a JVM that runs out of memory
 * with it exits and says so on stderr) and environment, the stop at the end of the child's stdin, the heap
 * sample asked over the child's stdin and read from its stdout, the generated body's hash, the partner's echo and hash, and a
 * request that fails, and a run whose database cannot be reached.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StreamHeapMainPartsTest {

    Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() {
        vertx.close().await();
    }

    @Test
    void theChildRunsTheStreamHeapNodeAtTheRunsXmxAndExitsOnAnOutOfMemoryError() {
        List<String> command = StreamHeapMain.command("96m");
        assertTrue(command.contains("-Xmx96m"), "the child's heap is --xmx: " + command);
        assertTrue(command.contains("-XX:+ExitOnOutOfMemoryError"), command.toString());
        assertTrue(command.contains("-XX:+DisplayVMOutputToStderr"), "stdout carries the heap answers only: " + command);
        assertEquals(StreamHeapNode.class.getName(), command.getLast());
    }

    @Test
    void aChildOutOfMemoryExitsAndItsLogNamesTheError(@TempDir Path dir) throws Exception {
        List<String> command = new ArrayList<>(StreamHeapMain.command("16m"));
        command.set(command.size() - 1, Oom.class.getName());   // the run's JVM options, a main that runs out of memory
        Path log = dir.resolve("oom.log");
        Process child = StreamHeapMain.startNode(command, Map.of(), log);
        try {
            assertEquals(0, child.getInputStream().readAllBytes().length, "the JVM's own output goes to stderr, not to stdout");
            assertEquals(3, child.waitFor(), "-XX:+ExitOnOutOfMemoryError ends the JVM");
            assertTrue(StreamHeapMain.oom(log), "the log names the OutOfMemoryError");
        } finally {
            child.destroyForcibly().waitFor();
        }
        Path clean = dir.resolve("clean.log");
        Files.writeString(clean, "node closed\n");
        assertFalse(StreamHeapMain.oom(clean));
    }

    @Test
    void aChildThatExitsAtTheEndOfItsStdinIsStoppedAndOneThatDoesNotIsRefused(@TempDir Path dir) throws Exception {
        Process cat = new ProcessBuilder("cat").start();
        try {
            StreamHeapMain.stop(new PrintStream(cat.getOutputStream(), true, StandardCharsets.UTF_8), cat, Duration.ofSeconds(30), dir.resolve("cat.log"));
            assertEquals(0, cat.exitValue(), "cat ends at the end of its stdin");
        } finally {
            cat.destroyForcibly().waitFor();
        }
        Process sleep = new ProcessBuilder("sleep", "60").start();
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StreamHeapMain.stop(new PrintStream(sleep.getOutputStream(), true, StandardCharsets.UTF_8), sleep, Duration.ofMillis(100), dir.resolve("sleep.log")));
            assertTrue(refused.getMessage().contains("sleep.log"), "names the child's output: " + refused.getMessage());
        } finally {
            sleep.destroyForcibly().waitFor();
        }
    }

    @Test
    void aChildThatExitedAnswersNoHeapAndIsStoppedAllTheSame(@TempDir Path dir) throws Exception {
        Process gone = new ProcessBuilder("true").start();
        try {
            assertEquals(0, gone.waitFor());
            PrintStream asks = new PrintStream(gone.getOutputStream(), true, StandardCharsets.UTF_8);
            assertEquals(new StreamHeapNode.Heap(-1, -1), StreamHeapMain.heap(asks, gone.inputReader(StandardCharsets.UTF_8)), "an exited child answers nothing");
            StreamHeapMain.stop(asks, gone, Duration.ofSeconds(30), dir.resolve("gone.log"));   // an OutOfMemoryError ended it: the run still writes its record
        } finally {
            gone.destroyForcibly().waitFor();
        }
    }

    @Test
    void theNodeEnvironmentIsADevNodeOnTheRunsDatabase() {
        StreamHeapMain.Options o = StreamHeapMain.options(new String[] {"--db", "x_bench", "--host", "db.local", "--port", "6543", "--user", "u", "--password", "p"});
        NodeConfig node = NodeConfig.from(StreamHeapMain.nodeEnv(o, Path.of("/data/node"), 4321));
        assertEquals(Path.of("/data/node"), node.dataDir());
        assertEquals(4321, node.admin().port());
        assertEquals("db.local", node.pg().host());
        assertEquals(6543, node.pg().port());
        assertEquals("x_bench", node.pg().database());
        assertEquals("u", node.pg().user());
        assertEquals("p", node.pg().password());
    }

    @Test
    void aHeapSampleIsAskedWithOneLineAndReadFromTheAnswer() throws Exception {
        ByteArrayOutputStream asked = new ByteArrayOutputStream();
        StreamHeapNode.Heap heap = StreamHeapMain.heap(new PrintStream(asked, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("123 456\n")));
        assertEquals(new StreamHeapNode.Heap(123, 456), heap);
        assertEquals("heap" + System.lineSeparator(), asked.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aChildThatAnsweredNothingGivesMinusOne() throws Exception {
        StreamHeapNode.Heap heap = StreamHeapMain.heap(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));
        assertEquals(new StreamHeapNode.Heap(-1, -1), heap, "the child exited (an OutOfMemoryError ends it)");
    }

    @Test
    void thePartnerEchoesTheSentBodyAndHashesItAsTheRunDoes() throws Exception {
        StreamHeapMain.Partner partner = new StreamHeapMain.Partner();
        HttpServer echo = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await();
        HttpClientAgent client = vertx.createHttpClient();
        long size = 3L * StreamHeapMain.CHUNK + 5;
        try {
            assertEquals(new StreamHeapMain.Reply(200, 7), StreamHeapMain.send(client, echo.actualPort(), 7), "a first request");
            StreamHeapMain.Reply reply = StreamHeapMain.send(client, echo.actualPort(), size);
            assertEquals(new StreamHeapMain.Reply(200, size), reply, "the echo answered every byte");
            assertEquals(StreamHeapMain.sha256(size), partner.sha256.get(), "the partner hashed the second request alone, as the run hashes the body");

            HttpClientRequest cut = client.request(HttpMethod.POST, echo.actualPort(), "127.0.0.1", "/echo").await();
            cut.setChunked(true).write(Buffer.buffer("abc")).await();
            assertTrue(LedgerMain.until(vertx, Duration.ofSeconds(10), () -> partner.sha256.get() == null),
                    "a request the partner reads has no hash until it ended, not the previous one's");
            cut.reset().await();
            assertEquals(new StreamHeapMain.Reply(200, size), StreamHeapMain.send(client, echo.actualPort(), size));
            assertEquals(StreamHeapMain.sha256(size), partner.sha256.get(), "a request after a cut one is hashed alone");
        } finally {
            client.close().await();
            echo.close().await();
        }
    }

    /** Runs out of memory: at the run's JVM options the JVM says so on stderr and exits 3. */
    static final class Oom {
        public static void main(String[] args) {
            List<long[]> held = new ArrayList<>();
            while (true) held.add(new long[1 << 17]);
        }
    }

    @Test
    void aRunThatCannotReachItsDatabaseThrowsTheFailureItself() {
        Throwable failed = assertThrows(Throwable.class, () -> StreamHeapMain.run(new String[] {"--host", "127.0.0.1", "--port", "1", "--db", "x_bench"}));
        assertInstanceOf(RuntimeException.class, failed, "Flyway's connect failure, not the virtual thread's wrapper: " + failed);
    }

    @Test
    void aRequestThatFailsIsStatusMinusOne() throws Exception {
        HttpClientAgent client = vertx.createHttpClient();
        try {
            assertEquals(new StreamHeapMain.Reply(-1, 0), StreamHeapMain.send(client, NodeBenchMain.freePort(), 10), "nothing listens on the port");
        } finally {
            client.close().await();
        }
    }
}
