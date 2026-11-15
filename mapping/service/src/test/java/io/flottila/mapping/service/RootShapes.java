package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Named;

/**
 * The wsdl and json root shapes as {@code contract/mapping-model.schema.json} defines them ({@code
 * $defs/wsdlRoot}, {@code wsdlOperation} and {@code jsonRoot}). The schema is read and checked
 * first, so a change to it fails here before a root test passes on a stale shape. Since #96 a wsdl
 * shape is allowed or forbidden: the schema allows a fault exactly when the direction is fault. The
 * json root is read by #97, whose tests cover it.
 */
final class RootShapes {

  private RootShapes() {}

  /** Input and output without a fault, and fault with one. */
  static List<Named<String>> wsdlAllowed() {
    return wsdl(true);
  }

  /** Input and output with a fault, and fault without one. */
  static List<Named<String>> wsdlForbidden() {
    return wsdl(false);
  }

  /** Every direction of the schema's enum, with or without a fault, as the schema allows or not. */
  private static List<Named<String>> wsdl(boolean allowed) {
    List<String> directions = checkedSchema();
    List<Named<String>> shapes = new ArrayList<>();
    for (String direction : directions) {
      String members =
          "\"interface\": \"OrderService\", \"operation\": \"submit\", \"direction\": \""
              + direction
              + "\"";
      boolean fault = direction.equals("fault");
      if (fault != allowed) {
        shapes.add(Named.of("wsdl " + direction, "{\"wsdl\": {" + members + "}}"));
      } else {
        shapes.add(
            Named.of(
                "wsdl " + direction + " with a fault",
                "{\"wsdl\": {" + members + ", \"fault\": \"Rejected\"}}"));
      }
    }
    return shapes;
  }

  /** Checks the schema's root shapes and returns the directions of its enum. */
  private static List<String> checkedSchema() {
    JsonNode defs = schema().path("$defs");
    JsonNode operation = defs.path("wsdlOperation");
    assertThat(names(operation.path("properties")))
        .as("wsdlOperation members")
        .containsExactlyInAnyOrder("interface", "operation", "direction", "fault");
    assertThat(strings(operation.path("required")))
        .as("wsdlOperation required")
        .containsExactlyInAnyOrder("interface", "operation", "direction");
    assertThat(operation.path("additionalProperties").isBoolean()).isTrue();
    assertThat(operation.path("additionalProperties").booleanValue()).isFalse();
    assertThat(operation.path("properties").path("fault").path("type").textValue())
        .isEqualTo("string");
    List<String> directions = strings(operation.path("properties").path("direction").path("enum"));
    assertThat(directions)
        .as("wsdlOperation directions")
        .containsExactly("input", "output", "fault");
    assertThat(names(defs.path("wsdlRoot").path("properties"))).containsExactly("wsdl");
    assertThat(defs.path("jsonRoot").path("properties").path("json").path("const").textValue())
        .isEqualTo("$");
    // #96: fault is present exactly when the direction is fault
    assertThat(operation.path("if").path("properties").path("direction").path("const").textValue())
        .isEqualTo("fault");
    assertThat(strings(operation.path("then").path("required"))).containsExactly("fault");
    assertThat(strings(operation.path("else").path("not").path("required")))
        .containsExactly("fault");
    return directions;
  }

  private static JsonNode schema() {
    try {
      return Wire.read(
          Files.readAllBytes(
              Fixtures.repositoryRoot().resolve("contract/mapping-model.schema.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> names(JsonNode object) {
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static List<String> strings(JsonNode array) {
    List<String> strings = new ArrayList<>();
    for (JsonNode s : array) {
      strings.add(s.textValue());
    }
    return strings;
  }
}
