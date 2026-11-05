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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A loopback TCP relay in front of a Postgres server that reads what each client sends, at the client's side of the wire
 * (CORE-4c2, CORE-3 r1: "counts statements and round trips sent at the client"). It parses the frontend protocol: an untyped
 * startup message (an SSL or GSS request first is answered by the server and followed by the startup), then typed messages
 * — {@code [type][int32 length][payload]}. A <b>statement</b> is a simple Query ({@code 'Q'}, its SQL) or an Execute
 * ({@code 'E'}, the SQL its portal's Bind named by the Parse of that statement name). A <b>round trip</b> is a message the
 * server answers with a ReadyForQuery of its own: every Query and every Sync ({@code 'S'}). Bytes are forwarded unchanged,
 * one message at a time; the server's answers are copied back as they come.
 *
 * <p><b>Turns</b> (CORE-6a): each message records its connection and the number of ReadyForQuery messages ({@code 'Z'}) the
 * relay had forwarded to the client on that connection when it read the message — a message the client could only send once it
 * had a reply carries a higher number than every message it sent before that reply, so the save's messages fall into one group
 * per wait ({@link #turnsSince}). With {@link #countTurns()} a reply carrying a ReadyForQuery is forwarded only once the relay has
 * read everything the client sent before it (the client's side idle, nothing unread), so a pipelined message is never counted in
 * a later turn because the relay read it late.
 */
public final class PgWire implements AutoCloseable {

    /**
     * One frontend message: its type, for a Query, a Parse or an Execute the SQL it runs, the relayed connection it came on (1, 2,
     * … in accept order) and its turn: the ReadyForQuery messages forwarded to the client on that connection before it was read.
     */
    public record Sent(char type, String sql, int conn, long turn) {
        public Sent(char type, String sql) { this(type, sql, 0, 0); }

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
    private final List<Held> armed = new CopyOnWriteArrayList<>();
    private final List<Held> everyHold = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile boolean countTurns;
    private final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
    private final List<Conn> conns = new CopyOnWriteArrayList<>();

    /**
     * A frontend message held before it is forwarded (CORE-4d): the first message after {@link #holdNext} whose {@link Sent}
     * the predicate accepts. {@link #release()} forwards it and the connection goes on; {@link #cut()} never forwards it and
     * closes the client's side at once — the client loses its connection with the message's answer unknown — while the
     * server's session stays open, its transaction in progress, until {@link #abort()} closes it (the server rolls back) or
     * {@link #commitAfterCut()} forwards the held message, then closes it (a COMMIT held so commits).
     */
    public static final class Held {
        private final Predicate<Sent> matches;
        private Predicate<Sent> after;   // null: any connection; else only on a connection that sent a message it accepts first
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch decided = new CountDownLatch(1);
        private final CountDownLatch cutDone = new CountDownLatch(1);
        private final CountDownLatch upstreamGone = new CountDownLatch(1);
        private volatile String how;   // "release", "cut"; after a cut: "abort", "commit"
        private final CountDownLatch afterCut = new CountDownLatch(1);
        private volatile String sql;

        private Held(Predicate<Sent> matches) { this.matches = matches; }

        /** Waits (bounded) until the held message arrived at the relay. */
        public void awaitReached() throws InterruptedException {
            if (!reached.await(20, TimeUnit.SECONDS)) throw new AssertionError("no matching message reached the relay within 20 s");
        }

        boolean isReached() { return reached.getCount() == 0; }

        /** The SQL of the held message. */
        String sql() { return sql; }

        void release() { how = "release"; decided.countDown(); }

        /** Never forwards it; the client's side closed before this returns. */
        public void cut() throws InterruptedException {
            how = "cut";
            decided.countDown();
            if (!cutDone.await(20, TimeUnit.SECONDS)) throw new AssertionError("the relay did not cut the client within 20 s");
        }

        /** After {@link #cut()}: closes the server's session without the message (its transaction rolls back); returns once closed. */
        void abort() throws InterruptedException {
            how = "abort";
            afterCut.countDown();
            if (!upstreamGone.await(20, TimeUnit.SECONDS)) throw new AssertionError("the relay did not close the session within 20 s");
        }

        /** After {@link #cut()}: forwards the held message, then closes the server's session; returns once closed. */
        void commitAfterCut() throws InterruptedException {
            how = "commit";
            afterCut.countDown();
            if (!upstreamGone.await(20, TimeUnit.SECONDS)) throw new AssertionError("the relay did not close the session within 20 s");
        }

        /** Lets a held relay thread go at the end of a case, whatever was decided. */
        public void releaseQuietly() {
            if (how == null) how = "release";
            decided.countDown();
            if (how.equals("cut")) how = "abort";
            afterCut.countDown();
        }
    }

    /** One relayed connection's state shared by its two threads. */
    private static final class Conn {
        final int id;
        final Socket client;
        volatile HeldReply replyHold;
        volatile FatalReply fatalReply;
        /** ReadyForQuery messages forwarded to the client: the turn of the next message read. */
        volatile long delivered;
        /** SSL or GSS requests forwarded whose one-byte answer the server has not sent yet. */
        final java.util.concurrent.atomic.AtomicInteger negotiations = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.locks.Condition changed = lock.newCondition();
        private boolean idle;      // the client's side waits for bytes and had none unread when it began to
        private boolean held;      // the client's side waits on a held message (Held): it reads nothing until decided
        private boolean ended;

        Conn(int id, Socket client) {
            this.id = id;
            this.client = client;
        }

        void idle(boolean now) { set(() -> idle = now); }

        void held(boolean now) { set(() -> held = now); }

        void ended() { set(() -> ended = true); }

        private void set(Runnable change) {
            lock.lock();
            try {
                change.run();
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Bytes the client sent that the relay has not read. */
        int unread() {
            try {
                return client.getInputStream().available();
            } catch (IOException gone) {
                return 0;
            }
        }

        /** Waits (bounded) until the relay has read everything the client sent so far, or the client's side waits on a hold. */
        void awaitDrained() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            lock.lock();
            try {
                while (!(ended || held || (idle && unread() == 0))) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) throw new AssertionError("the relay did not read what connection " + id + " sent within 20 s");
                    changed.awaitNanos(left);   // every change of the client's side signals; unread bytes wake it, so it signals
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private final List<HeldReply> replyHolds = new CopyOnWriteArrayList<>();
    private final List<HeldReply> everyReplyHold = new CopyOnWriteArrayList<>();

    /**
     * The server's answer to a frontend message held before the client gets it (CORE-4d r1): once the first message
     * {@code matches} accepts is forwarded, the next bytes the server sends on that connection — its answer and what follows — wait
     * until {@link #release()}. The statement has run; only its reply is late.
     */
    static final class HeldReply {
        private final Predicate<Sent> matches;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch go = new CountDownLatch(1);

        private HeldReply(Predicate<Sent> matches) { this.matches = matches; }

        /** Waits (bounded) until the reply arrived at the relay and is held. */
        void awaitReached() throws InterruptedException {
            if (!reached.await(20, TimeUnit.SECONDS)) throw new AssertionError("no reply to a matching message reached the relay within 20 s");
        }

        void release() { go.countDown(); }
    }

    /**
     * The server's answer to a frontend message replaced by a FATAL error (CORE-6b review r2): once the first message {@code matches}
     * accepts is forwarded, the next bytes the server sends on that connection — its answer: for a COMMIT, CommandComplete, so the
     * transaction committed — are swallowed; the client gets an ErrorResponse of severity FATAL with SQLSTATE {@code 57P01} instead and
     * the connection is closed on both sides. {@link #awaitDone} returns once that happened.
     */
    static final class FatalReply {
        private final Predicate<Sent> matches;
        private final CountDownLatch done = new CountDownLatch(1);

        private FatalReply(Predicate<Sent> matches) { this.matches = matches; }

        void awaitDone() throws InterruptedException {
            if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("no reply to a matching message was replaced within 20 s");
        }
    }

    private final List<FatalReply> fatalReplies = new CopyOnWriteArrayList<>();

    /** Replaces the reply to the next frontend message {@code matches} accepts by a FATAL 57P01 error ({@link FatalReply}). */
    FatalReply fatalReplyTo(Predicate<Sent> matches) {
        FatalReply f = new FatalReply(matches);
        fatalReplies.add(f);
        return f;
    }

    /** An ErrorResponse: severity FATAL (S and V), SQLSTATE 57P01, an administrator's termination. */
    private static byte[] fatal57P01() {
        java.io.ByteArrayOutputStream fields = new java.io.ByteArrayOutputStream();
        for (String f : List.of("SFATAL", "VFATAL", "C57P01", "Mterminating connection due to administrator command")) {
            fields.writeBytes(f.getBytes(StandardCharsets.UTF_8));
            fields.write(0);
        }
        fields.write(0);
        byte[] body = fields.toByteArray();
        java.nio.ByteBuffer msg = java.nio.ByteBuffer.allocate(1 + 4 + body.length);
        msg.put((byte) 'E').putInt(4 + body.length).put(body);
        return msg.array();
    }

    /** Holds the reply to the next frontend message {@code matches} accepts ({@link HeldReply}). */
    HeldReply holdReplyTo(Predicate<Sent> matches) {
        HeldReply r = new HeldReply(matches);
        everyReplyHold.add(r);
        replyHolds.add(r);
        return r;
    }

    private final List<Held> connectHolds = new CopyOnWriteArrayList<>();

    /**
     * Holds the next connection the relay accepts before its startup message reaches the server (CORE-6b): the client has its TCP
     * connection and waits for the server's answer, so a pool's connect is in progress until {@link Held#release()}; reached once
     * the relay has accepted the connection.
     */
    Held holdNextConnection() {
        Held h = new Held(s -> false);
        everyHold.add(h);
        connectHolds.add(h);
        return h;
    }

    /** Holds the next frontend message {@code matches} accepts, on whichever connection it comes ({@link Held}). */
    Held holdNext(Predicate<Sent> matches) {
        Held h = new Held(matches);
        everyHold.add(h);
        armed.add(h);
        return h;
    }

    /**
     * As {@link #holdNext}, but only on a connection that sent a message {@code after} accepts before (CORE-4d r1: the COMMIT of
     * the intake's transaction — the one that inserted the work item — among a node's other transactions).
     */
    public Held holdNextAfter(Predicate<Sent> after, Predicate<Sent> matches) {
        Held h = new Held(matches);
        h.after = after;
        everyHold.add(h);
        armed.add(h);
        return h;
    }

    /** A simple Query of exactly {@code sql} (case and surrounding blanks ignored): Vert.x sends BEGIN and COMMIT so. */
    public static Predicate<Sent> query(String sql) {
        return s -> s.type() == 'Q' && s.sql() != null && s.sql().trim().equalsIgnoreCase(sql);
    }

    /** A statement (Query or Parse) whose SQL starts with {@code prefix}, case ignored. */
    static Predicate<Sent> statementStarting(String prefix) {
        return s -> (s.type() == 'Q' || s.type() == 'P') && s.sql() != null && s.sql().trim().toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT));
    }

    /** The first armed hold that accepts the message just recorded, disarmed; null when none does. */
    private Held heldFor(Sent last, java.util.Set<Held> primed) {
        for (Held h : armed) {
            if (h.after != null && !primed.contains(h)) {
                if (h.after.test(last)) primed.add(h);
                continue;
            }
            if (h.matches.test(last) && armed.remove(h)) return h;
        }
        return null;
    }

    /** Listens on 127.0.0.1, an ephemeral port, relaying every connection to {@code host:port}. */
    public PgWire(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("pg-wire-accept").start(this::accept);
    }

    /** The relay's port: a client connects here instead of to the server. */
    public int port() { return server.getLocalPort(); }

    /** A point in what was sent so far: {@link #since} lists what came after it. */
    public int mark() { return sent.size(); }

    /** Every message sent after {@code mark}, in the order the relay read them (one connection: send order). */
    public List<Sent> since(int mark) { return List.copyOf(sent.subList(mark, sent.size())); }

    /**
     * From now on a server reply carrying a ReadyForQuery reaches the client only once the relay has read everything the client
     * sent before it (CORE-6a): the turns {@link #turnsSince} counts are the client's waits, never the relay's lag.
     */
    void countTurns() { countTurns = true; }

    /**
     * The messages sent after {@code mark}, on one connection, grouped by turn — one group per wait of the client, in order: each
     * group lists its Queries and Executes by their SQL (trimmed, on one line) and its Parses as {@code "parse " + sql}; a group of
     * other messages only is an empty list. Messages of more than one connection fail: turns are a connection's.
     */
    List<List<String>> turnsSince(int mark) {
        List<Sent> messages = since(mark);
        if (messages.stream().map(Sent::conn).distinct().count() > 1) throw new AssertionError("turns are counted on one connection: " + messages);
        List<List<String>> turns = new ArrayList<>();
        long turn = Long.MIN_VALUE;
        for (Sent s : messages) {
            if (turns.isEmpty() || s.turn() != turn) {
                turns.add(new ArrayList<>());
                turn = s.turn();
            }
            if (s.statement()) turns.getLast().add(oneLine(s.sql()));
            else if (s.type() == 'P') turns.getLast().add("parse " + oneLine(s.sql()));
        }
        return turns.stream().map(List::copyOf).toList();
    }

    /** Waits (bounded) until, on every connection, the relay has read everything the client sent (or the client waits on a hold). */
    void awaitQuiet() throws InterruptedException {
        for (Conn c : conns) c.awaitDrained();
    }

    private static String oneLine(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }

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
                Conn conn = new Conn(connections.incrementAndGet(), client);
                conns.add(conn);
                Thread.ofVirtual().name("pg-wire-up").start(() -> frontend(client, upstream, conn));
                Thread.ofVirtual().name("pg-wire-down").start(() -> copy(upstream, client, conn));
            } catch (IOException e) {
                closeQuietly(client);
            }
        }
    }

    /** Client → server: message by message, each recorded before it is forwarded. */
    private void frontend(Socket client, Socket upstream, Conn conn) {
        Map<String, String> statements = new ConcurrentHashMap<>();   // statement name → SQL (per connection)
        Map<String, String> portals = new ConcurrentHashMap<>();      // portal name → SQL
        java.util.Set<Held> primed = new java.util.HashSet<>();       // holds whose `after` this connection met
        try (DataInputStream in = new DataInputStream(client.getInputStream()); OutputStream out = upstream.getOutputStream()) {
            for (Held connecting : connectHolds) {
                if (!connectHolds.remove(connecting)) continue;   // another connection took it
                connecting.reached.countDown();
                conn.held(true);
                connecting.decided.await();
                conn.held(false);
                break;
            }
            boolean started = false;
            while (!started) {
                int length = in.readInt();
                byte[] body = new byte[length - 4];
                in.readFully(body);
                int code = ((body[0] & 0xff) << 24) | ((body[1] & 0xff) << 16) | ((body[2] & 0xff) << 8) | (body[3] & 0xff);
                started = code != SSL_REQUEST && code != GSS_REQUEST;
                if (!started) conn.negotiations.incrementAndGet();   // before it goes: the server's one-byte answer is no message
                writeInt(out, length);
                out.write(body);
                out.flush();
            }
            while (true) {
                if (in.available() == 0) conn.idle(true);
                int type = in.read();
                conn.idle(false);
                if (type < 0) return;
                int length = in.readInt();
                byte[] body = new byte[length - 4];
                in.readFully(body);
                Sent recorded = record((char) type, body, statements, portals, conn);
                for (FatalReply f : fatalReplies) {
                    if (f.matches.test(recorded) && fatalReplies.remove(f)) {
                        conn.fatalReply = f;   // set before the message goes: its answer is replaced
                        break;
                    }
                }
                if (!replyHolds.isEmpty()) {
                    for (HeldReply r : replyHolds) {
                        if (r.matches.test(recorded) && replyHolds.remove(r)) {
                            conn.replyHold = r;   // set before the message goes: its answer is held
                            break;                // one hold per message: a second waits for the next match
                        }
                    }
                }
                Held held = armed.isEmpty() ? null : heldFor(recorded, primed);
                if (held != null) {
                    held.sql = recorded.sql();
                    held.reached.countDown();
                    conn.held(true);
                    held.decided.await();
                    conn.held(false);
                    if ("cut".equals(held.how)) {
                        closeQuietly(client);
                        held.cutDone.countDown();
                        held.afterCut.await();
                        if ("commit".equals(held.how)) {
                            out.write(type);
                            writeInt(out, length);
                            out.write(body);
                            out.flush();
                        }
                        closeQuietly(upstream);
                        held.upstreamGone.countDown();
                        return;
                    }
                }
                out.write(type);
                writeInt(out, length);
                out.write(body);
                out.flush();
            }
        } catch (EOFException end) {
            // the client closed its connection
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) throw new IllegalStateException("the relay lost a connection", e);
        } finally {
            conn.ended();
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private Sent record(char type, byte[] body, Map<String, String> statements, Map<String, String> portals, Conn conn) {
        Sent last;
        switch (type) {
            case 'Q' -> last = (new Sent(type, cstring(body, 0)));
            case 'P' -> {
                String name = cstring(body, 0);
                String sql = cstring(body, name.getBytes(StandardCharsets.UTF_8).length + 1);
                statements.put(name, sql);
                last = (new Sent(type, sql));
            }
            case 'B' -> {
                String portal = cstring(body, 0);
                String statement = cstring(body, portal.getBytes(StandardCharsets.UTF_8).length + 1);
                portals.put(portal, statements.getOrDefault(statement, "?" + statement));
                last = (new Sent(type, null));
            }
            case 'E' -> last = (new Sent(type, portals.getOrDefault(cstring(body, 0), "?")));
            default -> last = (new Sent(type, null));
        }
        last = new Sent(last.type(), last.sql(), conn.id, conn.delivered);
        sent.add(last);
        return last;
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

    private void copy(Socket from, Socket to, Conn conn) {
        byte[] buffer = new byte[16 * 1024];
        byte[] header = new byte[5];   // a backend message's type and length, as far as read
        int headerFill = 0;
        long payloadLeft = 0;          // bytes of the current message's payload still to come
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            for (int n; (n = in.read(buffer)) >= 0; ) {
                FatalReply fatal = conn.fatalReply;
                if (fatal != null) {   // the answer swallowed, a FATAL error in its place, the connection gone
                    conn.fatalReply = null;
                    out.write(fatal57P01());
                    out.flush();
                    closeQuietly(to);
                    closeQuietly(from);
                    fatal.done.countDown();
                    return;
                }
                HeldReply r = conn.replyHold;
                if (r != null) {
                    conn.replyHold = null;
                    r.reached.countDown();
                    r.go.await();
                }
                int ready = 0;   // ReadyForQuery messages that start in this chunk
                for (int i = 0; i < n; ) {
                    if (payloadLeft > 0) {
                        int skip = (int) Math.min(payloadLeft, n - i);
                        i += skip;
                        payloadLeft -= skip;
                    } else if (headerFill == 0 && conn.negotiations.get() > 0) {
                        conn.negotiations.decrementAndGet();   // the one-byte answer to an SSL or GSS request
                        i++;
                    } else {
                        header[headerFill++] = buffer[i++];
                        if (headerFill == header.length) {
                            if (header[0] == 'Z') ready++;
                            payloadLeft = (((header[1] & 0xffL) << 24) | ((header[2] & 0xff) << 16) | ((header[3] & 0xff) << 8) | (header[4] & 0xff)) - 4;
                            headerFill = 0;
                        }
                    }
                }
                if (ready > 0) {
                    if (countTurns) conn.awaitDrained();
                    conn.delivered += ready;   // before the client can answer it: what it sends next is of a later turn
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // either side closed
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
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
        for (Held h : everyHold) h.releaseQuietly();
        for (HeldReply r : everyReplyHold) r.release();
        server.close();
        for (Socket s : sockets) closeQuietly(s);
    }
}
