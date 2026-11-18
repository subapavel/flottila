package dev.rail8.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.mapping.api.CompileOptions;
import dev.rail8.mapping.api.CompileResult;
import dev.rail8.mapping.api.EngineFacade;
import dev.rail8.mapping.api.ExportResult;
import dev.rail8.mapping.structure.importer.ReferenceResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * removeContexts over single values is a single value on both back ends, and over a queue the queue
 * it is: graphs around a constant fed into removeContexts that SAP PO and CPI have not been seen to
 * run, so the outputs pinned here are assumed readings. Over src-orders and tgt-list, one Item per
 * Order of three: removeContexts of its own call and concat of it with a second constant go into
 * every Item, as a constant does; count over it and removeContexts over copyValue's block read one
 * context, so only the first Item gets them. The models are no corpus cases.
 */
class KeepsSingleValueAgreementTest {

  /** The fingerprints of {@code src-orders.xsd} and {@code tgt-list.xsd}, which compile checks. */
  private static final String SOURCE_FINGERPRINT =
      "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7";

  private static final String TARGET_FINGERPRINT =
      "sha256:626cbacff639a86b480bb3c20cc032d8aa47ad341d9111e31882ad30ca6eca70";
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final byte[] INPUT =
      ("<Orders><Order><Id>o1</Id><Line><Sku>a</Sku></Line><Line><Sku>b</Sku></Line>"
              + "<Line><Sku>c</Sku></Line></Order>"
              + "<Order><Id>o2</Id><Line><Sku>d</Sku></Line></Order>"
              + "<Order><Id>o3</Id><Line><Sku>e</Sku></Line><Line><Sku>f</Sku></Line></Order>"
              + "</Orders>")
          .getBytes(UTF_8);

  @TempDir Path dir;

  private final EngineFacade facade = new EngineFacade();

  /** Item per Order (T1), then V (T2) and W (T3), each given as its result and its boxes. */
  private static byte[] model(String v, String w) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-list.xsd"], "fingerprint": "%s",
                    "root": {"element": "Out"}},
         "targetFieldMappings": {
           "/Out/Item": {"id": "T1", "result": "S1", "boxes": {
             "S1": {"kind": "source", "field": "/Orders/Order", "context": "/Orders"}}},
           "/Out/Item/V": {"id": "T2", %s},
           "/Out/Item/W": {"id": "T3", %s}}}
        """
        .formatted(SOURCE_FINGERPRINT, TARGET_FINGERPRINT, v, w)
        .getBytes(UTF_8);
  }

  private static final String K1 =
      "\"K1\": {\"kind\": \"function\", \"fn\": \"constant\", \"params\": {\"value\": \"k\"}}";
  private static final String K2 =
      "\"K2\": {\"kind\": \"function\", \"fn\": \"constant\", \"params\": {\"value\": \"k\"}}";

  private static ReferenceResolver structures() {
    return DirectoryResolver.over(CaseFiles.corpus().resolve("structures/observations"));
  }

  /** Our output and Saxon-HE's over the export, which agree; ours is returned. */
  private byte[] agreed(byte[] model, String what) throws Exception {
    CompileResult compiled = facade.compile(model, structures(), CompileOptions.DEPLOY);
    assertThat(compiled).as(what).isInstanceOf(CompileResult.Compiled.class);
    byte[] ours = facade.run(((CompileResult.Compiled) compiled).mapping(), INPUT);
    ExportResult.Exported exported = SaxonRunner.exported(facade.export(model, structures()), what);
    byte[] saxon =
        SaxonRunner.run(exported.stylesheet(), exported.helperModule(), INPUT, dir.resolve(what));
    SaxonRunner.assertAgree(saxon, ours, what);
    return ours;
  }

  /**
   * V from removeContexts over removeContexts over the constant k, and W from concat over
   * removeContexts of k and the constant z: k and kz in every Item.
   */
  @Test
  void removeContextsOfAConstantGoesIntoEveryItemThroughSingleValueBoxes() throws Exception {
    byte[] model =
        model(
            """
            "result": "R2", "boxes": {%s,
              "R1": {"kind": "function", "fn": "removeContexts", "inputs": ["K1"]},
              "R2": {"kind": "function", "fn": "removeContexts", "inputs": ["R1"]}}"""
                .formatted(K1),
            """
            "result": "C1", "boxes": {%s,
              "R3": {"kind": "function", "fn": "removeContexts", "inputs": ["K2"]},
              "K3": {"kind": "function", "fn": "constant", "params": {"value": "z"}},
              "C1": {"kind": "function", "fn": "concat", "inputs": ["R3", "K3"]}}"""
                .formatted(K2));

    String item = "<Item><V>k</V><W>kz</W></Item>";
    assertThat(new String(agreed(model, "every-item"), UTF_8))
        .isEqualTo(DECLARATION + "<Out>" + item + item + item + "</Out>");
  }

  /**
   * V from count over removeContexts of the constant k, and W from removeContexts over copyValue of
   * Sku at position 1 in context Order, b: one context each, so only the first Item gets 1 and b.
   */
  @Test
  void aQueueReadOfRemoveContextsGoesIntoTheFirstItemOnly() throws Exception {
    byte[] model =
        model(
            """
            "result": "N1", "boxes": {%s,
              "R1": {"kind": "function", "fn": "removeContexts", "inputs": ["K1"]},
              "N1": {"kind": "function", "fn": "count", "inputs": ["R1"]}}"""
                .formatted(K1),
            """
            "result": "R2", "boxes": {
              "S2": {"kind": "source", "field": "/Orders/Order/Line/Sku",
                     "context": "/Orders/Order"},
              "F1": {"kind": "function", "fn": "copyValue", "inputs": ["S2"],
                     "params": {"position": "1"}},
              "R2": {"kind": "function", "fn": "removeContexts", "inputs": ["F1"]}}""");

    assertThat(new String(agreed(model, "first-item"), UTF_8))
        .isEqualTo(DECLARATION + "<Out><Item><V>1</V><W>b</W></Item><Item/><Item/></Out>");
  }
}
