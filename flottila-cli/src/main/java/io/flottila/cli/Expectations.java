package io.flottila.cli;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A settled case's {@code expect} against what it observed (D-080), one line per failed key in the order {@code expect} lists
 * them, {@code expect.<key>: expected X, was Y}:
 * <ul>
 *   <li>{@code status} — the http door's answer to the case's answer ({@link HttpComponent#status});</li>
 *   <li>{@code steps} — the message's snapshots, its ingress included;</li>
 *   <li>{@code keys} — per name ({@code keys.<name>}), the message store finds the message by that key and value; was: the
 *       value the message carries for the name;</li>
 *   <li>{@code dlq} — the DLQ's rows;</li>
 *   <li>{@code retries} — the sends to any mock beyond the first of the same message at the same step (the step the envelope
 *       sent carries, D-080 [A]): two {@code to} steps to one endpoint are no retry;</li>
 *   <li>{@code error} — the answer's failure message, or its Nack's reason, contains the text;</li>
 *   <li>{@code sent.<endpoint>} — {@code count}: the endpoint's sends, retries included; {@code equals} or {@code contains}:
 *       holds for every send, on the value of {@code xpath} or, without it, on the body as UTF-8 — one line for the first send
 *       it does not hold for, {@code was no send} when there is none ({@code sent.<endpoint>.count} first, then
 *       {@code .equals} or {@code .contains}).</li>
 * </ul>
 * A value that is not there is {@value #NONE}; {@code steps} and {@code keys} of a case whose from endpoint delivered no message
 * (a feed whose glob matched no file) were {@value #NO_MESSAGE}.
 */
final class Expectations {

    static final String NONE = "(none)";
    /** What {@code steps} and {@code keys} were when the case's from endpoint delivered no message (a feed whose glob matched no file). */
    static final String NO_MESSAGE = "no message";

    /**
     * What a settled case observed.
     *
     * @param messageId the message the case's send became, or the first a feed delivered; empty when none was delivered
     * @param ack the answer, when it succeeded; else null
     * @param failure what the answer failed with; else null
     * @param dlq the DLQ's rows
     * @param sends every send to any mock, in each mock's order
     * @param sentTo an endpoint's sends, in order
     * @param xpaths each {@code xpath} of the case's {@code sent}, compiled
     */
    record Observed(Optional<String> messageId, Ack ack, Throwable failure, MessageStore messages, int dlq, List<Envelope> sends,
                    Function<String, List<Envelope>> sentTo, Map<String, Function<Envelope, Optional<String>>> xpaths) {}

    /** One {@code expect} key's check: adds the lines of what failed. */
    @FunctionalInterface
    private interface Check {
        void run(JsonObject expect, Observed observed, List<String> failed);
    }

    /** By key; the test schema allows no other. */
    private static final Map<String, Check> CHECKS = Map.of(
            "status", (expect, o, failed) -> compare(failed, "status", expect.getInteger("status"), HttpComponent.status(o.ack(), o.failure())),
            "steps", Expectations::steps,
            "keys", Expectations::keys,
            "dlq", (expect, o, failed) -> compare(failed, "dlq", expect.getInteger("dlq"), o.dlq()),
            "retries", (expect, o, failed) -> compare(failed, "retries", expect.getInteger("retries"), retries(o.sends())),
            "error", Expectations::error,
            "sent", Expectations::sent);

    private Expectations() {}

    /** The lines of the keys of {@code expect} that do not hold, in its order; none when every key holds. */
    static List<String> failed(JsonObject expect, Observed observed) {
        List<String> failed = new ArrayList<>();
        for (String key : expect.fieldNames()) CHECKS.get(key).run(expect, observed, failed);
        return failed;
    }

    private static void compare(List<String> failed, String key, int expected, int was) {
        if (expected != was) failed.add(line(key, expected, was));
    }

    private static String line(String key, Object expected, Object was) {
        return "expect." + key + ": expected " + expected + ", was " + was;
    }

    /** Sends beyond the first of each message at each step. */
    static int retries(List<Envelope> sends) {
        return sends.size() - (int) sends.stream().map(e -> List.of(e.id(), e.step())).distinct().count();
    }

    private static void steps(JsonObject expect, Observed o, List<String> failed) {
        int expected = expect.getInteger("steps");
        o.messageId().ifPresentOrElse(id -> compare(failed, "steps", expected, o.messages().timeline(id).await().size()),
                () -> failed.add(line("steps", expected, NO_MESSAGE)));
    }

    private static void keys(JsonObject expect, Observed o, List<String> failed) {
        JsonObject keys = expect.getJsonObject("keys");
        for (String name : keys.fieldNames()) {
            String value = keys.getString(name);
            if (o.messageId().isEmpty()) {
                failed.add(line("keys." + name, value, NO_MESSAGE));
                continue;
            }
            String id = o.messageId().get();
            if (!o.messages().findByKey(name, value, Optional.empty(), Page.MAX_LIMIT).await().items().contains(id)) {
                String carried = o.messages().timeline(id).await().stream().map(s -> s.keys().get(name)).filter(Objects::nonNull)
                        .reduce((first, last) -> last).orElse(NONE);
                failed.add(line("keys." + name, value, carried));
            }
        }
    }

    private static void error(JsonObject expect, Observed o, List<String> failed) {
        String text = expect.getString("error");
        Optional<String> error = o.failure() != null ? Optional.of(String.valueOf(o.failure().getMessage()))
                : o.ack() instanceof Ack.Nack nack ? Optional.of(nack.reason()) : Optional.empty();
        if (error.filter(e -> e.contains(text)).isEmpty()) failed.add(line("error", text, Cli.oneLine(error.orElse(NONE))));
    }

    private static void sent(JsonObject expect, Observed o, List<String> failed) {
        JsonObject sent = expect.getJsonObject("sent");
        for (String endpoint : sent.fieldNames()) {
            JsonObject check = sent.getJsonObject(endpoint);
            List<Envelope> sends = o.sentTo().apply(endpoint);
            if (check.containsKey("count")) compare(failed, "sent." + endpoint + ".count", check.getInteger("count"), sends.size());
            String op = check.containsKey("equals") ? "equals" : "contains";
            if (!check.containsKey(op)) continue;   // a count alone
            String expected = check.getString(op);
            if (sends.isEmpty()) {   // a value check holds for every send, and needs one
                failed.add(line("sent." + endpoint + "." + op, expected, "no send"));
                continue;
            }
            BiPredicate<String, String> holds = op.equals("equals") ? String::equals : String::contains;
            Function<Envelope, Optional<String>> value = check.containsKey("xpath") ? o.xpaths().get(check.getString("xpath"))
                    : e -> Optional.of(e.body().whole().toString(StandardCharsets.UTF_8));
            for (Envelope send : sends) {
                String was;
                try {
                    Optional<String> v = value.apply(send);
                    if (v.filter(s -> holds.test(s, expected)).isPresent()) continue;
                    was = v.orElse(NONE);
                } catch (RuntimeException unreadable) {   // the xpath cannot read the send: a body that is no XML
                    was = "unreadable: " + unreadable.getMessage();
                }
                failed.add(line("sent." + endpoint + "." + op, expected, Cli.oneLine(was)));
                break;
            }
        }
    }
}
