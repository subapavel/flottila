package dev.rail8.api.settings;

import dev.rail8.api.settings.Settings.Problem;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The cases in {@code check-cases.json}, which the UI's form runs too: its description is the JSON form of
 * {@link SettingsCheckTest#DROP}, and {@link Settings#check} finds each case's problems in their order.
 */
class SettingsCheckCasesTest {

    static JsonObject cases() throws IOException {
        try (InputStream in = SettingsCheckCasesTest.class.getResourceAsStream("check-cases.json")) {
            assertNotNull(in, "check-cases.json");
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void theFileDescribesTheSameSettings() throws IOException {
        assertEquals(cases().getJsonObject("description"), new JsonObject(new JsonObject(SettingsCheckTest.DROP.toJson()).encode()));
    }

    @TestFactory
    Stream<DynamicTest> everyCaseHasItsProblems() throws IOException {
        JsonArray all = cases().getJsonArray("cases");
        return all.stream().map(JsonObject.class::cast).map(c -> DynamicTest.dynamicTest(c.getString("name"), () -> {
            List<Problem> expected = new ArrayList<>();
            for (Object p : c.getJsonArray("problems")) {
                JsonObject problem = (JsonObject) p;
                expected.add(new Problem(problem.getString("path"), problem.getString("message")));
            }
            Direction direction = Direction.valueOf(c.getString("direction").toUpperCase(Locale.ROOT));
            assertEquals(expected, SettingsCheckTest.DROP.check(c.getJsonObject("options").getMap(), direction));
        }));
    }
}
