package io.flottila.schemas;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-06 (GATE checks 3-5, 8): the demo package holds three flows — {@code invoice-sync}, {@code invoice-async} (the same steps at
 * least once, behind its own http door) and {@code sftp-invoice} (a file in the SFTP inbox, the same transformation at least once) —
 * and every secret of its endpoints is a reference, never a literal.
 */
class DemoFlowsTest {

    static final Path DEMO = Path.of("..", "packages", "demo");
    /** An option whose whole value is a secret reference (D-081). */
    static final Pattern REFERENCE = Pattern.compile("secret://[a-z0-9][a-z0-9._-]*");
    /** The option names that carry a credential or the identity of a server: a literal there is a secret in the repo. */
    static final Set<String> SECRET_OPTIONS = Set.of("password", "privateKey", "hostKey", "token", "secret", "secretKey", "accessKey", "apiKey");

    static Package demo() throws IOException {
        return Package.load(DEMO);
    }

    static JsonObject flow(String id) throws IOException {
        JsonObject flow = demo().flows().get(id);
        assertTrue(flow != null, "the demo has a flow '" + id + "', it has " + demo().flowIds());
        return flow;
    }

    static JsonObject endpoint(String id) throws IOException {
        JsonObject endpoint = demo().endpoints().get(id);
        assertTrue(endpoint != null, "the demo has an endpoint '" + id + "', it has " + demo().endpointIds());
        return endpoint;
    }

    static List<String> stepTypes(JsonObject flow) {
        List<String> types = new ArrayList<>();
        flow.getJsonArray("steps").forEach(s -> types.add(((JsonObject) s).getString("type")));
        return types;
    }

    /** The option names under {@code config} (and under every environment's overlay) whose value is a literal credential. */
    static List<String> literalSecrets(JsonObject endpoint) {
        List<String> literals = new ArrayList<>();
        collect("config", endpoint.getJsonObject("config", new JsonObject()), literals);
        JsonObject environments = endpoint.getJsonObject("environments", new JsonObject());
        for (String env : environments.fieldNames()) {
            collect("environments." + env + ".config", environments.getJsonObject(env).getJsonObject("config", new JsonObject()), literals);
        }
        return literals;
    }

    private static void collect(String where, JsonObject options, List<String> literals) {
        for (String name : options.fieldNames()) {
            Object value = options.getValue(name);
            if (value instanceof JsonObject nested) {
                collect(where + "." + name, nested, literals);
            } else if (SECRET_OPTIONS.contains(name) && !(value instanceof String s && REFERENCE.matcher(s).matches())) {
                literals.add(where + "." + name);
            }
        }
    }

    // ---- the three flows ------------------------------------------------------------------------------------------------

    @Test
    void theThreeFlowsLoadAndTheWholePackageIsValid() throws IOException {
        Package pkg = demo();
        assertEquals(Set.of("invoice-sync", "invoice-async", "sftp-invoice"), Set.copyOf(pkg.flowIds()));
        assertEquals(Set.of("http-in", "http-in-async", "sftp-inbox", "partner-domestic", "partner-foreign"), pkg.endpointIds());
        assertEquals(List.of(), pkg.problems(), "every document is valid against its schema and the package is consistent across files");
        for (String id : List.of("invoice-sync", "invoice-async", "sftp-invoice")) {
            assertTrue(Schemas.forKind("flow").validate(flow(id)).valid(), id + " is valid against the flow schema");
        }
    }

    @Test
    void invoiceAsyncIsInvoiceSyncAtLeastOnceOverItsOwnHttpDoor() throws IOException {
        JsonObject sync = flow("invoice-sync");
        JsonObject async = flow("invoice-async");
        assertEquals("http-in-async", async.getJsonObject("from").getString("endpoint"));
        assertEquals("http-in", sync.getJsonObject("from").getString("endpoint"), "the sync flow keeps its door");
        JsonObject delivery = async.getJsonObject("delivery");
        assertEquals("at-least-once", delivery.getString("mode"));
        assertEquals("postgres", delivery.getString("queue"));
        assertEquals("5s", delivery.getString("saveTimeout"));
        assertEquals(sync.getJsonArray("steps"), async.getJsonArray("steps"), "invoice-async runs invoice-sync's steps");
        assertEquals(sync.getJsonObject("retry"), async.getJsonObject("retry"), "and retries as the sync flow does");
        assertEquals(sync.getJsonObject("limits"), async.getJsonObject("limits"));
        assertEquals("invoiceId", async.getString("partitionBy"), "invoices of one id are delivered in order");
    }

