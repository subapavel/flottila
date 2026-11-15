package io.flottila.mapping.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.ExportResult;
import io.flottila.mapping.oracle.CaseFiles.CorpusCase;
import io.flottila.mapping.runtime.MappingRunException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * po:FIXVALUES names the key the table lacks (#83, ADR-0026 "Table params"), on both back ends and
 * in the same words: the corpus case fn/fixValues/004, whose key f its note names, after the box
 * its expected-error.json names, F1 (F-19). The QName is the differential oracle's; this class is
 * no corpus or differential class, so the ratchet does not read its test as a case id
 * (scripts/ratchet.java).
 */
class FixValuesErrorMessageTest {

  @TempDir Path dir;

  @Test
  void bothBackEndsNameTheMissingKey() throws Exception {
    String id = "fn/fixValues/004";
    String message = "the function box F1: fixValues: the key \"f\" is not in the table";
    CorpusCase c = CaseFiles.read(CaseFiles.cases(), CaseFiles.cases().resolve(id));
    Path mapping = CaseFiles.corpus().resolve(c.mapping());
    byte[] model = Files.readAllBytes(mapping);
    byte[] input = Files.readAllBytes(c.input());
    EngineFacade facade = new EngineFacade();
    CompileResult result =
        facade.compile(model, DirectoryResolver.over(mapping.getParent()), CompileOptions.DEPLOY);
    assertThat(result).as(id).isInstanceOf(CompileResult.Compiled.class);
    CompiledMapping compiled = ((CompileResult.Compiled) result).mapping();
    ExportResult.Exported exported =
        SaxonRunner.exported(facade.export(model, DirectoryResolver.over(mapping.getParent())), id);

    Throwable ours = catchThrowable(() -> facade.run(compiled, input));
    SaxonApiException saxon =
        SaxonRunner.raised(exported.stylesheet(), exported.helperModule(), input, dir, id);

    assertThat(ours).as("%s: ours", id).isInstanceOf(MappingRunException.class);
    assertThat(ours.getMessage()).as("%s: ours", id).isEqualTo(message);
    SaxonRunner.assertSameError(saxon, "po:FIXVALUES", id);
    assertThat(saxon.getMessage()).as("%s: Saxon's", id).isEqualTo(message);
  }
}
