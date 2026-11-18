package dev.rail8.api;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A secret a step holds: its reference, {@code secret://<name>}, and a way to read its value. A step takes one at build
 * time from {@link Services#secret} and reads {@link #value()} when it needs it, on every use: the value is not kept
 * here, so a source whose value changes (a rotated secret) is seen without a redeploy.
 *
 * <p>{@link #toString()} is the reference, never the value, so a secret logged or put into a message by mistake shows
 * only its name. Two secrets are equal only when they are the same object.
 *
 * @since 0.1
 */
public final class Secret {

    private static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");

    private final String reference;
    private final Supplier<String> value;

    private Secret(String reference, Supplier<String> value) {
        this.reference = reference;
        this.value = value;
    }

    /**
     * The secret {@code reference} names, whose value {@code value} reads on every call of {@link #value()}.
     *
     * @throws IllegalArgumentException naming {@code reference} when it is not {@code secret://} and a name of
     *                                  {@code [a-z0-9][a-z0-9._-]*}
     */
    public static Secret of(String reference, Supplier<String> value) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(value, "value");
        if (!REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("'" + reference + "' is not a secret reference: secret://<name>, the name [a-z0-9][a-z0-9._-]*");
        }
        return new Secret(reference, value);
    }

    /** {@code secret://<name>}: what the flow file names, safe to show anywhere. */
    public String reference() {
        return reference;
    }

    /**
     * The value now, read from the source on every call. A value from the node's environment is read when the node starts
     * and this call cannot fail. A source that is asked on every call may throw when it cannot answer. The step then
     * fails as it would on any other failure.
     *
     * @throws IllegalStateException naming the reference when the source has no value for it
     */
    public String value() {
        String v = value.get();
        if (v == null) throw new IllegalStateException(reference + " has no value");
        return v;
    }

    /** The reference, never the value. */
    @Override
    public String toString() {
        return reference;
    }
}
