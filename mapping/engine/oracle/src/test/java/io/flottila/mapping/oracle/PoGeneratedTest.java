package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.xslt.export.XsltExporter;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The helper module's {@code po:generated} (#217, F-46): the value a copy of a generating block
 * writes, the block's first slot when it is a value, and none when it is SUPPRESS or the block has
 * no slot, so that copy writes nothing. A block is the export's queue, an array of one sequence per
 * context, {@code $po:SUPPRESS} at a SUPPRESS slot; the first slot counts across contexts. Saxon-HE
 * runs a stylesheet of one template that imports the packed helper module and calls it; it lives
 * here, as xslt-export has no Saxon. Not a corpus or differential class, so the ratchet does not
 * read its tests as case ids (scripts/ratchet.java).
 */
class PoGeneratedTest {

  /** A text stylesheet writing how many items {@code po:generated(block)} gives, then them. */
  private static String generated(String block) {
    return """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:po="urn:flottila:mapping:po"
            exclude-result-prefixes="#all">
          <xsl:import href="po-runtime.xsl"/>
          <xsl:output method="text"/>
          <xsl:template match="/">
            <xsl:variable name="generated" select="po:generated(%s)"/>
            <xsl:value-of select="count($generated), $generated" separator=":"/>
          </xsl:template>
        </xsl:stylesheet>
        """
        .formatted(block);
  }

  static Stream<Arguments> blocks() {
    return Stream.of(
        // fn/copyValue/001: copyValue{position=1} gives b
        Arguments.of("[('b')]", "1:b"),
        // fn/copyValue/004: copyValue{position=1} gives SUPPRESS
        Arguments.of("[($po:SUPPRESS)]", "0"),
        // past the last slot, and an empty queue: one empty context
        Arguments.of("[()]", "0"),
        Arguments.of("[]", "0"),
        // "" is a value
        Arguments.of("[('')]", "1:"),
        Arguments.of("[('a', 'b')]", "1:a"),
        // the first slot counts across contexts, past an empty one
        Arguments.of("[(), ('x')]", "1:x"),
        // a SUPPRESS first slot gives none, whatever follows it
        Arguments.of("[($po:SUPPRESS, 'a')]", "0"),
        Arguments.of("[($po:SUPPRESS), ('a')]", "0"));
  }

  @ParameterizedTest(name = "{0} gives {1}")
  @MethodSource("blocks")
  void poGeneratedGivesTheFirstValueOrNone(String block, String expected, @TempDir Path dir)
      throws Exception {
    byte[] out =
        SaxonRunner.run(generated(block), XsltExporter.helperModule(), "<a/>".getBytes(UTF_8), dir);

    assertThat(new String(out, UTF_8)).isEqualTo(expected);
  }
}
