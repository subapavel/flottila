package io.flottila.runtime.journal.peer;

/** Allowed (F-35): the peer link's package opens its server and its client with Vert.x Net*. */
public final class PeerLinkFixture {
    public static io.vertx.core.Future<io.vertx.core.net.NetSocket> link(io.vertx.core.Vertx vertx, io.vertx.core.net.NetServer[] out) {
        out[0] = vertx.createNetServer(new io.vertx.core.net.NetServerOptions());
        return vertx.createNetClient().connect(8083, "127.0.0.1");
    }
}
