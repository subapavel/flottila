package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code compile}: the working copy in the request answers 200 with its diagnostics, stages 1 to 3
 * and the {@code unreachable-box} warning alike, never a 4xx for them. Only a transport error is a
 * problem.
 */
class CompileTest {

  private static final Calls CALLS = Calls.studio();

  /** A compile whose bundle references are read against the corpus mapping's directory. */
  private static Calls.Answer compile(byte[] body) {
    return CALLS.post("compile", body, Workspace.mappings());
  }

  /** The 200 reply, which is a compile result and never a problem. */
  private static Calls.Answer compiled(String model) {
    Calls.Answer reply = compile(bytes(model));
    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    return reply;
  }

  private static List<String> diagnostics(String model) {
    JsonNode result = compiled(model).body();
    assertThat(result.properties()).extracting(Map.Entry::getKey).containsExactly("diagnostics");
    return described(result.path("diagnostics"));
  }

  @Test
  void aWorkingCopyThatCompilesHasNoDiagnosticsAndTheStoredFileIsUntouched() {
    assertThat(diagnostics(workingCopy("\"a1\""))).isEmpty();
  }

  @Test
  void aBoxTheResultDoesNotReachIsTheUnreachableBoxWarningAtItsAuthoringId() {
    String model =
        workingCopy("\"a1\"")
            .replace(
                "\"boxes\": {",
                "\"boxes\": {\"f9\": {\"kind\": \"function\", \"fn\": \"trim\", \"inputs\": [\"a1\"]}, ");
    assertThat(model)
        .as("the working copy has boxes to add to")
        .isNotEqualTo(workingCopy("\"a1\""));

    assertThat(diagnostics(model)).containsExactly("warning unreachable-box at id f9");
  }

  @Test
  void stage1ErrorsAre200() {
    String model = "{\"extra\": 1, " + workingCopy("\"a1\"").substring(1);

    assertThat(diagnostics(model)).containsExactly("error unknown-member at pointer /extra");
  }

  @Test
  void stage2ErrorsAre200() {
    assertThat(diagnostics(workingCopy("\"zz\"")))
        .containsExactly("error unknown-box-reference at id t1");
  }

  @Test
  void stage3ErrorsAre200WithTheWarningsBesideThem() {
    assertThat(diagnostics(workingCopy("null")))
        .containsExactly(
            "warning unreachable-box at id a1", "error unconnected-target-field at id t1");
  }

  @Test
  void aStaleFingerprintIs200WithFingerprintMismatch() {
    String stale = workingCopy("\"a1\"").replace(FINGERPRINT, "sha256:" + "0".repeat(64));
    assertThat(stale).as("both sides store the fingerprint").isNotEqualTo(workingCopy("\"a1\""));

    assertThat(diagnostics(stale))
        .containsExactly(
            "error fingerprint-mismatch at pointer /source/fingerprint",
            "error fingerprint-mismatch at pointer /target/fingerprint");
  }

  @Test
  void aBundleThatDoesNotLoadIs200WithItsFilePositions() {
    String missing = workingCopy("\"a1\"").replace("order.xsd", "missing.xsd");

    assertThat(diagnostics(missing))
        .containsExactly(
            "error schema-unreadable at file ../structures/skeleton/missing.xsd 1:1",
            "error schema-unreadable at file ../structures/skeleton/missing.xsd 1:1");
  }

  @Test
  void aBodyThatIsNoJsonDocumentIs400MalformedJson() {
    assertProblem(compile(bytes("{\"formatVersion\": 1,}")), 400, "malformed-json");
  }
}
