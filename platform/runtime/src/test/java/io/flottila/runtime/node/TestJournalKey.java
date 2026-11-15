package io.flottila.runtime.node;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * The journal master key of the nodes a test JVM starts (CORE-4c1, D-106: a node without one refuses to start): 256 bits made
 * when this class loads, never written anywhere nor printed, shared by every node of the JVM so a restart on the same database
 * unwraps its {@code journal_keys}. A required configuration of every test that starts a node, not an expectation.
 */
public final class TestJournalKey {

    /** {@code FLOTTILA_JOURNAL_KEY}'s value for this JVM. */
    private static final String VALUE = fresh();

    private TestJournalKey() { }

    private static String fresh() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** {@code env} with {@code FLOTTILA_JOURNAL_KEY} set to this JVM's key; answers {@code env}. */
    public static Map<String, String> with(Map<String, String> env) {
        env.put("FLOTTILA_JOURNAL_KEY", VALUE);
        return env;
    }
}
