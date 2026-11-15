package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flottila.mapping.api.CompileOptions;
import io.flottila.mapping.api.CompileResult;
import io.flottila.mapping.api.CompiledMapping;
import io.flottila.mapping.api.EngineFacade;
import io.flottila.mapping.api.ExportResult;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import java.nio.file.Path;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A generating structure field evaluates its value on both back ends though it never writes it
 * (ADR-0006 as amended by #83): on tgt-gen's structure field Gen (2..3, per Grp per Order), add
 * over the constants x and 1 raises err:FORG0001 at the add box A1, in the runtime and in Saxon-HE,
 * which evaluates the export's zero-length {@code substring(value, 1, 0)}. That Saxon-HE 12.5
 * evaluates it is observed, not required by XSLT 3.0; this test pins it. Witness: with the
 * constants 2 and 1 both back ends run and agree. The model is no corpus case and needs no
 * observation row; this class is no corpus or differential class, so the ratchet does not read its
 * test as a case id (scripts/ratchet.java).
 */
class GeneratingValueErrorTest {

  /**
   * The fingerprints of {@code src-orders.xsd} and {@code tgt-gen.xsd}, which compile checks (#91);
   * {@code FingerprintPinTest} in {@code engine/api} pins them.
   */
  private static final String SOURCE_FINGERPRINT =
      "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7";

  private static final String TARGET_FINGERPRINT =
      "sha256:4a92b5bce393664363954017c2b1029537235b8a4557f9a2a220c66d516f4e35";
  private static final String WHAT = "add(constant, constant) on Gen";
  private static final byte[] INPUT = "<Orders><Order><Id>o1</Id></Order></Orders>".getBytes(UTF_8);

  @TempDir Path dir;

  private final EngineFacade facade = new EngineFacade();

  /** Grp per Order (T1), and Gen from A1, add over the constants C1 {@code first} and C2 1. */
  private static byte[] model(String first) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-gen.xsd"], "fingerprint": "%3$s",
                    "root": {"element": "Gens"}},
         "targetFieldMappings": {
           "/Gens/Grp": {"id": "T1", "result": "S1", "boxes": {
             "S1": {"kind": "source", "field": "/Orders/Order", "context": "/Orders"}}},
           "/Gens/Grp/Gen": {"id": "T2", "result": "A1", "boxes": {
             "A1": {"kind": "function", "fn": "add", "inputs": ["C1", "C2"]},
             "C1": {"kind": "function", "fn": "constant", "params": {"value": "%2$s"}},
             "C2": {"kind": "function", "fn": "constant", "params": {"value": "1"}}}}}}
        """
        .formatted(SOURCE_FINGERPRINT, first, TARGET_FINGERPRINT)
        .getBytes(UTF_8);
  }

  private static ReferenceResolver structures() {
    return DirectoryResolver.over(CaseFiles.corpus().resolve("structures/observations"));
  }

  private CompiledMapping compiled(byte[] model, CompileOptions options) throws Exception {
    CompileResult result = facade.compile(model, structures(), options);
    assertThat(result).as(WHAT).isInstanceOf(CompileResult.Compiled.class);
    return ((CompileResult.Compiled) result).mapping();
  }

  private ExportResult.Exported exported(byte[] model) throws Exception {
    return SaxonRunner.exported(facade.export(model, structures()), WHAT);
  }

  @Test
  void bothBackEndsRaiseTheValuesDataErrorAtItsBox() throws Exception {
    byte[] runs = model("2");
    ExportResult.Exported witness = exported(runs);
    byte[] ours = facade.run(compiled(runs, CompileOptions.DEPLOY), INPUT);
    byte[] saxon =
        SaxonRunner.run(witness.stylesheet(), witness.helperModule(), INPUT, dir.resolve("runs"));
    SaxonRunner.assertAgree(saxon, ours, WHAT + ", witness");

    byte[] fails = model("x");
    ExportResult.Exported exported = exported(fails);
    for (CompileOptions options :
        new CompileOptions[] {CompileOptions.DEPLOY, CompileOptions.TEST_RUN}) {
      Throwable error = catchThrowable(() -> facade.run(compiled(fails, options), INPUT));
      assertThat(error).as("%s: ours, %s", WHAT, options).isInstanceOf(MappingRunException.class);
      MappingRunException run = (MappingRunException) error;
      assertThat(run.code()).as("%s: ours", WHAT).isEqualTo("err:FORG0001");
      assertThat(run.authoringId().value()).as("%s: ours", WHAT).isEqualTo("A1");
    }
    SaxonApiException raised =
        SaxonRunner.raised(
            exported.stylesheet(), exported.helperModule(), INPUT, dir.resolve("fails"), WHAT);
    SaxonRunner.assertSameError(raised, "err:FORG0001", WHAT);
    SaxonRunner.assertNamesItsPlace(raised.getMessage(), "err:FORG0001", "A1", WHAT);
  }
}
