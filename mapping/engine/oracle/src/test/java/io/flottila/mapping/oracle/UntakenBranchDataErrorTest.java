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
import java.util.stream.Stream;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every input of a single-value call is evaluated before the call, as PO computes every input queue
 * of a box before the box runs (F-22, ADR-0006): {@code if} evaluates both branches, and {@code
 * and} and {@code or} both operands, so a data error in the one the result does not need is raised
 * at its box, in the runtime and in Saxon-HE. On tgt-list's All, {@code if(Sku, Qty, divide(1, 0))}
 * with every Sku {@code true}, {@code and(Sku, divide(1, 0))} with every Sku {@code false} and
 * {@code or(Sku, divide(1, 0))} with every Sku {@code true} raise err:FOAR0001 at the divide box
 * D1. Witness: with {@code divide(1, 1)} both back ends run and agree; its 1 casts to true, as
 * every boolean input does since F-44 (0.5 would raise the cast error). The model is no corpus case
 * and needs no observation row; this class is no corpus or differential class, so the ratchet does
 * not read its test as a case id (scripts/ratchet.java).
 */
class UntakenBranchDataErrorTest {

  /**
   * The fingerprints of {@code src-orders.xsd} and {@code tgt-list.xsd}, which compile checks
   * (#91); {@code FingerprintPinTest} in {@code engine/api} pins them.
   */
  private static final String SOURCE_FINGERPRINT =
      "sha256:5e9bdd3db3b284fc086f0233797ede5e4f3fb874bd696ba3f496dd30bffef9a7";

  private static final String TARGET_FINGERPRINT =
      "sha256:626cbacff639a86b480bb3c20cc032d8aa47ad341d9111e31882ad30ca6eca70";

  @TempDir Path dir;

  private final EngineFacade facade = new EngineFacade();

