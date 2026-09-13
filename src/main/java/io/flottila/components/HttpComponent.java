package io.flottila.components;

import io.flottila.core.Body;
import io.flottila.core.Envelope;
import io.flottila.runtime.BatchingWriter;
import io.flottila.runtime.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.Router;
import io.vertx.mutiny.ext.web.RoutingContext;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.handler.BodyHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Native Vert.x HTTP on both ends. One Router per port, shared with the admin API. */
public class HttpComponent implements Component {
    private static final Logger log = LoggerFactory.getLogger(HttpComponent.class);
    private final Vertx vertx;
    private final WebClient client;
    private final int inMemoryLimit;
    private final Map<Integer, Router> routers = new ConcurrentHashMap<>();

    public HttpComponent(Vertx vertx, int inMemoryLimit) {
        this.vertx = vertx; this.client = WebClient.create(vertx); this.inMemoryLimit = inMemoryLimit;
    }

    @Override public String scheme() { return "http"; }

    public Router router(int port) {
        return routers.computeIfAbsent(port, p -> { Router r = Router.router(vertx); r.route().handler(BodyHandler.create()); return r; });
    }

    @Override public Consumer consumer(URI endpoint, String flow) {
        return new HttpConsumer(endpoint, flow);
    }

    @Override public Producer producer(URI endpoint, JsonObject opts) {
        Set<String> forward = opts.getJsonArray("forwardHeaders", new JsonArray()).stream()
                .map(o -> o.toString().toLowerCase()).collect(Collectors.toSet());
        return e -> {
            var req = client.postAbs(endpoint.toString())
                    .putHeader("content-type", "application/xml")
                    .putHeader("x-flottila-id", e.id());
            // nothing propagates implicitly; only the flow-config allow-list
            e.headers().asMap().forEach((k, v) -> { if (forward.contains(k)) req.putHeader(k, v); });
            return req.sendBuffer(Buffer.newInstance(io.vertx.core.buffer.Buffer.buffer(e.body().bytes())))
                    .onItem().transform(resp -> {
                        if (resp.statusCode() >= 300) throw new IllegalStateException("HTTP " + resp.statusCode() + " from " + endpoint);
                        return e.next(Body.of(resp.bodyAsBuffer() == null ? io.vertx.core.buffer.Buffer.buffer() : resp.bodyAsBuffer().getDelegate(), inMemoryLimit),
                                Map.of("http.status", String.valueOf(resp.statusCode())));
                    });
        };
    }

    /** Request/reply ingress: envelope id ↔ pending HTTP request. */
    final class HttpConsumer implements Consumer {
        private final URI endpoint; private final String flow;
        private final AtomicReference<MultiEmitter<? super Envelope>> emitter = new AtomicReference<>();
        private final Map<String, RoutingContext> pending = new ConcurrentHashMap<>();
        private volatile DurableIngress durable = e -> Uni.createFrom().voidItem();
        private volatile boolean queued = false;

        HttpConsumer(URI endpoint, String flow) { this.endpoint = endpoint; this.flow = flow; }

        @Override public Multi<Envelope> stream() {
            return Multi.createFrom().<Envelope>emitter(emitter::set, BackPressureStrategy.BUFFER);
        }

        @Override public void durableIngress(DurableIngress d, boolean queued) { this.durable = d; this.queued = queued; }

        @Override public Uni<Void> start() {
            Router router = router(endpoint.getPort());
            router.post(endpoint.getPath()).handler(this::onRequest);
            // irreversible-ACK variant: 202 only after the ingress snapshot is durable
            router.post(endpoint.getPath() + "-async").handler(this::onAsyncRequest);
            return vertx.createHttpServer().requestHandler(router).listen(endpoint.getPort()).replaceWithVoid();
        }

        private Envelope envelope(RoutingContext ctx) {
            Map<String, String> h = new HashMap<>();
            ctx.request().headers().entries().forEach(e -> h.put(e.getKey().toLowerCase(), e.getValue()));
            Buffer buf = ctx.body().buffer();
            return Envelope.ingress(flow, h, Body.of(buf == null ? io.vertx.core.buffer.Buffer.buffer() : buf.getDelegate(), inMemoryLimit));
        }

        /** Request/reply: emit immediately, never await the save — the client owns the retry. */
        private void onRequest(RoutingContext ctx) {
            var em = emitter.get();
            if (em == null) { ctx.response().setStatusCode(503).endAndForget("flow not deployed"); return; }
            Envelope e = envelope(ctx);
            pending.put(e.id(), ctx);
            em.emit(e);
        }

        /** Irreversible ACK: durable save (+ enqueue when queued) with timeout → 202; else 503 retry. */
        private void onAsyncRequest(RoutingContext ctx) {
            var em = emitter.get();
            if (em == null) { ctx.response().setStatusCode(503).endAndForget("flow not deployed"); return; }
            Envelope e = envelope(ctx);
            durable.persist(e).subscribe().with(
                    v -> {
                        if (!queued) em.emit(e);            // queued: a worker picks it up instead
                        ctx.response().setStatusCode(202).putHeader("x-flottila-id", e.id()).endAndForget();
                    },
                    t -> ctx.response().setStatusCode(503).putHeader("Retry-After", "1")
                            .putHeader("x-flottila-id", e.id()).endAndForget(t.getMessage()));
        }

        @Override public void reply(Envelope result) {
            RoutingContext ctx = pending.remove(result.id());
            if (ctx == null) return;                                   // replayed message: nobody waiting
            if (log.isDebugEnabled()) log.debug("ack id={} t={}us", result.id(), System.nanoTime() / 1000);
            ctx.response().setStatusCode(200).putHeader("x-flottila-id", result.id())
                    .putHeader("content-type", result.headers().getOrDefault(Envelope.H_CONTENT_TYPE, "application/xml"))
                    .endAndForget(Buffer.newInstance(io.vertx.core.buffer.Buffer.buffer(result.body().bytes())));
        }

        @Override public void fail(Envelope original, Throwable t) {
            RoutingContext ctx = pending.remove(original.id());
            if (ctx == null) return;
            if (t instanceof BatchingWriter.OverloadedException) {      // not accepted — ask the client to retry
                ctx.response().setStatusCode(503).putHeader("Retry-After", "1")
                        .putHeader("x-flottila-id", original.id()).endAndForget(t.getMessage());
                return;
            }
            ctx.response().setStatusCode(t instanceof IllegalArgumentException ? 400 : 502)
                    .putHeader("x-flottila-id", original.id()).endAndForget(t.getMessage());
        }
    }
}
