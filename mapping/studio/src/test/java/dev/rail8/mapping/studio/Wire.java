package dev.rail8.mapping.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire shapes the tests read: problems, diagnostics and their locations. Bodies are parsed
 * strictly: a duplicate key fails the test.
 */
final class Wire {

  private Wire() {}

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  static final String PROBLEM_TYPE = "urn:rail8:mapping:problem:";

  static JsonNode read(byte[] body) {
    try {
      return JSON.readTree(body);
    } catch (IOException e) {
      throw new AssertionError("the body is no JSON document: " + e.getMessage(), e);
    }
  }

  /** {@code text} as a JSON string literal. */
  static String quoted(String text) {
    try {
      return JSON.writeValueAsString(text);
    } catch (JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * The answer is a problem (RFC 9457) of {@code slug}, and the answer's status is the problem's.
   */
  static JsonNode assertProblem(Calls.Answer answer, int status, String slug) {
    assertThat(answer.status()).as("status of %s", answer).isEqualTo(status);
    JsonNode problem = answer.body();
    assertThat(problem.path("type").textValue())
        .as("type of %s", answer)
        .isEqualTo(PROBLEM_TYPE + slug);
    assertThat(problem.path("status").isInt()).as("status member of %s", answer).isTrue();
    assertThat(problem.path("status").intValue())
        .as("status member of %s", answer)
        .isEqualTo(status);
    assertThat(problem.path("title").textValue()).as("title of %s", answer).isNotBlank();
    return problem;
  }

  /** The member names of {@code object}, in order. */
  static List<String> names(JsonNode object) {
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /**
   * Severity, code and location of each diagnostic, in order; never the message, which only has to
   * be there.
   */
  static List<String> described(JsonNode diagnostics) {
    assertThat(diagnostics.isArray()).as("diagnostics are an array: %s", diagnostics).isTrue();
    List<String> described = new ArrayList<>();
    for (JsonNode d : diagnostics) {
      assertThat(d.path("message").textValue()).as("message of %s", d).isNotBlank();
      described.add(
          d.path("severity").textValue()
              + " "
              + d.path("code").textValue()
              + " at "
              + location(d.path("location")));
    }
    return described;
  }

  private static String location(JsonNode location) {
    String kind = location.path("kind").textValue();
    if (kind == null) {
      return "no kind: " + location;
    }
    return switch (kind) {
      case "pointer" -> "pointer " + location.path("pointer").textValue();
      case "authoring-id" -> "id " + location.path("authoringId").textValue();
      case "file-position" ->
          "file "
              + location.path("file").textValue()
              + " "
              + location.path("line").asText()
              + ":"
              + location.path("column").asText();
      // a location kind the wire does not know is shown as it came, so the assertion fails on it
      default -> "unknown kind " + location;
    };
  }

  /** A {@code TestRequest}: the working copy's JSON text and the input message. */
  static String testRequest(String model, String input) {
    return "{\"model\": " + model + ", \"input\": " + quoted(input) + "}";
  }

  /** A {@code CreateMappingRequest} with the same bundle and root on both sides. */
  static String createRequest(String id, String bundle, String rootElement) {
    String side =
        "{\"bundle\": ["
            + quoted(bundle)
            + "], \"root\": {\"element\": "
            + quoted(rootElement)
            + "}}";
    return "{\"id\": " + quoted(id) + ", \"source\": " + side + ", \"target\": " + side + "}";
  }

  /** A {@code StructureRequest}. */
  static String structureRequest(String base, String bundle, String rootElement) {
    return "{\"base\": "
        + quoted(base)
        + ", \"bundle\": ["
        + quoted(bundle)
        + "], \"root\": {\"element\": "
        + quoted(rootElement)
        + "}}";
  }
}
