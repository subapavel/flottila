package dev.rail8.runtime.sample;

import dev.rail8.api.plugin.AdminReply;
import dev.rail8.api.plugin.AdminRequest;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.Plugin;
import dev.rail8.api.plugin.PluginSettings;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A sample of a module that brings services of the admin API: the plugin {@code admin-sample}, whose services let a test make
 * every kind of call and reply the node passes on or refuses.
 * <ul>
 * <li>{@code GET clock}: 200 {@code {"now": <the instant of the call's clock>, "json": <the call's json>}}.</li>
 * <li>{@code POST echo}: 200 with the call's JSON as it came.</li>
 * <li>{@code POST reply}: the reply the call asks for. {@code {"status": n, "json": "<text>"}} answers {@code n} with that
 *     text; {@code {"status": n, "bytes": k}} answers {@code n} with a JSON string of {@code k} bytes in UTF-8, of
 *     {@code a}s, or with {@code "wide": true} mostly of the two-byte {@code é}; {@code {"none": true}} answers no reply at all
 *     (null).</li>
 * <li>{@code POST fail}: throws.</li>
 * <li>{@code POST hold}: holds the call until the test lets it go, then answers 200 {@code {"held": true}}; interrupted, it
 *     throws the {@link InterruptedException}.</li>
 * </ul>
 * Its services keep no state between calls: {@code hold} reports to the {@link Holds} the test gives the plugin.
 */
public final class AdminSamplePlugin implements Plugin {

    private final Holds holds;

    /** The plugin, its {@code hold} service reporting to {@code holds}. */
    public AdminSamplePlugin(Holds holds) {
        this.holds = holds;
    }

    @Override public String name() { return "admin-sample"; }

    @Override public String api() { return API; }

    @Override
    public List<AdminService> services(PluginSettings settings) {
        return List.of(new Clock(), new Echo(), new Reply(), new Fail(), new Hold(holds));
    }

    /**
     * What {@code POST hold} reports and waits for: the thread of each held call as it starts, the thread of each interrupted
     * one as it ends, and, for each held call, the gate the test opens to let it go.
     */
    public static final class Holds {
        private static final Duration WAIT = Duration.ofSeconds(20);

        private final BlockingQueue<Thread> held = new LinkedBlockingQueue<>();
        private final Set<Thread> interrupted = ConcurrentHashMap.newKeySet();
        private final Map<Thread, CountDownLatch> gates = new ConcurrentHashMap<>();

        /** The thread of the next call that is held, waited for up to 20 s. */
        public Thread next() throws InterruptedException {
            Thread next = held.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
            if (next == null) throw new AssertionError("no call was held within " + WAIT);
            return next;
        }

        /** Whether the call on {@code thread} ended interrupted. */
        public boolean interrupted(Thread thread) {
            return interrupted.contains(thread);
        }

        /** Lets the held call on {@code thread} go, a thread {@link #next()} gave. */
        public void letGo(Thread thread) {
            gates.get(thread).countDown();
        }
    }

    private record Clock() implements AdminService {
        @Override public String method() { return "GET"; }
        @Override public String path() { return "clock"; }
        @Override public AdminReply handle(AdminRequest request) {
            return new AdminReply(200, new JsonObject().put("now", request.clock().instant().toString()).put("json", request.json()).encode());
        }
    }

    private record Echo() implements AdminService {
        @Override public String method() { return "POST"; }
        @Override public String path() { return "echo"; }
        @Override public AdminReply handle(AdminRequest request) { return new AdminReply(200, request.json()); }
    }

    private record Reply() implements AdminService {
        @Override public String method() { return "POST"; }
        @Override public String path() { return "reply"; }
        @Override public AdminReply handle(AdminRequest request) {
            JsonObject asked = new JsonObject(request.json());
            if (asked.getBoolean("none", false)) return null;
            int status = asked.getInteger("status");
            if (asked.containsKey("bytes")) {
                int inside = asked.getInteger("bytes") - 2;   // the quotes are a byte each
                String text = asked.getBoolean("wide", false) ? "a".repeat(inside % 2) + "é".repeat(inside / 2) : "a".repeat(inside);
                return new AdminReply(status, "\"" + text + "\"");
            }
            return new AdminReply(status, asked.getString("json"));
        }
    }

    private record Fail() implements AdminService {
        @Override public String method() { return "POST"; }
        @Override public String path() { return "fail"; }
        @Override public AdminReply handle(AdminRequest request) { throw new IllegalStateException("the sample fails as asked"); }
    }

    private record Hold(Holds holds) implements AdminService {
        @Override public String method() { return "POST"; }
        @Override public String path() { return "hold"; }
        @Override public AdminReply handle(AdminRequest request) throws InterruptedException {
            CountDownLatch gate = new CountDownLatch(1);
            holds.gates.put(Thread.currentThread(), gate);
            holds.held.add(Thread.currentThread());
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                holds.interrupted.add(Thread.currentThread());
                throw interrupted;
            }
            return new AdminReply(200, "{\"held\":true}");
        }
    }
}
