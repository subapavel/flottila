package dev.rail8.components.direct;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.Deadline;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Key;
import dev.rail8.api.NackException;
import dev.rail8.api.Producer;
import dev.rail8.api.Timer;
import dev.rail8.api.Views;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Calls between the flows of one node. An in endpoint of scheme {@code direct} serves an {@code address}; an out endpoint
 * naming the same address calls the flow it belongs to, and the send is answered with what that flow answered. Nothing
 * leaves the node: the callee's flow receives the caller's body itself, not a copy.
 *
 * <p><b>Address</b> (option {@code address}, in and out): a path such as {@code /orders} or {@code /orders/eu}, at most 256
 * characters, matched exactly. {@link #internalAddress} answers it for both directions. An in endpoint also takes the
 * node's {@code maxBodyBytes} (required, positive: the largest body its flow takes) and {@code flow} (its flow's name,
 * by default the endpoint's name).
 *
 * <p><b>Consumer</b>: its start claims the address, and fails with an {@link IllegalStateException} naming the flow that
 * serves it already. Its stop releases the address at once, so the next version of the flow can claim it, takes nothing
 * new, and completes once every answer its flow still owes has been given. A stopped consumer is never started again.
 *
 * <p><b>Producer</b>: a send looks the address up and is refused, in this order:
 * <ol>
 *   <li>the call would be more than {@value #MAX_DEPTH} calls deep (the prop {@code rail8.depth} the caller arrived with,
 *       plus one): a final {@link NackException}, also for a depth that is not a whole number from 0;</li>
 *   <li>no flow serves the address, or its consumer is stopping: a retryable {@link NackException};</li>
 *   <li>the body is above the callee's {@code maxBodyBytes}: a final {@link NackException}.</li>
 * </ol>
 * Otherwise the callee's flow receives a new message: a new id, the caller's headers with {@link Envelope#H_MESSAGE_ID} set
 * to the caller's id, the caller's body, the key {@code rail8.ref} (the caller's own {@code rail8.ref}, else the caller's
 * id: every message of a chain of calls carries the id of its first) and the prop {@code rail8.depth}; nothing else. Its
 * handler runs on a virtual thread of its own. What the caller gets:
 * <ul>
 *   <li>a {@link Ack.Reply}: the caller's message one step on — its id, flow, props, keys plus {@code rail8.ref} and
 *       attachments — with the reply's headers and the reply's body copied under the caller's {@link Envelope#nextKey()}; a
 *       copy that fails fails the send with the store's failure;</li>
 *   <li>an {@link Ack.Accepted}: the message as sent, plus {@code rail8.ref};</li>
 *   <li>an {@link Ack.Nack}: a {@link NackException} with its reason and its retryable;</li>
 *   <li>a failed answer, a handler that throws or one that answers nothing: a failure whose text names the callee's
 *       message id and whose cause is the callee's failure. The first {@link Fault} in the callee's failure decides: a
 *       {@link NackException} gives a {@link NackException} with the same retryable, another fault a fault of the same
 *       kind, none at all a fault of kind {@link Fault.Kind#INTERNAL}. So the caller's flow judges the failure final or
 *       retryable as the callee's flow would judge a fault. An {@link Error} or an {@link InterruptedException} passes
 *       through as it is.</li>
 * </ul>
 * A send with a {@link Deadline} that has no time left starts nothing and fails with a retryable {@link NackException}; one
 * still unanswered when its deadline runs out is cut on the injected {@link Timer} — the deadline re-read each time it
 * fires — with a retryable {@link NackException}, and the answer that comes later is dropped, nothing copied.
 *
 * <p><b>Health</b>: up while the component serves an address, down otherwise — also on a node whose flows only call.
 *
 * @since 0.1
 */
public final class DirectComponent implements Component {

    /** The key every message of a chain of calls carries: the id of the chain's first message. */
    private static final String REF = "rail8.ref";
    /** The prop of a callee's message: how many calls deep it is. */
    private static final String DEPTH = "rail8.depth";
    private static final Key<Integer> DEPTH_KEY = new Key<>(DEPTH);
    /** The most calls deep a chain may go. */
    static final int MAX_DEPTH = 8;
    private static final String ADDRESS_PATTERN = "^(?=.{2,256}$)/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$";
    private static final Pattern ADDRESS = Pattern.compile(ADDRESS_PATTERN);
    private static final Settings SETTINGS = Settings.of(
            Field.text("address", "The address in this node: an in endpoint serves it, an out endpoint calls the flow serving it. "
                    + "A path such as /orders, matched exactly.").required().pattern(ADDRESS_PATTERN));

    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final Map<String, Served> served = new ConcurrentHashMap<>();

    /**
     * One that copies a reply's body into {@code blobs}, gives each callee's message an id from {@code ids} and cuts a send at
     * its deadline on {@code timer}.
     */
    public DirectComponent(BlobStore blobs, IdGenerator ids, Timer timer) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    @Override public String scheme() { return "direct"; }

    @Override public Settings settings() { return SETTINGS; }

    /** The endpoint's {@code address}; an {@link IllegalArgumentException} when it has none or not a path. */
    @Override
    public Optional<String> internalAddress(EndpointConfig cfg) {
        return Optional.of(address(cfg));
    }

    private static String address(EndpointConfig cfg) {
        Object v = cfg.options().get("address");
        if (!(v instanceof String a) || !ADDRESS.matcher(a).matches()) {
            throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option 'address' must be a path such as /orders, of at most 256 characters, was " + v);
        }
        return a;
    }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(handler, "handler");
        Object max = cfg.options().get("maxBodyBytes");
        if (!(max instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalArgumentException("endpoint '" + cfg.name() + "': option 'maxBodyBytes' must be a positive integer (never unlimited), was " + max);
        }
        Object flow = cfg.options().get("flow");
        return new Served(address(cfg), flow == null ? cfg.name() : flow.toString(), n.longValue(), handler);
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        String address = address(Objects.requireNonNull(cfg, "cfg"));
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                return call(address, e, null);
            }

            @Override public Future<Envelope> send(Envelope e, Deadline d) {
                Duration left = d.remaining();
                if (!left.isPositive()) return Future.failedFuture(new NackException("deadline passed: message " + e.id() + " not sent", true));
                return call(address, e, d);
            }

            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(served.isEmpty() ? Health.down("no address served") : Health.up());
    }

    /** A call of the flow serving {@code address}, cut when {@code d} — none: never — runs out first. */
    private Future<Envelope> call(String address, Envelope e, Deadline d) {
        Object stamped = e.props().asMap().get(DEPTH);
        Integer depth = depth(stamped);
        if (depth == null) {
            return Future.failedFuture(new NackException("message " + e.id() + ": the prop " + DEPTH
                    + " is not a whole number of calls from 0 to " + MAX_DEPTH + ", was " + stamped, false));
        }
        if (depth > MAX_DEPTH) {
            return Future.failedFuture(new NackException("message " + e.id() + " would call " + address + " " + depth
                    + " calls deep, more than the " + MAX_DEPTH + " a chain of calls may go", false));
        }
        Served callee = served.get(address);
        if (callee == null) return notServed(address);
        String ref = e.key(REF).orElse(e.id());
        String calleeId = ids.next();
        Future<Ack> answer = callee.offer(Envelope.ingress(calleeId, callee.flow, e.withOutboundId().headers(), e.body())
                .withKey(REF, ref).with(DEPTH_KEY, depth));
        if (answer == null) return notServed(address);   // its consumer is stopping
        Promise<Envelope> out = Promise.promise();
        AtomicReference<Timer.Cancellable> armed = new AtomicReference<>();
        if (d != null) cutAt(d, out, armed);
        answer.onComplete(done -> {
            Timer.Cancellable cut = armed.get();
            if (cut != null) cut.cancel();
            if (out.future().isComplete()) return;   // cut at the deadline: the late answer is dropped, nothing copied
            try {
                answered(e, ref, calleeId, done).onComplete(settled -> {
                    if (settled.succeeded()) out.tryComplete(settled.result());
                    else out.tryFail(settled.cause());
                });
            } catch (RuntimeException failed) {
                out.tryFail(failed);
            }
        });
        return out.future();
    }

    private static Future<Envelope> notServed(String address) {
        return Future.failedFuture(new NackException("no flow serves the direct address " + address, true));
    }

    /**
     * Cuts {@code out} with a retryable Nack once {@code d} has no time left: re-read now and each time the timer fires, re-armed
     * on the timer while time is left.
     */
    private void cutAt(Deadline d, Promise<Envelope> out, AtomicReference<Timer.Cancellable> armed) {
        Runnable[] reread = new Runnable[1];
        reread[0] = () -> {
            if (out.future().isComplete()) return;
            Duration left = d.remaining();
            if (left.isPositive()) armed.set(timer.schedule(left, reread[0]));
            else out.tryFail(new NackException("deadline passed: the call was cut", true));
        };
        reread[0].run();
    }

    /** What the caller of {@code e} gets for the callee's answer {@code done}. */
    private Future<Envelope> answered(Envelope e, String ref, String calleeId, AsyncResult<Ack> done) {
        if (done.failed()) return Future.failedFuture(failure(calleeId, done.cause()));
        return switch (done.result()) {
            case Ack.Reply r -> blobs.write(e.nextKey(), r.reply().body().open(), r.reply().body().size())
                    .map(copy -> new Envelope(e.id(), e.flow(), e.step() + 1, r.reply().headers(), e.props(), e.keys().with(REF, ref),
                            copy, e.attachments(), new Views()));
            case Ack.Accepted a -> Future.succeededFuture(e.withKey(REF, ref));
            case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
            case null -> Future.failedFuture(failure(calleeId, new IllegalStateException("the flow answered nothing")));
        };
    }

    /**
     * The caller's failure for {@code failed}, the failure of the callee's message {@code calleeId}: its text names that
     * message and the fault that decides, its cause is {@code failed}.
     */
    private static Throwable failure(String calleeId, Throwable failed) {
        if (failed instanceof Error || failed instanceof InterruptedException) return failed;
        Throwable decides = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failed; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof Fault) {
                decides = t;
                break;
            }
        }
        if (decides instanceof NackException nack) {
            NackException rebuilt = new NackException("message " + calleeId + " of the called flow: " + nack.reason(), nack.retryable());
            rebuilt.initCause(failed);
            return rebuilt;
        }
        Fault.Kind kind = decides instanceof Fault f ? f.kind() : Fault.Kind.INTERNAL;
        return new CallFailed(kind, "message " + calleeId + " of the called flow failed: " + (decides == null ? failed : decides), failed);
    }

    /**
     * The depth of a call made by a message whose prop {@code rail8.depth} is {@code stamped}: one more than it, 1 when it is
     * absent; null when it is not a whole number from 0 to {@value #MAX_DEPTH} of any {@link Number} type, as a prop restored
     * from storage may be.
     */
    private static Integer depth(Object stamped) {
        if (stamped == null) return 1;
        if (!(stamped instanceof Number n)) return null;
        double d = n.doubleValue();
        return d >= 0 && d <= MAX_DEPTH && d == Math.rint(d) ? (int) d + 1 : null;
    }

    /** A callee's failure that is not a refusal, named by the callee's message, with the kind of its first fault. */
    private static final class CallFailed extends RuntimeException implements Fault {
        private final Kind kind;

        CallFailed(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        @Override public Kind kind() { return kind; }
    }

    /** A consumer: the address it claims, its flow, its body limit and its handler, and the answers it still owes. */
    private final class Served implements Consumer {
        private final String address;
        private final String flow;
        private final long maxBodyBytes;
        private final Function<Envelope, Future<Ack>> handler;
        private final Promise<Void> drained = Promise.promise();
        private int open = 1;            // guarded by this: the consumer's own hold until its stop, plus each answer owed
        private boolean stopping;        // guarded by this

        Served(String address, String flow, long maxBodyBytes, Function<Envelope, Future<Ack>> handler) {
            this.address = address;
            this.flow = flow;
            this.maxBodyBytes = maxBodyBytes;
            this.handler = handler;
        }

        @Override
        public Future<Void> start() {
            Served holder;
            synchronized (this) {
                if (stopping) return Future.failedFuture(new IllegalStateException("a stopped consumer of direct address " + address + " is never started again"));
                holder = served.putIfAbsent(address, this);
            }
            if (holder != null && holder != this) {
                return Future.failedFuture(new IllegalStateException("the direct address " + address + " is served by the flow " + holder.flow + " already"));
            }
            return Future.succeededFuture();
        }

        /** Releases the address and takes nothing new at once; completes once every answer owed has been given. The same future on every call. */
        @Override
        public Future<Void> stop() {
            boolean last = false;
            synchronized (this) {
                if (!stopping) {
                    stopping = true;
                    served.remove(address, this);
                    last = --open == 0;
                }
            }
            if (last) drained.tryComplete();
            return drained.future();
        }

        /**
         * The answer of this consumer's flow to {@code arrival}, from the handler on a virtual thread of its own and owed
         * until it completes; a final Nack for a body above the limit; null once the consumer is stopping.
         */
        Future<Ack> offer(Envelope arrival) {
            synchronized (this) {
                if (stopping) return null;
                if (arrival.body().size() > maxBodyBytes) {
                    return Future.succeededFuture(new Ack.Nack("a body of " + arrival.body().size() + " bytes is above the " + maxBodyBytes
                            + " the flow " + flow + " takes", false));
                }
                open++;
            }
            Promise<Ack> answer = Promise.promise();
            answer.future().onComplete(done -> release());
            Thread.ofVirtual().name("direct-" + flow).start(() -> Future.<Ack>future(p -> handler.apply(arrival).onComplete(p)).onComplete(answer));
            return answer.future();
        }

        private void release() {
            boolean last;
            synchronized (this) { last = --open == 0; }
            if (last) drained.tryComplete();
        }
    }
}
