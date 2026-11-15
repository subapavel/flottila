package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.ExportResult;
import io.flottila.mapping.runtime.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A character written raw in a JSON input, not escaped, on both sides (ADR-0008 "JSON as a source
 * at run time", #89): the export reads its input with {@code unparsed-text}, which Saxon-HE 12.5
 * refuses with {@code err:FOUT1190} when the text holds a character XML 1.0 cannot hold, a raw
 * U+FFFE or U+FFFF, and which drops a leading byte order mark. Our runtime refuses the first with
 * {@link MalformedInputException} and drops the second too, so that the output stays well-formed
 * XML and a run reads what the export reads. The corpus has no case for malformed input, so this
 * pins it. The mapping is the corpus's {@code json-tags}, which reads {@code $/tags}.
 */
class JsonRawCharacterTest {

  @TempDir Path dir;

  private static final Path MAPPING = CaseFiles.corpus().resolve("mappings/json-tags.mapping.json");

  @Test
  void aRawU0fffeInAStringFailsOnBothSides() throws Exception {
    assertBothRefuse("{\"tags\": [\"a" + (char) 0xFFFE + "\"]}", "a raw U+FFFE");
  }

  @Test
  void aRawU0ffffInAKeyFailsOnBothSides() throws Exception {
    assertBothRefuse("{\"x" + (char) 0xFFFF + "\": 1, \"tags\": [\"a\"]}", "a raw U+FFFF");
  }

  @Test
  void aLeadingByteOrderMarkIsDroppedOnBothSides() throws Exception {
    assertBothRead((char) 0xFEFF + "{\"tags\": [\"a\"]}", "<All>a</All>", "a leading BOM");
  }

  /** The witness: U+FEFF inside a string is a character XML holds, kept on both sides. */
  @Test
  void aByteOrderMarkInsideAStringIsKeptOnBothSides() throws Exception {
    assertBothRead(
        "{\"tags\": [\"" + (char) 0xFEFF + "a\"]}", "<All>" + (char) 0xFEFF + "a</All>", "U+FEFF");
  }

  private void assertBothRead(String json, String expected, String what) throws Exception {
    byte[] input = json.getBytes(UTF_8);
    EngineFacade facade = new EngineFacade();
    ExportResult.Exported exported = exported(facade);

    byte[] saxon = SaxonRunner.run(exported.stylesheet(), exported.helperModule(), input, dir);
    byte[] ours = facade.run(compiled(facade), input);

    SaxonRunner.assertAgree(saxon, ours, what);
    assertThat(new String(ours, UTF_8)).as(what).contains(expected);
  }

  private void assertBothRefuse(String json, String what) throws Exception {
    byte[] input = json.getBytes(UTF_8);
    EngineFacade facade = new EngineFacade();
    ExportResult.Exported exported = exported(facade);

    SaxonRunner.assertSameError(
        SaxonRunner.raised(exported.stylesheet(), exported.helperModule(), input, dir, what),
        "err:FOUT1190",
        what);
    CompiledMapping compiled = compiled(facade);
    assertThatThrownBy(() -> facade.run(compiled, input))
        .as("%s: ours", what)
        .isInstanceOf(MalformedInputException.class)
        .hasMessageContaining("a character XML 1.0 cannot hold");
  }

  private static ExportResult.Exported exported(EngineFacade facade) throws Exception {
    return SaxonRunner.exported(
        facade.export(Files.readAllBytes(MAPPING), DirectoryResolver.over(MAPPING.getParent())),
        "json-tags");
  }

  private static CompiledMapping compiled(EngineFacade facade) throws Exception {
    CompileResult result =
        facade.compile(
            Files.readAllBytes(MAPPING),
            DirectoryResolver.over(MAPPING.getParent()),
            CompileOptions.DEPLOY);
    if (!(result instanceof CompileResult.Compiled(CompiledMapping compiled))) {
      throw new AssertionError("json-tags: expected Compiled, got " + result);
    }
    return compiled;
  }
}
