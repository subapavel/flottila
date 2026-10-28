package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.BodyKind;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-01 (D-075): {@code partitionBy: <key>} on an at-least-once flow names the key of its one {@code extractKey} step;
 * {@link FlowBuilder#delivery} compiles that step's expression with its language ({@code value(xpath, resources)}) — it builds no
 * stage and opens no producer — and the delivery enqueues each message under the value. An at-least-once flow does not build
 * ({@link IllegalArgumentException}, Deployments' "does not build" line) when no extractKey step sets the key, when two do, when
 * that step's language needs the whole body and the flow's {@code maxBodyBytes} is above the store's tree limit, or [A] when a
 * {@code split} or {@code to} step comes before it. {@code partitionBy} on a sync or at-most-once flow has no effect.
 *
 * <p>Two test languages read a header named by the expression: {@code header} (a {@link BodyKind#STREAM} language) and
 * {@code header-whole} ({@link BodyKind#WHOLE}); each counts the expressions it compiled. The store's tree limit is the testkit's
 * default ({@value FakeRuntime#DEFAULT_TREE_LIMIT} bytes).
 */
@Timeout(10)
class FlowBuilderPartitionTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final long TREE_LIMIT = FakeRuntime.DEFAULT_TREE_LIMIT;
    static final Map<String, Object> TO_PARTNER = Map.of("type", "to", "name", "partner", "endpoint", "partner");
    static final Map<String, Object> SPLIT = Map.of("type", "split", "name", "parts", "language", "header", "xpath", "lines");
    static final Map<String, Object> MARK = Map.of("type", "mark", "name", "mark");
    static final Map<String, Object> VALIDATE = Map.of("type", "validate", "name", "check", "language", "header", "required", "id=4711");

    /** A language reading the header its expression names; counts the values it compiled. */
    static final class HeaderLanguage implements Expressions {
        final String name;
        final BodyKind kind;
        final AtomicInteger compiled = new AtomicInteger();

        HeaderLanguage(String name, BodyKind kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override public String language() { return name; }

        @Override public BodyKind bodyKind() { return kind; }

        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
            int eq = expression.indexOf('=');
            return e -> expression.substring(eq + 1).equals(e.headers().get(expression.substring(0, eq)));
        }

        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
            compiled.incrementAndGet();
            return e -> e.header(expression);
        }
    }

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final InMemoryDlq dlq = new InMemoryDlq();
    final HeaderLanguage header = new HeaderLanguage("header", BodyKind.STREAM);
    final HeaderLanguage whole = new HeaderLanguage("header-whole", BodyKind.WHOLE);
    /** Stages the {@code mark} factory built: a custom step that changes nothing. */
    final AtomicInteger marks = new AtomicInteger();
    final StepFactory mark = new StepFactory() {
        @Override public String type() { return "mark"; }
        @Override public String configSchema() { return "{}"; }
        @Override public BodyKind bodyKind() { return BodyKind.STREAM; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            marks.incrementAndGet();
            return Stage.of(e -> e);
        }
    };

    { rt.withComponent(partner); }

    FlowBuilder builder() {
        return new FlowBuilder(rt.runtime(), List.of(mark), List.of(header, whole),
                Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of())))
                .recording(store, Meter.system()).queue(queue).dlq(dlq);
    }

    /** {@code extractKey} {@code name}: key {@code key} from the header {@code x-<key>}, in {@code language}. */
    static Map<String, Object> extractKey(String name, String key, String language) {
        return Map.of("type", "extractKey", "name", name, "language", language, "key", key, "xpath", "x-" + key);
    }

    static Map<String, Object> extractKey(String name, String key) {
        return extractKey(name, key, "header");
    }

    /** Flow {@code orders} in {@code mode}, {@code maxBodyBytes}, {@code partitionBy} when given, these steps. */
    static FlowDefinition flow(String mode, long maxBodyBytes, String partitionBy, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", maxBodyBytes, "maxInFlight", 4));
        if (partitionBy != null) doc.put("partitionBy", partitionBy);
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    static FlowDefinition atLeastOnce(String partitionBy, List<Map<String, Object>> steps) {
        return flow("at-least-once", 65_536, partitionBy, steps);
    }

    Envelope ingress(Headers headers) {
        return rt.ingress("orders", headers.with(Envelope.H_CONTENT_TYPE, "application/xml"), ORDER);
    }

    /** The delivery of {@code def} does not build: an {@link IllegalArgumentException} naming the key; its message. */
    String doesNotBuild(FlowDefinition def, String what) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> builder().delivery(def, NO_FILES), what + " does not build");
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("customer"), what + ": the refusal names the partitionBy key: " + message);
        return message;
    }

    // --- what the delivery compiles

    /**
     * The delivery compiles the extractKey step's own expression ({@code x-customer}, not the key's name) once, at build time, and
     * nothing else: no stage is built ({@code mark} after it is never created) and no producer opened ({@code to partner}); each
     * message is enqueued under the value the expression gives it, one without a value under none.
     */
    @Test
    void partitionByKeyReachesTheQueueAndTheDeliveryBuildsNoStage() {
        FlowDefinition def = atLeastOnce("customer", List.of(extractKey("key", "customer"), MARK, TO_PARTNER));

        Delivery delivery = builder().delivery(def, NO_FILES);

        assertEquals(1, header.compiled.get(), "the extractKey expression is compiled once, at build time");
        assertEquals(0, marks.get(), "no stage is built for the delivery");
        assertEquals(0, partner.producersBuilt(), "no producer is opened for the delivery");

        Envelope keyed = ingress(Headers.empty().with("x-customer", "C-7").with("customer", "not-this"));
        Envelope unkeyed = ingress(Headers.empty().with("customer", "not-this"));
        assertEquals(new Ack.Accepted(keyed.id()), delivery.apply(keyed).await());
        assertEquals(new Ack.Accepted(unkeyed.id()), delivery.apply(unkeyed).await());

        Map<String, String> keys = new HashMap<>();
        for (WorkQueue.Item i : queue.snapshot()) keys.put(i.messageId(), i.partitionKey());
        assertEquals(2, keys.size(), "both queued: " + queue.snapshot());
        assertEquals("C-7", keys.get(keyed.id()), "the value of the step's expression is the item's key");
        assertEquals(null, keys.get(unkeyed.id()), "a message without a value is queued without a key");
        assertEquals(1, header.compiled.get(), "compiled once, not per message");
        assertEquals(List.of(), partner.sent(), "nothing of the pipeline ran");
    }

    /** Without {@code partitionBy} an at-least-once flow's items have no key, whatever extractKey steps it has; nothing is compiled. */
    @Test
    void withoutPartitionByItemsAreUnordered() {
        Delivery delivery = builder().delivery(atLeastOnce(null, List.of(extractKey("key", "customer"), TO_PARTNER)), NO_FILES);
        Envelope in = ingress(Headers.empty().with("x-customer", "C-7"));

        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());

        assertEquals(1, queue.snapshot().size());
        assertEquals(null, queue.snapshot().get(0).partitionKey(), "no partitionBy, no key");
        assertEquals(0, header.compiled.get(), "nothing compiled for the delivery");
    }

    // --- build refusals, at-least-once

    @Test
    void partitionByWithoutItsExtractKeyDoesNotBuild() {
        doesNotBuild(atLeastOnce("customer", List.of(extractKey("key", "region"), TO_PARTNER)), "an extractKey for another key only");
        doesNotBuild(atLeastOnce("customer", List.of(TO_PARTNER)), "no extractKey step at all");
        assertDoesNotThrow(() -> builder().delivery(atLeastOnce("region", List.of(extractKey("key", "region"), TO_PARTNER)), NO_FILES),
                "control: partitionBy naming the step's key builds");
    }

    /**
     * Only an {@code extractKey} step sets a key: a custom step that happens to carry the option {@code key: customer} (and a
     * compilable {@code xpath} in a registered language) is no setter. Beside the extractKey for the key — before it, so a
     * first-setter pick would take it — the flow builds and its items carry the extractKey's value; alone it does not build.
     */
    @Test
    void aCustomStepWithAKeyOptionSetsNoPartitionKey() {
        Map<String, Object> keyedMark = Map.of("type", "mark", "name", "tagged", "key", "customer", "language", "header", "xpath", "x-region");
        Delivery delivery = assertDoesNotThrow(() -> builder().delivery(atLeastOnce("customer", List.of(keyedMark, extractKey("key", "customer"), TO_PARTNER)),
                NO_FILES), "a custom step with option key: customer beside the extractKey builds");
        Envelope in = ingress(Headers.empty().with("x-customer", "C-7").with("x-region", "EU"));

        assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await());

        assertEquals(1, queue.snapshot().size());
        assertEquals("C-7", queue.snapshot().get(0).partitionKey(), "the key is the extractKey's value, not the custom step's");
        assertEquals(1, header.compiled.get(), "one expression compiled: the extractKey's");
        doesNotBuild(atLeastOnce("customer", List.of(keyedMark, TO_PARTNER)), "a custom step with option key: customer and no extractKey");
    }

    @Test
    void twoExtractKeysForItDoNotBuild() {
        doesNotBuild(atLeastOnce("customer", List.of(extractKey("first", "customer"), extractKey("second", "customer"), TO_PARTNER)),
                "two extractKey steps for the key");
        assertDoesNotThrow(() -> builder().delivery(atLeastOnce("customer", List.of(extractKey("first", "customer"), extractKey("second", "region"), TO_PARTNER)),
                NO_FILES), "control: a second extractKey for another key builds");
    }

    /**
     * The key's language needs the whole body and the flow's max body is above the tree limit: refused at build, never on the
     * first large message. At the limit it builds; a language that streams builds above it.
     */
    @Test
    void wholeBodyLanguageAboveTreeLimitDoesNotBuild() {
        doesNotBuild(flow("at-least-once", TREE_LIMIT + 1, "customer", List.of(extractKey("key", "customer", "header-whole"), TO_PARTNER)),
                "a WHOLE-body key language above the tree limit");
        assertDoesNotThrow(() -> builder().delivery(flow("at-least-once", TREE_LIMIT, "customer", List.of(extractKey("key", "customer", "header-whole"), TO_PARTNER)),
                NO_FILES), "control: at the tree limit it builds");
        assertDoesNotThrow(() -> builder().delivery(flow("at-least-once", TREE_LIMIT + 1, "customer", List.of(extractKey("key", "customer", "header"), TO_PARTNER)),
                NO_FILES), "control: a streaming language above the tree limit builds");
    }

    /**
     * [A] The key is evaluated on the body as it arrived: a {@code split} or a {@code to} before its extractKey would make the
     * step's value another than the queue's — refused, naming that step. A custom step before it (not detected, documented) and a
     * {@code validate} before it build; so do a split and a to after it.
     */
    @Test
    void aSplitOrToBeforeItsExtractKeyDoesNotBuild() {
        String afterTo = doesNotBuild(atLeastOnce("customer", List.of(TO_PARTNER, extractKey("key", "customer"))), "a to before the extractKey");
        assertTrue(afterTo.contains("partner"), "names the to step: " + afterTo);
        String afterSplit = doesNotBuild(atLeastOnce("customer", List.of(SPLIT, extractKey("key", "customer"), TO_PARTNER)), "a split before the extractKey");
        assertTrue(afterSplit.contains("parts"), "names the split step: " + afterSplit);

        for (List<Map<String, Object>> fine : List.of(
                List.of(MARK, extractKey("key", "customer"), TO_PARTNER),
                List.of(VALIDATE, extractKey("key", "customer"), TO_PARTNER),
                List.of(extractKey("key", "customer"), SPLIT, TO_PARTNER))) {
            assertDoesNotThrow(() -> builder().delivery(atLeastOnce("customer", fine), NO_FILES), "control: builds with " + fine);
        }
    }

    // --- no effect on the other modes

    /**
     * Sync and at-most-once flows have no work queue: {@code partitionBy} there refuses nothing — not without its extractKey, not
     * with two of them, not after a to — and the flow runs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"sync", "at-most-once"})
    void partitionByOnASyncFlowHasNoEffect(String mode) {
        List<List<Map<String, Object>>> shapes = new ArrayList<>();
        shapes.add(List.of(TO_PARTNER));
        shapes.add(List.of(extractKey("first", "customer"), extractKey("second", "customer"), TO_PARTNER));
        shapes.add(List.of(TO_PARTNER, extractKey("key", "customer")));
        for (List<Map<String, Object>> steps : shapes) {
            Delivery delivery = assertDoesNotThrow(() -> builder().delivery(flow(mode, 65_536, "customer", steps), NO_FILES), mode + " builds with " + steps);
            Envelope in = ingress(Headers.empty().with("x-customer", "C-7"));
            Ack ack = delivery.apply(in).await();
            if (mode.equals("sync")) assertEquals(in.id(), assertInstanceOf(Ack.Reply.class, ack, "the sync flow runs").reply().id());
            else assertEquals(new Ack.Accepted(in.id()), ack, "the at-most-once flow accepts");
        }
        partner.awaitSent(shapes.size());
        assertEquals(List.of(), queue.snapshot(), "nothing of a " + mode + " flow is queued");
    }
}
