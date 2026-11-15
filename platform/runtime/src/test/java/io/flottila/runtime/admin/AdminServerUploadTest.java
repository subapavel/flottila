package io.flottila.runtime.admin;

import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static io.flottila.runtime.admin.AdminServerDeployTest.AUTH;
import static io.flottila.runtime.admin.AdminServerDeployTest.TOKEN;
import static io.flottila.runtime.admin.AdminServerDeployTest.assertRawError;
import static io.flottila.runtime.admin.AdminServerDeployTest.chunk;
import static io.flottila.runtime.admin.AdminServerDeployTest.entries;
import static io.flottila.runtime.admin.AdminServerDeployTest.filler;
import static io.flottila.runtime.admin.AdminServerDeployTest.read;
import static io.flottila.runtime.admin.AdminServerDeployTest.shop;
import static io.flottila.runtime.admin.AdminServerDeployTest.until;
import static io.flottila.runtime.admin.AdminServerDeployTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-09d1b, implementer: the upload's edges the deploy cases leave open — a Content-Length the handler could not parse never
 * reaches it, a client that hangs up mid-body leaves no temp file and no deploy, and an answer given after the whole body was
 * read keeps the connection open for the next request (only an answer before the end closes it).
 */
class AdminServerUploadTest {

    static final long MAX = 16 * 1024;

    static Vertx vertx;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    Deployments deployments;
    AdminServer server;
    int port;

    @BeforeAll
    static void up() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void down() {
        vertx.close().await();
    }

