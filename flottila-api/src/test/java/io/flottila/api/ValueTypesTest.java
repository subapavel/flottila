package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small immutable value types of the api: every with* copies, every constructor validates. */
class ValueTypesTest {

    @Test
    void headersCopyOnWriteAndNeverExposeTheirMap() {
        Headers h = Headers.empty().with("a", "1");
        Headers h2 = h.withAll(Map.of("b", "2", "a", "9"));
        assertEquals("1", h.get("a"));
        assertNull(h.get("b"));
        assertEquals("9", h2.get("a"));
        assertEquals("2", h2.get("b"));
        assertEquals("d", h.getOrDefault("zzz", "d"));
        assertEquals("1", h.getOrDefault("a", "d"));
        assertThrows(UnsupportedOperationException.class, () -> h.asMap().put("x", "y"));
        assertEquals(Headers.empty(), new Headers(Map.of()));
    }

    @Test
    void keysCopyOnWrite() {
        Keys k = Keys.empty().with("invoiceId", "4711");
        Keys k2 = k.with("orderNo", "1");
        assertEquals("4711", k.get("invoiceId"));
        assertNull(k.get("orderNo"));
        assertEquals("1", k2.get("orderNo"));
        assertThrows(UnsupportedOperationException.class, () -> k2.asMap().clear());
    }

    @Test
    void propsAreTypedByKey() {
        Key<Integer> n = new Key<>("n");
        Key<String> s = new Key<>("s");
        Props p = Props.empty().with(n, 1).with(s, "x");
        assertEquals(1, p.get(n).orElseThrow());
        assertEquals("x", p.get(s).orElseThrow());
        assertTrue(Props.empty().get(n).isEmpty());
        assertEquals(Map.of("n", 1, "s", "x"), p.asMap());
        assertThrows(UnsupportedOperationException.class, () -> p.asMap().remove("n"));
    }

    @Test
    void viewsMemoizePerType() {
        Views v = new Views();
        int[] calls = {0};
        assertEquals("a", v.get(String.class, () -> { calls[0]++; return "a"; }));
        assertEquals("a", v.get(String.class, () -> { calls[0]++; return "b"; }));
        assertEquals(7, v.get(Integer.class, () -> { calls[0]++; return 7; }));
        assertEquals(2, calls[0]);
    }

