package io.flottila.runtime.journal;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback TCP relay in front of a Postgres server that reads what each client sends, at the client's side of the wire
 * (CORE-4c2, CORE-3 r1: "counts statements and round trips sent at the client"). It parses the frontend protocol: an untyped
 * startup message (an SSL or GSS request first is answered by the server and followed by the startup), then typed messages
 * — {@code [type][int32 length][payload]}. A <b>statement</b> is a simple Query ({@code 'Q'}, its SQL) or an Execute
 * ({@code 'E'}, the SQL its portal's Bind named by the Parse of that statement name). A <b>round trip</b> is a message the
 * server answers with a ReadyForQuery of its own: every Query and every Sync ({@code 'S'}). Bytes are forwarded unchanged,
 * one message at a time; the server's answers are copied back as they come.
 */
final class PgWire implements AutoCloseable {

    /** One frontend message: its type and, for a Query or an Execute, the SQL it runs. */
    record Sent(char type, String sql) {
        boolean statement() { return type == 'Q' || type == 'E'; }
        boolean roundTrip() { return type == 'Q' || type == 'S'; }
    }

    private static final int SSL_REQUEST = 80877103;
    private static final int GSS_REQUEST = 80877104;

    private final String host;
    private final int port;
    private final ServerSocket server;
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /** Listens on 127.0.0.1, an ephemeral port, relaying every connection to {@code host:port}. */
    PgWire(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("pg-wire-accept").start(this::accept);
    }

    /** The relay's port: a client connects here instead of to the server. */
    int port() { return server.getLocalPort(); }

    /** A point in what was sent so far: {@link #since} lists what came after it. */
    int mark() { return sent.size(); }

    /** Every message sent after {@code mark}, in the order the relay read them (one connection: send order). */
    List<Sent> since(int mark) { return List.copyOf(sent.subList(mark, sent.size())); }

    /** The SQL of every statement sent after {@code mark}, trimmed and on one line. */
    List<String> statementsSince(int mark) {
        List<String> out = new ArrayList<>();
        for (Sent s : since(mark)) if (s.statement()) out.add(s.sql().strip().replaceAll("\\s+", " "));
        return out;
    }

    /** The round trips sent after {@code mark}. */
    long roundTripsSince(int mark) {
        return since(mark).stream().filter(Sent::roundTrip).count();
    }

    private void accept() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;   // closed
            }
            try {
                Socket upstream = new Socket(host, port);
                sockets.add(client);
                sockets.add(upstream);
                Thread.ofVirtual().name("pg-wire-up").start(() -> frontend(client, upstream));
                Thread.ofVirtual().name("pg-wire-down").start(() -> copy(upstream, client));
            } catch (IOException e) {
                closeQuietly(client);
            }
        }
    }

    /** Client → server: message by message, each recorded before it is forwarded. */
    private void frontend(Socket client, Socket upstream) {
        Map<String, String> statements = new ConcurrentHashMap<>();   // statement name → SQL (per connection)
        Map<String, String> portals = new ConcurrentHashMap<>();      // portal name → SQL
        try (DataInputStream in = new DataInputStream(client.getInputStream()); OutputStream out = upstream.getOutputStream()) {
            boolean started = false;
            while (!started) {
                int length = in.readInt();
                byte[] body = new byte[length - 4];
                in.readFully(body);
                int code = ((body[0] & 0xff) << 24) | ((body[1] & 0xff) << 16) | ((body[2] & 0xff) << 8) | (body[3] & 0xff);
                writeInt(out, length);
                out.write(body);
                out.flush();
                started = code != SSL_REQUEST && code != GSS_REQUEST;
            }
            while (true) {
                int type = in.read();
                if (type < 0) return;
                int length = in.readInt();
                byte[] body = new byte[length - 4];
                in.readFully(body);
                record((char) type, body, statements, portals);
                out.write(type);
                writeInt(out, length);
                out.write(body);
                out.flush();
            }
        } catch (EOFException end) {
            // the client closed its connection
        } catch (IOException e) {
            if (!closed) throw new IllegalStateException("the relay lost a connection", e);
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void record(char type, byte[] body, Map<String, String> statements, Map<String, String> portals) {
        switch (type) {
            case 'Q' -> sent.add(new Sent(type, cstring(body, 0)));
            case 'P' -> {
                String name = cstring(body, 0);
                String sql = cstring(body, name.getBytes(StandardCharsets.UTF_8).length + 1);
                statements.put(name, sql);
                sent.add(new Sent(type, sql));
            }
            case 'B' -> {
                String portal = cstring(body, 0);
                String statement = cstring(body, portal.getBytes(StandardCharsets.UTF_8).length + 1);
                portals.put(portal, statements.getOrDefault(statement, "?" + statement));
                sent.add(new Sent(type, null));
            }
            case 'E' -> sent.add(new Sent(type, portals.getOrDefault(cstring(body, 0), "?")));
            default -> sent.add(new Sent(type, null));
        }
    }

    private static String cstring(byte[] b, int from) {
        int end = from;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, from, end - from, StandardCharsets.UTF_8);
    }

    private static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private void copy(Socket from, Socket to) {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // either side closed
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing a relay socket: the other side already went
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        server.close();
        for (Socket s : sockets) closeQuietly(s);
    }
}
