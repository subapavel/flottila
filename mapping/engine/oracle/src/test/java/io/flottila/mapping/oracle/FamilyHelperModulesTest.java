package io.flottila.mapping.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XmlProcessingError;
import net.sf.saxon.s9api.XsltCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The helper module's sources (ADR-0025 "Families", F-15): {@code core.xsl} and one module per
 * function family in {@code engine/xslt-export/src/main/xsl/po-runtime}, which the build joins into
 * {@code po-runtime.xsl}. A family module may use what core declares and nothing of another family,
 * so each one compiles on Saxon-HE with core alone: a stylesheet that includes the two.
 */
class FamilyHelperModulesTest {

  private static final String CORE = "core.xsl";

  private static final String STYLESHEET =
      "<xsl:stylesheet version=\"3.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""
          + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:po=\"urn:flottila:mapping:po\">\n";

  /**
   * The static errors of a stylesheet in {@code dir} that includes {@code modules}, compiled with a
   * fresh {@code Processor(false)}; empty when it compiles.
   */
  static List<String> compileErrors(Path dir, List<Path> modules) throws Exception {
    StringBuilder wrapper = new StringBuilder(STYLESHEET);
    for (Path module : modules) {
      String href = module.toAbsolutePath().toUri().toString().replace("&", "&amp;");
      wrapper.append("  <xsl:include href=\"").append(href).append("\"/>\n");
    }
    wrapper.append("</xsl:stylesheet>\n");
    Files.createDirectories(dir);
    Path file = Files.writeString(dir.resolve("includes.xsl"), wrapper);
    XsltCompiler compiler = new Processor(false).newXsltCompiler();
    List<XmlProcessingError> reported = new ArrayList<>();
    compiler.setErrorList(reported);
    List<String> errors = new ArrayList<>();
    try {
      compiler.compile(file.toFile());
    } catch (SaxonApiException e) {
      for (XmlProcessingError error : reported) {
        if (!error.isWarning()) {
          errors.add(error.getErrorCode() + " " + error.getMessage());
        }
      }
      if (errors.isEmpty()) {
        errors.add(e.getMessage());
      }
    }
    return errors;
  }

  @Test
  void everyFamilyModuleCompilesWithCoreAlone(@TempDir Path tmp) throws Exception {
    // witness: a module that uses what no included module declares does not compile, and it does
    // with the module that declares it
    Path declares =
        Files.writeString(
            tmp.resolve("declares.xsl"),
            STYLESHEET
                + "  <xsl:variable name=\"po:ELSEWHERE\" as=\"xs:string\" select=\"'x'\"/>\n"
                + "</xsl:stylesheet>\n");
    Path uses =
        Files.writeString(
            tmp.resolve("uses.xsl"),
            STYLESHEET
                + "  <xsl:function name=\"po:uses\" as=\"xs:string\">\n"
                + "    <xsl:sequence select=\"$po:ELSEWHERE\"/>\n"
                + "  </xsl:function>\n"
                + "</xsl:stylesheet>\n");
    assertThat(compileErrors(tmp.resolve("alone"), List.of(uses))).isNotEmpty();
    assertThat(compileErrors(tmp.resolve("together"), List.of(declares, uses))).isEmpty();

    Path dir = FamilyLocalityTest.repoRoot().resolve(FamilyLocalityTest.HELPER_DIR);
    assertThat(dir).as("the helper module's sources").isDirectory();
    Path core = dir.resolve(CORE);
    assertThat(core).as(CORE).isRegularFile();
    List<Path> families;
    try (Stream<Path> listing = Files.list(dir)) {
      families =
          listing
              .filter(p -> p.getFileName().toString().endsWith(".xsl"))
              .filter(p -> !p.getFileName().toString().equals(CORE))
              .sorted()
              .toList();
    }
    // today's eleven functions form the family context (ADR-0025)
    assertThat(families)
        .as("the family modules beside %s", CORE)
        .contains(dir.resolve("context.xsl"));

    List<String> failures = new ArrayList<>();
    for (Path family : families) {
      String name = family.getFileName().toString();
      for (String error : compileErrors(tmp.resolve(name), List.of(core, family))) {
        failures.add(name + " with " + CORE + ": " + error);
      }
    }

    assertThat(failures).as("family modules that do not compile with core alone").isEmpty();
  }
}
