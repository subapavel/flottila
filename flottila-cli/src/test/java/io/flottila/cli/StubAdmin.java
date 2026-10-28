package io.flottila.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A node's admin API as {@code flottila deploy} sees it (P2-03a test fixture): the JDK's HTTP server on 127.0.0.1 and an ephemeral
 * port that records every request whole — method, path, raw query, headers (names lower-cased), body — before it answers the
 * status and body the case set (200 {@code {}} by default). A request is recorded before its answer leaves, so once the CLI
 * returned, what it sent is here; {@link #requests()} empty after a run is the witness that nothing was sent.
 */
final class StubAdmin implements AutoCloseable {

    /** One request as it arrived. */
    record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        /** The values of the header {@code name} (any case), empty when it was not sent. */
        List<String> header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{}";

    StubAdmin() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** From now on every request is answered {@code status} with {@code body}. */
    StubAdmin answer(int status, String body) {
        this.status = status;
        this.answer = body;
        return this;
    }

    /** {@code http://127.0.0.1:<port>}, no trailing slash. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The one request sent; fails the case naming what arrived when there is not exactly one. */
    Request only() {
        List<Request> all = requests();
        if (all.size() != 1) throw new AssertionError("exactly one request expected, got " + all.size() + ": " + all.stream().map(r -> r.method() + " " + r.path() + "?" + r.query()).toList());
        return all.getFirst();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(), headers, body));
            int s = status;
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (s == 204 || s == 304 || bytes.length == 0) {
                exchange.sendResponseHeaders(s, -1);
            } else {
                exchange.sendResponseHeaders(s, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
