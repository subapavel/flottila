package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.repositoryRoot;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * {@code test} with a JSON input: the request's {@code input} is the JSON text, unchanged, and the
 * mapping's root selector {@code {"json": "$"}} makes the run read it as JSON. The mapping and its
 * structures are the corpus's {@code json-tags}.
 */
class JsonTestRunTest {

  private static final Calls CALLS = Calls.studio();

  /** The text of {@code corpus/<path>}. */
  private static String corpus(String path) throws IOException {
    return new String(Files.readAllBytes(repositoryRoot().resolve("corpus").resolve(path)), UTF_8);
  }

  private static Calls.Answer test(String input) throws IOException {
    String model = corpus("mappings/json-tags.mapping.json");
    return CALLS.post("test", testRequest(model, input), Workspace.mappings());
  }

  @Test
  void aJsonInputRunsThroughAJsonRoot() throws IOException {
    Calls.Answer reply = test("{\"tags\": [\"a\", 1.50, true]}");

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
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
