package io.flottila.runtime.deploy;

import io.flottila.runtime.node.NodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-04 (D-081): {@link Secrets} resolves a reference {@code secret://<name>}, name {@code [a-z0-9][a-z0-9._-]*}, from the env map
 * it was built from — {@code FLOTTILA_SECRET_<NAME>}, upper case, {@code .} and {@code -} as {@code _} — and its placeholder twin
 * (the CLI's dry build) answers every well-formed reference with one fixed value and no env at all. Both refuse a malformed one.
 *
 * <p>The shape these cases assume (P2-04 test-author): {@code Secrets.fromEnv(Map<String, String>)}, {@code Secrets.placeholder()},
 * {@code Optional<String> resolve(String reference)} — empty when the variable is not set, an {@link IllegalArgumentException}
 * whose message names the reference when it is malformed.
 */
class SecretsTest {

    /** A reference's variable is its name upper-cased, {@code .} and {@code -} as {@code _}; decoys hold the unmapped spellings. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "secret://partner-token, FLOTTILA_SECRET_PARTNER_TOKEN, partner-token",
            "secret://db.main-pw,    FLOTTILA_SECRET_DB_MAIN_PW,    db.main-pw",
            "secret://0key_x,        FLOTTILA_SECRET_0KEY_X,        0key_x",
            "secret://a,             FLOTTILA_SECRET_A,             a"})
    void nameMapping(String reference, String variable, String name) {
        Map<String, String> env = new HashMap<>();
        env.put(variable, "the-value-of-" + variable);
        env.put("FLOTTILA_SECRET_" + name, "decoy: the name as written");
        env.put("FLOTTILA_SECRET_" + name.toUpperCase(java.util.Locale.ROOT), "decoy: upper case, . and - kept");
        env.put(name, "decoy: the bare name");
        env.put(variable.substring("FLOTTILA_SECRET_".length()), "decoy: no prefix");
        env.put(variable, "the-value-of-" + variable);   // last: a decoy whose key happens to be the variable is overwritten

        assertEquals(Optional.of("the-value-of-" + variable), Secrets.fromEnv(env).resolve(reference));
    }

    /** A variable that is not set is an absent value — never an empty string, never another variable's value. */
    @Test
    void missingIsAbsent() {
        Map<String, String> env = Map.of(
                "FLOTTILA_SECRET_OTHER", "other-value",
                "PARTNER_TOKEN", "unprefixed-value",
                "FLOTTILA_SECRET_PARTNER", "prefix-of-the-name");

        assertEquals(Optional.empty(), Secrets.fromEnv(env).resolve("secret://partner-token"));
        assertEquals(Optional.empty(), Secrets.fromEnv(Map.of()).resolve("secret://partner-token"));
    }

    /** {@code secret://} followed by anything that is not a name is a problem naming the reference, env or placeholder. */
    @ParameterizedTest
    @ValueSource(strings = {"secret://", "secret://Bad Name", "secret://Upper", "secret://-lead", "secret://.lead", "secret://_lead",
            "secret://a/b", "secret://a b", "secret://a:b"})
    void malformedIsAProblem(String reference) {
        Map<String, String> env = Map.of("FLOTTILA_SECRET_", "x", "FLOTTILA_SECRET_UPPER", "x", "FLOTTILA_SECRET_BAD_NAME", "x",
                "FLOTTILA_SECRET__LEAD", "x", "FLOTTILA_SECRET_A_B", "x");

        IllegalArgumentException fromEnv = assertThrows(IllegalArgumentException.class, () -> Secrets.fromEnv(env).resolve(reference),
                "the env-backed resolver refuses " + reference);
        assertTrue(String.valueOf(fromEnv.getMessage()).contains(reference), "the refusal names the reference: " + fromEnv.getMessage());
        IllegalArgumentException placeholder = assertThrows(IllegalArgumentException.class, () -> Secrets.placeholder().resolve(reference),
                "the placeholder checks the syntax too: " + reference);
        assertTrue(String.valueOf(placeholder.getMessage()).contains(reference), "the refusal names the reference: " + placeholder.getMessage());
    }

    /** The dry build's resolver needs no env: every well-formed reference gets one fixed, non-empty value — not the reference echoed. */
    @Test
    void placeholderResolvesWithoutEnv() {
        Optional<String> one = Secrets.placeholder().resolve("secret://partner-token");
        Optional<String> other = Secrets.placeholder().resolve("secret://db.main-pw");

        assertTrue(one.isPresent(), "a well-formed reference resolves without any variable: " + one);
        assertFalse(one.orElseThrow().isEmpty(), "the placeholder is not an empty string");
        assertEquals(one, other, "one fixed placeholder for every reference");
        assertNotEquals("secret://partner-token", one.orElseThrow(), "the placeholder is a value, not the reference handed back");
        assertEquals(Optional.of("https://secret.invalid/"), one, "the placeholder is a URL, so a url option validates (D-081)");
    }

    /** A variable that is set but empty counts as not set: absent, as a missing one. */
    @Test
    void anEmptyVariableIsAbsent() {
        Map<String, String> env = Map.of("FLOTTILA_SECRET_PARTNER_TOKEN", "", "FLOTTILA_SECRET_OTHER", "other-value");

        assertEquals(Optional.empty(), Secrets.fromEnv(env).resolve("secret://partner-token"));
        assertEquals(Optional.of("other-value"), Secrets.fromEnv(env).resolve("secret://other"), "fixture: the env is read");
    }

    /** What the node holds for its secrets (D-081: the env map NodeConfig is built from) never shows a value in its text. */
    @Test
    void aValueNeverShowsInTheNodeConfigOrTheResolver() {
        String value = "s3cr3t-value-0815";
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", "/tmp/flottila-secrets-test");
        env.put("FLOTTILA_SECRET_PARTNER_TOKEN", value);
        Secrets secrets = Secrets.fromEnv(env);
        assertEquals(Optional.of(value), secrets.resolve("secret://partner-token"), "the value is there: the checks below are not vacuous");

        assertFalse(String.valueOf(secrets).contains(value), "Secrets' text: " + secrets);
        String config = String.valueOf(NodeConfig.from(env));
        assertFalse(config.contains(value), "NodeConfig's text: " + config);
    }
}
