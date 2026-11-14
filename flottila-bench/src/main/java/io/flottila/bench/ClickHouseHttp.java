package io.flottila.bench;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ClickHouse over its HTTP interface with the JDK client (CORE-2: no new dependency). The statement is the request body, or
 * the {@code query} parameter when the body carries insert data; {@code param_<name>} binds {@code {name:Type}} placeholders.
 * A status other than 200 is a {@link Failed} with ClickHouse's message.
 *
 * @since 0.1
 */
final class ClickHouseHttp {

    /** A statement ClickHouse answered with an error status; the message is the server's text. */
    static final class Failed extends IOException {
        Failed(int status, String message) {
            super("HTTP " + status + ": " + message.strip());
        }
    }

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String user;
    private final String password;

    ClickHouseHttp(String base, String user, String password) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.user = user;
        this.password = password;
    }

    /** One statement, its whole answer as text. */
    String query(String sql) throws IOException, InterruptedException {
        return new String(send(Map.of(), HttpRequest.BodyPublishers.ofString(sql)), StandardCharsets.UTF_8);
    }

    /** One statement with bound parameters and query id, its whole answer as bytes. */
    byte[] query(String sql, String queryId, Map<String, String> params) throws IOException, InterruptedException {
        Map<String, String> url = new java.util.LinkedHashMap<>();
        url.put("query_id", queryId);
        params.forEach((k, v) -> url.put("param_" + k, v));
        return send(url, HttpRequest.BodyPublishers.ofString(sql));
    }

    /** {@code INSERT INTO <table> FORMAT RowBinary} with the first {@code length} bytes of {@code rows} as the body. */
    void insertRowBinary(String table, String queryId, byte[] rows, int length) throws IOException, InterruptedException {
        send(Map.of("query", "INSERT INTO " + table + " FORMAT RowBinary", "query_id", queryId),
                HttpRequest.BodyPublishers.ofByteArray(rows, 0, length));
    }

    private byte[] send(Map<String, String> url, HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        String q = url.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + (q.isEmpty() ? "" : "?" + q)))
                .header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password)
                .POST(body).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new Failed(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        return response.body();
    }
}
