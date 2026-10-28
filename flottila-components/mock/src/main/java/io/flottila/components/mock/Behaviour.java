package io.flottila.components.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a mocked endpoint does with a message — the {@code mocks} entry of a flow's {@code *.test.yaml} (schema v1
 * {@code behaviour}): answer with a fixture ({@code reply}), after a {@code delay}, or {@code fail} with an HTTP status or
 * a transport fault, from the {@code after}-th send on or on {@code every} n-th send. On the consumer side, {@code files}
 * names the fixtures a polling consumer finds where it polls at its start (D-076).
 *
 * @param reply the fixture file the reply body comes from; empty = the request echoed
 * @param delay the wait before answering, through the injected timer
 * @param fail the failure, if any
 * @param after the first send (1-based) the failure applies to; 1 = from the start
 * @param every of the sends from {@code after} on, every n-th fails; 1 = every send
 * @param files the fixtures a consumer is fed at its start: a glob over file names (its last segment) in a directory,
 *              resolved against the test file's directory; empty = none
 * @since 0.1
 */
public record Behaviour(Optional<Path> reply, Duration delay, Optional<Failure> fail, int after, int every, Optional<Path> files) {

    /** A transport fault or an HTTP status to fail with. @since 0.1 */
    public sealed interface Failure permits Status, Fault {}

    /** An HTTP-style status; 5xx and 429 are retryable. @since 0.1 */
    public record Status(int code) implements Failure {
        public Status {
            if (code < 400 || code > 599) throw new IllegalArgumentException("fail must be a 4xx or 5xx status, was " + code);
        }
        public boolean retryable() { return code >= 500 || code == 429; }
    }

    /** A transport-level fault: {@code timeout} and {@code reset} are retryable, {@code malformed} is not. @since 0.1 */
    public enum Fault implements Failure {
        TIMEOUT, RESET, MALFORMED;

        public boolean retryable() { return this != MALFORMED; }
    }

    /** Echo at once, never fail. */
    public static final Behaviour ECHO = new Behaviour(Optional.empty(), Duration.ZERO, Optional.empty(), 1, 1, Optional.empty());

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");

    public Behaviour {
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(fail, "fail");
        Objects.requireNonNull(files, "files");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative, was " + delay);
        if (after < 1) throw new IllegalArgumentException("after starts at 1, was " + after);
        if (every < 1) throw new IllegalArgumentException("every starts at 1, was " + every);
    }

    /** A producer-side behaviour: no {@code files}. */
    public Behaviour(Optional<Path> reply, Duration delay, Optional<Failure> fail, int after, int every) {
        this(reply, delay, fail, after, every, Optional.empty());
    }

    /** The schema's {@code behaviour} object; {@code reply} resolves against {@code baseDir} (the test file's directory). */
    public static Behaviour from(Map<String, Object> node, Path baseDir) {
        Objects.requireNonNull(node, "behaviour");
        Objects.requireNonNull(baseDir, "baseDir");
        Optional<Path> reply = node.get("reply") instanceof String r && !r.isEmpty() ? Optional.of(baseDir.resolve(r).normalize()) : Optional.empty();
        Duration delay = node.get("delay") == null ? Duration.ZERO : parseDuration(String.valueOf(node.get("delay")));
        Optional<Failure> fail = Optional.ofNullable(node.get("fail")).map(Behaviour::failure);
        int after = node.get("after") instanceof Number n ? n.intValue() : 1;
        int every = node.get("every") instanceof Number n ? n.intValue() : 1;
        Optional<Path> files = node.get("files") instanceof String f && !f.isEmpty() ? Optional.of(baseDir.resolve(f).normalize()) : Optional.empty();
        return new Behaviour(reply, delay, fail, after, every, files);
    }

    /** Whether the {@code n}-th send (1-based) fails: of the sends from {@code after} on, every {@code every}-th does. */
    public boolean failsOn(long n) {
        return fail.isPresent() && n >= after && (n - after + 1) % every == 0;
    }

    static Failure failure(Object node) {
        if (node instanceof Number n) return new Status(n.intValue());
        return switch (String.valueOf(node)) {
            case "timeout" -> Fault.TIMEOUT;
            case "reset" -> Fault.RESET;
            case "malformed" -> Fault.MALFORMED;
            default -> throw new IllegalArgumentException("fail must be a status 400..599 or timeout | reset | malformed, was '" + node + "'");
        };
    }

    static Duration parseDuration(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("not a duration (<number>ms|s|m|h): '" + text + "'");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