    @BeforeEach
    void wire() throws Exception {
        Map<String, String> env = Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_DATA_DIR", tmp.resolve("data").toString(),
                "FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(MAX));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        InMemoryMessageStore messages = new InMemoryMessageStore();
        InMemoryDlq dlq = new InMemoryDlq();
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq, new InMemoryWorkQueue(rt.clock()), () -> 0L);
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments));
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        doors.releaseAll();
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    Socket socket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        s.setSoTimeout(10_000);
        return s;
    }

    String head(String version, String name, String framing) {
        return "PUT /admin/packages/" + name + " HTTP/" + version + "\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: " + AUTH + "\r\n" + framing + "\r\n";
    }

    /** The route parses Content-Length without a guard: what it could not parse is refused by the HTTP decoder before routing. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Content-Length: abc", "Content-Length: -1", "Content-Length: 99999999999999999999", "Content-Length: 5, 6"})
    void aContentLengthThatIsNotOneNumberIs400BeforeTheRoute(String header) throws Exception {
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("1.1", "shop", header + "\r\n"));
            s.getOutputStream().write(filler(5));
            s.getOutputStream().flush();

            assertEquals(400, read(s.getInputStream()).status(), header);
        }
        assertEquals(List.of(), entries(deployments.incomingDir()), "the route never ran: no temp file, no incoming directory content");
        assertEquals(List.of(), doors.events(), "nothing was deployed");
    }

    /**
     * The client hangs up once its first 512 bytes are in the temp file, or (racing the file's open) right after sending them:
     * either way the upload fails, its temp file is deleted, nothing is deployed, and the node takes the next upload. Only the
     * {@code true} case carries "the temp file is deleted" and "nothing is deployed": its file is seen holding the bytes before the
     * hang-up, so its emptiness afterwards is the server's work. In the racing {@code false} case the server may not yet have
     * opened the file, or read the request, when those checks run — they can hold vacuously there; that case pins only that the
     * race leaves the node taking the next upload.
     */
    @ParameterizedTest(name = "wait for the bytes in the file: {0}")
    @ValueSource(booleans = {true, false})
    void aClientThatHangsUpMidBodyLeavesNoTempFileAndTheNodeStillDeploys(boolean waitForTheBytes) throws Exception {
        Path incoming = deployments.incomingDir();
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            write(out, head("1.1", "shop", "Transfer-Encoding: chunked\r\n"));
            chunk(out, filler(512));
            if (waitForTheBytes) until(() -> entries(incoming).stream().anyMatch(f -> f.toFile().length() == 512), "the body is piped to a temp file as it arrives");
        }   // closed: the request fails mid-body

        until(() -> entries(incoming).isEmpty(), "the temp file of the broken upload is deleted: " + entries(incoming));
        assertEquals(List.of(), doors.events(), "nothing was deployed");
        try (Socket s = socket()) {
            byte[] zip = shop().zip();
            write(s.getOutputStream(), head("1.1", "shop", "Content-Length: " + zip.length + "\r\n"));
            s.getOutputStream().write(zip);
            s.getOutputStream().flush();
            assertEquals(200, read(s.getInputStream()).status(), "the next upload deploys");
        }
    }

    /**
     * Review round 1 (M1): a request that fails after its whole package arrived — chunked without the last chunk, or one byte short
     * of its declared Content-Length — is a failed upload, never a deploy of what was received. The whole zip is seen in the temp
     * file before the hang-up, and the file's deletion afterwards is the witness that the server is done with the request (a
     * deploy would run before it) before "nothing deployed" is asserted; the next upload of the same package is then 200, not 409.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"chunked without the last chunk", "Content-Length one above the bytes sent"})
    void aRequestThatFailsMidBodyIsNeverDeployed(String framing) throws Exception {
        Path incoming = deployments.incomingDir();
        byte[] zip = shop().zip();
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            if (framing.startsWith("chunked")) {
                write(out, head("1.1", "shop", "Transfer-Encoding: chunked\r\n"));
                chunk(out, zip);
            } else {
                write(out, head("1.1", "shop", "Content-Length: " + (zip.length + 1) + "\r\n"));
                out.write(zip);
                out.flush();
            }
            until(() -> entries(incoming).stream().anyMatch(f -> f.toFile().length() == zip.length), "the whole zip is in the temp file before the hang-up");
        }   // closed: the request fails before its end

        until(() -> entries(incoming).isEmpty(), "the server is done with the broken upload: its temp file is deleted: " + entries(incoming));
        assertEquals(List.of(), doors.events(), "what was received was never deployed");
        assertEquals(List.of(), deployments.flows(), "nothing is listed");
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("1.1", "shop", "Content-Length: " + zip.length + "\r\n"));
            s.getOutputStream().write(zip);
            s.getOutputStream().flush();
            assertEquals(200, read(s.getInputStream()).status(), "the same package deploys next: it was never deployed");
        }
    }

    /** A 413 given before the body's end — on the head, or at byte max+1 — closes the connection once written: the rest is never read. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"declared", "counted"})
    void aRefusalBeforeTheBodysEndClosesTheConnection(String how) throws Exception {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            if (how.equals("declared")) {
                write(out, head("1.1", "shop", "Content-Length: " + (MAX + 1) + "\r\n"));
            } else {
                write(out, head("1.1", "shop", "Transfer-Encoding: chunked\r\n"));
                chunk(out, filler((int) MAX + 1));
            }
            InputStream in = s.getInputStream();
            assertRawError(413, read(in), how);

            assertEquals(-1, in.read(), "the server closed the connection after the 413 (a SocketTimeoutException: it kept it open)");
        }
    }

    /** {@code zip} grown to exactly {@code size} bytes by its end-of-central-directory comment: still the same valid archive. */
    static byte[] padded(byte[] zip, int size) {
        int eocd = zip.length - 22;
        assertEquals(0x06054b50, ByteBuffer.wrap(zip, eocd, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), "fixture: a zip without a comment");
        int comment = size - zip.length;
        byte[] grown = Arrays.copyOf(zip, size);
        ByteBuffer.wrap(grown, zip.length - 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) comment);
        System.arraycopy(filler(comment), 0, grown, zip.length, comment);
        return grown;
    }

    /** A valid package of exactly the limit deploys, declared or counted: the last byte of the limit is written like every other. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"declared", "counted"})
    void aPackageOfExactlyTheLimitDeploys(String how) throws Exception {
        byte[] zip = padded(shop().zip(), (int) MAX);
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            if (how.equals("declared")) {
                write(out, head("1.1", "shop", "Content-Length: " + MAX + "\r\n"));
                out.write(zip);
            } else {
                write(out, head("1.1", "shop", "Transfer-Encoding: chunked\r\n"));
                chunk(out, Arrays.copyOfRange(zip, 0, 512));
                chunk(out, Arrays.copyOfRange(zip, 512, zip.length));
                write(out, "0\r\n\r\n");
            }
            out.flush();

            assertEquals(200, read(s.getInputStream()).status(), how + ": " + MAX + " bytes");
        }
        assertEquals(List.of("shop/orders"), deployments.flows().stream().map(f -> f.id()).toList());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Content-Length: 0\r\n", "Transfer-Encoding: chunked\r\n"})
    void anEmptyBodyIsNoZip(String framing) throws Exception {
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("1.1", "shop", framing) + (framing.startsWith("Transfer") ? "0\r\n\r\n" : ""));

            assertRawError(400, read(s.getInputStream()), framing.trim());
        }
        assertEquals(List.of(), entries(deployments.incomingDir()), "no temp file is left");
    }

    @Test
    void anAnswerAfterTheWholeBodyKeepsTheConnectionForTheNextRequest() throws Exception {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            write(out, head("1.1", "shop", "Content-Length: 5\r\n"));
            out.write(filler(5));
            out.flush();
            assertRawError(400, read(in), "five bytes are no zip");

            byte[] zip = shop().zip();
            write(out, head("1.1", "shop", "Content-Length: " + zip.length + "\r\n"));
            out.write(zip);
            out.flush();

            assertEquals(200, read(in).status(), "the same connection carries the next upload: the 400 came after the body's end");
        }
    }
}
