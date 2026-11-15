package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrType;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.runtime.Interpreter;
import io.flottila.mapping.runtime.MappingRunException;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.xslt.export.XsltExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code ifWithoutElse} agrees with {@code po:ifWithoutElse} (F-44) on the context shapes the
 * corpus cases name only by example, in the style of {@code SuppressContextFunctionsAgreementTest}:
 * IR built by hand, our interpreter and Saxon-HE on its export, {@link SaxonRunner#assertAgree}
 * judges. The condition Tag and the value Sku are read per Order or over all Orders, so a pair of
 * contexts has values of different counts, an empty context, a value context beyond the condition's
 * last and a condition context beyond the value's last. Each pairing is written three ways:
 * removeContexts, collapseContexts, and collapseContexts of a split by each value after
 * removeContexts, which alone tells an empty context from a SUPPRESS one under today's
 * collapseContexts. No corpus or differential class: its tests are no case ids
 * (scripts/ratchet.java).
 */
class IfWithoutElseAgreementTest {

  private static final QueueType QUEUE = new QueueType();
  private static final AtomicType STRING = AtomicType.STRING;
  private static final AuthoringId T1 = new AuthoringId("t1");
  // the booleans the family casts (F-44)
  private static final List<String> CONDITIONS =
      List.of("true", "false", "1", "0", "TRUE", "False");
  private static final List<String> SKUS = List.of("a", "b", "", "c");

  @TempDir Path dir;

  private static Name local(String name) {
    return new Name("", name);
  }

  /** {@code Orders/Order/steps} in the context {@code depth} steps from the root. */
  private static SourceQueue source(int depth, String box, String... steps) {
    List<Name> field = new ArrayList<>(List.of(local("Orders"), local("Order")));
    for (String step : steps) {
      field.add(local(step));
    }
    return new SourceQueue(field, depth, new AuthoringId(box));
  }

  private static FunCall call(String function, String box, List<Expr> args) {
    List<IrType> types = new ArrayList<>();
    for (Expr arg : args) {
      types.add(arg instanceof Literal ? STRING : QUEUE);
    }
    return new FunCall(new FunctionRef(function, types, QUEUE), args, new AuthoringId(box));
  }

  private static Literal param(String value, String box) {
    return new Literal(value, new AuthoringId(box));
  }

  /** {@code <name>{value}</name>} per slot of context 1 of {@code queue}. */
  private static TargetSlots leaf(String name, Expr queue) {
    return new TargetSlots(
        queue,
        new VarRef("root", AtomicType.INTEGER, T1),
        0,
        Long.MAX_VALUE,
        "slot",
        "value",
        new ElementConstructor(
            "", local(name), List.of(new TextConstructor(new VarRef("value", STRING, T1), T1)), T1),
        T1);
  }

  /** {@code ifWithoutElse(Tag, Sku; keep)} at box {@code box}, Tag and Sku at their depths. */
  private static FunCall ifWithoutElse(
      String box, int conditionDepth, int valueDepth, String keep) {
    return call(
        "ifWithoutElse",
        box,
        List.of(
            source(conditionDepth, box + "t", "Tag"),
            source(valueDepth, box + "s", "Line", "Sku"),
            param(keep, box)));
  }

  /** The three readings of {@code result}, named {@code name} A, B and C. */
  private static List<TargetSlots> readings(String name, String box, Expr result) {
    Expr flat = call("removeContexts", box + "r", List.of(result));
    Expr split = call("splitByValue", box + "p", List.of(flat, param("eachValue", box + "p")));
    return List.of(
        leaf(name + "A", flat),
        leaf(name + "B", call("collapseContexts", box + "k", List.of(result))),
        leaf(name + "C", call("collapseContexts", box + "q", List.of(split))));
  }

  /**
   * Per pairing, its readings: Tag and Sku per Order (S), Tag over all Orders against Sku per Order
   * (F, a value context beyond the condition's last) and Tag per Order against Sku over all Orders
   * (M, a condition context beyond the value's last).
   */
  private static TargetDocument document(String keep) {
    List<TargetSlots> leaves = new ArrayList<>();
    leaves.addAll(readings("S", "x1", ifWithoutElse("x1", 2, 2, keep)));
    leaves.addAll(readings("F", "x2", ifWithoutElse("x2", 1, 2, keep)));
    leaves.addAll(readings("M", "x3", ifWithoutElse("x3", 2, 1, keep)));
    return new TargetDocument("", local("Out"), "root", Collections.unmodifiableList(leaves));
  }

  /** Orders, each with up to three Tags and up to three Lines, their counts independent. */
  private static String xml(RandomGenerator random) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1 + random.nextInt(4); o > 0; o--) {
      xml.append("<Order>");
      for (int t = random.nextInt(4); t > 0; t--) {
        xml.append("<Tag>").append(CONDITIONS.get(random.nextInt(CONDITIONS.size())));
        xml.append("</Tag>");
      }
      for (int l = random.nextInt(4); l > 0; l--) {
        String sku = SKUS.get(random.nextInt(SKUS.size()));
        xml.append("<Line>").append(sku.isEmpty() ? "<Sku/>" : "<Sku>" + sku + "</Sku>");
        xml.append("</Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  private static byte[] ours(TargetDocument document, byte[] input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input), out, new Tracer(), CaseFiles.DEFAULT_NOW);
    return out.toByteArray();
  }

  private byte[] saxon(TargetDocument document, byte[] input, String what) throws Exception {
    return SaxonRunner.run(
        XsltExporter.export(document), XsltExporter.helperModule(), input, dir.resolve(what));
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void ifWithoutElseAgreesWithItsExport(long seed) throws Exception {
    byte[] input = xml(new SplittableRandom(seed)).getBytes(UTF_8);
    String keep = seed % 2 == 0 ? "false" : "true";
    TargetDocument document = document(keep);
    IrVerifier.verify(document);

    byte[] ours = ours(document, input);
    // witness: the seed's run writes the root, so the agreement is about a run that completed
    assertThat(new String(ours, UTF_8)).as("seed %d", seed).contains("<Out");

    SaxonRunner.assertAgree(saxon(document, input, "seed-" + seed), ours, "seed " + seed);
  }

  /**
   * Witness for the seeds: some seed has a second Order with a Line, so the F pairing has a value
   * context beyond the condition's last.
   */
  @Test
  void someSeedHasAValueContextBeyondTheConditionsLast() {
    long seeds =
        seeds()
            .filter(seed -> xml(new SplittableRandom(seed)).matches(".*</Order><Order>.*<Line>.*"))
            .count();

    assertThat(seeds).isPositive();
  }

  /**
   * A value context beyond the condition's last is an empty context, not a SUPPRESS slot (F-44):
   * the split after removeContexts writes a for Order 1 and nothing for Order 2's b, on both sides.
   */
  @Test
  void aValueContextBeyondTheConditionsLastIsEmptyOnBothSides() throws Exception {
    byte[] input =
        "<Orders><Order><Tag>true</Tag><Line><Sku>a</Sku></Line></Order><Order><Line><Sku>b</Sku></Line></Order></Orders>"
            .getBytes(UTF_8);
    TargetDocument document = document("false");

    byte[] ours = ours(document, input);
    assertThat(new String(ours, UTF_8)).contains("<FA>a</FA><FB>a</FB><FB/><FC>a</FC><MA>");

    SaxonRunner.assertAgree(saxon(document, input, "beyond"), ours, "beyond");
  }

  /**
   * A condition that is no boolean ends the run with err:FORG0001 at the box on both sides (F-44);
   * witness: the same value against an empty value context, or in a condition context beyond the
   * value's last, is never cast, and both sides run.
   */
  @Test
  void aConditionThatIsNoBooleanStopsBothSidesAtItsBox() throws Exception {
    TargetDocument paired =
        new TargetDocument(
            "", local("Out"), "root", List.of(leaf("V", ifWithoutElse("x1", 2, 2, "false"))));
    byte[] keep =
        "<Orders><Order><Tag>true</Tag><Line><Sku>a</Sku></Line></Order><Order><Tag>keep</Tag><Line><Sku>b</Sku></Line></Order></Orders>"
            .getBytes(UTF_8);

    Throwable error = catchThrowable(() -> ours(paired, keep));
    assertThat(error).isInstanceOf(MappingRunException.class);
    MappingRunException run = (MappingRunException) error;
    assertThat(run.code()).isEqualTo("err:FORG0001");
    assertThat(run.authoringId().value()).isEqualTo("x1");
    SaxonApiException raised =
        SaxonRunner.raised(
            XsltExporter.export(paired),
            XsltExporter.helperModule(),
            keep,
            dir.resolve("keep"),
            "keep");
    SaxonRunner.assertSameError(raised, "err:FORG0001", "keep");
    SaxonRunner.assertNamesItsPlace(raised.getMessage(), "err:FORG0001", "x1", "keep");

    // witness: Order 2's keep has no Line to pair with in S, and lies beyond Sku's one context in
    // M; the F pairing would pair it with a, so it is left out
    byte[] unpaired =
        "<Orders><Order><Tag>true</Tag><Line><Sku>a</Sku></Line></Order><Order><Tag>keep</Tag></Order></Orders>"
            .getBytes(UTF_8);
    List<TargetSlots> leaves = new ArrayList<>();
    leaves.addAll(readings("S", "x1", ifWithoutElse("x1", 2, 2, "false")));
    leaves.addAll(readings("M", "x3", ifWithoutElse("x3", 2, 1, "false")));
    TargetDocument document = new TargetDocument("", local("Out"), "root", List.copyOf(leaves));
    byte[] ours = ours(document, unpaired);
    assertThat(new String(ours, UTF_8)).contains("<SA>a</SA>");
    SaxonRunner.assertAgree(saxon(document, unpaired, "unpaired"), ours, "unpaired");
  }
}
