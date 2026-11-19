package dev.rail8.steps.mapping;

import dev.rail8.api.plugin.AdminReply;
import dev.rail8.api.plugin.AdminRequest;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.mapping.studio.Studio;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping studio's services of the admin API: one for each operation, each answering the studio's reply. The engine's work
 * runs on a platform thread of its own, and a call returns only once that thread has ended, also when the call is interrupted.
 */
class StudioServiceTest {

    private static final Clock UTC = Clock.fixed(Instant.parse("2026-03-04T10:00:00Z"), ZoneOffset.UTC);

    /** The current-date mapping of the step's test package, with the files of its bundles, by reference. */
    private static final String MAPPING = "/package/mappings/current-date.mapping.json";
    private static final Map<String, String> FILES = Map.of(
            "../structures/src-orders.xsd", "/package/structures/src-orders.xsd",
            "../structures/tgt-list.xsd", "/package/structures/tgt-list.xsd");

    private static final String ORDERS = "<Orders><Order><Line><Qty>7</Qty></Line></Order></Orders>";

    private static List<AdminService> services() {
        return new MappingPlugin().services(PluginSettings.of("mapping", Map.of()));
    }

    private static AdminService service(String path) {
        return services().stream().filter(s -> s.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void theModuleBringsAServiceForEachOperationOfTheStudio() {
        assertEquals(List.of("GET catalogue", "POST canonical", "POST create", "POST structure", "POST compile", "POST test",
                "POST refresh"), services().stream().map(s -> s.method() + " " + s.path()).toList());
    }

    /** A test run's output is bounded so that the reply that carries it can be the largest a service may give. */
    @Test
    void theStudiosOutputBoundIsTheLargestReply() {
        assertEquals(AdminService.MAX_REPLY_BYTES, Studio.MAX_OUTPUT_BYTES);
    }

    @Test
    void aServiceAnswersTheStudiosReply() throws InterruptedException {
        AdminReply catalogue = service("catalogue").handle(new AdminRequest("", UTC));
        assertEquals(200, catalogue.status());
        assertTrue(catalogue.json().contains("\"useOneAsMany\""), catalogue.json());

        AdminReply compiled = service("compile").handle(new AdminRequest(call(resource(MAPPING), true), UTC));
        assertEquals(new AdminReply(200, "{\"diagnostics\":[]}"), compiled);

        AdminReply needed = service("compile").handle(new AdminRequest(call(resource(MAPPING), false), UTC));
        assertEquals(409, needed.status());
        assertTrue(needed.json().contains("\"references\":[\"../structures/src-orders.xsd\",\"../structures/tgt-list.xsd\"]"),
                needed.json());
    }

    /** 23:30 in UTC on 4 March is 5 March one hour ahead: the run writes the date in the node's zone. */
    @Test
    void aTestRunSeesTheNodesClockAndItsZone() throws InterruptedException {
        Clock clock = Clock.fixed(Instant.parse("2026-03-04T23:30:00Z"), ZoneOffset.ofHours(1));

        AdminReply reply = service("test").handle(new AdminRequest(call(testRequest(), true), clock));

        assertEquals(200, reply.status(), reply.json());
        assertTrue(reply.json().contains("<All>2026/03/05</All>"), reply.json());
    }

    /**
     * A call interrupted while its test run holds in the clock is not over: it waits for the engine's thread, which waits for
     * the run's, and it throws the interrupt only once both have ended. Until then the call holds its place.
     */
    @Test
    void anInterruptedCallReturnsOnlyOnceTheEnginesThreadHasEnded() throws Exception {
        HeldClock clock = new HeldClock();
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(service("test").handle(new AdminRequest(call(testRequest(), true), clock)));
            } catch (InterruptedException | RuntimeException e) {
                outcome.set(e);
            }
        });
        clock.read.await();
        Thread run = clock.reader.get();
        assertEquals("rail8 test run", run.getName(), "the clock is read by the test run's own thread");
        Thread engine = thread("rail8 mapping test");

        try {
            call.interrupt();
            // the engine's thread waits for the run's once the interrupt reached it; the call then waits for the engine's
            while (call.isAlive() && !(joining(engine) && joining(call))) {
                Thread.onSpinWait();
            }

            assertTrue(call.isAlive(), "the call has not returned while the engine's thread holds");
        } finally {
            clock.released.countDown();
            call.join();
        }
        assertInstanceOf(InterruptedException.class, outcome.get());
        assertFalse(engine.isAlive(), "the engine's thread has ended");
        assertFalse(run.isAlive(), "the test run's thread has ended");
    }

    /** A call of the studio: the request, and the files of the mapping's bundles when {@code files}. */
    private static String call(byte[] request, boolean files) {
        StringBuilder call = new StringBuilder("{\"request\": \"").append(base64(request)).append('"');
        if (files) {
            call.append(", \"files\": {");
            String separator = "";
            for (Map.Entry<String, String> file : FILES.entrySet()) {
                call.append(separator).append('"').append(file.getKey()).append("\": \"").append(base64(resource(file.getValue())))
                        .append('"');
                separator = ", ";
            }
            call.append('}');
        }
        return call.append('}').toString();
    }

    /** A test run of the current-date mapping over {@link #ORDERS}. */
    private static byte[] testRequest() {
        String model = new String(resource(MAPPING), StandardCharsets.UTF_8);
        return ("{\"model\": " + model + ", \"input\": \"" + ORDERS + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] resource(String name) {
        try (InputStream in = StudioServiceTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("no resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The live platform thread named {@code name}. */
    private static Thread thread(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    /** Whether {@code thread} waits inside {@code Thread.join}. */
    private static boolean joining(Thread thread) {
        return Stream.of(thread.getStackTrace())
                .anyMatch(f -> f.getClassName().equals(Thread.class.getName()) && f.getMethodName().equals("join"));
    }

    /** A clock whose reader holds, deaf to interrupts, until it is released; its interrupt is kept. */
    private static final class HeldClock extends Clock {

        private final CountDownLatch read = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<Thread> reader = new AtomicReference<>();

        @Override
        public Instant instant() {
            reader.set(Thread.currentThread());
            read.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    released.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return Instant.EPOCH;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
    }
}
