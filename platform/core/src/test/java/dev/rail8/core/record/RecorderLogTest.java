package dev.rail8.core.record;

import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lines a step's code logs while the step runs on the message's thread, through {@code System.Logger} or
 * {@code java.util.logging}, are kept in the step's own row: the time on the runtime's clock, the level, the logger, the text
 * formatted as the node's log formats it and the failure logged with it. A line below the node log's level is not kept. The
 * flow's recording level keeps the lines exactly where it keeps the row. The lines a step logs after it handed an envelope on
 * are not kept, and the tries of a retried step share one log. Each run of a step keeps at most {@value StepLog#MAX_LINES}
 * lines and {@value StepLog#MAX_BYTES} bytes, whole lines only, and counts the lines it did not keep.
 */
@Timeout(30)
class RecorderLogTest {

    static final System.Logger LOG = System.getLogger("test.steps");

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();

    static StepFactory factory(String type, Function<StepConfig, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step); }
        };
    }

    final AtomicInteger tries = new AtomicInteger();

    final List<StepFactory> factories = List.of(
            factory("say", s -> Stage.of(e -> { LOG.log(INFO, "{0} says hello", s.name()); return e; })),
            factory("drop", s -> (e, rest) -> { LOG.log(INFO, "dropped at {0}", s.name()); return e; }),
            factory("boom", s -> Stage.of(e -> {
                LOG.log(WARNING, "the partner said no", new IllegalStateException("503 from partner"));
                throw new IllegalStateException("boom at " + s.name());
            })),
            factory("around", s -> (e, rest) -> {
                LOG.log(INFO, "before");
                Envelope out = rest.apply(e);
                LOG.log(INFO, "after");
                return out;
            }),
            factory("flaky", s -> Stage.of(e -> {
                int n = tries.incrementAndGet();
                LOG.log(INFO, "try {0}", n);
                if (n < 2) throw new IllegalStateException("once");
                return e;
            })),
            factory("chatty", s -> Stage.of(e -> {
                int lines = Integer.parseInt(String.valueOf(s.config().get("lines")));
                int length = Integer.parseInt(String.valueOf(s.config().get("length")));
                for (int i = 0; i < lines; i++) System.getLogger("t").log(INFO, "x".repeat(length));
                System.getLogger("t").log(INFO, String.valueOf(s.config().getOrDefault("then", "")));
                return e;
            })),
            factory("levels", s -> Stage.of(e -> {
                Logger jul = Logger.getLogger("test.levels");
                for (Level level : List.of(Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST)) jul.log(level, level.getName());
                return e;
            })),
            factory("later", s -> Stage.of(e -> { rt.clock().advance(Duration.ofMillis(5)); LOG.log(INFO, "signed {0} bytes with the key of {1}", e.body().size(), "2026-01-01"); return e; })),
            factory("quiet", s -> Stage.of(e -> { LOG.log(DEBUG, "below the node's level"); return e; })),
            factory("odd", s -> Stage.of(e -> { LOG.log(INFO, "a\u0000b\uD800c"); return e; })));

    static Map<String, Object> flow(Map<String, Object> recording, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>(Map.of("schemaVersion", 1, "kind", "flow", "id", "f", "version", "1.0.0", "from", Map.of("endpoint", "in"),
                "delivery", Map.of("mode", "sync"), "limits", Map.of("maxBodyBytes", 4096), "steps", steps));
        if (recording != null) doc.put("recording", recording);
        return doc;
    }

    @SafeVarargs
    final Pipeline build(String level, Map<String, Object>... steps) {
        return new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0)
                .build(FlowDefinition.from(flow(Map.of("level", level), List.of(steps))), java.nio.file.Path.of(""));
    }

    static Map<String, Object> step(String type, String name) { return Map.of("type", type, "name", name); }

    Envelope in() { return rt.ingress("f", Headers.empty(), Buffer.buffer("<order id='4711'/>")); }

    /** Each recorded step's name to the texts of its lines. */
    Map<String, List<String>> texts(Envelope in) {
        return store.timeline(in.id()).await().stream()
                .collect(Collectors.toMap(Snapshot::stepName, s -> s.log().lines().stream().map(StepLog.Line::text).toList(), (a, b) -> a, java.util.LinkedHashMap::new));
    }

    Snapshot row(Envelope in, String name) {
        return store.timeline(in.id()).await().stream().filter(s -> s.stepName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aLineTheStepLogsIsInItsRowWithTheTimeTheLevelTheLoggerAndTheFormattedText() {
        Envelope in = in();
        build("all", step("later", "sign")).apply(in);
        assertEquals(new StepLog(List.of(new StepLog.Line(FakeRuntime.T0.plusMillis(5), INFO, "test.steps", "signed 18 bytes with the key of 2026-01-01",
                Optional.empty())), 0), row(in, "sign").log());
        assertEquals(StepLog.EMPTY, row(in, "ingress").log(), "ingress runs no code of a step");
    }

    @Test
    void eachJavaUtilLoggingLevelIsKeptAsTheNearestSystemLoggerLevel() {
        Logger jul = Logger.getLogger("test.levels");
        Level before = jul.getLevel();
        jul.setLevel(Level.ALL);
        try {
            Envelope in = in();
            build("all", step("levels", "levels")).apply(in);
            assertEquals(List.of(ERROR, WARNING, INFO, INFO, DEBUG, TRACE, TRACE), row(in, "levels").log().lines().stream().map(StepLog.Line::level).toList());
            assertEquals(List.of("SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST"), texts(in).get("levels"));
        } finally {
            jul.setLevel(before);
        }
    }

    @Test
    void aLineBelowTheNodeLogsLevelIsNotKept() {
        Envelope in = in();
        build("all", step("quiet", "q")).apply(in);
        assertEquals(StepLog.EMPTY, row(in, "q").log(), "the node log's level is INFO");
    }

    @Test
    void theRecordingLevelKeepsTheLinesWhereItKeepsTheRow() {
        Envelope all = in();
        build("all", step("say", "a"), step("say", "b")).apply(all);
        assertEquals(Map.of("ingress", List.of(), "a", List.of("a says hello"), "b", List.of("b says hello")), texts(all));
        Envelope ends = in();
        build("ends", step("say", "a"), step("say", "b")).apply(ends);
        assertEquals(Map.of("ingress", List.of(), "b", List.of("b says hello")), texts(ends), "a's row is not kept, nor are its lines");
        Envelope errors = in();
        assertThrows(IllegalStateException.class, () -> build("errors", step("say", "a"), step("boom", "x")).apply(errors));
        assertEquals(Map.of("x", List.of("the partner said no")), texts(errors));
        Envelope off = in();
        assertThrows(IllegalStateException.class, () -> build("off", step("say", "a"), step("boom", "x")).apply(off));
        assertEquals(Map.of(), texts(off));
    }

    @Test
    void aFilteringStepsLinesAreInItsFilteredRow() {
        Envelope in = in();
        build("all", step("drop", "f")).apply(in);
        Snapshot f = row(in, "f");
        assertEquals(Snapshot.Outcome.FILTERED, f.outcome());
        assertEquals(List.of("dropped at f"), texts(in).get("f"));
    }

    @Test
    void aFailedStepsLinesAreInItsFailedRowEachWithTheFailureItLoggedWithoutItsStack() {
        Envelope in = in();
        assertThrows(IllegalStateException.class, () -> build("all", step("boom", "x")).apply(in));
        Snapshot x = row(in, "x");
        assertEquals(Snapshot.Outcome.FAILED, x.outcome());
        StepLog.Line line = x.log().lines().getFirst();
        assertEquals(WARNING, line.level());
        assertEquals("the partner said no", line.text());
        assertEquals(Optional.of("java.lang.IllegalStateException: 503 from partner"), line.thrown());
    }

    @Test
    void theLinesAStepLogsAfterItHandedOnAreNotKeptNorInTheNextStepsRow() {
        Envelope in = in();
        build("all", step("around", "a"), step("say", "b")).apply(in);
        assertEquals(List.of("before"), texts(in).get("a"));
        assertEquals(List.of("b says hello"), texts(in).get("b"));
    }

    @Test
    void theTriesOfARetriedStepShareOneLog() throws Exception {
        Map<String, Object> doc = flow(Map.of("level", "all"), List.of(Map.of("type", "flaky", "name", "io", "retryable", true)));
        doc.put("retry", Map.of("attempts", 2, "initial", "10ms", "max", "20ms"));
        Pipeline p = new FlowBuilder(rt.runtime(), factories, List.of(), Map.<String, EndpointConfig>of()).recording(store, () -> 0)
                .build(FlowDefinition.from(doc), java.nio.file.Path.of(""));
        Envelope in = in();
        Thread t = Thread.ofVirtual().start(() -> p.apply(in));
        // the run's time limit is pending throughout: a second task is the retry's wait, the earlier of the two
        while (t.isAlive()) { if (rt.timer().pending() > 1) rt.clock().advance(rt.timer().nextDelay()); Thread.onSpinWait(); Thread.yield(); }
        t.join();
        assertEquals(List.of("try 1", "try 2"), texts(in).get("io"));
    }

    @Test
    void aRunOfAStepKeepsAtMostTheBoundInLinesAndCountsTheLinesItDropped() {
        Envelope in = in();
        build("all", Map.of("type", "chatty", "name", "c", "lines", StepLog.MAX_LINES, "length", 1, "then", "one too many")).apply(in);
        StepLog log = row(in, "c").log();
        assertEquals(StepLog.MAX_LINES, log.lines().size());
        assertEquals(1, log.dropped());
    }

    @Test
    void aRunOfAStepKeepsAtMostTheBoundInBytesAndALaterLineThatFitsIsStillKept() {
        long perLine = StepLog.MAX_BYTES / 32;   // the logger "t" is one byte of it
        Envelope full = in();
        build("all", Map.of("type", "chatty", "name", "c", "lines", 33, "length", perLine - 1, "then", "")).apply(full);
        StepLog log = row(full, "c").log();
        assertEquals(32, log.lines().size(), "32 lines of " + perLine + " bytes fill the bound exactly");
        assertEquals(StepLog.MAX_BYTES, log.lines().stream().mapToLong(StepLog.Line::bytes).sum());
        assertEquals(2, log.dropped(), "the 33rd long line, and the empty one after it: its logger's one byte does not fit");

        Envelope big = in();
        build("all", Map.of("type", "chatty", "name", "c", "lines", 1, "length", StepLog.MAX_BYTES, "then", "small")).apply(big);
        assertEquals(List.of("small"), texts(big).get("c"), "a line is never cut: one too big for the bound is dropped whole, and the next one is kept");
        assertEquals(1, row(big, "c").log().dropped());
    }

    @Test
    void aNulOrALoneSurrogateInALineIsReplaced() {
        Envelope in = in();
        build("all", step("odd", "o")).apply(in);
        assertEquals(List.of("a�b�c"), texts(in).get("o"));
    }

    @Test
    void linesAreStillKeptAfterTheNodeReadsItsLoggingConfigurationAgain() throws Exception {
        Pipeline p = build("all", step("say", "a"));
        LogManager.getLogManager().readConfiguration();
        Envelope in = in();
        p.apply(in);
        assertEquals(List.of("a says hello"), texts(in).get("a"));
    }
}
