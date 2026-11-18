package dev.rail8.api.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The JSON form of a description, as the admin API serves it and the UI draws it: the platform's common tabs around the
 * adapter's own, every field flat with its tab, and optional keys only where the field sets them.
 */
class SettingsJsonTest {

    static Map<String, Object> tab(String id, String label, boolean common) {
        return Map.of("id", id, "label", label, "common", common);
    }

    static final List<Map<String, Object>> COMMON_ONLY = List.of(tab("general", "General", true), tab("retry", "Retry", true),
            tab("recording", "Recording", true), tab("security", "Security", true));

    @Test
    void anUndescribedComponentHasTheCommonTabsAndNoField() {
        assertEquals(Map.of("format", 1, "described", false, "tabs", COMMON_ONLY, "fields", List.of(), "rules", List.of()), Settings.NONE.toJson());
    }

    @Test
    void ownTabsComeBetweenGeneralAndRetryInTheOrderFieldsFirstUseThem() {
        Tab files = Tab.of("files", "Files");
        Tab polling = Tab.of("polling", "Polling");
        Settings s = Settings.of(Field.text("a", "a").tab(polling), Field.text("b", "b").tab(Tab.SECURITY), Field.text("c", "c").tab(files),
                Field.text("d", "d").tab(polling), Field.text("e", "e"));
        assertEquals(List.of(tab("general", "General", true), tab("polling", "Polling", false), tab("files", "Files", false),
                tab("retry", "Retry", true), tab("recording", "Recording", true), tab("security", "Security", true)), s.toJson().get("tabs"));
    }

    @Test
    void everyFieldWithWhatItSets() {
        Settings s = Settings.of(
                        Field.text("host", "The server.").pattern("[a-z.]+").byDefault("localhost"),
                        Field.integer("port", "The port.").range(1, 65535).byDefault(22),
                        Field.integer("least", "At least one.").min(1),
                        Field.secret("password", "The password.").tab(Tab.SECURITY),
                        Field.secret("privateKey", "The key.").tab(Tab.SECURITY),
                        Field.choice("afterSuccess", "What then.", "delete", "move").byDefault("delete").in(),
                        Field.text("moveTo", "Where to.").required().visibleWhen("afterSuccess", "move").in(),
                        Field.bool("tls", "Whether TLS is on.").byDefault(false).out(),
                        Field.text("ca", "The CA.").visibleWhen("tls", true).out())
                .exactlyOne("password", "privateKey");
        List<Map<String, Object>> fields = List.of(
                Map.of("name", "host", "type", "text", "help", "The server.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "pattern", "[a-z.]+", "default", "localhost"),
                Map.of("name", "port", "type", "integer", "help", "The port.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "min", 1L, "max", 65535L, "default", 22L),
                Map.of("name", "least", "type", "integer", "help", "At least one.", "tab", "general", "required", false, "directions", List.of("in", "out"),
                        "min", 1L),
                Map.of("name", "password", "type", "secret", "help", "The password.", "tab", "security", "required", false, "directions", List.of("in", "out")),
                Map.of("name", "privateKey", "type", "secret", "help", "The key.", "tab", "security", "required", false, "directions", List.of("in", "out")),
                Map.of("name", "afterSuccess", "type", "choice", "help", "What then.", "tab", "general", "required", false, "directions", List.of("in"),
                        "choices", List.of("delete", "move"), "default", "delete"),
                Map.of("name", "moveTo", "type", "text", "help", "Where to.", "tab", "general", "required", true, "directions", List.of("in"),
                        "visibleWhen", Map.of("field", "afterSuccess", "values", List.of("move"))),
                Map.of("name", "tls", "type", "boolean", "help", "Whether TLS is on.", "tab", "general", "required", false, "directions", List.of("out"),
                        "default", false),
                Map.of("name", "ca", "type", "text", "help", "The CA.", "tab", "general", "required", false, "directions", List.of("out"),
                        "visibleWhen", Map.of("field", "tls", "values", List.of(true))));
        assertEquals(Map.of("format", 1, "described", true, "tabs", COMMON_ONLY, "fields", fields,
                "rules", List.of(Map.of("kind", "exactlyOne", "fields", List.of("password", "privateKey")))), s.toJson());
    }

    @Test
    void theJsonFormIsImmutableAndTheSameEveryTime() {
        Settings s = Settings.of(Field.text("host", "The server."));
        assertEquals(s.toJson(), s.toJson());
        assertThrows(UnsupportedOperationException.class, () -> s.toJson().put("format", 2));
    }

    @Test
    void theLastDirectionChosenWins() {
        assertEquals(List.of("out"), ((Map<?, ?>) ((List<?>) Settings.of(Field.text("url", "u").in().out()).toJson().get("fields")).get(0)).get("directions"));
    }
}
