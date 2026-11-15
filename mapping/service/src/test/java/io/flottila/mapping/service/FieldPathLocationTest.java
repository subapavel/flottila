package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The {@code {side, fieldPath}} location of ADR-0015 on the wire (#105): no engine diagnostic
 * carries it yet, so this test writes a diagnostic with it through the service's own Jackson mapper
 * and reads the JSON back.
 */
@QuarkusTest
class FieldPathLocationTest {

  @Inject ObjectMapper json;

  @Test
  void aDiagnosticAtAFieldPathIsWrittenWithItsKindSideAndFieldPath() throws Exception {
    String fieldPath = "/Q{urn:flottila:corpus:skeleton}Order/OrderId";
    DiagnosticDto diagnostic =
        new DiagnosticDto(
            "warning", "unreachable-box", "a message", new FieldPathLocation("target", fieldPath));

    JsonNode written = json.readTree(json.writeValueAsBytes(diagnostic));

    assertThat(written.path("location").properties())
        .extracting(e -> e.getKey() + "=" + e.getValue().asText())
        .containsExactly("kind=field-path", "side=target", "fieldPath=" + fieldPath);
    assertThat(written.path("severity").textValue()).isEqualTo("warning");
  }
}
