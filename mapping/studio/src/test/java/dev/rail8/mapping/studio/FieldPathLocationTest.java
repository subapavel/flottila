package dev.rail8.mapping.studio;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The {@code {side, fieldPath}} location on the wire: no engine diagnostic carries it yet, so this
 * test writes a diagnostic with it through the studio's {@link Json#write} and reads the JSON back.
 */
class FieldPathLocationTest {

  @Test
  void aDiagnosticAtAFieldPathIsWrittenWithItsKindSideAndFieldPath() {
    String fieldPath = "/Q{urn:rail8:corpus:skeleton}Order/OrderId";
    DiagnosticDto diagnostic =
        new DiagnosticDto(
            "warning", "unreachable-box", "a message", new FieldPathLocation("target", fieldPath));

    JsonNode written = Wire.read(Json.write(diagnostic).getBytes(UTF_8));

    assertThat(written.path("location").properties())
        .extracting(e -> e.getKey() + "=" + e.getValue().asText())
        .containsExactly("kind=field-path", "side=target", "fieldPath=" + fieldPath);
    assertThat(written.path("severity").textValue()).isEqualTo("warning");
  }
}
