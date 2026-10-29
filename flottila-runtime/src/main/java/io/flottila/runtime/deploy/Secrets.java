package io.flottila.runtime.deploy;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Resolves a secret reference (D-081): an endpoint option whose whole string value is {@code secret://<name>}, the name
 * {@code [a-z0-9][a-z0-9._-]*}. On a node ({@link #fromEnv}) the value is the environment variable {@code FLOTTILA_SECRET_<NAME>}
 * — the name upper-cased, {@code .} and {@code -} as {@code _} — of the env map the node was started with; a variable set to the
 * empty string counts as not set. Off the node ({@link #placeholder}: {@code flottila validate}, {@code flottila test}) every
 * well-formed reference is {@code https://secret.invalid/}, so a url option validates and no secret is needed. Both refuse a malformed
 * reference. No text of this class carries a value. The wallet (ARCHITECTURE §9) replaces the env backing behind the same syntax.
 *
 * @since 0.1
 */
public final class Secrets {

    /** What a reference starts with. */
    static final String SCHEME = "secret://";
    /** What {@link #placeholder()} resolves every well-formed reference to: a URL, so a url option validates. */
    static final String PLACEHOLDER = "https://secret.invalid/";

    private static final String VARIABLE = "FLOTTILA_SECRET_";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Secrets PLACEHOLDERS = new Secrets(null);

    /** The {@code FLOTTILA_SECRET_*} variables by name; null for the placeholder. */
    private final Map<String, String> variables;

    private Secrets(Map<String, String> variables) {
        this.variables = variables;
    }

    /** The node's: every {@code FLOTTILA_SECRET_*} variable of {@code env}, read now (a change needs a restart); nothing else of it is kept. */
    public static Secrets fromEnv(Map<String, String> env) {
        Map<String, String> variables = new HashMap<>();
        env.forEach((key, value) -> {
            if (key.startsWith(VARIABLE)) variables.put(key, value);
        });
        return new Secrets(Map.copyOf(variables));
    }

    /** The dry build's: syntax checked, every well-formed reference resolved to {@code https://secret.invalid/}, no env read. */
    public static Secrets placeholder() {
        return PLACEHOLDERS;
    }

    /**
     * The value of {@code reference}; empty when its variable is not set or set to the empty string.
     *
     * @throws IllegalArgumentException {@code reference} is not {@code secret://} and a name; the message names the reference
     */
    public Optional<String> resolve(String reference) {
        String variable = variable(reference);
        if (variables == null) return Optional.of(PLACEHOLDER);
        return Optional.ofNullable(variables.get(variable)).filter(value -> !value.isEmpty());
    }

    /**
     * The environment variable {@code reference} names: {@code FLOTTILA_SECRET_} and its name upper-cased, {@code .} and {@code -}
     * as {@code _}.
     *
     * @throws IllegalArgumentException {@code reference} is not {@code secret://} and a name; the message names the reference
     */
    static String variable(String reference) {
        String name = reference.startsWith(SCHEME) ? reference.substring(SCHEME.length()) : "";
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + reference + "' is not a secret reference: secret://<name>, the name [a-z0-9][a-z0-9._-]*");
        }
        return VARIABLE + name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** Equal when both are the placeholder, or both hold the same variables. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Secrets s && Objects.equals(variables, s.variables);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(variables);
    }

    /** The kind and the names of the variables held, never a value. */
    @Override
    public String toString() {
        return variables == null ? "Secrets[placeholder " + PLACEHOLDER + "]" : "Secrets[env " + new TreeSet<>(variables.keySet()) + "]";
    }
}
