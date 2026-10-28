package io.flottila.components.mock;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PollingConsumerContract;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** The mock standing in for the {@code sftp} scheme passes the same polling suite as the real component (D-076). */
class MockPollingContractTest extends PollingConsumerContract {

    private final FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream into the store
    private final MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());

    @Override protected Component component() { return sftp; }

    @Override protected EndpointConfig endpoint() {
        return new EndpointConfig("sftp-in", URI.create("sftp://partner.example/inbox"),
                Map.of("user", "flottila", "password", "not-used-by-the-mock", "hostKey", "SHA256:bm90LXVzZWQtYnktdGhlLW1vY2s", "maxBodyBytes", 1_000_000,
                        "flow", "contract"));
    }

    @Override protected void drop(String name, ReadStream<Buffer> bytes) { sftp.drop(endpoint().name(), name, bytesOf(bytes)); }

    @Override protected Set<String> remaining() { return Set.copyOf(sftp.remaining(endpoint().name())); }

    @Override protected void tick() { advanceToNextRound(rt.clock(), rt.timer()); }
}
