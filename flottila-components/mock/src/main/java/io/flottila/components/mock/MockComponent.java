package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.BlobKey;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.IdGenerator;
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The in-process mock of any scheme (ARCHITECTURE §7 "records what it was sent, replies from fixtures; selected per
 * environment"; §11): bound to the scheme it stands in for, so the flow file stays byte-identical, with one
 * {@link Behaviour} per endpoint from the flow's {@code *.test.yaml}. A producer records every envelope per endpoint and
 * answers with the reply of the consumer running on that same endpoint when there is one, else per behaviour: the fixture as
 * the next body (or the request echoed), after the delay through the injected timer, or a failure — a status or fault
 * as {@link NackException}, retryable as HTTP would be. Each delivery calls a consumer's handler on a virtual thread of its
 * own (D-057). Standing in for a polling component such as {@code sftp} (D-076), a consumer also polls: every second on the
 * injected timer it delivers the files {@link #drop dropped} on its endpoint — and the behaviour's {@code files} fixtures,
 * fed at its start in name order — oldest first, one at a time, by the rules of the real one: Accepted or Reply removes
 * the file, a retryable Nack or a failed answer keeps it and ends the round, a final Nack (or a body above the endpoint's
 * {@code maxBodyBytes}) keeps it and never delivers it again to that consumer. Development and CI only: a deployed node
 * refuses a mock binding by its scheme and by this package (the node's start).
 *
 * @since 0.1
 */
public final class MockComponent implements Component {

    static final String CONFIG_SCHEMA = "{\"type\":\"object\"}";   // the real endpoint's options are not the mock's business
    /** How often a consumer polls the files dropped on its endpoint. */
    static final Duration ROUND = Duration.ofSeconds(1);

    private final String scheme;
    private final BlobStore blobs;
    private final Timer timer;
    private final IdGenerator ids;
    private final Map<String, Inbox> inboxes = new ConcurrentHashMap<>();
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sends = new ConcurrentHashMap<>();
    private final List<Envelope> sent = new CopyOnWriteArrayList<>();
    private final Map<String, List<Envelope>> sentTo = new ConcurrentHashMap<>();
    private final Map<String, Function<Envelope, Future<Ack>>> handlers = new ConcurrentHashMap<>();   // by endpoint name: a consumer answers its own endpoint only

    /** A mock whose consumers number their deliveries with ids of a random prefix of its own. */
    public MockComponent(String scheme, BlobStore blobs, Timer timer) {
        this(scheme, blobs, timer, counting("mock-" + UUID.randomUUID() + "-"));
    }

    /** A mock whose consumers take the ids of the deliveries they poll from {@code ids} (the runtime's, D-076). */
    public MockComponent(String scheme, BlobStore blobs, Timer timer, IdGenerator ids) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    private static IdGenerator counting(String prefix) {
        AtomicLong n = new AtomicLong();
        return () -> prefix + n.incrementAndGet();
    }

    /**
     * Places a file named {@code name} holding {@code bytes} on the endpoint named {@code endpoint}, younger than every file
     * there; a file of that name is replaced. A consumer of that endpoint delivers it in its next round.
     */
    public void drop(String endpoint, String name, Buffer bytes) {
        inbox(endpoint).drop(new Dropped(Objects.requireNonNull(name, "name"), bytes.copy()));
    }

    /** The names of the files still on the endpoint named {@code endpoint}, oldest first. */
    public Collection<String> remaining(String endpoint) {
        return inbox(endpoint).files().stream().map(Dropped::name).toList();
    }

    private Inbox inbox(String endpoint) {
        return inboxes.computeIfAbsent(Objects.requireNonNull(endpoint, "endpoint"), k -> new Inbox());
    }

    /** Binds {@code behaviour} to the endpoint named {@code endpoint}; an unbound endpoint echoes. */
    public MockComponent bind(String endpoint, Behaviour behaviour) {
        behaviours.put(Objects.requireNonNull(endpoint, "endpoint"), Objects.requireNonNull(behaviour, "behaviour"));
        return this;
    }

    /** Binds every entry of a {@code mocks} section ({@code endpoint → behaviour}), fixture paths against {@code baseDir}. */
    public MockComponent bindAll(Map<String, Object> mocks, Path baseDir) {
        mocks.forEach((endpoint, node) -> {
            if (!(node instanceof Map<?, ?> m)) throw new IllegalArgumentException("mocks." + endpoint + " must be an object");
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            bind(endpoint, Behaviour.from(copy, baseDir));
        });
        return this;
    }

    public List<Envelope> sent() { return List.copyOf(sent); }

    public List<Envelope> sentTo(String endpoint) { return List.copyOf(sentTo.getOrDefault(endpoint, List.of())); }

    @Override public String scheme() { return scheme; }

    @Override public String configSchema() { return CONFIG_SCHEMA; }

    @Override
    public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> h) {
        Objects.requireNonNull(h, "handler");
        Objects.requireNonNull(cfg, "cfg");
        Drain drain = new Drain();
        Function<Envelope, Future<Ack>> tracked = e -> drain.deliver(h, e);
        Poller poller = new Poller(cfg, tracked, drain);
        return new Consumer() {
            /** Registers the handler, feeds the behaviour's {@code files} and schedules round 0; fails when a fixture cannot be read. */
            @Override public Future<Void> start() {
                try {
                    poller.feed(behaviours.getOrDefault(cfg.name(), Behaviour.ECHO).files());
                } catch (IOException unreadable) {
                    return Future.failedFuture(new IllegalStateException("mock files fixtures cannot be read for endpoint " + cfg.name(), unreadable));
                }
                handlers.put(cfg.name(), tracked);
                poller.start();
                return Future.succeededFuture();
            }
            /**
             * Unregisters the handler and cancels the next round at once; completes once every answer it handed out has
             * completed and the round in flight has removed its file (D-056).
             */
            @Override public Future<Void> stop() { handlers.remove(cfg.name(), tracked); poller.stop(); return drain.stop(); }
        };
    }

    /** A dropped file; equal only to itself, so a file dropped again under the same name is a new one. */
    private static final class Dropped {
        private final String name;
        private final Buffer bytes;

        Dropped(String name, Buffer bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        String name() { return name; }

        Buffer bytes() { return bytes; }
    }

    /** The files dropped on one endpoint, oldest first. */
    private static final class Inbox {
        private final List<Dropped> files = new ArrayList<>();   // guarded by this

        synchronized void drop(Dropped file) {
            files.removeIf(f -> f.name().equals(file.name()));
            files.add(file);
        }

        synchronized List<Dropped> files() { return List.copyOf(files); }

        synchronized void remove(Dropped file) { files.remove(file); }
    }

    /** A consumer's rounds: every {@link #ROUND} on the injected timer, each on a virtual thread of its own. */
    private final class Poller {
        private final String endpoint;
        private final String flow;
        private final Object maxBodyBytes;   // the endpoint's limit, if it sets one; else a file's own size
        private final Function<Envelope, Future<Ack>> handler;
        private final Drain drain;
        private final Set<Dropped> refused = ConcurrentHashMap.newKeySet();
        private Timer.Cancellable next;   // guarded by this
        private boolean stopped;          // guarded by this

        Poller(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler, Drain drain) {
            this.endpoint = cfg.name();
            this.flow = cfg.options().get("flow") instanceof String f ? f : cfg.name();
            this.maxBodyBytes = cfg.options().get("maxBodyBytes");
            this.handler = handler;
            this.drain = drain;
        }

        /** Drops the fixtures {@code glob} matches, in name order: regular files of its directory whose name matches its last segment. */
        void feed(Optional<Path> glob) throws IOException {
            if (glob.isEmpty()) return;
            Path dir = glob.get().getParent();
            PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob.get().getFileName());
            List<Path> fixtures;
            try (Stream<Path> listed = Files.list(dir)) {
                fixtures = listed.filter(p -> Files.isRegularFile(p) && matcher.matches(p.getFileName())).sorted().toList();
            }
            for (Path fixture : fixtures) drop(endpoint, fixture.getFileName().toString(), Buffer.buffer(Files.readAllBytes(fixture)));
        }

        synchronized void start() { next = timer.schedule(Duration.ZERO, this::fire); }

        synchronized void stop() {
            stopped = true;
            if (next != null) next.cancel();
        }

        private synchronized void schedule() {
            if (!stopped) next = timer.schedule(ROUND, this::fire);
        }

        /**
         * The timer's task: the round runs on a virtual thread, held by the drain so a stop waits for its removal. A round
         * fired as it was cancelled takes no file: it sees the stop before its first.
         */
        private void fire() {
            drain.hold();
            Thread.ofVirtual().name("mock-round").start(this::round);
        }

        private synchronized boolean stopped() { return stopped; }

        private void round() {
            try {
                for (Dropped file : inbox(endpoint).files()) {
                    if (stopped()) break;   // stop takes no new file
                    if (refused.contains(file)) continue;
                    if (!take(file)) break;
                }
            } finally {
                drain.release();
                schedule();
            }
        }

        /** Delivers one file; whether the round goes on. */
        private boolean take(Dropped file) {
            String id = ids.next();
            long max = maxBodyBytes instanceof Number n ? n.longValue() : file.bytes().length();
            Future<Body> written = blobs.write(BlobKey.body(id, 0), file.bytes(), max);
            written.otherwiseEmpty().await();
            if (written.failed()) return refuse(file);   // above the endpoint's maxBodyBytes: refused finally, as the real one does
            Ack ack = handler.apply(Envelope.ingress(id, flow, Headers.empty(), written.result())).otherwiseEmpty().await();
            return switch (ack) {
                case Ack.Accepted a -> removed(file);
                case Ack.Reply r -> removed(file);
                case Ack.Nack n when n.retryable() -> false;
                case Ack.Nack n -> refuse(file);
                case null -> false;   // the handler threw, answered null or its answer failed: kept, the round ends
            };
        }

        /** A final refusal: kept, never delivered again to this consumer. */
        private boolean refuse(Dropped file) {
            refused.add(file);
            return true;
        }

        private boolean removed(Dropped file) {
            inbox(endpoint).remove(file);
            return true;
        }
    }

    @Override
    public Producer producer(EndpointConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        String name = cfg.name();
        List<Envelope> mine = sentTo.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
        AtomicLong count = sends.computeIfAbsent(name, k -> new AtomicLong());
        return new Producer() {
            @Override public Future<Envelope> send(Envelope e) {
                sent.add(e);
                mine.add(e);
                Behaviour b = behaviours.getOrDefault(name, Behaviour.ECHO);
                long n = count.incrementAndGet();
                return after(b.delay(), () -> {
                    if (b.failsOn(n)) return Future.failedFuture(failure(name, b.fail().orElseThrow()));
                    Function<Envelope, Future<Ack>> running = handlers.get(name);
                    if (running != null) return deliver(running, e);
                    return b.reply().map(fixture -> reply(e, fixture)).orElse(Future.succeededFuture(e));
                });
            }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    @Override
    public Future<Health> health() {
        return Future.succeededFuture(handlers.isEmpty() ? Health.down("no consumer running") : Health.up());
    }

    /**
     * The answers a consumer's handler handed out and that are still outstanding (D-056): the consumer itself holds one more
     * until {@link #stop}, so the count reaches zero — and the drain completes — only after the stop and the last answer.
     */
    private static final class Drain {
        private final Promise<Void> drained = Promise.promise();
        private final Promise<Void> stopping = Promise.promise();   // completes once, however often stop() is called
        private int open = 1;                                       // guarded by this: the consumer's own hold plus each outstanding answer

        Drain() {
            stopping.future().onComplete(stopped -> release());
        }

        /**
         * Calls {@code handler} with {@code e} on a virtual thread of its own — one per delivery, never the sender's (D-057) —
         * and returns its answer, counted until it completes before the handler runs: a stop issued meanwhile waits for it.
         * A handler that throws or answers null fails the answer ({@link Future#future} catches what its handler throws).
         */
        Future<Ack> deliver(Function<Envelope, Future<Ack>> handler, Envelope e) {
            Promise<Ack> answer = Promise.promise();
            synchronized (this) { open++; }
            answer.future().onComplete(done -> release());
            Thread.ofVirtual().name("mock-handler").start(() -> Future.<Ack>future(p -> handler.apply(e).onComplete(p)).onComplete(answer));
            return answer.future();
        }

        /** Holds the drain for a round: a stop completes only after the round's {@link #release}. */
        synchronized void hold() { open++; }

        void release() {
            boolean last;
            synchronized (this) { last = --open == 0; }
            if (last) drained.tryComplete();
        }

        /** Completes once no answer is outstanding, at once when none is; the same future for every call. */
        Future<Void> stop() {
            stopping.tryComplete();
            return drained.future();
        }
    }

    private <T> Future<T> after(java.time.Duration delay, java.util.function.Supplier<Future<T>> then) {
        if (delay.isZero()) return then.get();
        Promise<T> p = Promise.promise();
        timer.schedule(delay, () -> then.get().onComplete(p));
        return p.future();
    }

    private static Future<Envelope> deliver(Function<Envelope, Future<Ack>> handler, Envelope e) {
        return handler.apply(e).compose(ack -> switch (ack) {
            case Ack.Reply r -> Future.succeededFuture(r.reply());
            case Ack.Accepted a -> Future.succeededFuture(e);
            case Ack.Nack n -> Future.failedFuture(new NackException(n.reason(), n.retryable()));
        });
    }

    /** The fixture as the next step's body, under {@link Envelope#nextKey()} (the run's own from its second attempt on, D-066); the content type from the file's extension. */
    private Future<Envelope> reply(Envelope e, Path fixture) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(fixture);
        } catch (IOException x) {
            return Future.failedFuture(new IllegalStateException("mock reply fixture cannot be read: " + fixture, x));
        }
        return blobs.write(e.nextKey(), Buffer.buffer(bytes), bytes.length)
                .map(body -> e.next(body).withHeader(Envelope.H_CONTENT_TYPE, contentType(fixture)));
    }

    static String contentType(Path fixture) {
        String name = fixture.getFileName().toString();
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    static NackException failure(String endpoint, Behaviour.Failure failure) {
        return switch (failure) {
            case Behaviour.Status s -> new NackException("mock " + endpoint + " answered " + s.code(), s.retryable());
            case Behaviour.Fault f -> new NackException("mock " + endpoint + " " + f.name().toLowerCase(java.util.Locale.ROOT), f.retryable());
        };
    }
}
