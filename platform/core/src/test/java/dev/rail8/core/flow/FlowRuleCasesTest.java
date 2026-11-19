package dev.rail8.core.flow;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cases in {@code flow-rule-cases.json}, which the flow editor's view runs too: the rules of step names and process calls.
 * A case without problems loads; a case with problems is refused, and the refusal starts with the place of one of them, as the
 * node writes a place ({@code processes.enrich.steps[1].name}).
 */
class FlowRuleCasesTest {

    static JsonObject cases() throws IOException {
        try (InputStream in = FlowRuleCasesTest.class.getResourceAsStream("flow-rule-cases.json")) {
            assertNotNull(in, "flow-rule-cases.json");
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** A path into the file, such as {@code ["processes", "enrich", "steps", 1, "name"]}, as the node names the place. */
    static String place(JsonArray path) {
        StringBuilder out = new StringBuilder();
        for (Object segment : path) {
            if (segment instanceof Number n) out.append('[').append(n.intValue()).append(']');
            else out.append(out.isEmpty() ? "" : ".").append(segment);
        }
        return out.toString();
    }

    @TestFactory
    Stream<DynamicTest> everyCaseIsJudgedAsTheEditorJudgesIt() throws IOException {
        return cases().getJsonArray("cases").stream().map(JsonObject.class::cast).map(c -> DynamicTest.dynamicTest(c.getString("name"), () -> {
            Map<String, Object> doc = new HashMap<>(FlowDefinitionTest.demo());
            doc.put("steps", c.getJsonArray("steps").getList());
            if (c.containsKey("processes")) doc.put("processes", c.getJsonObject("processes").getMap());
            JsonArray problems = c.getJsonArray("problems");
            if (problems.isEmpty()) {
                assertDoesNotThrow(() -> FlowDefinition.from(doc));
                return;
            }
            String refusal = assertThrows(IllegalArgumentException.class, () -> FlowDefinition.from(doc)).getMessage();
            List<String> places = problems.stream().map(p -> place(((JsonObject) p).getJsonArray("path"))).toList();
            assertTrue(places.stream().anyMatch(refusal::startsWith), "the refusal \"" + refusal + "\" starts with one of " + places);
        }));
    }
}
