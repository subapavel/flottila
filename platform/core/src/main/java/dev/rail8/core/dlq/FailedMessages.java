package dev.rail8.core.dlq;

import io.vertx.core.Future;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The operator's acts on a parked message: {@link #retry} runs it once more under its own id, {@link #cancel} ends it; and
 * {@link #end}, which ends an at-least-once message wherever it is, parked or not yet done. Each act
 * that changes anything writes one audit row in the same transaction ({@code message.retry} or {@code message.cancel}, subject
 * {@code message/<id>}) and answers it as an {@link Event}; an act that changes nothing writes none.
 *
 * <p>A parked message of an ordered flow stops its key: its work row stays the oldest of its key, held, and every later
 * message of that key waits behind it. A retry hands that row back to the claim in place, so it runs before the rows behind
 * it; a cancel deletes it, so the key's next row runs. A message whose work row is not stopped — one still queued, leased or
 * held for another reason, the park of an unordered message whose complete has not landed — is {@link Unsettled}: the act
 * writes nothing.
 *
 * @since 0.1
 */
public interface FailedMessages {

    /** The longest reason a cancel takes, in characters, after stripping. */
    int REASON_MAX = 1024;
    /** The longest copy of a failure's text an audit row keeps, in UTF-16 units: longer texts are cut to it. */
    int ERROR_MAX = 4096;

    /**
     * Who acted: the self-declared name ({@code unknown} when none was given) and the client's address.
     *
     * @param name the actor's name; never blank
     * @param remote the address the act came from
     * @since 0.1
     */
    record Actor(String name, String remote) {

        public Actor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(remote, "remote");
            if (name.isBlank()) throw new IllegalArgumentException("an actor's name is not blank");
        }
    }

    /** What an act did: retry or cancel. */
    enum Kind { RETRY, CANCEL }

    /**
     * One act as its audit row records it.
     *
     * @param id the audit row's id
     * @param at when the act committed
     * @param messageId the message
     * @param flow its flow
     * @param actor who acted, by name
     * @param kind retry or cancel
     * @param attempt for a retry, the attempt it starts (k + 1 after a park at attempt k); empty for a cancel
     * @param reason for a cancel, the reason when one was given; empty for a retry
     * @param error for a cancel, the parked failure's text, cut to {@link #ERROR_MAX}; empty for a retry
     * @since 0.1
     */
    record Event(long id, Instant at, String messageId, String flow, String actor, Kind kind, OptionalInt attempt, Optional<String> reason,
                 Optional<String> error) {

        public Event {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(attempt, "attempt");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * An act's answer.
     *
     * @since 0.1
     */
    sealed interface Act permits Done, Stopping, Unsettled, NotParked {}

    /**
     * The act committed.
     *
     * @param event its audit row
     * @param node the node whose journal holds the body of the row that may run now — the retried row, or after a cancel the
     *             next row of the cancelled row's key — when there is one and it names its node: the node to tell. Never null
     * @since 0.1
     */
    record Done(Event event, Optional<String> node) implements Act {

        public Done {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(node, "node");
        }
    }

    /**
     * The cancel committed on a message that has not ended: its work row is marked, and its run stops — the one running now at
     * its holder's next renewal, a queued or waiting one at its next claim, before its first step does anything. Only
     * {@link #end} answers it.
     *
     * @param event its audit row; for a message marked already, the first cancel's
     * @param node the node holding the row's live lease, which stops the run; empty when no one holds it. Never null
     * @since 0.1
     */
    record Stopping(Event event, Optional<String> node) implements Act {

        public Stopping {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(node, "node");
        }
    }

    /**
     * Refused: a work row of the message is there that the act cannot work on — one that is not stopped, so the message is not
     * done with yet, or for a retry a stopped one whose body is lost. Nothing was written.
     *
     * @param seq that row's seq (the oldest when there are several)
     * @since 0.1
     */
    record Unsettled(long seq) implements Act {}

    /**
     * Nothing to act on: no such parked message (for a retry: none of that flow parked by its worker at step 1 after that
     * attempt). Nothing was written.
     *
     * @since 0.1
     */
    record NotParked() implements Act {

        /** The one answer. */
        public static final NotParked INSTANCE = new NotParked();
    }

    /**
     * Runs {@code messageId}, parked by its worker in {@code flow} at step 1 after attempt {@code attempt} (k, at least 1) under
     * {@code partitionKey} (null: none), once more as attempt k + 1, its last. A message that stops its key is handed back to the
     * claim in place: its work row keeps its seq, so it still heads its key, and is due at once, unleased, with no doubt and its
     * own bound k + 1. Any other parked message — an unordered one, or one parked before its key stopped — gets a new row at
     * the tail of its key from its ingress body's kept ref, as handed out k times and bound to k + 1. Either way the parked row is
     * deleted and the act audited, in one transaction; {@link Done} names the body node of the row that runs.
     *
     * <p>{@link NotParked} without that parked row. {@link Unsettled} while another work row of the message is there, or while its
     * stopped row's body is lost (the row cannot run). A store that keeps bodies fails a new row whose ingress ref is gone with
     * {@code BlobNotFoundException} ({@code BodyLostException} when an accepted loss gave it up); nothing changes.
     */
    Future<Act> retry(String messageId, String flow, String partitionKey, int attempt, Actor by);

    /**
     * Ends {@code messageId}'s park: its parked row is deleted, and so is the work row by which it stops its key (lost or not), so
     * the key's next row runs; one audit row records it with the flow, the step, its name, the park's instant, the partition key,
     * the body's hash and size, the {@code reason} when given, the remote address and the failure's text cut to
     * {@link #ERROR_MAX} — marked when cut. One transaction; a keyed row is deleted under its key's lock, as a keyed complete
     * takes it. The message's history and bodies stay until retention takes them.
     *
     * <p>{@link NotParked} without a parked row of that id. {@link Unsettled} while a work row of the message is there that is not
     * stopped: nothing written.
     *
     * @throws IllegalArgumentException a {@code reason} that is blank or longer than {@link #REASON_MAX} characters after
     *                                  stripping — thrown at the call
     */
    Future<Act> cancel(String messageId, Actor by, Optional<String> reason);

    /**
     * Ends the at-least-once message {@code messageId} wherever it is. A parked one is cancelled as {@link #cancel} does,
     * answering what it answers. One whose work row is queued, waiting for its retry or leased — not held, not stopped, its
     * body not lost — has the row marked with the cancel ({@link dev.rail8.core.queue.WorkQueue.Cancel}) and a waiting row
     * made due now, with one audit row ({@code message.cancel}, subject {@code message/<id>}, the flow, the step, the partition
     * key, the {@code reason} when given and the remote address), in one transaction: {@link Stopping}. One marked already
     * answers {@link Stopping} with the first cancel's event and writes nothing.
     *
     * <p>{@link Unsettled} for a row that is held, stopped without a parked row, or lost: nothing written. {@link NotParked}
     * when the message has neither a parked row nor a work row.
     *
     * @throws IllegalArgumentException a {@code reason} that is blank or longer than {@link #REASON_MAX} characters after
     *                                  stripping — thrown at the call
     */
    Future<Act> end(String messageId, Actor by, Optional<String> reason);

    /**
     * {@code reason} stripped, checked as {@link #cancel} takes it.
     *
     * @throws IllegalArgumentException blank, or longer than {@link #REASON_MAX} characters after stripping
     */
    static Optional<String> reason(Optional<String> reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isEmpty()) return reason;
        String stripped = reason.get().strip();
        if (stripped.isEmpty() || stripped.length() > REASON_MAX) {
            throw new IllegalArgumentException("a reason is 1 to " + REASON_MAX + " characters after stripping, was " + stripped.length());
        }
        return Optional.of(stripped);
    }

    /** {@code error}'s first {@link #ERROR_MAX} UTF-16 units, one fewer when the cut would split a surrogate pair; whole when shorter. */
    static String cut(String error) {
        if (error.length() <= ERROR_MAX) return error;
        int end = Character.isHighSurrogate(error.charAt(ERROR_MAX - 1)) ? ERROR_MAX - 1 : ERROR_MAX;
        return error.substring(0, end);
    }
}