  /**
   * All from F1, {@code fn} over {@code inputs} of S1 Sku, S2 Qty and D1, the division of the
   * constant C1 1 by the constant C2 {@code divisor}: a single value, which F1's Lift reads in its
   * body (ADR-0006 since #83).
   */
  private static byte[] model(String fn, String inputs, String divisor) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-list.xsd"], "fingerprint": "%5$s",
                    "root": {"element": "Out"}},
         "targetFieldMappings": {
           "/Out/All": {"id": "T1", "result": "F1", "boxes": {
             "F1": {"kind": "function", "fn": "%3$s", "inputs": %4$s},
             "D1": {"kind": "function", "fn": "divide", "inputs": ["C1", "C2"]},
             "C1": {"kind": "function", "fn": "constant", "params": {"value": "1"}},
             "C2": {"kind": "function", "fn": "constant", "params": {"value": "%2$s"}},
             "S1": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders"},
             "S2": {"kind": "source", "field": "/Orders/Order/Line/Qty", "context": "/Orders"}}}}}
        """
        .formatted(SOURCE_FINGERPRINT, divisor, fn, inputs, TARGET_FINGERPRINT)
        .getBytes(UTF_8);
  }

  /** One Order whose Lines carry the Skus {@code first} and {@code second}, Qty 4 and 6. */
  private static byte[] input(String first, String second) {
    return ("<Orders><Order><Id>o1</Id>"
            + "<Line><Sku>"
            + first
            + "</Sku><Qty>4</Qty></Line>"
            + "<Line><Sku>"
            + second
            + "</Sku><Qty>6</Qty></Line>"
            + "</Order></Orders>")
        .getBytes(UTF_8);
  }

  private static ReferenceResolver structures() {
    return DirectoryResolver.over(CaseFiles.corpus().resolve("structures/observations"));
  }

  private CompiledMapping compiled(byte[] model, CompileOptions options, String what)
      throws Exception {
    CompileResult result = facade.compile(model, structures(), options);
    assertThat(result).as(what).isInstanceOf(CompileResult.Compiled.class);
    return ((CompileResult.Compiled) result).mapping();
  }

  /**
   * Per function: its inputs, the Skus under which the result does not need D1, and the witness
   * output of {@code divide(1, 1)} over the Skus true and false: 1 is true.
   */
  static Stream<Arguments> calls() {
    return Stream.of(
        Arguments.of("if", "[\"S1\", \"S2\", \"D1\"]", "true", "4", "1"),
        Arguments.of("and", "[\"S1\", \"D1\"]", "false", "true", "false"),
        Arguments.of("or", "[\"S1\", \"D1\"]", "true", "true", "true"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("calls")
  void theUnneededInputsDataErrorIsRaisedAtItsBoxOnBothBackEnds(
      String fn, String inputs, String unneeded, String first, String second) throws Exception {
    String fine = fn + " over divide(1, 1) on All";
    byte[] runs = model(fn, inputs, "1");
    ExportResult.Exported witness = SaxonRunner.exported(facade.export(runs, structures()), fine);
    byte[] mixed = input("true", "false");
    for (CompileOptions options :
        new CompileOptions[] {CompileOptions.DEPLOY, CompileOptions.TEST_RUN}) {
      byte[] ours = facade.run(compiled(runs, options, fine), mixed);
      assertThat(new String(ours, UTF_8))
          .as("%s: ours, %s", fine, options)
          .contains("<All>" + first + "</All><All>" + second + "</All>");
      byte[] saxon =
          SaxonRunner.run(witness.stylesheet(), witness.helperModule(), mixed, dir.resolve("fine"));
      SaxonRunner.assertAgree(saxon, ours, fine + ", " + options);
    }

    String what = fn + " over divide(1, 0) on All, every Sku " + unneeded;
    byte[] fails = model(fn, inputs, "0");
    ExportResult.Exported exported = SaxonRunner.exported(facade.export(fails, structures()), what);
    byte[] untaken = input(unneeded, unneeded);
    for (CompileOptions options :
        new CompileOptions[] {CompileOptions.DEPLOY, CompileOptions.TEST_RUN}) {
      Throwable error = catchThrowable(() -> facade.run(compiled(fails, options, what), untaken));
      assertThat(error).as("%s: ours, %s", what, options).isInstanceOf(MappingRunException.class);
      MappingRunException run = (MappingRunException) error;
      assertThat(run.code()).as("%s: ours", what).isEqualTo("err:FOAR0001");
      assertThat(run.authoringId().value()).as("%s: ours", what).isEqualTo("D1");
    }
    SaxonApiException raised =
        SaxonRunner.raised(
            exported.stylesheet(), exported.helperModule(), untaken, dir.resolve("fails"), what);
    SaxonRunner.assertSameError(raised, "err:FOAR0001", what);
    SaxonRunner.assertNamesItsPlace(raised.getMessage(), "err:FOAR0001", "D1", what);
  }

  /**
   * All from F1, {@code replaceValue} of the box {@code input} with the replacement x, beside the
   * {@code boxes} that build its input.
   */
  private static byte[] replaced(String input, String boxes) {
    return """
        {"formatVersion": 1,
         "source": {"bundle": ["src-orders.xsd"], "fingerprint": "%1$s",
                    "root": {"element": "Orders"}},
         "target": {"bundle": ["tgt-list.xsd"], "fingerprint": "%2$s",
                    "root": {"element": "Out"}},
         "targetFieldMappings": {
           "/Out/All": {"id": "T1", "result": "F1", "boxes": {
             "F1": {"kind": "function", "fn": "replaceValue", "inputs": ["%3$s"],
                    "params": {"replacement": "x"}},
             %4$s}}}}
        """
        .formatted(SOURCE_FINGERPRINT, TARGET_FINGERPRINT, input, boxes)
        .getBytes(UTF_8);
  }

  /** {@code createIf(Sku)}: C1 over S1, Sku in the context Orders. */
  private static final String CREATE_IF =
      """
      "C1": {"kind": "function", "fn": "createIf", "inputs": ["S1"]},
      "S1": {"kind": "source", "field": "/Orders/Order/Line/Sku", "context": "/Orders"}""";

  /** {@code divide(1, divisor)}: D1 over the constants C1 and C2. */
  private static String divide(String divisor) {
    return """
        "D1": {"kind": "function", "fn": "divide", "inputs": ["C1", "C2"]},
        "C1": {"kind": "function", "fn": "constant", "params": {"value": "1"}},
        "C2": {"kind": "function", "fn": "constant", "params": {"value": "%s"}}"""
        .formatted(divisor);
  }

  /**
   * Per input of replaceValue: the model and input that run, the All they write, and the model and
   * input whose input box raises, with the code and the box. createIf(Sku) is a queue, and over
   * Skus true, false it gives "", SUPPRESS, which replaceValue replaces both (F-45, as
   * fn/replaceValue/002 observes); no is no boolean (F-37). divide(1, 0) is a single value, which
   * enters replaceValue as AtomicToQueue (ADR-0006 since #83).
   */
  static Stream<Arguments> replaceValueInputs() {
    return Stream.of(
        Arguments.of(
            "createIf(Sku), a queue",
            replaced("C1", CREATE_IF),
            input("true", "false"),
            "<All>x</All><All>x</All>",
            replaced("C1", CREATE_IF),
            input("true", "no"),
            "po:CREATEIF",
            "C1"),
        Arguments.of(
            "divide(1, 0), a single value",
            replaced("D1", divide("1")),
            input("true", "false"),
            "<All>x</All>",
            replaced("D1", divide("0")),
            input("true", "false"),
            "err:FOAR0001",
            "D1"));
  }

  /**
   * replaceValue is a context function since #216 (F-45): its result needs none of its input's
   * values, yet the input is computed before the box runs, as PO computes every input queue of a
   * box (F-22, ADR-0006). A data error of the input is raised at the input's box, in the runtime
   * and in Saxon-HE. Witness: on an input that meets no error both back ends write the replacement
   * at every slot and agree.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("replaceValueInputs")
  void replaceValueStillRaisesItsInputsErrorAtThatBox(
      String what,
      byte[] runs,
      byte[] fine,
      String all,
      byte[] fails,
      byte[] failing,
      String code,
      String box)
      throws Exception {
    String witness = "replaceValue of " + what + " on All";
    ExportResult.Exported runsExported =
        SaxonRunner.exported(facade.export(runs, structures()), witness);
    for (CompileOptions options :
        new CompileOptions[] {CompileOptions.DEPLOY, CompileOptions.TEST_RUN}) {
      byte[] ours = facade.run(compiled(runs, options, witness), fine);
      assertThat(new String(ours, UTF_8))
          .as("%s: ours, %s", witness, options)
          .contains("<Out>" + all + "</Out>");
      byte[] saxon =
          SaxonRunner.run(
              runsExported.stylesheet(), runsExported.helperModule(), fine, dir.resolve("runs"));
      SaxonRunner.assertAgree(saxon, ours, witness + ", " + options);
    }

    String raising = witness + ", its input raising " + code;
    ExportResult.Exported exported =
        SaxonRunner.exported(facade.export(fails, structures()), raising);
    for (CompileOptions options :
        new CompileOptions[] {CompileOptions.DEPLOY, CompileOptions.TEST_RUN}) {
      Throwable error =
          catchThrowable(() -> facade.run(compiled(fails, options, raising), failing));
      assertThat(error)
          .as("%s: ours, %s", raising, options)
          .isInstanceOf(MappingRunException.class);
      MappingRunException run = (MappingRunException) error;
      assertThat(run.code()).as("%s: ours", raising).isEqualTo(code);
      assertThat(run.authoringId().value()).as("%s: ours", raising).isEqualTo(box);
    }
    SaxonApiException raised =
        SaxonRunner.raised(
            exported.stylesheet(),
            exported.helperModule(),
            failing,
            dir.resolve("raises"),
            raising);
    SaxonRunner.assertSameError(raised, code, raising);
    SaxonRunner.assertNamesItsPlace(raised.getMessage(), code, box, raising);
  }
}
