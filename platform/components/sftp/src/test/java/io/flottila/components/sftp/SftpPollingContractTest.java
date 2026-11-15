package io.flottila.components.sftp;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PollingConsumerContract;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** The real component against an embedded MINA SSHD server on the loopback interface passes the same polling suite as the mock. */
class SftpPollingContractTest extends PollingConsumerContract {

    @TempDir
    Path root;

    private final FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream into the store
    private EmbeddedSftp server;

    @BeforeEach
    void up() throws IOException {
        server = EmbeddedSftp.start(root);
        server.mkdir("/inbox");
    }

    @AfterEach
    void down() throws IOException {
        server.close();
    }

    @Override protected Component component() { return new SftpComponent(rt.blobs(), rt.ids(), rt.timer(), rt.clock()); }

    @Override protected EndpointConfig endpoint() { return server.endpoint("sftp-in", "/inbox", Map.of()); }

    @Override protected void drop(String name, ReadStream<Buffer> bytes) { server.drop("/inbox", name, bytesOf(bytes)); }

    @Override protected Set<String> remaining() { return server.files("/inbox"); }

    @Override protected void tick() { advanceToNextRound(rt.clock(), rt.timer()); }
}
