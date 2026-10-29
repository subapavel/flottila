package io.flottila.runtime.deploy;

import io.flottila.runtime.node.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-04 (D-081): what {@link Secrets} keeps of the env, how two compare (a {@link NodeConfig} is a record: its equality covers
 * them), what its text shows, and how {@link NodeConfig} carries them — read by {@link NodeConfig#from}, none by its other
 * constructors, swapped by {@link NodeConfig#withSecrets}.
 */
class SecretsEqualityTest {

    static Map<String, String> node() {
        return Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_DATA_DIR", "/tmp/flottila-secrets-equality");
    }

    /** A string that does not start with {@code secret://} is no reference, even when the rest would be a name. */
    @Test
    void aStringWithoutTheSchemeIsRefused() {
        for (Secrets s : new Secrets[] {Secrets.fromEnv(Map.of("FLOTTILA_SECRET_PARTNER_TOKEN", "x")), Secrets.placeholder()}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.resolve("partner-token"));
            assertTrue(e.getMessage().contains("'partner-token'"), e.getMessage());
        }
    }

    /** Only the {@code FLOTTILA_SECRET_*} variables are kept: two envs that differ elsewhere hold equal secrets. */
    @Test
    void equalOverTheSecretVariablesOnly() {
        Secrets one = Secrets.fromEnv(Map.of("FLOTTILA_SECRET_A", "1", "PATH", "/bin"));
        Secrets same = Secrets.fromEnv(Map.of("FLOTTILA_SECRET_A", "1", "HOME", "/root"));
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, Secrets.fromEnv(Map.of("FLOTTILA_SECRET_A", "2")), "another value");
        assertNotEquals(one, Secrets.fromEnv(Map.of("FLOTTILA_SECRET_B", "1")), "another variable");
        assertEquals(Secrets.fromEnv(Map.of()), Secrets.fromEnv(Map.of("PATH", "/bin")), "none kept of an env without one");
        assertEquals(Secrets.placeholder(), Secrets.placeholder());
        assertNotEquals(Secrets.placeholder(), Secrets.fromEnv(Map.of()), "the placeholder is not an empty env");
        assertNotEquals(Secrets.fromEnv(Map.of()), Secrets.placeholder(), "nor the other way round");
        assertFalse(one.equals("FLOTTILA_SECRET_A"), "no other type is equal");
    }

    /** The text names the variables, never a value; the placeholder's names the placeholder. */
    @Test
    void textNamesNoValue() {
        assertEquals("Secrets[env [FLOTTILA_SECRET_A, FLOTTILA_SECRET_B]]", Secrets.fromEnv(Map.of("FLOTTILA_SECRET_B", "v-b", "FLOTTILA_SECRET_A", "v-a",
                "PATH", "/bin")).toString());
        assertEquals("Secrets[placeholder https://secret.invalid/]", Secrets.placeholder().toString());
    }

    /** {@link NodeConfig#from} reads the env's secrets; {@link NodeConfig#withSecrets} replaces them and nothing else. */
    @Test
    void nodeConfigCarriesTheSecrets() {
        Map<String, String> env = new java.util.HashMap<>(node());
        env.put("FLOTTILA_SECRET_A", "value-a");
        NodeConfig read = NodeConfig.from(env);
        assertEquals(Optional.of("value-a"), read.secrets().resolve("secret://a"));
        assertEquals(Secrets.fromEnv(env), read.secrets());

        NodeConfig dry = read.withSecrets(Secrets.placeholder());
        assertEquals(Secrets.placeholder(), dry.secrets());
        assertEquals(read, dry.withSecrets(read.secrets()), "every other component is kept");
        assertNotEquals(read, dry, "the secrets are part of the config's equality");
        assertThrows(NullPointerException.class, () -> read.withSecrets(null), "a config always has secrets");
    }

    /** A config built by its constructors without secrets has none: every reference is not set. */
    @Test
    void theOtherConstructorsHaveNoSecrets() {
        NodeConfig read = NodeConfig.from(node());
        NodeConfig eight = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(),
                read.pg(), read.blobs());
        NodeConfig ten = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(),
                read.pg(), read.blobs(), read.archives(), read.s3());
        for (NodeConfig c : new NodeConfig[] {eight, ten}) {
            assertEquals(Secrets.fromEnv(Map.of()), c.secrets());
            assertEquals(Optional.empty(), c.secrets().resolve("secret://a"));
        }
        assertEquals(read, ten, "an env without FLOTTILA_SECRET_* reads as no secrets");
    }
}
