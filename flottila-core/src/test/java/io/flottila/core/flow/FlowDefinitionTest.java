package io.flottila.core.flow;

import io.flottila.api.StepConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flow file (schema v1, D-040) as core sees it: a plain map in, a typed definition out, step numbers kept. */
class FlowDefinitionTest {

    static Map<String, Object> demo() {
        return Map.ofEntries(
                Map.entry("schemaVersion", 1), Map.entry("kind", "flow"), Map.entry("id", "invoice-sync"), Map.entry("version", "0.1.0"),
                Map.entry("from", Map.of("endpoint", "http-in")),
                Map.entry("delivery", Map.of("mode", "sync")),
                Map.entry("limits", Map.of("maxBodyBytes", 1048576, "maxInFlight", 16)),
                Map.entry("partitionBy", "invoiceId"),
                Map.entry("forwardHeaders", List.of("Content-Type")),
                Map.entry("retry", Map.of("attempts", 3, "initial", "100ms", "max", "2s")),
                Map.entry("steps", List.of(
                        Map.of("type", "validate", "name", "validate", "required", "/Invoice/Id"),
                        Map.of("type", "xslt", "name", "toPartner", "stylesheet", "invoice-to-partner.xsl"),
                        Map.of("type", "to", "name", "partner", "retryable", true, "byProp", "PARTNER", "endpoints", Map.of("domestic", "partner-domestic")))));
    }

