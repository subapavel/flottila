package io.flottila.mapping.oracle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.runtime.Interpreter;
import io.flottila.mapping.runtime.Tracer;
import io.flottila.mapping.xslt.export.XsltExporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * {@code Lift} agrees with the export's {@code po:lift}, and a target leaf with {@code po:slots},
 * over SUPPRESS (ADR-0006, ADR-0025 since #51), in the style of {@code ExportAgreementTest}: IR is
 * built by hand as lowering gives it, our interpreter runs it, Saxon-HE runs its export, and {@link
 * SaxonRunner#assertAgree} judges. The source is {@code src-orders} without a namespace, the target
 * {@code Out} with its leaf {@code All}. No corpus or differential class: its tests are no case ids
 * (scripts/ratchet.java).
 */
class LiftAgreementTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final QueueType QUEUE = new QueueType();
  private static final FunctionRef CREATE_IF = new FunctionRef("createIf", List.of(QUEUE), QUEUE);
  private static final FunctionRef REMOVE_CONTEXTS =
      new FunctionRef("removeContexts", List.of(QUEUE), QUEUE);
  private static final AuthoringId T1 = new AuthoringId("t1");

  @TempDir Path dir;

  private static Name local(String name) {
    return new Name("", name);
  }

  /** The queue of {@code Orders/…/steps} with the context {@code depth} steps from the root. */
  private static SourceQueue source(int depth, String box, String... steps) {
    List<Name> field = new ArrayList<>(List.of(local("Orders")));
    for (String step : steps) {
      field.add(local(step));
    }
    return new SourceQueue(field, depth, new AuthoringId(box));
  }

  /**
   * {@code <Out>} with {@code <All>{value}</All>} per slot of {@code queue}, at most {@code max}.
   */
  private static TargetDocument all(Expr queue, long max) {
    return new TargetDocument(
        "",
        local("Out"),
        "root",
        List.of(
            new TargetSlots(
                queue,
                new VarRef("root", AtomicType.INTEGER, T1),
                0,
                max,
                "slot",
                "value",
                new ElementConstructor(
                    "",
                    local("All"),
                    List.of(new TextConstructor(new VarRef("value", AtomicType.STRING, T1), T1)),
                    T1),
                T1)));
  }

  private byte[] ours(TargetDocument document, byte[] input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, false)
        .run(new ByteArrayInputStream(input), out, new Tracer(), CaseFiles.DEFAULT_NOW);
    return out.toByteArray();
  }

  private byte[] saxon(TargetDocument document, byte[] input, String what) throws Exception {
    return SaxonRunner.run(
        XsltExporter.export(document), XsltExporter.helperModule(), input, dir.resolve(what));
  }

  /** The text of every {@code All} of {@code document}, in order. */
  private static List<String> allTexts(byte[] document) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Document parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
    NodeList all = parsed.getElementsByTagNameNS("", "All");
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < all.getLength(); i++) {
      texts.add(all.item(i).getTextContent());
    }
    return texts;
  }

  /**
   * ADR-0006: a SUPPRESS slot creates nothing and does not count toward {@code max}. {@code
   * Lift([createIf(Tag), Sku], in2)} in context Orders is SUPPRESS, b, SUPPRESS, d, e; max 2 gives
   * b and d, on both sides.
   */
  @Test
  void suppressSlotsAreSkippedAndDoNotCountTowardMax() throws Exception {
    Lift lift =
        new Lift(
            List.of(
                new FunCall(
                    CREATE_IF, List.of(source(1, "a1", "Order", "Tag")), new AuthoringId("c1")),
                source(1, "a2", "Order", "Line", "Sku")),
            List.of("in1", "in2"),
            new VarRef("in2", AtomicType.STRING, new AuthoringId("l1")),
            new AuthoringId("l1"));
    byte[] input =
        ("<Orders><Order><Tag>false</Tag><Tag>1</Tag><Tag>0</Tag><Tag>TRUE</Tag><Tag>true</Tag>"
                + "<Line><Sku>a</Sku></Line><Line><Sku>b</Sku></Line><Line><Sku>c</Sku></Line>"
                + "<Line><Sku>d</Sku></Line><Line><Sku>e</Sku></Line></Order></Orders>")
            .getBytes(UTF_8);
    TargetDocument two = all(lift, 2);
    TargetDocument unbounded = all(lift, Long.MAX_VALUE);
    IrVerifier.verify(two);

    byte[] ours = ours(two, input);
    // witness: unbounded, the three values past SUPPRESS are all created
    assertThat(new String(ours(unbounded, input), UTF_8))
        .isEqualTo(DECLARATION + "<Out><All>b</All><All>d</All><All>e</All></Out>");
    assertThat(new String(ours, UTF_8))
        .isEqualTo(DECLARATION + "<Out><All>b</All><All>d</All></Out>");

    SaxonRunner.assertAgree(saxon(two, input, "max-2"), ours, "max 2");
    SaxonRunner.assertAgree(
        saxon(unbounded, input, "unbounded"), ours(unbounded, input), "unbounded");
  }

  // --- generated lifts (ADR-0018: a seeded generator, the seed in the case name, no shrinking)

  /**
   * Conditions createIf meets: true for 1 and true in any case, false for 0 and false in any case
   * (ADR-0006 as amended with #133). Before #133 the last three were yes, "" and " true", which
   * createIf now refuses (F-37); the list keeps its size, so each seed draws as it did.
   */
  private static final List<String> CONDITIONS =
      List.of("true", "false", "1", "0", "TRUE", "FALSE", "False", "tRuE");

  /** One Order of the input: its Tags, and per Line its Sku, null for a Line without one. */
  private record Order(List<String> tags, List<String> lines) {}

  private static List<Order> orders(RandomGenerator random) {
    List<Order> orders = new ArrayList<>();
    int skus = 0;
    for (int o = random.nextInt(6); o > 0; o--) {
      List<String> tags = new ArrayList<>();
      for (int t = random.nextInt(5); t > 0; t--) {
        tags.add(CONDITIONS.get(random.nextInt(CONDITIONS.size())));
      }
      List<String> lines = new ArrayList<>();
      for (int l = random.nextInt(5); l > 0; l--) {
        lines.add(random.nextInt(7) == 0 ? null : "s" + ++skus);
      }
      orders.add(new Order(tags, lines));
    }
    return orders;
  }

  private static String xml(List<Order> orders) {
    StringBuilder xml = new StringBuilder("<Orders>");
    for (Order order : orders) {
      xml.append("<Order>");
      for (String tag : order.tags()) {
        xml.append("<Tag>").append(tag).append("</Tag>");
      }
      for (String sku : order.lines()) {
        xml.append(
            sku == null ? "<Line><Qty>1</Qty></Line>" : "<Line><Sku>" + sku + "</Sku></Line>");
      }
      xml.append("</Order>");
    }
    return xml.append("</Orders>").toString();
  }

  /**
   * The reference, ADR-0006 read off the queues: Tag in context Order, Sku in context Order or
   * Line; context i pairs both inputs' context i, empty if either is empty or missing; the longer
   * sets the count and the shorter reuses its last value; a condition that is 0 or false in any
   * case gives SUPPRESS, which removeContexts keeps and the target skips without counting it.
   */
  private static List<String> expected(List<Order> orders, boolean skuPerLine, long max) {
    List<List<String>> tags = new ArrayList<>();
    List<List<String>> skus = new ArrayList<>();
    for (Order order : orders) {
      tags.add(order.tags());
      if (skuPerLine) {
        for (String sku : order.lines()) {
          skus.add(sku == null ? List.of() : List.of(sku));
        }
      } else {
        skus.add(order.lines().stream().filter(s -> s != null).toList());
      }
    }
    List<String> created = new ArrayList<>();
    for (int i = 0; i < Math.max(tags.size(), skus.size()); i++) {
      if (i >= tags.size() || i >= skus.size() || tags.get(i).isEmpty() || skus.get(i).isEmpty()) {
        continue;
      }
      List<String> t = tags.get(i);
      List<String> s = skus.get(i);
      for (int j = 0; j < Math.max(t.size(), s.size()); j++) {
        String condition = t.get(Math.min(j, t.size() - 1));
        if ((condition.equals("1") || condition.equalsIgnoreCase("true")) && created.size() < max) {
          created.add(s.get(Math.min(j, s.size() - 1)));
        }
      }
    }
    return created;
  }

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 200);
  }

  /**
   * A seeded two-input Lift over the SUPPRESS that createIf makes, first or second input, its body
   * the Sku's slot variable, flattened by removeContexts under a target leaf with a seeded max.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void generatedLiftsAgreeWithPoLift(long seed) throws Exception {
    RandomGenerator random = new SplittableRandom(seed);
    List<Order> orders = orders(random);
    boolean skuPerLine = random.nextBoolean();
    boolean createIfFirst = random.nextBoolean();
    long max = List.of(Long.MAX_VALUE, 1L, 2L, 3L).get(random.nextInt(4));
    Expr condition =
        new FunCall(CREATE_IF, List.of(source(2, "a1", "Order", "Tag")), new AuthoringId("c1"));
    Expr sku = source(skuPerLine ? 3 : 2, "a2", "Order", "Line", "Sku");
    AuthoringId l1 = new AuthoringId("l1");
    Lift lift =
        new Lift(
            createIfFirst ? List.of(condition, sku) : List.of(sku, condition),
            List.of("in1", "in2"),
            new VarRef(createIfFirst ? "in2" : "in1", AtomicType.STRING, l1),
            l1);
    TargetDocument document =
        all(new FunCall(REMOVE_CONTEXTS, List.of(lift), new AuthoringId("r1")), max);
    IrVerifier.verify(document);
    byte[] input = xml(orders).getBytes(UTF_8);

    byte[] ours = ours(document, input);
    // witness: ours is ADR-0006's reading, so the agreement is about it
    assertThat(allTexts(ours)).as("seed %d", seed).isEqualTo(expected(orders, skuPerLine, max));
    byte[] saxon = saxon(document, input, "seed-" + seed);

    SaxonRunner.assertAgree(saxon, ours, "seed " + seed);
  }
}
