package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Witnesses of the golden-corpus check of a case that expects a design-time error at a pointer
 * (#65): it fails when the refusal stands elsewhere. Not a corpus class, so the ratchet does not
 * read these tests as case ids (scripts/ratchet.java).
 */
class DesignTimeErrorCheckTest {

  /**
   * A synthetic case over the corpus's create-c, which leaves the mandatory {@code Req} unmapped
   * (#31 c1): it expects {@code unmapped-mandatory-leaf} at the location {@code location}, a JSON
   * member of {@code expected-error.json}.
   */
  private static CorpusCase designTimeCase(Path cases, String location) throws Exception {
    Path dir = Files.createDirectories(cases.resolve("synthetic/design-time-error/001"));
    Files.writeString(
        dir.resolve("case.json"),
        """
        {"mapping": "mappings/create-c.mapping.json", "provenance": "decided",
         "ref": "ADR-0018", "expect": "error", "note": "synthetic"}
        """,
        UTF_8);
    Files.writeString(
        dir.resolve("expected-error.json"),
        "{\"code\": \"unmapped-mandatory-leaf\", " + location + "}",
        UTF_8);
    return CaseFiles.read(cases, dir);
  }

  @Test
  void aDesignTimeCaseAtAPointerFailsAtAnotherLocation(@TempDir Path cases) throws Exception {
    // witness: compiling refuses Req at its absent target field mapping, so the check passes
    CorpusCase req =
        designTimeCase(
            cases.resolve("a"), "\"pointer\": \"/targetFieldMappings/~1Unmapped~1Head~1Req\"");
    assertThat(GoldenCorpusTest.expectsRuntimeError(req)).isFalse();
    assertThatCode(() -> GoldenCorpusTest.check(req)).doesNotThrowAnyException();

    // the pointer of another target field: the case fails
    CorpusCase otherField =
        designTimeCase(
            cases.resolve("b"), "\"pointer\": \"/targetFieldMappings/~1Unmapped~1Head~1A\"");
    assertThatThrownBy(() -> GoldenCorpusTest.check(otherField)).isInstanceOf(AssertionError.class);
    // an authoring id, here the mapping of A, is no pointer: the case fails
    CorpusCase atId = designTimeCase(cases.resolve("c"), "\"authoringId\": \"T1\"");
    assertThatThrownBy(() -> GoldenCorpusTest.check(atId)).isInstanceOf(AssertionError.class);
  }
}
