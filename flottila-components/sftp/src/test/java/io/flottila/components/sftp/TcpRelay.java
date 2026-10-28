package io.flottila.components.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP relay on 127.0.0.1 in front of {@code target}: it forwards both ways and counts the connections whose client closed
 * its side (its stream to the relay ended or was reset). The relay never closes a client's side itself, not even after the
 * server closed its own, so a count means the client closed its socket — which a MINA client session does only after it
 * left its open state ({@code close(true)} flips the state first, then closes the socket). That is the witness for "the
 * client knows its session is closed" which the server's own close count cannot give: the server may count the close of
 * its session first, while the client's close is still pending behind the write of its DISCONNECT.
 */
final class TcpRelay implements AutoCloseable {

    private final ServerSocket listening;
    private final int target;
    private final AtomicInteger clientsClosed = new AtomicInteger();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    TcpRelay(int target) throws IOException {
        this.target = target;
        this.listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("relay-accept").start(this::accept);
    }

    int port() { return listening.getLocalPort(); }

    /** The connections the client side closed so far. */
    int clientsClosed() { return clientsClosed.get(); }

    private void accept() {
        while (true) {
            Socket client;
            Socket server;
            try {
                client = listening.accept();
            } catch (IOException relayClosed) {
                return;   // close() closed the listening socket: no more connections
            }
            sockets.add(client);
            try {
                server = new Socket(InetAddress.getLoopbackAddress(), target);
            } catch (IOException serverDown) {
                close(client);   // as a stopped server would: the client sees its connect fail
                continue;
            }
            sockets.add(server);
            Thread.ofVirtual().name("relay-up").start(() -> clientToServer(client, server));
            Thread.ofVirtual().name("relay-down").start(() -> serverToClient(server, client));
        }
    }

    /** Forwards until the client closes; a server gone meanwhile only stops the forwarding. Counts the client's close. */
    private void clientToServer(Socket client, Socket server) {
        byte[] buf = new byte[16 * 1024];
        boolean serverGone = false;
        try {
            InputStream in = client.getInputStream();
            OutputStream out = server.getOutputStream();
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                if (serverGone) continue;
                try {
                    out.write(buf, 0, n);
                    out.flush();
                } catch (IOException closedByServer) {
                    serverGone = true;   // keep reading: only the client's own close is counted
                }
            }
        } catch (IOException reset) {
            // a reset by the client (it closed with bytes unread) is its close as well; a reset by close() at teardown too
        }
        clientsClosed.incrementAndGet();
        close(server);
        close(client);
    }

    /** Forwards until the server closes or the client is gone; never closes the client's side. */
    private void serverToClient(Socket server, Socket client) {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = server.getInputStream();
            OutputStream out = client.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException gone) {
            // the server or the client went away: clientToServer sees the client's close and counts it
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException alreadyGone) {
            // closing a socket twice or after a reset: nothing left to release
        }
    }

    @Override
    public void close() throws IOException {
        listening.close();
        sockets.forEach(TcpRelay::close);
    }
}
