package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * po:USEONEASMANY says which count failed (ADR-0025), on both back ends and in the same words: the
 * corpus's six useOneAsMany error cases, each its counts from its note, after the box its
 * expected-error.json names, U1 (ADR-0026, F-19); the conditions are PO 7.50's and CPI's (F-48).
 * The QName is the differential oracle's; this class is no corpus or differential class, so the
 * ratchet does not read its tests as case ids (scripts/ratchet.java).
 */
class UseOneAsManyErrorMessageTest {

  @TempDir Path dir;

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "fn/useOneAsMany/006 | the function box U1: useOneAsMany needs as many slots in its third"
            + " input as in its second: the third has 2 slots, the second 3",
        "fn/useOneAsMany/007 | the function box U1: useOneAsMany needs as many slots in its third"
            + " input as in its second: the third has 3 slots, the second 2",
        "fn/useOneAsMany/008 | the function box U1: useOneAsMany needs one value in each context of"
            + " its first input: its context 3 has 0 values",
        "fn/useOneAsMany/003 | the function box U1: useOneAsMany needs as many contexts in its first"
            + " input as in its second: the first has 1 contexts, the second 2",
        "fn/useOneAsMany/005 | the function box U1: useOneAsMany needs as many contexts in its first"
            + " input as in its second: the first has 1 contexts, the second 2",
        "fn/useOneAsMany/009 | the function box U1: useOneAsMany needs as many contexts in its first"
            + " input as in its second: the first has 1 contexts, the second 2"
      })
  void bothBackEndsSayWhichCountFailed(String id, String message) throws Exception {
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
    SaxonRunner.assertSameError(saxon, "po:USEONEASMANY", id);
    assertThat(saxon.getMessage()).as("%s: Saxon's", id).isEqualTo(message);
  }

  /**
   * The corpus mapping of fn/useOneAsMany/006 with Tag in Order's context as input 1 and Sku in
   * Orders' as input 3: no corpus input holds two values in a context of input 1 whose contexts
   * match input 2's, as Id holds at most one per Order.
   */
  private static final String TAG_PER_SKU =
      """
      {"formatVersion": 1,
       "source": {"bundle": ["../structures/observations/src-orders.xsd"],
         "fingerprint": "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7",
         "root": {"element": "Orders"}},
       "target": {"bundle": ["../structures/observations/tgt-list.xsd"],
         "fingerprint": "sha256:626cbacff639a86b480bb3c20cc032d8aa47ad341d9111e31882ad30ca6eca70",
         "root": {"element": "Out"}},
       "targetFieldMappings": {"/Out/All": {"id": "T1", "result": "U1", "boxes": {
         "S1": {"kind": "source", "field": "/Orders/Order/Tag"},
         "S2": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders/Order"},
         "S3": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders"},
         "U1": {"kind": "function", "fn": "useOneAsMany", "inputs": ["S1", "S2", "S3"]}}}}}
      """;

  /**
   * Input 1 with as many contexts as input 2 but two values in one, Tag t1, t2 in the one Order:
   * both back ends refuse it in the same words, after the box (F-48). Witness: one Tag runs on
   * both.
   */
  @Test
  void bothBackEndsRefuseTwoValuesInAContextOfTheFirstInput() throws Exception {
    byte[] model = TAG_PER_SKU.getBytes(UTF_8);
    var resolver = DirectoryResolver.over(CaseFiles.corpus().resolve("mappings"));
    EngineFacade facade = new EngineFacade();
    CompileResult result = facade.compile(model, resolver, CompileOptions.DEPLOY);
    assertThat(result).isInstanceOf(CompileResult.Compiled.class);
    CompiledMapping compiled = ((CompileResult.Compiled) result).mapping();
    ExportResult.Exported exported =
        SaxonRunner.exported(facade.export(model, resolver), "two Tags");
    byte[] one =
        "<Orders><Order><Tag>t1</Tag><Line><Sku>s</Sku></Line></Order></Orders>".getBytes(UTF_8);
    byte[] two =
        "<Orders><Order><Tag>t1</Tag><Tag>t2</Tag><Line><Sku>s</Sku></Line></Order></Orders>"
            .getBytes(UTF_8);
    // witness: one Tag per Order runs, the same on both back ends
    byte[] ran = facade.run(compiled, one);
    assertThat(new String(ran, UTF_8)).contains("<Out><All>t1</All></Out>");
    SaxonRunner.assertAgree(
        SaxonRunner.run(exported.stylesheet(), exported.helperModule(), one, dir.resolve("one")),
        ran,
        "one Tag");

    Throwable ours = catchThrowable(() -> facade.run(compiled, two));
    SaxonApiException saxon =
        SaxonRunner.raised(
            exported.stylesheet(), exported.helperModule(), two, dir.resolve("two"), "two Tags");

    String message =
        "the function box U1: useOneAsMany needs one value in each context of its first input: its"
            + " context 1 has 2 values";
    assertThat(ours).isInstanceOf(MappingRunException.class).hasMessage(message);
    SaxonRunner.assertSameError(saxon, "po:USEONEASMANY", "two Tags");
    assertThat(saxon.getMessage()).isEqualTo(message);
  }
}
