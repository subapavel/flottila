package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Swagger UI resolves the served document's external {@code $ref}s against the document's URL
 * {@code /q/openapi} (#45). The contract keeps {@code ./mapping-model.schema.json} verbatim
 * (ADR-0015), so the service serves that file, the one in {@code contract/} (ADR-0011), at the URL
 * the reference resolves to.
 */
@QuarkusTest
class SwaggerUiRefsTest {

  @TestHTTPResource URI root;

  @Test
  void everyExternalRefOfTheServedDocumentResolves() throws IOException {
    Http http = new Http(root);
    URI document = URI.create("/q/openapi");
    Http.Reply served = http.get("q/openapi?format=json");
    assertThat(served.status()).as("%s", served).isEqualTo(200);
    Set<String> external = new TreeSet<>();
    collectExternalRefs(served.json(), external);
    assertThat(external)
        .as("the model schema and its root selector")
        .contains("./mapping-model.schema.json", "./mapping-model.schema.json#/$defs/rootSelector");

    for (String ref : external) {
      String[] parts = ref.split("#", 2);
      String path = document.resolve(parts[0]).getPath();
      Http.Reply reply = http.get(path.substring(1));

      assertThat(reply.status()).as("%s -> %s: %s", ref, path, reply).isEqualTo(200);
      assertThat(reply.contentType()).as("%s -> %s", ref, path).startsWith("application/json");
      JsonNode schema = reply.json();
      assertThat(schema.path("$schema").textValue())
          .as("%s -> %s", ref, path)
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      if (parts.length == 2) {
        assertThat(schema.at(parts[1]).isMissingNode()).as("%s resolves", ref).isFalse();
      }
      // the contract's file, byte for byte: one source (ADR-0011)
      Fixtures.assertBytes(
          reply.body(),
          Files.readAllBytes(Fixtures.repositoryRoot().resolve("contract").resolve(parts[0])));
    }
  }

  private static void collectExternalRefs(JsonNode node, Set<String> refs) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              e -> {
                if (e.getKey().equals("$ref") && e.getValue().isTextual()) {
                  if (!e.getValue().textValue().startsWith("#")) {
                    refs.add(e.getValue().textValue());
                  }
                } else {
                  collectExternalRefs(e.getValue(), refs);
                }
              });
    } else if (node.isArray()) {
      node.forEach(child -> collectExternalRefs(child, refs));
    }
  }
}
