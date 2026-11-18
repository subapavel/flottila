package dev.rail8.runtime;

import dev.rail8.examples.webhook.WebhookPlugin;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.node.TestJournalKey;
import dev.rail8.testkit.Ports;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node started as {@code Main} starts one, with the jar of the sample webhook plugin (examples/webhook-plugin) in
 * {@code RAIL8_PLUGINS}: the sample's package deploys over the admin API, and an order posted to its webhook endpoint passes
 * through the plugin's consumer and producer to a partner, whose reply is the answer. The message is recorded under its flow.
 */
class WebhookPluginIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final Path PACKAGE = Path.of("../../examples/webhook-plugin/package");
    static Vertx vertx;   // the partner's

    @TempDir Path dir;
    Main.Started started;
    HttpServer partner;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @AfterEach
    void stopTheNode() throws Exception {
        if (started != null && started.node().isPresent()) {
            Runnable stopper = Main.stopper(started.node().get(), started.vertx().orElseThrow(), started.plugins().orElseThrow());
            OnVirtualThread.call(() -> {
                stopper.run();
                return null;
            });
        }
        if (partner != null) partner.close().await();
    }

    /** The environment of a node on the class's database, with {@code plugins} as its plugin directory. */
    Map<String, String> env(int adminPort, Path plugins) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "ci");
        env.put("RAIL8_NODE_ID", "webhook-plugin-it");
        env.put("RAIL8_ADMIN_PORT", String.valueOf(adminPort));
        env.put("RAIL8_PEER_PORT", "0");
        env.put("RAIL8_DATA_DIR", dir.resolve("data").toString());
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", pg.getDatabaseName());
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        env.put("RAIL8_PLUGINS", plugins.toString());
        TestJournalKey.with(env);
        return env;
    }

    /** A plugin directory holding the sample's jar, as its own build packaged it. */
    Path plugins() throws Exception {
        Path jar = Path.of(WebhookPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(jar.getFileName().toString().endsWith(".jar"), "the sample is resolved as its packaged jar: " + jar);
        Path plugins = Files.createDirectories(dir.resolve("plugins"));
        Files.copy(jar, plugins.resolve("webhook.jar"));
        return plugins;
    }

    /** The sample's package, its webhook endpoint on {@code inPort} and its partner at {@code partnerPort}. */
    static Map<String, byte[]> packageFiles(int inPort, int partnerPort) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(PACKAGE)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(PACKAGE.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        files.put("endpoints/orders-in.yaml", NodeIT.replaced(files.get("endpoints/orders-in.yaml"), Map.of("port: 18090", "port: " + inPort)));
        files.put("endpoints/partner.yaml", NodeIT.replaced(files.get("endpoints/partner.yaml"),
                Map.of("http://127.0.0.1:18091", "http://127.0.0.1:" + partnerPort)));
        return files;
    }

    @Test
    void anOrderPassesThroughThePluginsConsumerAndProducer() throws Exception {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        List<String> messageIds = new CopyOnWriteArrayList<>();
        partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            partnerSaw.add(req.path() + " " + body);
            messageIds.add(String.valueOf(req.getHeader("x-message-id")));
            req.response().putHeader("content-type", "application/xml").end("<Ack><Id>7</Id></Ack>");
        })).listen(0, "127.0.0.1").await();
        int admin = Ports.free();
        int in = Ports.free();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        started = OnVirtualThread.call(() -> Main.start(env(admin, plugins()), errors));
        assertEquals(0, started.code(), "the node starts with the plugin: " + err.toString(StandardCharsets.UTF_8));

        try (HttpClient client = NodeIT.http()) {
            HttpResponse<String> deployed = NodeIT.send(client, "PUT", admin, "/admin/packages/webhook-relay",
                    NodeIT.zip(packageFiles(in, partner.actualPort())), "application/zip");
            assertEquals(200, deployed.statusCode(), "the package over the plugin's scheme deploys: " + deployed.body());
            assertEquals(new JsonArray().add("webhook-relay/relay"), new JsonObject(deployed.body()).getJsonArray("flows"), deployed.body());

            byte[] order = Files.readAllBytes(PACKAGE.resolve("fixtures/order.xml"));
            HttpResponse<String> answer = NodeIT.send(client, "POST", in, "/orders", order, "application/xml");

            assertEquals(200, answer.statusCode(), "the plugin's consumer answers with the flow's reply: " + answer.body());
            assertEquals("<Ack><Id>7</Id></Ack>", answer.body(), "the reply is the partner's, brought back by the plugin's producer");
            assertEquals(List.of("/orders <Order><Id>7</Id></Order>\n"), partnerSaw, "the plugin's producer sent the order to the partner once");
            String id = messageIds.get(0);
            NodeIT.until(() -> {
                try {
                    HttpResponse<String> message = NodeIT.get(admin, "/admin/messages/" + id);
                    return message.statusCode() == 200 && "webhook-relay/relay".equals(new JsonObject(message.body()).getString("flow"));
                } catch (IOException | InterruptedException e) {
                    throw new AssertionError("the admin API did not answer", e);
                }
            }, "the message the partner saw, by the id it was sent with, is recorded under the sample's flow");
        }
    }
}
