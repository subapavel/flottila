package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrType;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
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
 * The context functions over the SUPPRESS that createIf makes agree with their {@code po:}
 * functions (ADR-0006 since #51, ADR-0025), in the style of {@code LiftAgreementTest}: IR built by
 * hand, our interpreter and Saxon-HE on its export, {@link SaxonRunner#assertAgree} judges. Each
 * seed writes one leaf per reading under {@code Out}; a function's SUPPRESS is made visible by the
 * removeContexts or collapseContexts after it, as the target creates context 1 only and skips a
 * SUPPRESS slot. No corpus or differential class: its tests are no case ids (scripts/ratchet.java).
 */
class SuppressContextFunctionsAgreementTest {

  private static final QueueType QUEUE = new QueueType();
  private static final AtomicType STRING = AtomicType.STRING;
  private static final AuthoringId T1 = new AuthoringId("t1");
  // the booleans createIf casts (ADR-0006 as amended with #133); the last was "" before #133,
  // which createIf now refuses (F-37), and the list keeps its size, so each seed draws as it did
  private static final List<String> CONDITIONS =
      List.of("true", "false", "1", "0", "TRUE", "FALSE");
  private static final List<String> NUMBERS = List.of("1", "1.0", "-2", "10", "3", " 2 ", "0");
  private static final List<String> WORDS = List.of("a", "B", "b", "A", "", "c", "ä", "Ä");
  private static final List<String> COMPARISONS =
      List.of("lexicographic", "caseInsensitive", "numeric");
  private static final List<String> MODES = List.of("eachValue", "valueChange", "emptyValue");

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

  /** {@code Lift([createIf(Tag), Sku], in2)} per Order: each Line's Sku, or SUPPRESS. */
  private static Lift mixed(String box) {
    AuthoringId id = new AuthoringId(box);
    return new Lift(
        List.of(
            call("createIf", box + "c", List.of(source(2, box + "t", "Tag"))),
            source(2, box + "s", "Line", "Sku")),
        List.of("in1", "in2"),
        new VarRef("in2", STRING, id),
        id);
  }

  /**
   * Orders, each with an Id and as many Tags as Lines, a Line's Sku a number or a word: the Lift of
   * Tag and Sku has a slot per Line, and the Id one value per Order.
   */
  private static String xml(RandomGenerator random, List<String> skus) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (int o = 1 + random.nextInt(4); o > 0; o--) {
      xml.append("<Order><Id>").append(CONDITIONS.get(random.nextInt(4))).append("</Id>");
      int lines = random.nextInt(5);
      List<String> tags = new ArrayList<>();
      for (int l = 0; l < lines; l++) {
        String tag = CONDITIONS.get(random.nextInt(CONDITIONS.size()));
        xml.append("<Tag>").append(tag).append("</Tag>");
        tags.add(tag);
      }
      for (int l = 0; l < lines; l++) {
        String sku = skus.get(random.nextInt(skus.size()));
        xml.append("<Line>").append(sku.isEmpty() ? "<Sku/>" : "<Sku>" + sku + "</Sku>");
        xml.append("</Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  /**
   * One leaf per reading. Seed s takes comparison s mod 3, order (s / 3) mod 2 and mode (s / 6) mod
   * 3, so the seeds 1 to 200 run every comparison with every order and with every mode. The mode is
   * not the comparison's, so emptyValue also meets the words, whose "" it splits at.
   */
  private static TargetDocument document(String comparison, String order, String mode) {
    boolean numeric = comparison.equals("numeric");
    List<Expr> leaves = new ArrayList<>();
    // sort: SUPPRESS the greatest value; A shows the order of the values, B each context's first
    Expr sorted =
        call("sort", "s1", List.of(mixed("m1"), param(comparison, "s1"), param(order, "s1")));
    leaves.add(leaf("A", call("removeContexts", "r1", List.of(sorted))));
    leaves.add(leaf("B", call("collapseContexts", "k1", List.of(sorted))));
    // sortByKey: a SUPPRESS key is the greatest, except as a numeric key; a SUPPRESS value moves
    Expr skus = source(2, "q1", "Line", "Sku");
    List<Expr> byKey =
        numeric
            ? List.of(skus, mixed("m2"), param(comparison, "y1"), param(order, "y1"))
            : List.of(mixed("m2"), skus, param(comparison, "y1"), param(order, "y1"));
    leaves.add(leaf("C", call("removeContexts", "r2", List.of(call("sortByKey", "y1", byKey)))));
    // splitByValue over the Lift and over createIf alone: D and E the contexts, F the bits
    Expr splitMixed = call("splitByValue", "p1", List.of(mixed("m3"), param(mode, "p1")));
    leaves.add(leaf("D", call("collapseContexts", "k2", List.of(splitMixed))));
    Expr conditions = call("createIf", "c1", List.of(source(1, "a1", "Tag")));
    Expr splitConditions = call("splitByValue", "p2", List.of(conditions, param(mode, "p2")));
    leaves.add(leaf("E", call("collapseContexts", "k3", List.of(splitConditions))));
    leaves.add(leaf("F", call("removeContexts", "r3", List.of(splitMixed))));
    // collapseContexts: a SUPPRESS first slot stays SUPPRESS
    Expr perOrder = call("createIf", "c2", List.of(source(2, "a2", "Tag")));
    leaves.add(leaf("G", call("collapseContexts", "k4", List.of(perOrder))));
    // useOneAsMany: a SUPPRESS value repeated, a SUPPRESS occurrence counted; Id in Order's
    // context has the one value per context of the Lift's that useOneAsMany needs (F-48)
    Expr ids = call("createIf", "c3", List.of(source(2, "a3", "Id")));
    Expr repeated =
        call("useOneAsMany", "u1", List.of(ids, mixed("m4"), source(1, "a4", "Line", "Sku")));
    leaves.add(leaf("H", call("removeContexts", "r4", List.of(repeated))));
    // formatByExample: a SUPPRESS of the example counted, a SUPPRESS of the values kept
    Expr flat = call("removeContexts", "r5", List.of(mixed("m5")));
    Expr byExample = call("formatByExample", "f1", List.of(flat, perOrder));
    leaves.add(leaf("I", call("collapseContexts", "k5", List.of(byExample))));
    Expr cut = call("formatByExample", "f2", List.of(conditions, source(2, "a5", "Line", "Sku")));
    leaves.add(leaf("J", call("removeContexts", "r6", List.of(cut))));
    leaves.add(leaf("K", call("collapseContexts", "k6", List.of(cut))));
    // exists and mapWithDefault (#57): a SUPPRESS slot counts like a value, "" is a value (F-48)
    leaves.add(
        leaf(
            "L",
            call("removeContexts", "r7", List.of(call("exists", "e1", List.of(mixed("m6")))))));
    leaves.add(
        leaf("M", call("removeContexts", "r8", List.of(call("exists", "e2", List.of(perOrder))))));
    Expr filled = call("mapWithDefault", "w1", List.of(mixed("m7"), param("O'Brien & <Co>", "w1")));
    leaves.add(leaf("N", call("removeContexts", "r9", List.of(filled))));
    Expr filledConditions = call("mapWithDefault", "w2", List.of(perOrder, param("", "w2")));
    leaves.add(leaf("O", call("removeContexts", "r10", List.of(filledConditions))));
    // Sku in Line's context is one empty context when no Order has a Line (F-17): mapWithDefault
    // fills it, after removeContexts and on the queue itself
    Expr perLine = source(3, "a6", "Line", "Sku");
    Expr flatLines = call("removeContexts", "r11", List.of(perLine));
    leaves.add(leaf("P", call("mapWithDefault", "w3", List.of(flatLines, param("D", "w3")))));
    leaves.add(leaf("Q", call("mapWithDefault", "w4", List.of(perLine, param("D", "w4")))));
    // replaceValue (#216, F-45, observed): every slot the replacement, a SUPPRESS slot included,
    // and an Order without a Tag an empty context still; removeContexts shows each Order's slots.
    // It reads only queues already built here, so no seed draws more than before.
    Expr replaced = call("replaceValue", "v1", List.of(perOrder, param("O'Brien & <Co>", "v1")));
    leaves.add(leaf("R", call("removeContexts", "r12", List.of(replaced))));
    return new TargetDocument("", local("Out"), "root", Collections.unmodifiableList(leaves));
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

  /** The comparison of seed {@code seed}. */
  private static String comparison(long seed) {
    return COMPARISONS.get((int) (seed % 3));
  }

  /** The splitByValue mode of seed {@code seed}, not tied to its comparison. */
  private static String mode(long seed) {
    return MODES.get((int) ((seed / 6) % 3));
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void contextFunctionsOverSuppressAgreeWithTheirExport(long seed) throws Exception {
    RandomGenerator random = new SplittableRandom(seed);
    String comparison = comparison(seed);
    String order = (seed / 3) % 2 == 0 ? "ascending" : "descending";
    String mode = mode(seed);
    byte[] input = xml(random, comparison.equals("numeric") ? NUMBERS : WORDS).getBytes(UTF_8);
    TargetDocument document = document(comparison, order, mode);
    IrVerifier.verify(document);

    byte[] ours = ours(document, input);
    // witness: the seed's run writes the root, so the agreement is about a run that completed
    assertThat(new String(ours, UTF_8)).as("seed %d", seed).contains("<Out");
    String what = "seed " + seed + " " + comparison + " " + order + " " + mode;

    SaxonRunner.assertAgree(saxon(document, input, "seed-" + seed), ours, what);
  }

  /**
   * Witness for the seeds: some seed splits by emptyValue over the words with an empty Sku among
   * them, so the agreement compares a split at "" in the mixed queue (carried review finding 6).
   */
  @Test
  void someSeedSplitsAMixedQueueAtAnEmptyValue() {
    long seeds =
        seeds()
            .filter(seed -> mode(seed).equals("emptyValue"))
            .filter(seed -> !comparison(seed).equals("numeric"))
            .filter(seed -> xml(new SplittableRandom(seed), WORDS).contains("<Sku/>"))
            .count();

    assertThat(seeds).isPositive();
  }

  /**
   * #2 §3: "numeric keys must not be equal to the constant ResultList.SUPPRESS". A numeric
   * sortByKey whose keys hold SUPPRESS stops on both sides: ours throws, the export raises
   * po:SORTBYKEY; compared as strings the same keys sort on both sides.
   */
  @Test
  void aNumericSortByKeyOverASuppressKeyStopsOnBothSides() throws Exception {
    byte[] input =
        ("<Orders><Order><Tag>1</Tag><Tag>0</Tag><Line><Sku>2</Sku></Line>"
                + "<Line><Sku>1</Sku></Line></Order></Orders>")
            .getBytes(UTF_8);
    Expr skus = source(2, "q1", "Line", "Sku");
    TargetDocument lexicographic =
        new TargetDocument(
            "",
            local("Out"),
            "root",
            List.of(
                leaf(
                    "C",
                    call(
                        "sortByKey",
                        "y1",
                        List.of(
                            mixed("m1"),
                            skus,
                            param("lexicographic", "y1"),
                            param("ascending", "y1"))))));
    TargetDocument numeric =
        new TargetDocument(
            "",
            local("Out"),
            "root",
            List.of(
                leaf(
                    "C",
                    call(
                        "sortByKey",
                        "y1",
                        List.of(
                            mixed("m1"),
                            skus,
                            param("numeric", "y1"),
                            param("ascending", "y1"))))));
    // witness: as strings the SUPPRESS key comes last on both sides (F-47)
    byte[] ours = ours(lexicographic, input);
    assertThat(new String(ours, UTF_8)).contains("<Out><C>2</C><C>1</C></Out>");
    SaxonRunner.assertAgree(saxon(lexicographic, input, "lexicographic"), ours, "lexicographic");

    // since #52 the run stops with sortByKey's one code at its box (ADR-0025)
    assertThatThrownBy(() -> ours(numeric, input))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo("po:SORTBYKEY");
              assertThat(e.authoringId()).isEqualTo(new AuthoringId("y1"));
            })
        .hasMessageContaining("SUPPRESS");
    assertThatThrownBy(() -> saxon(numeric, input, "numeric"))
        .isInstanceOfSatisfying(
            SaxonApiException.class,
            e -> assertThat(e.getErrorCode().getLocalName()).isEqualTo("SORTBYKEY"));
  }
}
