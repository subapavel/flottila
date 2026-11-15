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
 * A duplicate key in a JSON input is malformed input on both sides (ADR-0008 "JSON as a source at
 * run time", #98): our runtime refuses the run with {@link MalformedInputException}, as it refuses
 * XML that is not well-formed, and the export's {@code json-to-xml} with {@code map{'duplicates':
 * 'reject'}} raises {@code err:FOJS0003} in Saxon-HE 12.5. The corpus has no case for malformed
 * input, so this pins it. The mapping is the corpus's {@code json-tags}, which reads {@code
 * $/tags}.
 */
class JsonDuplicateKeyTest {

  @TempDir Path dir;

  private static final Path MAPPING = CaseFiles.corpus().resolve("mappings/json-tags.mapping.json");

  /** A backslash, so that no escape of the JSON text below is read by javac. */
  private static final String BACKSLASH = "\\";

  @Test
  void aDuplicateKeyOfAFieldTheMappingReadsFailsOnBothSides() throws Exception {
    assertBothRefuse("{\"tags\": [\"a\"], \"tags\": [\"b\"]}", "the key tags twice");
  }

  @Test
  void aDuplicateKeyInAMemberTheProjectionSkipsFailsOnBothSides() throws Exception {
    assertBothRefuse(
        "{\"other\": {\"k\": 1, \"k\": 2}, \"tags\": [\"a\"]}",
        "the key k twice in a skipped member");
  }

  /** The keys compare as the strings they escape: the second key escapes its t, and is tags too. */
  @Test
  void aDuplicateKeySpelledWithAnEscapeFailsOnBothSides() throws Exception {
    assertBothRefuse(
        "{\"tags\": [\"a\"], \"" + BACKSLASH + "u0074ags\": [\"b\"]}", "tags and its escaped twin");
  }

  /** The witness: the same mapping with distinct keys runs on both sides, and they agree. */
  @Test
  void distinctKeysRunOnBothSides() throws Exception {
    byte[] input = "{\"other\": {\"k\": 1, \"j\": 2}, \"tags\": [\"a\"]}".getBytes(UTF_8);
    EngineFacade facade = new EngineFacade();
    ExportResult.Exported exported = exported(facade);

    byte[] saxon = SaxonRunner.run(exported.stylesheet(), exported.helperModule(), input, dir);
    byte[] ours = facade.run(compiled(facade), input);

    SaxonRunner.assertAgree(saxon, ours, "distinct keys");
    assertThat(new String(ours, UTF_8)).contains("<All>a</All>");
  }

  private void assertBothRefuse(String json, String what) throws Exception {
    byte[] input = json.getBytes(UTF_8);
    EngineFacade facade = new EngineFacade();
    ExportResult.Exported exported = exported(facade);

    SaxonRunner.assertSameError(
        SaxonRunner.raised(exported.stylesheet(), exported.helperModule(), input, dir, what),
        "err:FOJS0003",
        what);
    CompiledMapping compiled = compiled(facade);
    assertThatThrownBy(() -> facade.run(compiled, input))
        .as("%s: ours", what)
        .isInstanceOf(MalformedInputException.class)
        .hasMessageContaining("duplicate key");
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
