package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.xslt.export.XsltExporter;
import java.nio.file.Path;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The helper module's {@code po:data-error} (ADR-0026, F-19) raises the QName it is given at the
 * function box it is given: a code of the po namespace, as the context functions raise, and a W3C
 * error such as {@code err:FORG0001}, which a malformed number or date raises in both back ends
 * (ADR-0025, spec #70). Saxon-HE runs a stylesheet of one template that imports the packed helper
 * module and calls it. Not a corpus or differential class, so the ratchet does not read its tests
 * as case ids (scripts/ratchet.java).
 */
class HelperDataErrorTest {

  /** A stylesheet that raises {@code code}, an XPath expression, at box b1 through the helper. */
  private static String raising(String code) {
    return """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:po="urn:flottila:mapping:po"
            xmlns:err="http://www.w3.org/2005/xqt-errors" exclude-result-prefixes="#all">
          <xsl:import href="po-runtime.xsl"/>
          <xsl:template match="/">
            <xsl:sequence select="po:data-error(%s, 'b1', 'x is no number')"/>
          </xsl:template>
        </xsl:stylesheet>
        """
        .formatted(code);
  }

  private static SaxonApiException raised(String code, Path dir) throws Exception {
    return SaxonRunner.raised(
        raising(code), XsltExporter.helperModule(), "<a/>".getBytes(UTF_8), dir, code);
  }

  @Test
  void theHelperRaisesAW3cErrorAtItsBox(@TempDir Path dir) throws Exception {
    SaxonApiException forg0001 = raised("xs:QName('err:FORG0001')", dir.resolve("err"));

    assertThat(forg0001.getErrorCode()).as("%s", forg0001.getMessage()).isNotNull();
    assertThat(forg0001.getErrorCode().getNamespace())
        .isEqualTo("http://www.w3.org/2005/xqt-errors");
    assertThat(forg0001.getErrorCode().getLocalName()).isEqualTo("FORG0001");
    SaxonRunner.assertSameError(forg0001, "err:FORG0001", "err:FORG0001");
    assertThat(forg0001.getMessage()).isEqualTo("the function box b1: x is no number");
  }

  @Test
  void theHelperRaisesACodeOfThePoNamespaceAtItsBox(@TempDir Path dir) throws Exception {
    SaxonApiException useOneAsMany =
        raised("QName('urn:flottila:mapping:po', 'po:USEONEASMANY')", dir.resolve("po"));

    SaxonRunner.assertSameError(useOneAsMany, "po:USEONEASMANY", "po:USEONEASMANY");
    assertThat(useOneAsMany.getMessage()).isEqualTo("the function box b1: x is no number");
  }
}
