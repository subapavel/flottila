package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Witnesses of the oracles' runtime-error branches (ADR-0018 since #52): the golden-corpus check of
 * a case that expects a runtime error, and the differential comparison of Saxon's error QName, fail
 * when they should. Not a corpus or differential class, so the ratchet does not read these tests as
 * case ids (scripts/ratchet.java); that is why they are not in {@code GoldenCorpusTest} and {@code
 * DifferentialOracleTest}.
 */
class RuntimeErrorCheckTest {

  private static final String NS = "urn:flottila:corpus:skeleton";

  /**
   * A synthetic case over the corpus's field link {@code OrderId <- OrderId}, 1..1, at mapping t1:
   * it expects {@code code} at {@code authoringId} on {@code input}.
   */
  private static CorpusCase runtimeErrorCase(
      Path cases, String code, String authoringId, String input) throws Exception {
    Path dir = Files.createDirectories(cases.resolve("synthetic/runtime-error/001"));
    Files.writeString(
        dir.resolve("case.json"),
        """
        {"mapping": "mappings/order-id-copy.mapping.json", "provenance": "decided",
         "ref": "ADR-0018", "expect": "error", "note": "synthetic"}
        """,
        UTF_8);
    Files.writeString(
        dir.resolve("expected-error.json"),
        "{\"code\": \"" + code + "\", \"authoringId\": \"" + authoringId + "\"}",
        UTF_8);
    Files.writeString(dir.resolve("input.xml"), input, UTF_8);
    return CaseFiles.read(cases, dir);
  }

  @Test
  void aRuntimeErrorCaseFailsWhenTheRunWrites(@TempDir Path cases) throws Exception {
    String empty = "<s:Order xmlns:s=\"" + NS + "\"/>";
    String withOrderId = "<s:Order xmlns:s=\"" + NS + "\"><OrderId>1</OrderId></s:Order>";
    // witness: an empty source raises po:MINOCCURS at t1, so the check passes
    CorpusCase raises = runtimeErrorCase(cases.resolve("a"), "po:MINOCCURS", "t1", empty);
    assertThat(GoldenCorpusTest.expectsRuntimeError(raises)).isTrue();
    assertThatCode(() -> GoldenCorpusTest.check(raises)).doesNotThrowAnyException();

    // the run writes OrderId: the case fails
    CorpusCase writes = runtimeErrorCase(cases.resolve("b"), "po:MINOCCURS", "t1", withOrderId);
    assertThatThrownBy(() -> GoldenCorpusTest.check(writes)).isInstanceOf(AssertionError.class);
    // the run raises another code than the expected one: the case fails
    CorpusCase otherCode = runtimeErrorCase(cases.resolve("c"), "po:SORT", "t1", empty);
    assertThatThrownBy(() -> GoldenCorpusTest.check(otherCode)).isInstanceOf(AssertionError.class);
    // the run raises the expected code at another authoring id: the case fails
    CorpusCase otherId = runtimeErrorCase(cases.resolve("d"), "po:MINOCCURS", "a1", empty);
    assertThatThrownBy(() -> GoldenCorpusTest.check(otherId))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("authoring id");
  }

  /** A stylesheet that raises {@code Q{namespace}local} under the prefix po. */
  private static String raising(String namespace, String local) {
    return """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
          <xsl:template match="/">
            <xsl:sequence select="error(QName('%s', 'po:%s'), 'synthetic')"/>
          </xsl:template>
        </xsl:stylesheet>
        """
        .formatted(namespace, local);
  }

  private static final String NO_HELPER =
      """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"/>
      """;

  private static SaxonApiException raised(String stylesheet, Path dir) throws Exception {
    return SaxonRunner.raised(
        stylesheet, NO_HELPER, "<a/>".getBytes(UTF_8), dir, "a synthetic stylesheet");
  }

  @Test
  void aDifferentSaxonCodeFails(@TempDir Path dir) throws Exception {
    SaxonApiException useOneAsMany =
        raised(raising("urn:flottila:mapping:po", "USEONEASMANY"), dir.resolve("a"));
    SaxonApiException otherNamespace = raised(raising("urn:other", "MINOCCURS"), dir.resolve("b"));
    // witness: the same QName agrees
    assertThatCode(() -> SaxonRunner.assertSameError(useOneAsMany, "po:USEONEASMANY", "same"))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> SaxonRunner.assertSameError(useOneAsMany, "po:MINOCCURS", "other"))
        .isInstanceOf(AssertionError.class);
    // the same local name in another namespace is another error
    assertThatThrownBy(
            () -> SaxonRunner.assertSameError(otherNamespace, "po:MINOCCURS", "namespace"))
        .isInstanceOf(AssertionError.class);
    // a stylesheet that writes output raises nothing: the comparison fails before it starts
    assertThatThrownBy(() -> raised(NO_OUTPUT_ERROR, dir.resolve("c")))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * The differential oracle's check of the place a runtime error's message names (ADR-0026): the
   * function box and its id, then a colon, or for po:MINOCCURS the target field mapping and its id.
   */
  @Test
  void aMessageThatDoesNotNameItsPlaceFails() {
    String data = "po:USEONEASMANY";
    String min = "po:MINOCCURS";
    // witness: a message that names its place passes
    assertThatCode(
            () -> SaxonRunner.assertNamesItsPlace("the function box U1: x", data, "U1", "box"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                SaxonRunner.assertNamesItsPlace(
                    "the target field mapping T1 creates 0 occurrences", min, "T1", "mapping"))
        .doesNotThrowAnyException();

    for (String[] wrong :
        List.of(
            new String[] {"useOneAsMany needs one value", data, "U1"},
            new String[] {"the function box U2: x", data, "U1"},
            new String[] {"the function box U10: x", data, "U1"},
            new String[] {"the function box U1 x", data, "U1"},
            new String[] {"the target field mapping U1 creates", data, "U1"},
            new String[] {"the function box T1: x", min, "T1"},
            new String[] {"the target field mapping T10 creates", min, "T1"})) {
      assertThatThrownBy(() -> SaxonRunner.assertNamesItsPlace(wrong[0], wrong[1], wrong[2], "x"))
          .as("%s for %s at %s", (Object[]) wrong)
          .isInstanceOf(AssertionError.class);
    }
  }

  private static final String NO_OUTPUT_ERROR =
      """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        <xsl:template match="/"><out/></xsl:template>
      </xsl:stylesheet>
      """;
}