    @Test
    void hashRejectsABlankAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> new Hash("", 1L));
        assertThrows(IllegalArgumentException.class, () -> new Hash(null, 1L));
        assertEquals("ffffffffffffffff", new Hash("x", -1L).hex());
    }

    @Test
    void blobKeyFactoriesAndValidation() {
        assertEquals(new BlobKey("m", 2, BlobKey.BODY), BlobKey.body("m", 2));
        assertEquals(new BlobKey("m", 2, "a.pdf"), BlobKey.attachment("m", 2, "a.pdf"));
        assertNotEquals(BlobKey.body("m", 2), BlobKey.body("m", 3));
        assertThrows(NullPointerException.class, () -> BlobKey.body(null, 0));
        assertThrows(NullPointerException.class, () -> new BlobKey("m", 0, null));
        assertThrows(IllegalArgumentException.class, () -> BlobKey.body("m", -1));
    }

    @Test
    void partRequiresNameAndBody() {
        InMemoryBlobStore store = new InMemoryBlobStore(10);
        Body body = store.write(BlobKey.body("m", 0), Buffer.buffer("x"), 10).await();
        assertEquals("p", new Part("p", null, body).name());
        assertThrows(NullPointerException.class, () -> new Part(null, "t", body));
        assertThrows(NullPointerException.class, () -> new Part("p", "t", null));
    }

    @Test
    void bodyFactoryValidatesItsArguments() {
        InMemoryBlobStore store = new InMemoryBlobStore(10);
        Hash h = new Hash("x", 1L);
        BlobKey k = BlobKey.body("m", 0);
        assertThrows(NullPointerException.class, () -> Body.of(null, k, h, 1));
        assertThrows(NullPointerException.class, () -> Body.of(store, null, h, 1));
        assertThrows(NullPointerException.class, () -> Body.of(store, k, null, 1));
        assertThrows(IllegalArgumentException.class, () -> Body.of(store, k, h, -1));
        Body a = Body.of(store, k, h, 1);
        assertNotEquals(a, Body.of(store, BlobKey.body("m", 1), h, 1));
        assertNotEquals(a, Body.of(store, k, new Hash("x", 2L), 1));
        assertNotEquals(a, Body.of(store, k, h, 2));
        assertNotEquals(a, "not a body");
        assertEquals("Body[BlobKey[messageId=m, step=0, part=body], x:0000000000000001, 1 bytes]", a.toString());
    }

    @Test
    void bodyTooLargeExceptionCarriesKeySizeAndLimit() {
        BodyTooLargeException e = new BodyTooLargeException(BlobKey.body("m", 0), 11, 10);
        assertEquals(BlobKey.body("m", 0), e.key());
        assertEquals(11L, e.size());
        assertEquals(10L, e.limit());
        assertTrue(e.getMessage().contains("11 bytes, limit is 10"));
    }

    @Test
    void envelopeConstructorValidatesEveryField() {
        InMemoryBlobStore store = new InMemoryBlobStore(10);
        Body body = store.write(BlobKey.body("m", 0), Buffer.buffer("x"), 10).await();
        Envelope ok = new Envelope("m", "f", 0, Headers.empty(), Props.empty(), Keys.empty(), body, Map.of(), new Views());
        assertEquals(0, ok.step());
        assertThrows(NullPointerException.class, () -> ok.with(new Key<String>("none"), null), "props never hold null");
        assertThrows(NullPointerException.class, () -> new Envelope("m", null, 0, Headers.empty(), Props.empty(), Keys.empty(), body, Map.of(), new Views()));
        assertThrows(NullPointerException.class, () -> new Envelope("m", "f", 0, null, Props.empty(), Keys.empty(), body, Map.of(), new Views()));
        assertThrows(NullPointerException.class, () -> new Envelope("m", "f", 0, Headers.empty(), null, Keys.empty(), body, Map.of(), new Views()));
        assertThrows(NullPointerException.class, () -> new Envelope("m", "f", 0, Headers.empty(), Props.empty(), null, body, Map.of(), new Views()));
        assertThrows(NullPointerException.class, () -> new Envelope("m", "f", 0, Headers.empty(), Props.empty(), Keys.empty(), body, Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new Envelope("m", "f", -1, Headers.empty(), Props.empty(), Keys.empty(), body, Map.of(), new Views()));
    }

    @Test
    void endpointConfigExposesTheSchemeAndCopiesOptions() {
        EndpointConfig cfg = new EndpointConfig("partner", URI.create("http://partner:8080/in"), Map.of("timeout", 5));
        assertEquals("http", cfg.scheme());
        assertEquals(5, cfg.options().get("timeout"));
        assertThrows(UnsupportedOperationException.class, () -> cfg.options().clear());
        assertThrows(NullPointerException.class, () -> new EndpointConfig(null, URI.create("x:y"), Map.of()));
        assertThrows(NullPointerException.class, () -> new EndpointConfig("n", null, Map.of()));
    }

    @Test
    void healthFactories() {
        assertEquals(new Health(Health.Status.UP, ""), Health.up());
        assertEquals(new Health(Health.Status.DOWN, "pool exhausted"), Health.down("pool exhausted"));
        assertThrows(NullPointerException.class, () -> new Health(null, ""));
    }

    @Test
    void ackVariantsValidate() {
        InMemoryBlobStore store = new InMemoryBlobStore(10);
        Envelope reply = Envelope.ingress("m", "f", Headers.empty(), store.write(BlobKey.body("m", 0), Buffer.buffer("x"), 10).await());
        Ack a = new Ack.Accepted("m");
        Ack r = new Ack.Reply(reply);
        Ack n = new Ack.Nack("overloaded", true);
        assertEquals("m", ((Ack.Accepted) a).messageId());
        assertSame(reply, ((Ack.Reply) r).reply());
        assertTrue(((Ack.Nack) n).retryable());
        assertEquals("overloaded", ((Ack.Nack) n).reason());
        assertThrows(NullPointerException.class, () -> new Ack.Accepted(null));
        assertThrows(NullPointerException.class, () -> new Ack.Reply(null));
        assertThrows(NullPointerException.class, () -> new Ack.Nack(null, false));
        String kind = switch (a) {
            case Ack.Accepted x -> "accepted";
            case Ack.Reply x -> "reply";
            case Ack.Nack x -> "nack";
        };
        assertEquals("accepted", kind);
    }

    @Test
    void runtimeLooksUpComponentsBySchemeAndValidates() {
        InMemoryBlobStore store = new InMemoryBlobStore(10);
        Component mock = new Component() {
            @Override public String scheme() { return "mock"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Consumer consumer(EndpointConfig cfg, java.util.function.Function<Envelope, io.vertx.core.Future<Ack>> handler) { return null; }
            @Override public Producer producer(EndpointConfig cfg) { return null; }
            @Override public io.vertx.core.Future<Health> health() { return io.vertx.core.Future.succeededFuture(Health.up()); }
        };
        IdGenerator ids = () -> "id-1";
        Runtime rt = new Runtime(Clock.systemUTC(), ids, store, Hasher.sha256(), Map.of("mock", mock));
        assertSame(mock, rt.component("mock"));
        assertEquals("id-1", rt.ids().next());
        assertThrows(IllegalArgumentException.class, () -> rt.component("sftp"));
        assertThrows(UnsupportedOperationException.class, () -> rt.components().clear());
        assertThrows(NullPointerException.class, () -> new Runtime(null, ids, store, Hasher.sha256(), Map.of()));
        assertThrows(NullPointerException.class, () -> new Runtime(Clock.systemUTC(), null, store, Hasher.sha256(), Map.of()));
        assertThrows(NullPointerException.class, () -> new Runtime(Clock.systemUTC(), ids, null, Hasher.sha256(), Map.of()));
        assertThrows(NullPointerException.class, () -> new Runtime(Clock.systemUTC(), ids, store, null, Map.of()));
    }

    @Test
    void stageOfRejectsANullStep() {
        assertThrows(NullPointerException.class, () -> Stage.of(null));
    }
}
