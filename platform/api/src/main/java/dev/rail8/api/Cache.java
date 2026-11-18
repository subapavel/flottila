package dev.rail8.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * A cache of strings a step's code keeps between messages: a token fetched in an unusual way, a key derived from a
 * secret, anything the step can load again. Which keys a step sees is set by the node ({@link Services#cache()}). What is
 * kept may vanish at any time: at a restart, under the node's memory limit, on another node. So a value here is always
 * one the step can load again, and never state whose loss loses a message.
 *
 * <p>Both methods block, so they run only on a virtual thread, such as the message's thread a step runs on. On any other
 * thread they throw {@link IllegalStateException}. A key is 1 to {@link #MAX_KEY_CHARS} characters with no control
 * characters; any other key is refused with an {@link IllegalArgumentException} naming it. A key is not secret: errors may
 * show it. A value is never shown.
 *
 * @since 0.1
 */
public interface Cache {

    /** The longest key, in characters. */
    long MAX_KEY_CHARS = 256;
    /** The largest value, in bytes of its UTF-8 form. */
    long MAX_VALUE_BYTES = 65_536;
    /** The longest time a value is kept; a longer ttl is cut to this. */
    Duration MAX_TTL = Duration.ofHours(24);

    /**
     * The value kept under {@code key}; on a miss, the value {@code loader} loads, kept for its entry's ttl. A value is
     * kept until its ttl has passed on the node's clock, or less.
     *
     * <p>On one node a key is loaded once at a time. A caller that asks for a key while another caller's load of it is
     * under way waits for that load and gets its value. A loader that throws keeps nothing: the caller whose loader ran gets
     * the exception as thrown (a checked one too, undeclared, as {@code Future.await()} does), and each caller that waited
     * gets an {@link IllegalStateException} naming the key, with that exception as its cause. A loader that returns
     * {@code null}, or asks for its own key, fails the same way with an {@link IllegalStateException}.
     *
     * @throws IllegalArgumentException naming the key when it is not a key
     * @throws IllegalStateException    off a virtual thread, and as above
     */
    String get(String key, Loader loader);

    /**
     * Forgets {@code key}: a later {@link #get} loads it again, and a load already under way is not kept. Best effort: a
     * cache that cannot be reached logs it and returns, and the value then ages out on its ttl.
     *
     * @throws IllegalArgumentException naming the key when it is not a key
     * @throws IllegalStateException    off a virtual thread
     */
    void remove(String key);

    /**
     * Loads the value of a key that is not kept. It runs on the thread of the caller that asked, and may block.
     *
     * @since 0.1
     */
    @FunctionalInterface
    interface Loader {
        Entry load() throws Exception;
    }

    /**
     * A value and how long to keep it. A ttl of zero or less hands the value to the callers waiting for it and keeps
     * nothing; a ttl above {@link #MAX_TTL} is cut to it.
     *
     * @throws IllegalArgumentException when the value is longer than {@link #MAX_VALUE_BYTES} in UTF-8; the value is not shown
     * @since 0.1
     */
    record Entry(String value, Duration ttl) {
        public Entry {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(ttl, "ttl");
            long bytes = value.length() > MAX_VALUE_BYTES ? value.length() : value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("a cached value is at most " + MAX_VALUE_BYTES + " bytes in UTF-8, this one is at least " + bytes);
            }
            if (ttl.compareTo(MAX_TTL) > 0) ttl = MAX_TTL;
        }

        /** {@code value}'s length and the ttl, never the value. */
        @Override
        public String toString() {
            return "Entry[" + value.length() + " chars, ttl " + ttl + "]";
        }
    }
}
