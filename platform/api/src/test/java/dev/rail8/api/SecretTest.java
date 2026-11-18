package dev.rail8.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Secret} shows its reference and never its value, reads its value from the source on every call (a rotated value is
 * seen at once), refuses a reference that is not {@code secret://<name>}, and fails with the reference when the source has no
 * value.
 */
class SecretTest {

    @Test
    void itShowsTheReferenceAndNeverTheValue() {
        Secret s = Secret.of("secret://partner-hmac", () -> "s3cr3t-value");
        assertEquals("secret://partner-hmac", s.reference());
        assertEquals("secret://partner-hmac", s.toString());
        assertEquals("[secret://partner-hmac]", java.util.List.of(s).toString(), "a collection of secrets shows references too");
    }

    @Test
    void theValueIsReadOnEveryCall() {
        AtomicReference<String> source = new AtomicReference<>("first");
        Secret s = Secret.of("secret://rotating", source::get);
        assertEquals("first", s.value());
        source.set("second");
        assertEquals("second", s.value(), "a rotated value is seen without building the secret again");
    }

    @Test
    void aSourceWithoutAValueFailsNamingTheReference() {
        Secret s = Secret.of("secret://gone", () -> null);
        IllegalStateException e = assertThrows(IllegalStateException.class, s::value);
        assertTrue(e.getMessage().contains("secret://gone"), e.getMessage());
    }

    @Test
    void aReferenceThatIsNotSecretAndANameIsRefused() {
        for (String bad : new String[]{"partner-hmac", "secret://", "secret://Upper", "secret://-dash", "secret://a b", "https://x"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Secret.of(bad, () -> "v"), bad);
            assertTrue(e.getMessage().contains("'" + bad + "'"), e.getMessage());
        }
        assertEquals("secret://a.b_c-9", Secret.of("secret://a.b_c-9", () -> "v").reference());
    }

    @Test
    void twoSecretsAreEqualOnlyWhenTheyAreTheSameObject() {
        Secret a = Secret.of("secret://same", () -> "v");
        Secret b = Secret.of("secret://same", () -> "v");
        assertEquals(a, a);
        assertNotEquals(a, b);
    }
}