    @Test
    void parsesTheDemoShapeWithStepNumbersFromOne() {
        FlowDefinition f = FlowDefinition.from(demo());
        assertEquals("invoice-sync", f.id());
        assertEquals("0.1.0", f.version());
        assertEquals("http-in", f.fromEndpoint());
        assertEquals("sync", f.deliveryMode());
        assertEquals(1048576L, f.maxBodyBytes());
        assertEquals(Optional.of(16L), f.maxInFlight());
        assertEquals(Optional.empty(), f.maxInFlightBytes());
        assertEquals(Optional.of("invoiceId"), f.partitionBy());
        assertEquals(List.of("Content-Type"), f.forwardHeaders());
        assertEquals(new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0), f.retry());
        assertEquals(3, f.steps().size());
        StepConfig to = f.steps().get(2);
        assertEquals(3, to.index(), "ingress is step 0; the n-th configured step is step n");
        assertEquals("to", to.type());
        assertEquals("partner", to.name());
        assertTrue(to.retryable());
        assertEquals("PARTNER", to.config().get("byProp"));
        assertEquals(List.of("partner-domestic"), f.referencedEndpoints(), "endpoints a producer is needed for (from is a consumer)");
        assertEquals(false, f.steps().get(0).retryable(), "retryable defaults to false");
    }

    @Test
    void durationsAreTheSchemaStringsAndRetryIsOptional() {
        assertEquals(Duration.ofMillis(100), RetryPolicy.parseDuration("100ms"));
        assertEquals(Duration.ofSeconds(2), RetryPolicy.parseDuration("2s"));
        assertEquals(Duration.ofMinutes(3), RetryPolicy.parseDuration("3m"));
        assertEquals(Duration.ofHours(1), RetryPolicy.parseDuration("1h"));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parseDuration("100"));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.parseDuration("1d"));
        Map<String, Object> noRetry = new java.util.HashMap<>(demo());
        noRetry.remove("retry");
        assertEquals(RetryPolicy.NONE, FlowDefinition.from(noRetry).retry());
        assertEquals(0, RetryPolicy.NONE.attempts());
        assertEquals(0, new RetryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1), 1.0).attempts(), "zero retries is a valid policy, not an error");
    }

    @Test
    void retryDelaysGrowExponentiallyAndCapAtMax() {
        RetryPolicy p = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofMillis(350), 2.0);
        assertEquals(Duration.ofMillis(100), p.delayBefore(1));
        assertEquals(Duration.ofMillis(200), p.delayBefore(2));
        assertEquals(Duration.ofMillis(350), p.delayBefore(3), "capped");
        assertEquals(Duration.ofMillis(350), p.delayBefore(4));
        assertThrows(IllegalArgumentException.class, () -> p.delayBefore(0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, Duration.ofMillis(1), Duration.ofMillis(2), 2.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofMillis(5), Duration.ofMillis(2), 2.0), "max below initial");
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(2), 0.5), "multiplier below 1");
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(2), Double.NaN), "multiplier NaN");
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofMillis(-1), Duration.ofMillis(2), 2.0), "negative initial");
        assertEquals(Duration.ofMillis(100), new RetryPolicy(2, Duration.ofMillis(100), Duration.ofMillis(100), 1.0).delayBefore(2), "multiplier 1: constant");
        assertEquals(Duration.ofMillis(150), new RetryPolicy(2, Duration.ofMillis(100), Duration.ofSeconds(1), 1.5).delayBefore(2), "fractional growth");
    }

    @Test
    void referencedEndpointsListsEveryEndpointOnceAndSkipsWhatIsNotAName() {
        Map<String, Object> doc = new java.util.HashMap<>(demo());
        Map<String, Object> withNull = new java.util.HashMap<>(Map.of("type", "to", "name", "again", "endpoint", "partner-domestic"));
        withNull.put("recording", null);
        doc.put("steps", List.of(
                Map.of("type", "to", "name", "a", "endpoint", "partner-domestic"),
                Map.of("type", "to", "name", "b", "endpoint", 7),
                Map.of("type", "to", "name", "c", "byProp", "P", "endpoints", Map.of("x", "partner-foreign", "y", "partner-domestic", "z", 9)),
                withNull));
        FlowDefinition f = FlowDefinition.from(doc);
        assertEquals(List.of("partner-domestic", "partner-foreign"), f.referencedEndpoints());
        assertEquals(Map.of("endpoint", "partner-domestic"), f.steps().get(3).config(), "a null value is dropped from the step's config");
        assertThrows(IllegalArgumentException.class, () -> new FlowDefinition("id", "1", "in", "sync", 1, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), RetryPolicy.NONE, List.of()), "the record itself refuses an empty step list");
    }

    @Test
    void missingOrWrongPartsAreNamed() {
        Map<String, Object> noSteps = new java.util.HashMap<>(demo());
        noSteps.put("steps", List.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(noSteps));
        assertTrue(e.getMessage().contains("steps"), e.getMessage());
        Map<String, Object> noLimit = new java.util.HashMap<>(demo());
        noLimit.put("limits", Map.of());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(noLimit)).getMessage().contains("maxBodyBytes"));
        Map<String, Object> badStep = new java.util.HashMap<>(demo());
        badStep.put("steps", List.of(Map.of("name", "x")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badStep)).getMessage().contains("steps[0]"));
        Map<String, Object> badNumber = new java.util.HashMap<>(demo());
        badNumber.put("limits", Map.of("maxBodyBytes", 1.5));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badNumber)).getMessage().contains("maxBodyBytes"), "a fraction is not a size");
        badNumber.put("limits", Map.of("maxBodyBytes", -1));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badNumber)).getMessage().contains("maxBodyBytes"));
        badNumber.put("limits", Map.of("maxBodyBytes", 1.5f));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badNumber)).getMessage().contains("maxBodyBytes"), "nor a float");
        Map<String, Object> emptyId = new java.util.HashMap<>(demo());
        emptyId.put("id", "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(emptyId)).getMessage().contains("id"), "an empty string is no id");
        badNumber.put("limits", Map.of("maxBodyBytes", "big"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badNumber)).getMessage().contains("maxBodyBytes"));
        badNumber.put("limits", Map.of("maxBodyBytes", 0));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badNumber)).getMessage().contains("maxBodyBytes"), "zero: never unlimited, never empty");
        Map<String, Object> noFrom = new java.util.HashMap<>(demo());
        noFrom.remove("from");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(noFrom)).getMessage().contains("from"));
        noFrom.put("from", Map.of("endpoint", 7));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(noFrom)).getMessage().contains("from.endpoint"));
        Map<String, Object> badRetry = new java.util.HashMap<>(demo());
        badRetry.put("retry", "soon");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badRetry)).getMessage().contains("retry"));
        badRetry.put("retry", Map.of("initial", "1s", "max", "2s"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badRetry)).getMessage().contains("retry.attempts"));
        badRetry.put("retry", Map.of("attempts", 1, "initial", "1s", "max", "2s", "multiplier", 3));
        assertEquals(3.0, FlowDefinition.from(badRetry).retry().multiplier(), "multiplier is read when present, default 2");
        Map<String, Object> badHeaders = new java.util.HashMap<>(demo());
        badHeaders.put("forwardHeaders", "Content-Type");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(badHeaders)).getMessage().contains("forwardHeaders"));
        badHeaders.remove("forwardHeaders");
        assertEquals(List.of(), FlowDefinition.from(badHeaders).forwardHeaders());
        Map<String, Object> listStep = new java.util.HashMap<>(demo());
        listStep.put("steps", List.of("validate"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(listStep)).getMessage().contains("steps[0]"));
        listStep.put("steps", "validate");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(listStep)).getMessage().contains("steps"));
        Map<String, Object> dupNames = new java.util.HashMap<>(demo());
        dupNames.put("steps", List.of(Map.of("type", "a", "name", "same"), Map.of("type", "b", "name", "same")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(dupNames)).getMessage().contains("same"), "step names are the labels of the timeline: unique");
    }
}
