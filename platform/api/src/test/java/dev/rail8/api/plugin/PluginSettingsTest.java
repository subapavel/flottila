package dev.rail8.api.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A plugin's settings: the variables {@code RAIL8_<NAME>_<KEY>} of the environment it is given, read as whole numbers within a
 * range; an absent one is the fallback, and one out of its range or no whole number is refused naming the variable.
 */
class PluginSettingsTest {

    @Test
    void aSettingIsReadFromTheVariableOfThePluginsNameAndKey() {
        PluginSettings jq = PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", "100", "RAIL8_PG_PORT", "5433"));
        assertEquals(100, jq.whole("MAX_RECURSION_DEPTH", 10_000, 1, 4_194_304));
        assertEquals(7, jq.whole("PORT", 7, 1, 65_535), "another prefix's variable is not the plugin's");
    }

    @Test
    void aDashInTheNameIsAnUnderscoreInTheVariable() {
        PluginSettings acme = PluginSettings.of("acme-sign", Map.of("RAIL8_ACME_SIGN_ROUNDS", "3"));
        assertEquals(3, acme.whole("ROUNDS", 1, 1, 10));
    }

    @Test
    void anAbsentSettingIsTheFallbackAndTheBoundsAreInclusive() {
        PluginSettings none = PluginSettings.of("jq", Map.of());
        assertEquals(10_000, none.whole("MAX_RECURSION_DEPTH", 10_000, 1, 4_194_304));
        assertEquals(1, PluginSettings.of("jq", Map.of("RAIL8_JQ_DEPTH", "1")).whole("DEPTH", 5, 1, 9));
        assertEquals(9, PluginSettings.of("jq", Map.of("RAIL8_JQ_DEPTH", "9")).whole("DEPTH", 5, 1, 9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "4194305"})
    void aSettingOutOfItsRangeIsRefusedNamingTheVariable(String value) {
        PluginSettings jq = PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", value));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> jq.whole("MAX_RECURSION_DEPTH", 10_000, 1, 4_194_304));
        assertEquals("RAIL8_JQ_MAX_RECURSION_DEPTH must be between 1 and 4194304, was " + value, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deep", "", " 12", "1.5", "99999999999999999999"})
    void aSettingThatIsNoWholeNumberIsRefusedNamingTheVariable(String value) {
        PluginSettings jq = PluginSettings.of("jq", Map.of("RAIL8_JQ_MAX_RECURSION_DEPTH", value));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> jq.whole("MAX_RECURSION_DEPTH", 10_000, 1, 4_194_304));
        assertEquals("RAIL8_JQ_MAX_RECURSION_DEPTH must be a whole number, was '" + value + "'", e.getMessage());
    }

    @Test
    void aKeyIsUpperCaseLettersDigitsAndUnderscoresAndTheFallbackIsInItsRange() {
        PluginSettings jq = PluginSettings.of("jq", Map.of());
        for (String key : new String[] {"depth", "", "_DEPTH", "MAX-DEPTH", "D".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> jq.whole(key, 1, 1, 2), key);
        }
        assertThrows(IllegalArgumentException.class, () -> jq.whole("DEPTH", 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> jq.whole("DEPTH", 3, 1, 2));
    }

    @Test
    void theNameIsAPluginsName() {
        for (String name : new String[] {"Jq", "", "1jq", "j_q", "j".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> PluginSettings.of(name, Map.of()), name);
        }
        assertThrows(NullPointerException.class, () -> PluginSettings.of(null, Map.of()));
        assertThrows(NullPointerException.class, () -> PluginSettings.of("jq", null));
    }
}
