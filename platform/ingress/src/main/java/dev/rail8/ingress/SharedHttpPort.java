package dev.rail8.ingress;

import dev.rail8.api.Component;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Timer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Settings;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The node's HTTP port ({@link HttpIngress}): one Vert.x server on one host and port, owned by a verticle of its own and shared
 * by every component that declares a prefix. A request's path, the query aside, picks the mount that holds it, matched exactly,
 * and that mount's handler gets the request on the connection's event loop. The port answers a path no mount holds itself: 503
 * with {@code Retry-After: 1} when {@code held} (given to {@link #listen}) says a flow serves it at the moment, else 404. A
 * handler that throws gets its request a 500 when no response head was written yet, else the request is reset.
 *
 * <p>A mount's {@link Mount#close()} frees its path at once and completes once every request it took has ended its response or
 * lost its connection, looked at every {@link #DRAIN_CHECK} on the timer; at the drain deadline, counted from that close, those
 * still open are reset. A reset closes the request's connection under HTTP/1.1 and only the request's stream under HTTP/2. The
 * port keeps the requests a mount took in a set it prunes of answered ones whenever it doubled in size, so it stays near the
 * number of requests in flight.
 *
 * <p>Lifecycle: built, then {@link #listen} once, then {@link #close} once. A mount fails before the listen and after the close.
 */
public final class SharedHttpPort implements HttpIngress {

    /** How often a closing mount looks whether the requests it took have answered. */
    public static final Duration DRAIN_CHECK = Duration.ofMillis(20);

    /** The size of a mount's set of requests at which it is first pruned of the answered ones. */
    static final int PRUNE_AT = 64;

    private static final System.Logger LOG = System.getLogger(SharedHttpPort.class.getName());
    private static final Pattern PREFIX = Pattern.compile(PREFIX_PATTERN);
    private static final Pattern ADDRESS_FORM = Pattern.compile(ADDRESS_PATTERN);
    /** {@link #ADDRESS} as a description lists it: what a prefixed component's settings must hold. */
    private static final Object ADDRESS_FIELD = ((List<?>) Settings.of(ADDRESS).toJson().get("fields")).getFirst();

    private enum State { NEW, LISTENING, OPEN, CLOSED }

    private final Vertx vertx;
    private final Timer timer;
    private final String host;
    private final int port;
    private final Duration drain;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final ConcurrentMap<String, Mounted> mounts = new ConcurrentHashMap<>();
    private volatile Map<String, String> prefixes = Map.of();
    private volatile Predicate<String> held = path -> false;
    private volatile int bound = -1;
    private volatile String deployment;

    /**
     * A port that will listen on {@code host}:{@code port} (0: any free port).
     *
     * @param timer the node's timer: the drain checks and deadlines of closing mounts
     * @param drain how long a closing mount waits for the requests it took before it resets them; not negative
     */
    public SharedHttpPort(Vertx vertx, Timer timer, String host, int port, Duration drain) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port " + port + " is not from 0 to 65535");
        this.port = port;
        this.drain = Objects.requireNonNull(drain, "drain");
        if (drain.isNegative()) throw new IllegalArgumentException("drain " + drain + " is negative");
    }

    /**
     * A port on 127.0.0.1 and a free port, its drain checks and deadline (PT30S) on Vert.x timers of {@code vertx}, listening for
     * {@code prefixes} (by scheme) with no path held: for the tests of a component that takes requests on the node's HTTP port.
     */
    public static Future<SharedHttpPort> loopback(Vertx vertx, Map<String, String> prefixes) {
        Timer timer = (delay, task) -> {
            long id = vertx.setTimer(Math.max(1, delay.toMillis()), fired -> task.run());
            return () -> vertx.cancelTimer(id);
        };
        SharedHttpPort port = new SharedHttpPort(vertx, timer, "127.0.0.1", 0, Duration.ofSeconds(30));
        return port.listen(prefixes, path -> false).map(bound -> port);
    }

    /**
     * The prefix of each of {@code components} that declares one, by its scheme, each read once: what {@link #listen} takes.
     *
     * @throws IllegalStateException naming the component, with its scheme and class, when its {@code httpPrefix()} throws or
     *                               answers null, when the prefix is not of {@link #PREFIX_PATTERN} or is
     *                               {@link #RESERVED_PREFIX}, when its settings do not list {@link #ADDRESS}, and naming both
     *                               when two components declare one prefix
     */
    public static Map<String, String> prefixes(List<Component> components) {
        Map<String, String> byScheme = new HashMap<>();
        Map<String, Component> byPrefix = new HashMap<>();
        for (Component c : components) {
            Optional<String> declared;
            try {
                declared = Objects.requireNonNull(c.httpPrefix(), "httpPrefix() answered null");
            } catch (RuntimeException thrown) {
                throw new IllegalStateException(owner(c) + ": its HTTP prefix cannot be read: " + thrown, thrown);
            }
            if (declared.isEmpty()) continue;
            String prefix = declared.get();
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix '" + prefix + "': a prefix is / and one segment, a lower-case"
                        + " letter, then at most 31 lower-case letters, digits or dashes");
            }
            if (RESERVED_PREFIX.equals(prefix)) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix " + prefix + ", which is kept for the platform itself");
            }
            Component other = byPrefix.putIfAbsent(prefix, c);
            if (other != null) {
                throw new IllegalStateException(owner(other) + " and " + owner(c) + " both declare the HTTP prefix " + prefix
                        + "; a prefix has one component");
            }
            if (!((List<?>) c.settings().toJson().get("fields")).contains(ADDRESS_FIELD)) {
                throw new IllegalStateException(owner(c) + " declares the HTTP prefix " + prefix + " but its settings do not list the in field"
                        + " 'address' (HttpIngress.ADDRESS)");
            }
            byScheme.put(c.scheme(), prefix);
        }
        return Map.copyOf(byScheme);
    }

    private static String owner(Component c) {
        return "the component of the scheme " + c.scheme() + " (" + c.getClass().getName() + ")";
    }

    /**
     * The path an in endpoint with the option {@code address} is served at under {@code prefix}: the prefix, then the address.
     *
     * @throws IllegalArgumentException the address is missing, is no text or is not of {@link #ADDRESS_PATTERN}
     */
    public static String path(String prefix, Object address) {
        if (!(address instanceof String a) || !ADDRESS_FORM.matcher(a).matches()) {
            throw new IllegalArgumentException(address == null ? "option 'address' is missing" : "option 'address' " + address
                    + " is not a path such as /orders: segments of / and A-Z a-z 0-9 . _ ~ -, none of them . or .., at most 256 characters");
        }
        return prefix + a;
    }

    /**
     * Binds the port and from then on takes mounts.
     *
     * @param prefixes the prefix of each scheme whose consumers mount, as {@link #prefixes} answers it
     * @param held     whether a path no mount holds belongs to a flow at the moment, so its senders are to try again (503) rather
     *                 than hear that nothing serves it (404); called on the event loop, it must not block
     * @return the port bound (the actual one when it was built with 0); failed when it was called before or nothing could bind,
     *         and the port then takes no mounts
     */
    public Future<Integer> listen(Map<String, String> prefixes, Predicate<String> held) {
        Map<String, String> copied = Map.copyOf(prefixes);
        Objects.requireNonNull(held, "held");
        if (!state.compareAndSet(State.NEW, State.LISTENING)) return Future.failedFuture(new IllegalStateException("the HTTP port listens once"));
        this.prefixes = copied;
        this.held = held;
        Listener listener = new Listener();
        return vertx.deployVerticle(listener).compose(id -> {
            deployment = id;
            bound = listener.server.actualPort();
            if (state.compareAndSet(State.LISTENING, State.OPEN)) return Future.succeededFuture(bound);
            return vertx.undeploy(id).transform(ignored -> Future.failedFuture(new IllegalStateException("the HTTP port was closed while it bound")));
        }, failure -> {
            state.set(State.CLOSED);
            return Future.failedFuture(failure);
        });
    }

    /** The port bound: the actual one when it was built with 0; -1 before {@link #listen} succeeded. */
    public int port() {
        return bound;
    }

    /** How many requests the mount at {@code path} holds as taken, answered ones not yet pruned among them; 0 when none is mounted. */
    int taken(String path) {
        Mounted mounted = mounts.get(path);
        return mounted == null ? 0 : mounted.taken.size();
    }

    @Override
    public Future<Mount> mount(EndpointConfig cfg, Handler<HttpServerRequest> requests) {
        try {
            Objects.requireNonNull(cfg, "cfg");
            Objects.requireNonNull(requests, "requests");
            String prefix = prefixes.get(cfg.scheme());
            if (state.get() != State.OPEN) {
                throw new IllegalStateException("endpoint '" + cfg.name() + "': the HTTP port takes no mounts " + (state.get() == State.CLOSED
                        ? "any more: it is closed" : "yet: it is not listening"));
            }
            if (prefix == null) throw new IllegalArgumentException("endpoint '" + cfg.name() + "': the scheme " + cfg.scheme() + " declares no HTTP prefix");
            String path;
            try {
                path = path(prefix, cfg.options().get(ADDRESS.name()));
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("endpoint '" + cfg.name() + "': " + bad.getMessage(), bad);
            }
            Mounted mounted = new Mounted(cfg.name(), path, requests, url(path));
            Mounted other = mounts.putIfAbsent(path, mounted);
            if (other != null) {
                throw new IllegalStateException("endpoint '" + cfg.name() + "': the path " + path + " is mounted by endpoint '" + other.endpoint + "'");
            }
            if (state.get() != State.OPEN) {   // closed while it mounted: never routed to
                mounts.remove(path, mounted);
                throw new IllegalStateException("endpoint '" + cfg.name() + "': the HTTP port takes no mounts any more: it is closed");
            }
            return Future.succeededFuture(mounted);
        } catch (RuntimeException refused) {
            return Future.failedFuture(refused);
        }
    }

    private URI url(String path) {
        try {
            return new URI("http", null, host, bound, path, null, null);
        } catch (URISyntaxException unformed) {
            throw new IllegalArgumentException("the host " + host + " and the path " + path + " form no URL", unformed);
        }
    }

    /**
     * Takes no mounts from now on and stops listening: its connections close, a request still open on them is cut. Close the
     * mounts first, so their requests drain. Calling it again, or before {@link #listen}, does nothing more.
     */
    public Future<Void> close() {
        State was = state.getAndSet(State.CLOSED);
        String id = deployment;
        deployment = null;
        if (was == State.CLOSED || id == null) return Future.succeededFuture();
        return vertx.undeploy(id);
    }

    /** Routes {@code req} by its path; on the connection's event loop. */
    private void dispatch(HttpServerRequest req) {
        String path = req.path();
        Mounted mounted = path == null ? null : mounts.get(path);
        if (mounted != null && mounted.take(req)) {
            try {
                mounted.requests.handle(req);
            } catch (RuntimeException thrown) {
                LOG.log(System.Logger.Level.WARNING, "the handler of endpoint '" + mounted.endpoint + "' threw on a request at " + path, thrown);
                HttpServerResponse res = req.response();
                if (!res.headWritten()) res.setStatusCode(500).putHeader("content-type", "text/plain").end("the endpoint failed to take the request");
                else if (!res.ended()) res.reset();
            }
            return;
        }
        HttpServerResponse res = req.response().putHeader("content-type", "text/plain");
        if (path != null && held.test(path)) res.setStatusCode(503).putHeader("retry-after", "1").end("the endpoint at " + path + " takes no request at the moment");
        else res.setStatusCode(404).end("no endpoint at " + path);
    }

    /** Whether {@code req}'s response ended, or its connection is gone and it never will. */
    private static boolean answered(HttpServerRequest req) {
        HttpServerResponse res = req.response();
        return res.ended() || res.closed();
    }

    /** An endpoint mounted on the port, and the requests it took that may not have answered yet. */
    private final class Mounted implements Mount {
        final String endpoint;
        final String path;
        final Handler<HttpServerRequest> requests;
        final URI url;
        final Set<HttpServerRequest> taken = ConcurrentHashMap.newKeySet();
        final AtomicInteger pruneAt = new AtomicInteger(PRUNE_AT);
        final AtomicBoolean closing = new AtomicBoolean();
        final Promise<Void> drained = Promise.promise();
        volatile boolean closed;

        Mounted(String endpoint, String path, Handler<HttpServerRequest> requests, URI url) {
            this.endpoint = endpoint;
            this.path = path;
            this.requests = requests;
            this.url = url;
        }

        /** Whether the handler gets {@code req}: false once the mount is closing. Added before the look, so a close sees it. */
        boolean take(HttpServerRequest req) {
            taken.add(req);
            if (closed) {
                taken.remove(req);
                return false;
            }
            if (taken.size() >= pruneAt.get()) {
                taken.removeIf(SharedHttpPort::answered);
                pruneAt.set(Math.max(PRUNE_AT, 2 * taken.size()));
            }
            return true;
        }

        @Override
        public URI url() {
            return url;
        }

        @Override
        public Future<Void> close() {
            if (closing.compareAndSet(false, true)) {
                closed = true;
                mounts.remove(path, this);
                try {
                    Timer.Cancellable deadline = timer.schedule(drain, this::cut);
                    check(deadline);
                } catch (RuntimeException refused) {   // a timer that takes no task: nothing waits
                    cut();
                }
            }
            return drained.future();
        }

        private void check(Timer.Cancellable deadline) {
            taken.removeIf(SharedHttpPort::answered);
            if (taken.isEmpty()) {
                deadline.cancel();
                drained.tryComplete();
            } else if (!drained.future().isComplete()) {
                timer.schedule(DRAIN_CHECK, () -> check(deadline));
            }
        }

        /** Resets what is still open and completes the close. */
        private void cut() {
            for (HttpServerRequest req : taken) {
                if (answered(req)) continue;
                LOG.log(System.Logger.Level.WARNING, "endpoint '" + endpoint + "' closed with a request at " + path + " still open after " + drain
                        + ": it is reset");
                req.response().reset();
            }
            taken.clear();
            drained.tryComplete();
        }
    }

    /** The verticle that owns the server. */
    private final class Listener extends VerticleBase {
        volatile HttpServer server;

        @Override
        public Future<?> start() {
            return vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)).requestHandler(SharedHttpPort.this::dispatch).listen()
                    .map(listening -> server = listening);
        }

        @Override
        public Future<?> stop() {
            return server.close();
        }
    }
}
