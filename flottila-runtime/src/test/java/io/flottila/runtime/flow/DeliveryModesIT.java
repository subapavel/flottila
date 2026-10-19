package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-08 against Postgres: no 202 without a committed ingress row and queue item (the invariant behind the kill -9 ledger,
 * which is P1-10's measurement), and the worker drains the durable queue.
 */
class DeliveryModesIT {

    static final Path FLOWS = Path.of("../packages/demo/flows");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent caller;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    PgMessageStore store;
    PgWorkQueue queue;
    PgDlq dlq;
    HttpComponent.HttpConsumer consumer;
    FlowDefinition def;
    FlowWorker worker;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        caller = vertx.createHttpClient();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        caller.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate message_keys, messages, payloads, dlq, work").execute().await();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        queue = new PgWorkQueue(pool);
        dlq = new PgDlq(pool);
        rt.withComponent(partner);
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "http-in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        def = FlowDefinition.from(doc);
        SaxonXml xml = new SaxonXml();
        FlowBuilder builder = new FlowBuilder(rt.runtime(), Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints)
                .recording(store, Meter.system()).queue(queue).dlq(dlq);
        Function<Envelope, Future<Ack>> delivery = builder.delivery(def, FLOWS);
        worker = builder.worker(def, FLOWS, "w1");
        HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids());
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"),
                Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", def.id(), "maxBodyBytes", def.maxBodyBytes()));
        consumer = (HttpComponent.HttpConsumer) http.consumer(in, delivery);
        consumer.start().await();
    }

    @AfterEach
    void close() {
        consumer.stop().await();
        store.close();
    }

    /** Posts the order; the 202's message id. */
    String accepted() {
        record Answer(int status, String messageId) {}
        Answer a = caller.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + consumer.port() + "/in"))
                .compose(req -> { req.putHeader("content-type", "application/xml"); return req.send(ORDER); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader(HttpComponent.H_MESSAGE_ID)))).await();
        assertEquals(202, a.status());
        assertNotNull(a.messageId());
        return a.messageId();
    }

    @Test
    void theIngressRowAndTheQueueItemAreCommittedWhenThe202Arrives() {
        String id = accepted();
        // no flush: what the 202 promised is already in the database
        List<Snapshot> timeline = store.timeline(id).await();
        assertEquals(List.of("ingress"), timeline.stream().map(Snapshot::stepName).toList());
        assertTrue(timeline.get(0).bodyKey().isPresent(), "the ingress row keeps the payload's key");
        List<WorkQueue.Item> claimed = queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await();
        assertEquals(1, claimed.size());
        assertEquals(id, claimed.get(0).messageId());
        assertEquals(List.of(), partner.sent(), "nothing ran before the worker");
    }

    @Test
    void theWorkerProcessesTheQueuedMessageAndCompletesIt() {
        String id = accepted();
        assertEquals(1, worker.poll().await());
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(id, got.get(0).id());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString());
        assertEquals(List.of(), queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await(), "the item is completed");
        store.flush().await();
        assertEquals(Optional.of("partner"), store.timeline(id).await().stream().map(Snapshot::stepName).filter("partner"::equals).findFirst());
    }
}
