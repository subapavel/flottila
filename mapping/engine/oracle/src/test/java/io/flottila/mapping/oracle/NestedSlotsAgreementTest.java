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
import io.flottila.mapping.ir.IrVerifier;
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
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import net.sf.saxon.s9api.SaxonApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code TargetSlots} ≡ {@code po:slots} + {@code po:at-least} at depth four (ADR-0006 since #52):
 * a seeded {@code Doc/Grp/Row/Val} target over seeded Orders, each level a source field in one of
 * its contexts or createIf over it, with seeded min and max, runs on our runtime and, exported, on
 * Saxon-HE. Both write the same document, or both raise {@code po:MINOCCURS} with the same QName,
 * and both messages name the target field mapping that fell short. The generator makes SUPPRESS,
 * truncated slots, missing and empty contexts and min shortfalls; the runtime's
 * TargetSlotsInterpreterTest checks the same shape against a reference. This class is no corpus or
 * differential class, so the ratchet does not read its tests as case ids (scripts/ratchet.java).
 */
class NestedSlotsAgreementTest {

  private static final String MINOCCURS = "po:MINOCCURS";
  private static final String CREATEIF = "po:CREATEIF";
  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final QueueType QUEUE = new QueueType();
  private static final FunctionRef CREATE_IF = new FunctionRef("createIf", List.of(QUEUE), QUEUE);

  private static final Name ORDERS = local("Orders");
  private static final Name ORDER = local("Order");
  private static final Name ID = local("Id");
  private static final Name LINE = local("Line");
  private static final Name SKU = local("Sku");
  private static final Name QTY = local("Qty");

  @TempDir Path dir;

  private static Name local(String name) {
    return new Name("", name);
  }

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  // --- the generator (ADR-0018: a seeded generator, the seed in the case name, no shrinking)

  private static String pick(RandomGenerator random, String... values) {
    return values[random.nextInt(values.length)];
  }

  /**
   * Up to three Orders, each with an Id or none and up to three Lines, each with a Sku and a Qty or
   * none; the values include createIf's true (1, TRUE, true) and false (0) ones, and ones that tell
   * the contexts apart ({@code o1}, {@code s11}, {@code q11}), which are no boolean ({@link
   * #castable}).
   */
  private static String orders(RandomGenerator random) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1, count = random.nextInt(4); o <= count; o++) {
      xml.append("<Order>");
      if (random.nextInt(4) != 0) {
        xml.append("<Id>").append(pick(random, "1", "0", "o" + o)).append("</Id>");
      }
      for (int l = 1, lines = random.nextInt(4); l <= lines; l++) {
        xml.append("<Line>");
        if (random.nextInt(4) != 0) {
          xml.append("<Sku>").append(pick(random, "1", "0", "TRUE", "s" + o + l)).append("</Sku>");
        }
        if (random.nextInt(3) != 0) {
          xml.append("<Qty>").append(pick(random, "1", "true", "q" + o + l)).append("</Qty>");
        }
        xml.append("</Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  /**
   * {@code input} with each {@code field} value that is no boolean ({@code prefix} and digits) as
   * {@code false}, where createIf reads the field: createIf reads false as SUPPRESS, as it read
   * those values before #133, and raises {@code po:CREATEIF} on them since (F-37).
   */
  private static String castable(String input, boolean cast, String field, String prefix) {
    return cast
        ? input.replaceAll(
            "<" + field + ">" + prefix + "\\d+</" + field + ">",
            "<" + field + ">false</" + field + ">")
        : input;
  }

  private static long max(RandomGenerator random, long min) {
    long[] maxes = {1, 2, 3, UNBOUNDED};
    long max;
    do {
      max = maxes[random.nextInt(maxes.length)];
    } while (max < Math.max(min, 1));
    return max;
  }

  /** The queue of {@code steps} in the context at {@code depth}, or createIf over it. */
  private static Expr queue(boolean createIf, String box, int depth, Name... steps) {
    SourceQueue source = new SourceQueue(List.of(steps), depth, id("a" + box));
    return createIf ? new FunCall(CREATE_IF, List.of(source), id("c" + box)) : source;
  }

  /** The parent's slot number of a field at {@code depth}: the root's, or {@code slot<d - 1>}. */
  private static VarRef parentSlot(int depth, AuthoringId origin) {
    return new VarRef(depth == 2 ? "root" : "slot" + (depth - 1), AtomicType.INTEGER, origin);
  }

  private static TargetSlots slots(
      Expr queue, int depth, long min, long max, String mapping, Expr content) {
    AuthoringId origin = id(mapping);
    return new TargetSlots(
        queue,
        parentSlot(depth, origin),
        min,
        max,
        "slot" + depth,
        "value" + depth,
        content,
        origin);
  }

  /** The seeded input and target of {@code seed}. */
  private record Generated(String input, TargetDocument document) {}

  private static Generated generated(long seed) {
    RandomGenerator random = new SplittableRandom(seed);
    String drawn = orders(random);
    // Grp: Id in the root's context, or createIf over it
    long grpMin = random.nextInt(2);
    boolean grpCreateIf = random.nextBoolean();
    Expr grpQueue = queue(grpCreateIf, "1", 1, ORDERS, ORDER, ID);
    long grpMax = max(random, grpMin);
    // Row: Sku in Order's context, createIf over it, or Sku in the root's context
    long rowMin = random.nextInt(3);
    int rowChoice = random.nextInt(3);
    Expr rowQueue = queue(rowChoice == 1, "2", rowChoice == 2 ? 1 : 2, ORDERS, ORDER, LINE, SKU);
    long rowMax = max(random, rowMin);
    // Val: Qty in Line's context, in Order's context, or createIf over Qty in Line's context
    long valMin = random.nextInt(2);
    int valChoice = random.nextInt(3);
    Expr valQueue = queue(valChoice == 2, "3", valChoice == 1 ? 2 : 3, ORDERS, ORDER, LINE, QTY);
    long valMax = max(random, valMin);
    String input =
        castable(
            castable(castable(drawn, grpCreateIf, "Id", "o"), rowChoice == 1, "Sku", "s"),
            valChoice == 2,
            "Qty",
            "q");

    AuthoringId t3 = id("t3");
    TargetSlots val =
        slots(
            valQueue,
            4,
            valMin,
            valMax,
            "t3",
            new ElementConstructor(
                "",
                local("Val"),
                List.of(new TextConstructor(new VarRef("value4", AtomicType.STRING, t3), t3)),
                t3));
    TargetSlots row =
        slots(
            rowQueue,
            3,
            rowMin,
            rowMax,
            "t2",
            new ElementConstructor("", local("Row"), List.of(val), id("t2")));
    TargetSlots grp =
        slots(
            grpQueue,
            2,
            grpMin,
            grpMax,
            "t1",
            new ElementConstructor("", local("Grp"), List.of(row), id("t1")));
    TargetDocument document = new TargetDocument("", local("Doc"), "root", List.of(grp));
    // the IR is well-formed, so what the test compares is the two back ends
    IrVerifier.verify(document);
    return new Generated(input, document);
  }

  /** Ours: the document run by the interpreter. */
  private static byte[] ours(Generated generated) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(generated.document(), false)
        .run(
            new ByteArrayInputStream(generated.input().getBytes(UTF_8)),
            out,
            new Tracer(),
            CaseFiles.DEFAULT_NOW);
    return out.toByteArray();
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void generatedNestedTargetsAgreeWithPoSlots(long seed) throws Exception {
    Generated generated = generated(seed);
    String what = "seed " + seed + ", input " + generated.input();
    String stylesheet = XsltExporter.export(generated.document());
    byte[] input = generated.input().getBytes(UTF_8);
    Throwable thrown = catchThrowable(() -> ours(generated));

    if (thrown != null) {
      // ours stops only with a minOccurs shortfall here: the generator makes no other data error
      assertThat(thrown)
          .as(what)
          .isInstanceOfSatisfying(
              MappingRunException.class, e -> assertThat(e.code()).isEqualTo(MINOCCURS));
      SaxonApiException saxon =
          SaxonRunner.raised(stylesheet, XsltExporter.helperModule(), input, dir, what);
      SaxonRunner.assertSameError(saxon, MINOCCURS, what);
      // both name the target field mapping that fell short (CODING_STANDARDS, mapping location)
      String mapping =
          "the target field mapping " + ((MappingRunException) thrown).authoringId().value();
      assertThat(thrown.getMessage()).as(what).contains(mapping + " creates ");
      assertThat(saxon.getMessage()).as(what).contains(mapping + " creates ");
    } else {
      byte[] saxon = SaxonRunner.run(stylesheet, XsltExporter.helperModule(), input, dir);
      SaxonRunner.assertAgree(saxon, ours(generated), what);
    }
  }

  /**
   * Witness for the agreement: over the seeds, our runtime writes documents with Vals and raises
   * po:MINOCCURS at more than one level, so the property compares both branches.
   */
  @Test
  void theSeedsMakeDocumentsAndShortfallsAtSeveralLevels() throws Exception {
    int withVal = 0;
    Set<String> shortfalls = new TreeSet<>();
    for (long seed : seeds().toArray()) {
      Generated generated = generated(seed);
      Throwable thrown = catchThrowable(() -> ours(generated));
      if (thrown instanceof MappingRunException e) {
        shortfalls.add(e.authoringId().value());
      } else if (thrown == null && new String(ours(generated), UTF_8).contains("<Val>")) {
        withVal++;
      }
    }

    assertThat(withVal).as("seeds whose document has a Val").isGreaterThanOrEqualTo(20);
    assertThat(shortfalls).as("mappings that fall short of min").hasSizeGreaterThanOrEqualTo(2);
  }

  /**
   * The seed's target over its input as drawn, before {@link #castable}: a createIf may read a
   * value that is no boolean ({@code o1}, {@code s11}, {@code q11}).
   */
  private static Generated drawn(long seed) {
    return new Generated(orders(new SplittableRandom(seed)), generated(seed).document());
  }

  /**
   * F-37 (ADR-0006 as amended with #133): over the inputs as drawn, both back ends stop with the
   * same error at the same place, {@code po:CREATEIF} at a createIf box or {@code po:MINOCCURS} at
   * a target field mapping, or write the same document. A queue is read on first use (ADR-0006), so
   * a createIf under a parent that is never created raises on neither.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void generatedNestedTargetsAgreeOnTheCastError(long seed) throws Exception {
    Generated generated = drawn(seed);
    String what = "seed " + seed + ", input " + generated.input();
    String stylesheet = XsltExporter.export(generated.document());
    byte[] input = generated.input().getBytes(UTF_8);
    Throwable thrown = catchThrowable(() -> ours(generated));

    if (thrown != null) {
      assertThat(thrown).as(what).isInstanceOf(MappingRunException.class);
      MappingRunException ours = (MappingRunException) thrown;
      assertThat(ours.code()).as(what).isIn(CREATEIF, MINOCCURS);
      SaxonApiException saxon =
          SaxonRunner.raised(stylesheet, XsltExporter.helperModule(), input, dir, what);
      SaxonRunner.assertSameError(saxon, ours.code(), what);
      String place = ours.authoringId().value();
      SaxonRunner.assertNamesItsPlace(ours.getMessage(), ours.code(), place, what);
      SaxonRunner.assertNamesItsPlace(saxon.getMessage(), ours.code(), place, what);
    } else {
      byte[] saxon = SaxonRunner.run(stylesheet, XsltExporter.helperModule(), input, dir);
      SaxonRunner.assertAgree(saxon, ours(generated), what);
    }
  }

  /**
   * Witness for the cast agreement: over the seeds, our runtime raises po:CREATEIF at more than one
   * createIf box, and some seed whose createIf reads a value that is no boolean still writes its
   * document, as that queue is never read.
   */
  @Test
  void theDrawnSeedsRaiseTheCastErrorAtSeveralBoxes() throws Exception {
    Set<String> boxes = new TreeSet<>();
    int unread = 0;
    for (long seed : seeds().toArray()) {
      Generated generated = drawn(seed);
      Throwable thrown = catchThrowable(() -> ours(generated));
      if (thrown instanceof MappingRunException e && e.code().equals(CREATEIF)) {
        boxes.add(e.authoringId().value());
      } else if (thrown == null && !generated.input().equals(generated(seed).input())) {
        unread++;
      }
    }

    assertThat(boxes).as("createIf boxes that raise po:CREATEIF").hasSizeGreaterThanOrEqualTo(2);
    assertThat(unread).as("seeds whose unread createIf meets no boolean").isPositive();
  }
}
