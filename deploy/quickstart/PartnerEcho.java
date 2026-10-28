import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The quickstart's partner (docs/QUICKSTART.md): answers every path 200 with the body it was sent, under the request's
 * Content-Type; a body above 1 MiB (the demo's own limit) gets 413. Listens on 127.0.0.1 only: in docker-compose.yml it shares
 * the node's network namespace, so the demo's {@code http://localhost:8090/echo} and {@code /echo-foreign} reach it.
 * Run with the JDK's source launcher: {@code java PartnerEcho.java <port>}.
 */
public class PartnerEcho {

    static final int MAX_BODY = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                byte[] body = in.readNBytes(MAX_BODY + 1);
                System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body.length + " bytes");
                if (body.length > MAX_BODY) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        System.out.println("partner echo listening on 127.0.0.1:" + port);
    }
}
