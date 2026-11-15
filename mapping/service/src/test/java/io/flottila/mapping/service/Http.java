package io.flottila.mapping.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Requests to the service under test through the JDK {@link HttpClient} (no new dependency). Paths
 * are raw: they are sent exactly as written, percent-encodings included.
 */
final class Http {

  private final HttpClient client = HttpClient.newHttpClient();
  private final URI root;

  /**
   * @param root the service's root URL, as {@code @TestHTTPResource} injects it
   */
  Http(URI root) {
    this.root = root;
  }

  Reply get(String rawPath, String... headers) {
    return send("GET", rawPath, null, new byte[0], headers);
  }

  Reply post(String rawPath, String contentType, byte[] body) {
    return send("POST", rawPath, contentType, body);
  }

  Reply put(String rawPath, String contentType, byte[] body, String... headers) {
    return send("PUT", rawPath, contentType, body, headers);
  }

  /**
   * @param headers alternating names and values
   */
  Reply send(String method, String rawPath, String contentType, byte[] body, String... headers) {
    String base = root.toString().endsWith("/") ? root.toString() : root + "/";
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + rawPath))
            .method(
                method,
                body.length == 0 && method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      HttpResponse<byte[]> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Reply(response.statusCode(), response.headers(), response.body());
    } catch (IOException e) {
      throw new AssertionError(method + " " + rawPath + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(method + " " + rawPath + " interrupted", e);
    }
  }

  /** A response: its status, headers and body bytes. */
  record Reply(int status, HttpHeaders headers, byte[] body) {

    Optional<String> header(String name) {
      return headers.firstValue(name);
    }

    String contentType() {
      return header("Content-Type").orElse("");
    }

    String text() {
      return new String(body, UTF_8);
    }

    JsonNode json() {
      return Wire.read(body);
    }

    @Override
    public String toString() {
      return status + " " + contentType() + " " + text();
    }
  }
}
