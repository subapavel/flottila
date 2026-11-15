package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.repositoryRoot;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.testRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/mappings/{id}/test} with a JSON input (ADR-0008 "JSON as a source at run time",
 * #98): the request's {@code input} is the JSON text, unchanged, and the mapping's root selector
 * {@code {"json": "$"}} makes the run read it as JSON. The mapping and its structures are the
 * corpus's {@code json-tags}, copied into a directory of their own.
 */
@QuarkusTest
class JsonTestRunTest {

  @TestHTTPResource URI root;

  /** Copies {@code corpus/<path>} to {@code json-run/<path>} in the workspace; returns its text. */
  private static String copy(String path) throws IOException {
    byte[] content = Files.readAllBytes(repositoryRoot().resolve("corpus").resolve(path));
    TempWorkspace.write("json-run/" + path, content);
    return new String(content, UTF_8);
  }

  private Http.Reply test(String input) throws IOException {
    copy("structures/json-schema/run-input.schema.json");
    copy("structures/observations/tgt-list.xsd");
    String model = copy("mappings/json-tags.mapping.json");
    return new Http(root)
        .post(
            "api/mappings/json-run/mappings/json-tags/test",
            "application/json",
            bytes(testRequest(model, input)));
  }

  @Test
  void aJsonInputRunsThroughAJsonRoot() throws IOException {
    Http.Reply reply = test("{\"tags\": [\"a\", 1.50, true]}");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    // the number as the input spells it
    assertThat(result.path("output").textValue())
        .contains("<All>a</All><All>1.50</All><All>true</All>");
    assertThat(described(result.path("diagnostics"))).isEmpty();
  }

  @Test
  void aDuplicateKeyIs400MalformedInput() throws IOException {
    assertProblem(test("{\"tags\": [\"a\"], \"tags\": [\"b\"]}"), 400, "malformed-input");
  }
}
