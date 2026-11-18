package dev.rail8.core.flow;

import dev.rail8.api.Cache;
import dev.rail8.api.Fault;
import dev.rail8.api.NackException;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.core.node.FencedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The secrets of one build of a flow. While the flow builds, every whole string {@code secret://...} in a step's config is
 * checked ({@link #check}), and a factory takes the secrets its options name through {@link #services} (the reference is
 * resolved now, so one that is not set fails the build). Once the flow has built ({@link #built()}) no secret is handed out.
 * A stage wrapped by {@link #scrubbing} throws its own failures with every value handed out in the flow replaced by its
 * reference, in each message of the failure, its causes and its suppressed failures.
 */
final class FlowSecrets {

    private static final String SCHEME = "secret://";

    private final Function<String, String> source;
    private final List<Secret> handedOut = new CopyOnWriteArrayList<>();
    private volatile boolean built;

    /**
     * Secrets resolved by {@code source}: a reference to its value, or an {@link IllegalArgumentException} naming the
     * reference and where the node looks for it, never a value.
     */
    FlowSecrets(Function<String, String> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Resolves every string of {@code step}'s config, at any depth of its maps and lists, that starts with {@code secret://}.
     *
     * @throws IllegalArgumentException naming the step, the option's path and the source's refusal
     */
    void check(StepConfig step) {
        step.config().forEach((key, value) -> check(step, key, value));
    }

    private void check(StepConfig step, String path, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> check(step, path + "." + key, item));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) check(step, path + "[" + i + "]", list.get(i));
        } else if (value instanceof String reference && reference.startsWith(SCHEME)) {
            try {
                resolve(reference);
            } catch (IllegalArgumentException refused) {
                throw new IllegalArgumentException(step + ": '" + path + "': " + refused.getMessage(), refused);
            }
        }
    }

    /** The services of {@code step}: its secrets from this flow's source, and {@code cache}. */
    Services services(StepConfig step, Cache cache) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(cache, "cache");
        return new Services() {
            @Override
            public Secret secret(StepConfig of, String key) {
                return FlowSecrets.this.secret(of, key);
            }

            @Override
            public Cache cache() {
                return cache;
            }

            @Override
            public String toString() {
                return "Services[" + step + "]";
            }
        };
    }

    /** Ends the build: from now on {@link Services#secret} refuses. */
    void built() {
        built = true;
    }

    private Secret secret(StepConfig step, String key) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(key, "key");
        if (built) throw new IllegalStateException(step + ": '" + key + "': a secret is taken while the flow builds, in StepFactory.create");
        String reference = step.requireString(key);
        if (!reference.startsWith(SCHEME)) {
            throw new IllegalArgumentException(step + ": '" + key + "' must name a secret, secret://<name>; its value is not shown");
        }
        Secret secret;
        try {
            secret = Secret.of(reference, () -> source.apply(reference));
            resolve(reference);
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException(step + ": '" + key + "': " + refused.getMessage(), refused);
        }
        handedOut.add(secret);
        return secret;
    }

    private void resolve(String reference) {
        if (source.apply(reference) == null) throw new IllegalArgumentException("secret '" + reference + "' is not set");
    }

    /**
     * {@code stage}, whose own failures leave with every value handed out in this flow replaced by its reference. A failure
     * that came out of the rest of the pipeline leaves as it came: the step it came from scrubbed it. A failure that holds no
     * value leaves as thrown, and so does an {@link Error}, an {@link InterruptedException}, a {@link FencedException} and a
     * {@link RunStoppedException}. Otherwise the failure is a copy: a {@link NackException} stays one with the same retry
     * answer, a {@link Fault} keeps its kind, any other failure stays unclassified, and every link of its causes and
     * suppressed failures is copied the same way, each with its class name shown and its stack frames kept.
     */
    Stage scrubbing(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return (e, rest) -> {
            if (handedOut.isEmpty()) return stage.apply(e, rest);
            Set<Throwable> passedUp = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            Pipeline watched = out -> {
                try {
                    return rest.apply(out);
                } catch (Throwable failure) {
                    passedUp.add(failure);
                    throw failure;
                }
            };
            try {
                return stage.apply(e, watched);
            } catch (Throwable failure) {
                if (passedUp.contains(failure)) throw failure;
                throw FlowSecrets.<RuntimeException>undeclared(scrubbed(failure));
            }
        };
    }

    /** {@code failure}, or its copy with every value handed out replaced by its reference when it holds one. */
    Throwable scrubbed(Throwable failure) {
        if (failure instanceof Error || failure instanceof InterruptedException || failure instanceof FencedException
                || failure instanceof RunStoppedException) {
            return failure;
        }
        Map<String, String> values = values();
        if (values.isEmpty() || !holds(failure, values, Collections.newSetFromMap(new IdentityHashMap<>()))) return failure;
        return copy(failure, values, new IdentityHashMap<>());
    }

    /** Each value handed out, longest first, to its reference; a value that cannot be read now is left out. */
    private Map<String, String> values() {
        List<Secret> secrets = new ArrayList<>(handedOut);
        Map<String, String> values = new LinkedHashMap<>();
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        for (Secret s : secrets) {
            String value;
            try {
                value = s.value();
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (!value.isEmpty()) pairs.add(Map.entry(value, s.reference()));
        }
        pairs.sort(Comparator.comparingInt((Map.Entry<String, String> p) -> p.getKey().length()).reversed());
        pairs.forEach(p -> values.putIfAbsent(p.getKey(), p.getValue()));
        return values;
    }

    private static boolean holds(Throwable t, Map<String, String> values, Set<Throwable> seen) {
        if (t == null || !seen.add(t)) return false;
        String text = t.toString();
        for (String value : values.keySet()) if (text.contains(value)) return true;
        if (holds(t.getCause(), values, seen)) return true;
        for (Throwable s : t.getSuppressed()) if (holds(s, values, seen)) return true;
        return false;
    }

    private static Throwable copy(Throwable t, Map<String, String> values, Map<Throwable, Throwable> copies) {
        if (t == null) return null;
        Throwable done = copies.get(t);
        if (done != null) return done;
        Throwable copy;
        if (t instanceof NackException nack) {
            copy = new NackException(replaced(nack.reason(), values), nack.retryable());
        } else if (t instanceof Fault fault) {
            copy = new ScrubbedFault(t.getClass().getName(), replaced(t.getLocalizedMessage(), values), fault.kind());
        } else {
            copy = new ScrubbedException(t.getClass().getName(), replaced(t.getLocalizedMessage(), values));
        }
        copies.put(t, copy);
        copy.setStackTrace(t.getStackTrace());
        Throwable cause = copy(t.getCause(), values, copies);
        if (cause != null) copy.initCause(cause);
        for (Throwable s : t.getSuppressed()) copy.addSuppressed(copy(s, values, copies));
        return copy;
    }

    private static String replaced(String text, Map<String, String> values) {
        if (text == null) return null;
        String out = text;
        for (Map.Entry<String, String> v : values.entrySet()) out = out.replace(v.getKey(), v.getValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T undeclared(Throwable failure) throws T {
        throw (T) failure;
    }

    /** A copy of an unclassified failure: its class name and its message, values replaced. */
    static class ScrubbedException extends RuntimeException {
        private final String type;

        ScrubbedException(String type, String message) {
            super(message);
            this.type = type;
        }

        /** As the failure's own: its class name, then its message. */
        @Override
        public String toString() {
            String message = getLocalizedMessage();
            return message == null ? type : type + ": " + message;
        }
    }

    /** A copy of a {@link Fault}: its kind kept. */
    static final class ScrubbedFault extends ScrubbedException implements Fault {
        private final Kind kind;

        ScrubbedFault(String type, String message, Kind kind) {
            super(type, message);
            this.kind = kind;
        }

        @Override
        public Kind kind() {
            return kind;
        }
    }
}
