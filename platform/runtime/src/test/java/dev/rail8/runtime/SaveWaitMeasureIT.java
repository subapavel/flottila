package dev.rail8.runtime;

import dev.rail8.components.http.HttpComponent;
import dev.rail8.runtime.node.Node;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Not committed: measures the save and the wait for a worker of invoice-async at three body sizes. */
class SaveWaitMeasureIT extends InvoiceAsyncIT {

    static byte[] padded(String id, int size) {
        String head = "<Invoice><Id>" + id + "</Id><!--";
        String tail = "--><Currency>CZK</Currency><Lines><Line><Sku>S</Sku><Qty>1</Qty><Price>1</Price></Line></Lines></Invoice>";
        int pad = Math.max(0, size - head.length() - tail.length());
        return (head + "x".repeat(pad) + tail).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void measure() throws Exception {
        List<String> saw = new CopyOnWriteArrayList<>();
        var partner = partner(saw, Future.succeededFuture());
        StringBuilder out = new StringBuilder();
        try (HttpClient client = NodeIT.http()) {
            Node node = startNode("node-measure");
            deploy(client, node, asyncFiles("http://127.0.0.1:" + partner.actualPort()));
            int n = 0;
            for (int size : new int[] {200, 100 * 1024, 1000 * 1000}) {
                List<long[]> rows = new ArrayList<>();
                for (int i = 0; i < 13; i++) {
                    String inv = "M" + (n++);
                    byte[] body = padded(inv, size);
                    HttpResponse<String> r = NodeIT.send(client, "POST", node.httpPort(), "/http/in", body, "application/xml");
                    Instant got = Instant.now();
                    if (r.statusCode() != 202) throw new AssertionError(r.statusCode() + " " + r.body());
                    String id = r.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow();
                    int admin = node.adminPort();
                    JsonArray[] steps = new JsonArray[1];
                    NodeIT.until(() -> {
                        try {
                            steps[0] = new JsonObject(NodeIT.get(admin, "/admin/messages/" + id).body()).getJsonArray("steps");
                            return steps[0] != null && steps[0].size() == 6;
                        } catch (Exception e) { throw new AssertionError(e); }
                    }, "six steps");
                    Instant ingress = null, first = null;
                    for (Object o : steps[0]) {
                        JsonObject s = (JsonObject) o;
                        if (s.getInteger("step") == 0) ingress = Instant.parse(s.getString("startedAt"));
                        if (s.getInteger("step") == 1) first = Instant.parse(s.getString("startedAt"));
                    }
                    if (i >= 3) rows.add(new long[] {Duration.between(ingress, got).toNanos(), Duration.between(got, first).toNanos(), body.length});
                }
                out.append("size=").append(rows.get(0)[2]).append(" B\n");
                for (long[] x : rows) out.append(String.format("  save %.2f ms  wait %.2f ms%n", x[0] / 1e6, x[1] / 1e6));
                long[] s = rows.stream().mapToLong(x -> x[0]).sorted().toArray();
                long[] w = rows.stream().mapToLong(x -> x[1]).sorted().toArray();
                out.append(String.format("  median save %.2f ms (min %.2f, max %.2f), median wait %.2f ms (min %.2f, max %.2f)%n",
                        s[s.length / 2] / 1e6, s[0] / 1e6, s[s.length - 1] / 1e6, w[w.length / 2] / 1e6, w[0] / 1e6, w[w.length - 1] / 1e6));
            }
        }
        System.out.println("MEASURE\n" + out);
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "measure.txt"), out);
    }
}
