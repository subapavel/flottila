package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.flottila.runtime.node.NodeConfigPoolsTest.inProcess;
import static io.flottila.runtime.node.NodeConfigPoolsTest.read;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-172 P1 (CLAUDE.md: every limit finite): the longest wait for a pooled Postgres connection, {@code FLOTTILA_PG_POOL_WAIT}
 * (ISO-8601, PT0.001S..PT1M, default PT5S — the save timeout), and the most callers waiting per pool,
 * {@code FLOTTILA_PG_POOL_QUEUE} (1..65536, default 1024), read by {@link NodeConfig#from} and carried on {@link NodeConfig.Pg};
 * refused at start naming the key when not parseable or outside the range. The in-process constructors take the defaults.
 *
 * <p>The API assumed (packet FIX-172 P1, "last components"): {@code NodeConfig.Pg} gains the last components
 * {@code Duration poolWait} and {@code int poolQueue}, in that order (the canonical constructor's last two arguments); the 6-, 8-,
 * 9- and 10-argument constructors take PT5S and 1024.
 */
class NodeConfigPoolWaitTest {

    static final String WAIT = "FLOTTILA_PG_POOL_WAIT";
    static final String QUEUE = "FLOTTILA_PG_POOL_QUEUE";

    @Test
    void unsetTheWaitIsFiveSecondsAndTheQueue1024() {
        NodeConfig.Pg pg = read().pg();
        assertAll(
                () -> assertEquals(Duration.ofSeconds(5), pg.poolWait(), "FLOTTILA_PG_POOL_WAIT unset: PT5S, the save timeout"),
                () -> assertEquals(1024, pg.poolQueue(), "FLOTTILA_PG_POOL_QUEUE unset: 1024 waiting callers per pool"));
    }

    /** Both ends of each range and a value inside it are carried as given, never replaced by the default. */
    @ParameterizedTest(name = "FLOTTILA_PG_POOL_WAIT {0} → {1} ms")
    @CsvSource({"PT0.001S, 1", "PT0.2S, 200", "PT1M, 60000", "PT59.999S, 59999"})
    void theWaitIsAcceptedWithinItsRange(String raw, long millis) {
        assertEquals(Duration.ofMillis(millis), read(WAIT, raw).pg().poolWait(), WAIT + " " + raw + " is accepted as given");
    }

    @ParameterizedTest(name = "FLOTTILA_PG_POOL_QUEUE {0}")
    @ValueSource(ints = {1, 2, 17, 65535, 65536})
    void theQueueIsAcceptedWithinItsRange(int queue) {
        assertEquals(queue, read(QUEUE, String.valueOf(queue)).pg().poolQueue(), QUEUE + " " + queue + " is accepted as given");
    }

    /** One key set leaves the other at its default: the two are read each from its own key. */
    @Test
    void eachKeyIsReadFromItsOwn() {
        NodeConfig.Pg waitOnly = read(WAIT, "PT0.2S").pg();
        NodeConfig.Pg queueOnly = read(QUEUE, "1").pg();
        NodeConfig.Pg both = read(WAIT, "PT30S", QUEUE, "8").pg();
        assertAll(
                () -> assertEquals(1024, waitOnly.poolQueue(), "only the wait set: the queue keeps 1024"),
                () -> assertEquals(Duration.ofSeconds(5), queueOnly.poolWait(), "only the queue set: the wait keeps PT5S"),
                () -> assertEquals(List.of(Duration.ofSeconds(30), 8), List.of(both.poolWait(), both.poolQueue()), "both set: each as given"));
    }

    /** Below, above, unparseable: each refused at start, the refusal naming the key. */
    @Test
    void eachKeyIsRefusedOutsideItsRangeOrUnparseableNamingIt() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put(WAIT + "=PT0S", "PT0S");                // below PT0.001S: zero
        refused.put(WAIT + "=PT0.0009S", "PT0.0009S");      // below by 100 µs
        refused.put(WAIT + "=PT-1S", "PT-1S");              // negative
        refused.put(WAIT + "=PT1M0.001S", "PT1M0.001S");    // above PT1M by a millisecond
        refused.put(WAIT + "=PT2M", "PT2M");
        refused.put(WAIT + "=P1D", "P1D");
        refused.put(WAIT + "=5s", "5s");                    // not ISO-8601
        refused.put(WAIT + "=soon", "soon");
        refused.put(WAIT + "=", "");
        assertAll(refused.entrySet().stream().map(c -> () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(WAIT, c.getValue()), c.getKey() + " is refused");
            assertTrue(e.getMessage().contains(WAIT), c.getKey() + ": the refusal names the key: " + e.getMessage());
        }));

        List<String> queues = List.of("0", "-1", "65537", "2147483648", "many", "1.5", "");
        assertAll(queues.stream().map(raw -> () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(QUEUE, raw), QUEUE + "=" + raw + " is refused");
            assertTrue(e.getMessage().contains(QUEUE), QUEUE + "=" + raw + ": the refusal names the key: " + e.getMessage());
        }));
    }

    /** The in-process constructors carry the defaults; the canonical one carries what it is given, the wait then the queue. */
    @Test
    void theInProcessConstructorsCarryTheDefaults() {
        Map<String, NodeConfig.Pg> defaulted = new LinkedHashMap<>();
        defaulted.put("6-argument", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16));
        defaulted.put("8-argument", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.DISABLE, Optional.empty()));
        defaulted.put("9-argument", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.HA));
        defaulted.put("10-argument", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.VERIFY_CA,
                Optional.of(Path.of("/etc/flottila/pg-ca.pem")), NodeConfig.Pg.Durability.LOCAL, 7));
        assertAll(defaulted.entrySet().stream().map(c -> () -> assertEquals(List.of(Duration.ofSeconds(5), 1024),
                List.of(c.getValue().poolWait(), c.getValue().poolQueue()), "the " + c.getKey() + " constructor takes PT5S and 1024")));

        NodeConfig.Pg canonical = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, 7, Duration.ofMillis(250), 3);
        assertAll(
                () -> assertEquals(Duration.ofMillis(250), canonical.poolWait(), "the canonical constructor's wait as given"),
                () -> assertEquals(3, canonical.poolQueue(), "the canonical constructor's queue as given"),
                () -> assertEquals(7, canonical.intakePoolSize(), "and the share before them unchanged"));
    }

    static NodeConfig.Pg canonical(Duration poolWait, int poolQueue) {
        return new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, 5, poolWait, poolQueue);
    }

    /**
     * Mirrors {@link NodeConfigPoolsTest#thePgConstructorRefusesNoSplit}: the record checks nothing of the wait or the queue; the
     * node built over it does ({@link #anInProcessNodeRefusesAWaitOutsideItsRangeNamingTheKey}, {@link #anInProcessNodeRefusesAQueueOutsideItsRangeNamingTheKey}).
     */
    @Test
    void thePgConstructorRefusesNoWaitOrQueue() {
        NodeConfig.Pg pg = assertDoesNotThrow(() -> canonical(Duration.ZERO, 0), "an in-process caller's wait and queue are not checked by the record");
        assertEquals(List.of(Duration.ZERO, 0), List.of(pg.poolWait(), pg.poolQueue()), "carried as given");
    }

    /**
     * Review r1 (R1): an in-process node over the canonical {@code Pg} is held to the ranges the env reader enforces —
     * PT0.001S..PT1M and 1..65536 — and refused naming the key; a wait above Integer.MAX_VALUE ms (the int the client's
     * connection timeout takes) is refused, never narrowed.
     */
    @Test
    void anInProcessNodeRefusesAWaitOutsideItsRangeNamingTheKey() {
        Map<String, Duration> waits = new LinkedHashMap<>();
        waits.put("zero", Duration.ZERO);
        waits.put("negative, -1 s", Duration.ofSeconds(-1));
        waits.put("negative, -1 ns", Duration.ofNanos(-1));
        waits.put("PT0.0009S, below by 100 µs", Duration.parse("PT0.0009S"));
        waits.put("PT0.000999999S, below by 1 ns", Duration.ofNanos(999_999));
        waits.put("PT1M0.001S, above by a millisecond", Duration.parse("PT1M0.001S"));
        waits.put("PT1M plus 1 ns", Duration.ofMinutes(1).plusNanos(1));
        waits.put("Integer.MAX_VALUE + 1 ms", Duration.ofMillis(Integer.MAX_VALUE + 1L));
        waits.put("Long.MAX_VALUE s (toMillis overflows)", Duration.ofSeconds(Long.MAX_VALUE));
        assertAll(waits.entrySet().stream().map(c -> () -> {    // every case reported, not only the first
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> inProcess(canonical(c.getValue(), 1024)),
                    "poolWait " + c.getKey() + ": an in-process node over it is refused");
            assertTrue(e.getMessage().contains(WAIT), "poolWait " + c.getKey() + ": the refusal names " + WAIT + ": " + e.getMessage());
        }));
    }

    /** As {@link #anInProcessNodeRefusesAWaitOutsideItsRangeNamingTheKey}, for the queue: 1..65536. */
    @Test
    void anInProcessNodeRefusesAQueueOutsideItsRangeNamingTheKey() {
        assertAll(List.of(0, -1, 65537, Integer.MAX_VALUE, Integer.MIN_VALUE).stream().map(queue -> () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> inProcess(canonical(Duration.ofSeconds(5), queue)),
                    "poolQueue " + queue + ": an in-process node over it is refused");
            assertTrue(e.getMessage().contains(QUEUE), "poolQueue " + queue + ": the refusal names " + QUEUE + ": " + e.getMessage());
        }));
    }

    /** The edges of the in-process guard, each carried as given: PT0.001S and PT1M, 1 and 65536, and the defaults. */
    @Test
    void anInProcessNodeAcceptsTheBoundsOfTheWaitAndTheQueue() {
        Map<String, NodeConfig.Pg> accepted = new LinkedHashMap<>();
        accepted.put("PT0.001S / 1", canonical(Duration.ofMillis(1), 1));
        accepted.put("PT1M / 65536", canonical(Duration.ofSeconds(60), 65536));
        accepted.put("PT0.001S / 65536", canonical(Duration.ofMillis(1), 65536));
        accepted.put("PT1M / 1", canonical(Duration.ofSeconds(60), 1));
        accepted.put("6-argument defaults PT5S / 1024", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16));
        assertAll(accepted.entrySet().stream().map(c -> () -> {
            NodeConfig node = assertDoesNotThrow(() -> inProcess(c.getValue()), c.getKey() + ": an in-process node over it is accepted");
            assertEquals(List.of(c.getValue().poolWait(), c.getValue().poolQueue()), List.of(node.pg().poolWait(), node.pg().poolQueue()),
                    c.getKey() + ": carried as given");
        }));
    }

    /**
     * FIX-172b A6 (P5): a {@code FLOTTILA_PG_POOL_QUEUE} outside 1..65536 read from the environment is refused with a message
     * naming the key, the value given and the range 1..65536 — above the int range (2147483648, -2147483649) as well as within
     * it (65537, 0, -1) — never the int range it was parsed in.
     */
    @Test
    void anEnvQueueOutsideItsRangeIsRefusedNamingTheRange1To65536() {
        java.util.regex.Pattern range = java.util.regex.Pattern.compile("(?<![\\d-])1\\D{1,12}65536(?!\\d)");
        assertAll(List.of("2147483648", "-2147483649", "65537", "0", "-1").stream().map(raw -> () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(QUEUE, raw), QUEUE + "=" + raw + " is refused");
            String m = e.getMessage();
            assertTrue(m.contains(QUEUE) && m.contains(raw), QUEUE + "=" + raw + ": the refusal names the key and the value: " + m);
            assertTrue(range.matcher(m).find(), QUEUE + "=" + raw + ": the refusal names the range 1..65536: " + m);
            assertTrue(!m.contains("2147483647"), QUEUE + "=" + raw + ": not the int range it was parsed in: " + m);
        }));
    }
}