    @Test
    void theAsyncHttpDoorListensOnItsOwnPortAtItsPath() throws IOException {
        JsonObject door = endpoint("http-in-async");
        assertEquals("http", door.getString("scheme"));
        assertEquals("in", door.getString("direction"));
        JsonObject config = door.getJsonObject("config");
        assertEquals("/invoice/async", config.getString("path"));
        assertEquals(8082, config.getInteger("port"));
        assertEquals("0.0.0.0", config.getString("host"), "reachable from outside the node's container");
        // one HttpServer per endpoint, a port shared between servers is shared round-robin, not by path: no two doors on one port
        Map<Integer, String> ports = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : demo().endpoints().entrySet()) {
            JsonObject d = e.getValue();
            if (!"http".equals(d.getString("scheme")) || !"in".equals(d.getString("direction"))) continue;
            String clash = ports.put(d.getJsonObject("config").getInteger("port"), e.getKey());
            assertEquals(null, clash, "http endpoints '" + clash + "' and '" + e.getKey() + "' listen on one port");
        }
        assertEquals(2, ports.size(), "the demo's two doors: " + ports);
    }

    @Test
    void sftpInvoiceReadsTheSftpInboxAtLeastOnceAndSendsTheTransformedInvoiceToThePartner() throws IOException {
        JsonObject flow = flow("sftp-invoice");
        assertEquals("sftp-inbox", flow.getJsonObject("from").getString("endpoint"));
        assertEquals("at-least-once", flow.getJsonObject("delivery").getString("mode"));
        assertEquals("postgres", flow.getJsonObject("delivery").getString("queue"));
        assertEquals(List.of("xsd", "extractKey", "xslt", "to"), stepTypes(flow), "validate, key, map, send");
        JsonArray steps = flow.getJsonArray("steps");
        assertEquals("invoiceId", steps.getJsonObject(1).getString("key"));
        assertEquals("/Invoice/Id", steps.getJsonObject(1).getString("xpath"));
        assertEquals(flow("invoice-sync").getJsonArray("steps").getJsonObject(3).getString("stylesheet"),
                steps.getJsonObject(2).getString("stylesheet"), "the same stylesheet as the http flows");
        assertEquals("partner-domestic", steps.getJsonObject(3).getString("endpoint"));
        assertTrue(steps.getJsonObject(3).getBoolean("retryable", false), "the send is retried");
        assertTrue(flow.getJsonObject("limits").getLong("maxBodyBytes") > 0, "a finite body limit");
    }

    @Test
    void theSftpInboxPollsTheDirectoryTheComposeServerExposesAndKeepsItsSecretsAsReferences() throws IOException {
        JsonObject inbox = endpoint("sftp-inbox");
        assertEquals("sftp", inbox.getString("scheme"));
        assertEquals("in", inbox.getString("direction"));
        JsonObject config = inbox.getJsonObject("config");
        assertEquals("sftp", config.getString("host"), "the compose service's name");
        assertEquals(22, config.getInteger("port"));
        // atmoz/sftp: user demo is chrooted to /home/demo, the command 'demo:<password>:1001:100:inbox' creates /home/demo/inbox,
        // so the session sees /inbox (a relative 'inbox' resolves against the chroot root)
        assertTrue(Set.of("inbox", "/inbox").contains(config.getString("dir")), "dir is the inbox the server creates: " + config.getString("dir"));
        assertEquals("demo", config.getString("user"));
        assertEquals("secret://sftp-password", config.getString("password"));
        assertEquals("secret://sftp-host-key", config.getString("hostKey"));
        assertEquals("delete", config.getString("afterSuccess"));
        // the deploy sets maxBodyBytes on every in endpoint, from the flow's limits: `flottila validate` refuses the option here
        assertFalse(config.containsKey("maxBodyBytes"), "an in endpoint does not set maxBodyBytes (the flow's limits do)");
    }

    // ---- no secret in the repo ----------------------------------------------------------------------------------------

    @Test
    void theCheckForLiteralSecretsSeesALiteralAndAcceptsAReference() {
        // the witness that the scan below is not vacuous
        JsonObject literal = Documents.yaml("""
                config: { user: demo, password: hunter2, hostKey: "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
                environments:
                  prod: { config: { password: secret://sftp-password, nested: { token: abc } } }
                """);
        assertEquals(List.of("config.password", "config.hostKey", "environments.prod.config.nested.token"), literalSecrets(literal));
        assertEquals(List.of(), literalSecrets(Documents.yaml("config: { password: secret://sftp-password, hostKey: secret://sftp-host-key }")));
    }

    @Test
    void noEndpointOfTheDemoHoldsALiteralPasswordOrHostKey() throws IOException {
        Package pkg = demo();
        for (Map.Entry<String, JsonObject> e : pkg.endpoints().entrySet()) {
            assertEquals(List.of(), literalSecrets(e.getValue()), "endpoint '" + e.getKey() + "' holds a literal where only secret://<name> may stand");
        }
        JsonObject config = endpoint("sftp-inbox").getJsonObject("config");
        assertTrue(config.containsKey("password") && config.containsKey("hostKey"), "the scan has something to scan: the sftp endpoint names both");
        try (Stream<Path> files = Files.walk(DEMO)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(text.contains("PRIVATE KEY"), DEMO.relativize(file) + " holds a private key");
                assertFalse(Pattern.compile("SHA256:[A-Za-z0-9+/]{43}").matcher(text).find(), DEMO.relativize(file) + " holds a host key fingerprint");
            }
        }
    }

    // ---- the tests of the two new flows ---------------------------------------------------------------------------------

    private static JsonObject testDoc(String name) throws IOException {
        Path file = DEMO.resolve("flows").resolve(name);
        assertTrue(Files.isRegularFile(file), file + " must exist");
        JsonObject doc = Documents.read(file);
        assertTrue(Schemas.forKind("test").validate(doc).valid(), name + " is valid against the test schema");
        return doc;
    }

    private static JsonObject caseWith(JsonObject doc, java.util.function.Predicate<JsonObject> p, String what) {
        for (Object c : doc.getJsonArray("cases")) {
            if (p.test((JsonObject) c)) return (JsonObject) c;
        }
        throw new AssertionError("no case " + what + " in " + doc.encode());
    }

    private static boolean partnerDown(JsonObject c) {
        return c.getJsonObject("mocks", new JsonObject()).getJsonObject("partner-domestic", new JsonObject()).getInteger("fail", 0) == 503;
    }

    @Test
    void invoiceAsyncTestsHaveAHappyPathAnd503ThatEndsInTheDlqAfterTheRetries() throws IOException {
        JsonObject doc = testDoc("invoice-async.test.yaml");
        assertEquals("invoice-async", doc.getString("flow"));
        assertEquals(2, doc.getJsonArray("cases").size(), "a happy path and a partner down");
        JsonObject happy = caseWith(doc, c -> !partnerDown(c), "with the partner up");
        assertEquals("fixtures/sample-invoice.xml", happy.getString("send"));
        JsonObject expect = happy.getJsonObject("expect");
        assertEquals(202, expect.getInteger("status"), "accepted after the durable save");
        assertEquals("4711", expect.getJsonObject("keys").getString("invoiceId"));
        assertEquals(1, expect.getJsonObject("sent").getJsonObject("partner-domestic").getInteger("count"), "sent once");
        assertEquals(0, expect.getInteger("dlq", 0));
        JsonObject down = caseWith(doc, DemoFlowsTest::partnerDown, "with the partner failing 503");
        assertEquals(1, down.getJsonObject("expect").getInteger("dlq"), "parked once");
        assertEquals(flow("invoice-async").getJsonObject("retry").getInteger("attempts"), down.getJsonObject("expect").getInteger("retries"),
                "retries per policy");
        assertEquals(202, down.getJsonObject("expect").getInteger("status"), "the sender had its 202 before the partner failed");
    }

    @Test
    void sftpInvoiceTestsFeedAFixtureFileAndExpectOneSendAnd503ToTheDlq() throws IOException {
        JsonObject doc = testDoc("sftp-invoice.test.yaml");
        assertEquals("sftp-invoice", doc.getString("flow"));
        assertEquals(2, doc.getJsonArray("cases").size(), "a file and a partner down");
        // fed by the sftp mock: files under fixtures/, no send, no status (an sftp door has no answer)
        String files = doc.getJsonObject("mocks").getJsonObject("sftp-inbox").getString("files");
        assertTrue(files != null && files.startsWith("fixtures/"), "an SFTP fixture file under fixtures/: " + files);
        java.nio.file.PathMatcher glob = DEMO.getFileSystem().getPathMatcher("glob:" + files);
        try (Stream<Path> all = Files.walk(DEMO.resolve("fixtures"))) {
            assertTrue(all.filter(Files::isRegularFile).anyMatch(f -> glob.matches(DEMO.relativize(f))), "the fixture glob " + files + " matches a file");
        }
        JsonObject happy = caseWith(doc, c -> !partnerDown(c), "with the partner up");
        assertFalse(happy.containsKey("send"), "the file is fed by the mock, not sent");
        JsonObject sent = happy.getJsonObject("expect").getJsonObject("sent").getJsonObject("partner-domestic");
        assertEquals(1, sent.getInteger("count"), "sent once");
        assertEquals("/PartnerInvoice/@number", sent.getString("xpath"), "the transformed body, not the file");
        assertEquals("4711", sent.getString("equals"));
        JsonObject down = caseWith(doc, DemoFlowsTest::partnerDown, "with the partner failing 503");
        assertEquals(1, down.getJsonObject("expect").getInteger("dlq"), "parked once");
    }
}
