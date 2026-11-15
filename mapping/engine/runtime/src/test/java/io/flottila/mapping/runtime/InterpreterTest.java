package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.AttributeConstructor;
import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.ElementConstructor;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.IrVerifier;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The closure-compiled interpreter (ADR-0021) at {@code Interpreter.compile} and {@code
 * ExecutableMapping.run}: it projects the source queues in one StAX pass, creates the root once
 * with slot number 1, and creates a target field per slot of the context its parent's slot number
 * names (ADR-0006). Inputs are written by hand; expected outputs are byte literals (ADR-0022).
 *
 * <p>{@code Tracer.values()} lists every report in the order made, as an entry of the box's
 * authoring id and the queue block, so a second report of one box is visible; since #53 {@code
 * Tracer.occurrences()} lists every report of a traced {@code TargetSlots}, one per parent slot.
 */
class InterpreterTest {

  /** The time every run of this class starts at; no function here reads it. */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String ROOT_OPEN = "<ns0:Order xmlns:ns0=\"" + NS + "\">";
  private static final String ROOT_CLOSE = "</ns0:Order>";
  private static final String EMPTY_ROOT = "<ns0:Order xmlns:ns0=\"" + NS + "\"/>";
  private static final Name ORDER = new Name(NS, "Order");
  private static final String ROOT_SLOT = "root";
  private static final long UNBOUNDED = Long.MAX_VALUE;

  // --- IR, built by hand as lowering gives it

  private static Name local(String name) {
    return new Name("", name);
  }

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  private static TargetDocument document(Expr... content) {
    return new TargetDocument("ns0", ORDER, ROOT_SLOT, List.of(content));
  }

  /** A target leaf under the root, one per slot of {@code queue}'s context 1. */
  private static TargetSlots leaf(
      String target, SourceQueue queue, long min, long max, String mapping) {
    AuthoringId origin = id(mapping);
    return new TargetSlots(
        queue,
        new VarRef(ROOT_SLOT, AtomicType.INTEGER, origin),
        min,
        max,
        "slot",
        "v",
        new ElementConstructor(
            "",
            local(target),
            List.of(new TextConstructor(new VarRef("v", AtomicType.STRING, origin), origin)),
            origin),
        origin);
  }

  /** The queue of a leaf directly under the root, in the root's context. */
  private static SourceQueue rootLeaf(String field, String box) {
    return new SourceQueue(List.of(ORDER, local(field)), 1, id(box));
  }

  /** {@code OrderId <- OrderId}, 1..1: the skeleton's field link. */
  private static TargetDocument orderIdLink() {
    return document(leaf("OrderId", rootLeaf("OrderId", "a1"), 1, 1, "t1"));
  }

  /**
   * The same field link at 0..1, for the mechanics of an empty or missing context without {@code
   * po:MINOCCURS} (#52).
   */
  private static TargetDocument optionalOrderIdLink() {
    return document(leaf("OrderId", rootLeaf("OrderId", "a1"), 0, 1, "t1"));
  }

  /**
   * Asserts that {@code run} raises {@code po:MINOCCURS} at the target field mapping {@code t1}.
   */
  private static void assertMinOccursAtT1(ThrowingCallable run) {
    assertThatThrownBy(run)
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo("po:MINOCCURS");
              assertThat(e.authoringId()).isEqualTo(id("t1"));
              assertThat(e.getMessage()).isNotBlank();
            });
  }

  /** Three field links; {@code a1} and {@code a3} read the same source field. */
  private static TargetDocument threeLinks() {
    return document(
        leaf("OrderId", rootLeaf("OrderId", "a1"), 1, 1, "t1"),
        leaf("Note", rootLeaf("Note", "a2"), 0, UNBOUNDED, "t2"),
        leaf("Copy", rootLeaf("OrderId", "a3"), 0, 1, "t3"));
  }

  private static final String THREE_LINKS_INPUT =
      "<s:Order xmlns:s=\"" + NS + "\"><OrderId>1</OrderId><Note>x</Note><Note>y</Note></s:Order>";
  private static final String THREE_LINKS_OUTPUT =
      DECLARATION
          + ROOT_OPEN
          + "<OrderId>1</OrderId><Note>x</Note><Note>y</Note><Copy>1</Copy>"
          + ROOT_CLOSE;

  // --- running

  private static byte[] run(TargetDocument document, boolean traced, byte[] input, Tracer tracer)
      throws Exception {
    ExecutableMapping mapping = Interpreter.compile(document, traced);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    mapping.run(new ByteArrayInputStream(input), out, tracer, NOW);
    return out.toByteArray();
  }

  private static String run(TargetDocument document, String input) throws Exception {
    return new String(run(document, false, input.getBytes(UTF_8), new Tracer()), UTF_8);
  }

  private static String sourceOrder(String content) {
    return "<s:Order xmlns:s=\"" + NS + "\">" + content + "</s:Order>";
  }

  /** The block as contexts of values. */
  private static List<List<String>> contexts(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int c = 0; c < block.contexts(); c++) {
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.value(i));
      }
      contexts.add(context);
    }
    return contexts;
  }

  /** The one block traced for {@code box}; fails unless it was reported exactly once. */
  private static List<List<String>> tracedOnce(Tracer tracer, String box) {
    List<QueueBlock> blocks =
        tracer.values().stream()
            .filter(e -> e.getKey().equals(id(box)))
            .map(Map.Entry::getValue)
            .toList();
    assertThat(blocks).as("reports of %s", box).hasSize(1);
    return contexts(blocks.getFirst());
  }

  // --- target creation (ADR-0006)

  @Test
  void theRootIsCreatedOnceAndAFieldPerSlotOfContextOne() throws Exception {
    // Name under Item under Order, in Item's context: two contexts, [x1, x2] and [y]
    TargetDocument names =
        document(
            leaf(
                "Name",
                new SourceQueue(List.of(ORDER, local("Item"), local("Name")), 2, id("a1")),
                0,
                UNBOUNDED,
                "t1"));
    String input =
        sourceOrder("<Item><Name>x1</Name><Name>x2</Name></Item><Item><Name>y</Name></Item>");
    Tracer tracer = new Tracer();

    byte[] out = run(names, true, input.getBytes(UTF_8), tracer);

    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of("x1", "x2"), List.of("y"));
    // the root has slot number 1, so only context 1 is read
    assertThat(new String(out, UTF_8))
        .isEqualTo(DECLARATION + ROOT_OPEN + "<Name>x1</Name><Name>x2</Name>" + ROOT_CLOSE);
  }

  @Test
  void anEmptyContextCreatesNoFieldButWritesTheRoot() throws Exception {
    Tracer tracer = new Tracer();

    byte[] out = run(optionalOrderIdLink(), true, sourceOrder("").getBytes(UTF_8), tracer);

    // one occurrence of the context field without the field: one empty context, not none
    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of());
    assertThat(new String(out, UTF_8)).isEqualTo(DECLARATION + EMPTY_ROOT);
  }

  /**
   * A source queue has at least one context (ADR-0004 as amended, F-17, observed #31 d3): Name in
   * Item's context, and no Item occurs, so the queue is one empty context {@code {0,0}}, not none.
   * Where Item occurs, each occurrence is one context and nothing is added.
   */
  @Test
  void aContextElementThatNeverOccursGivesOneEmptyContext() throws Exception {
    TargetDocument names =
        document(
            leaf(
                "Name",
                new SourceQueue(List.of(ORDER, local("Item"), local("Name")), 2, id("a1")),
                0,
                UNBOUNDED,
                "t1"));
    Tracer absent = new Tracer();
    Tracer present = new Tracer();

    byte[] out = run(names, true, sourceOrder("<Other/>").getBytes(UTF_8), absent);
    run(names, true, sourceOrder("<Item/><Item><Name>y</Name></Item>").getBytes(UTF_8), present);

    assertThat(tracedOnce(absent, "a1")).containsExactly(List.of());
    assertThat(new String(out, UTF_8)).isEqualTo(DECLARATION + EMPTY_ROOT);
    assertThat(tracedOnce(present, "a1")).containsExactly(List.of(), List.of("y"));
  }

  /**
   * The same input under the required field: no occurrence is created, fewer than its min of 1, so
   * the run raises {@code po:MINOCCURS} at the target field mapping (ADR-0006 since #52).
   */
  @Test
  void anEmptyContextUnderARequiredFieldRaisesMinOccurs() {
    assertMinOccursAtT1(() -> run(orderIdLink(), sourceOrder("")));
  }

  @Test
  void creationStopsAfterMax() throws Exception {
    TargetDocument atMostTwo = document(leaf("Note", rootLeaf("Note", "a1"), 0, 2, "t1"));

    String out = run(atMostTwo, sourceOrder("<Note>a</Note><Note>b</Note><Note>c</Note>"));

    assertThat(out)
        .isEqualTo(DECLARATION + ROOT_OPEN + "<Note>a</Note><Note>b</Note>" + ROOT_CLOSE);
  }

  static Stream<Arguments> wrongRoots() {
    return Stream.of(
        Arguments.of(
            "another local name",
            "<s:Invoice xmlns:s=\"" + NS + "\"><OrderId>1</OrderId></s:Invoice>"),
        Arguments.of(
            "the root's local name in another namespace",
            "<o:Order xmlns:o=\"urn:other\"><OrderId>1</OrderId></o:Order>"),
        Arguments.of(
            "the root's local name without a namespace", "<Order><OrderId>1</OrderId></Order>"));
  }

  /**
   * The source root is not there, so each of its queues is one empty context (ADR-0004 as amended,
   * F-17): the root slot 1 names it, it holds no value, and the root is written without the field,
   * as for an empty source.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wrongRoots")
  void anInputWhoseRootIsNotTheSourceRootGivesWhatAnEmptySourceGives(String why, String input)
      throws Exception {
    String emptySource = run(optionalOrderIdLink(), sourceOrder(""));

    assertThat(emptySource).isEqualTo(DECLARATION + EMPTY_ROOT);
    assertThat(run(optionalOrderIdLink(), input)).isEqualTo(emptySource);
  }

  /**
   * Under the required field the one empty context creates no occurrence, fewer than its min of 1
   * (ADR-0006 since #52), so the run raises {@code po:MINOCCURS} at the target field mapping, as an
   * empty source does.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wrongRoots")
  void anInputWhoseRootIsNotTheSourceRootRaisesMinOccursUnderARequiredField(
      String why, String input) {
    assertMinOccursAtT1(() -> run(orderIdLink(), input));
  }

  @Test
  void anUnmappedSourceFieldIsNeverCreated() throws Exception {
    String input =
        "<s:Order xmlns:s=\""
            + NS
            + "\" channel=\"web\">"
            + "<OrderId>4711</OrderId><Quantity>3</Quantity><Note>n</Note>"
            // the same local name in another namespace is another field
            + "<o:OrderId xmlns:o=\"urn:other\">9</o:OrderId>"
            + "</s:Order>";

    String out = run(orderIdLink(), input);

    assertThat(out).isEqualTo(DECLARATION + ROOT_OPEN + "<OrderId>4711</OrderId>" + ROOT_CLOSE);
  }

  @Test
  void anEmptyLeafIsTheValueEmptyString() throws Exception {
    Tracer selfClosed = new Tracer();
    Tracer openClose = new Tracer();

    byte[] fromSelfClosed =
        run(orderIdLink(), true, sourceOrder("<OrderId/>").getBytes(UTF_8), selfClosed);
    byte[] fromOpenClose =
        run(orderIdLink(), true, sourceOrder("<OrderId></OrderId>").getBytes(UTF_8), openClose);

    assertThat(tracedOnce(selfClosed, "a1")).containsExactly(List.of(""));
    assertThat(tracedOnce(openClose, "a1")).containsExactly(List.of(""));
    // "" is a value, so the field is created, and empty
    String expected = DECLARATION + ROOT_OPEN + "<OrderId/>" + ROOT_CLOSE;
    assertThat(new String(fromSelfClosed, UTF_8)).isEqualTo(expected);
    assertThat(new String(fromOpenClose, UTF_8)).isEqualTo(expected);
  }

  @Test
  void whitespaceInsideAValueIsCopied() throws Exception {
    // indentation between elements is no value; inside a leaf every character is, with
    // references and CDATA resolved, and a CR from &#13; kept (an input CR the parser normalises)
    String input =
        "<s:Order xmlns:s=\""
            + NS
            + "\">\n  <OrderId>  PO\t4711 \n x&amp;y<![CDATA[ <z> ]]>&#13; </OrderId>\n</s:Order>\n";

    String out = run(orderIdLink(), input);

    assertThat(out)
        .isEqualTo(
            DECLARATION
                + ROOT_OPEN
                + "<OrderId>  PO\t4711 \n x&amp;y &lt;z&gt; &#13; </OrderId>"
                + ROOT_CLOSE);
  }

  // --- source attributes (#102): an attribute's queue is its own, beside an element of its name

  /** The queue of an attribute, by its element's steps and then its name. */
  private static SourceQueue attributeQueue(List<Name> field, int contextDepth, String box) {
    return new SourceQueue(field, contextDepth, false, true, id(box));
  }

  /**
   * {@code /Order/code} and {@code /Order/@code} are two fields with two queues (ADR-0007): the
   * element's text per occurrence, and the attribute's value in its element's context. The same
   * local name in another namespace is a third field, and the attribute of a leaf element is read
   * with the element's text.
   */
  @Test
  void elementAndAttributeOfOneNameAreTwoQueues() throws Exception {
    TargetDocument document =
        document(
            leaf("Elem", rootLeaf("code", "a1"), 0, UNBOUNDED, "t1"),
            leaf(
                "Attr", attributeQueue(List.of(ORDER, local("code")), 1, "a2"), 0, UNBOUNDED, "t2"),
            leaf(
                "Lang",
                attributeQueue(List.of(ORDER, local("code"), local("lang")), 1, "a3"),
                0,
                UNBOUNDED,
                "t3"));
    String input =
        "<s:Order xmlns:s=\""
            + NS
            + "\" xmlns:o=\"urn:other\" code=\"A\" o:code=\"other\">"
            + "<code lang=\"en\">E1</code><code>E2</code><code lang=\"de\">E3</code>"
            + "</s:Order>";
    Tracer tracer = new Tracer();

    byte[] out = run(document, true, utf8(input), tracer);

    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of("E1", "E2", "E3"));
    assertThat(tracedOnce(tracer, "a2")).containsExactly(List.of("A"));
    // lang in the root's context: one context, the second code adds nothing
    assertThat(tracedOnce(tracer, "a3")).containsExactly(List.of("en", "de"));
    assertThat(new String(out, UTF_8))
        .isEqualTo(
            DECLARATION
                + ROOT_OPEN
                + "<Elem>E1</Elem><Elem>E2</Elem><Elem>E3</Elem>"
                + "<Attr>A</Attr>"
                + "<Lang>en</Lang><Lang>de</Lang>"
                + ROOT_CLOSE);
  }

  /**
   * An attribute's default context is its element (ADR-0007, #2 §1): one context per occurrence of
   * the element, and an element without the attribute gives an empty context, not a skipped one and
   * not {@code ""}. A present empty attribute is the value {@code ""}.
   */
  @Test
  void absentAttributeGivesEmptyContext() throws Exception {
    SourceQueue ids = attributeQueue(List.of(ORDER, local("Item"), local("id")), 2, "a1");
    TargetDocument document = document(leaf("Id", ids, 0, UNBOUNDED, "t1"));
    String input =
        sourceOrder("<Item id=\"1\"/><Item/><Item id=\"\"><id>no</id></Item><Item id=\"4\"/>");
    Tracer tracer = new Tracer();

    byte[] out = run(document, true, utf8(input), tracer);

    assertThat(tracedOnce(tracer, "a1"))
        .containsExactly(List.of("1"), List.of(), List.of(""), List.of("4"));
    // the root's slot 1 reads context 1
    assertThat(new String(out, UTF_8))
        .isEqualTo(DECLARATION + ROOT_OPEN + "<Id>1</Id>" + ROOT_CLOSE);
  }

  // --- target attributes (ADR-0006 since #102)

  /**
   * Two attribute items on the root, as lowering gives them: {@code code} per slot of OrderId's
   * queue through {@code TargetSlots} (0..1), and the qualified {@code ns1:lang} through a 1-copy
   * {@code TargetRepeat} with a constant value. Both stand before the child Note, and both are
   * written on the root's start tag, before its content; {@code ns1} is declared where {@code lang}
   * needs it (ADR-0022).
   */
  @Test
  void targetAttributesAreWrittenOnTheirElementBeforeItsContent() throws Exception {
    AuthoringId t1 = id("t1");
    AuthoringId t2 = id("t2");
    TargetDocument document =
        document(
            new TargetSlots(
                rootLeaf("OrderId", "a1"),
                new VarRef(ROOT_SLOT, AtomicType.INTEGER, t1),
                0,
                1,
                "slot",
                "v",
                new AttributeConstructor(
                    "", local("code"), new VarRef("v", AtomicType.STRING, t1), t1),
                t1),
            new TargetRepeat(
                1,
                new VarRef(ROOT_SLOT, AtomicType.INTEGER, t2),
                "copy",
                new AttributeConstructor(
                    "ns1", new Name("urn:b", "lang"), new Literal("en", t2), t2),
                t2),
            leaf("Note", rootLeaf("Note", "a3"), 0, UNBOUNDED, "t3"));
    IrVerifier.verify(document);

    String out = run(document, sourceOrder("<OrderId>4711</OrderId><Note>x</Note>"));

    assertThat(out)
        .isEqualTo(
            DECLARATION
                + "<ns0:Order xmlns:ns0=\""
                + NS
                + "\" code=\"4711\" xmlns:ns1=\"urn:b\" ns1:lang=\"en\">"
                + "<Note>x</Note>"
                + ROOT_CLOSE);
  }

  // --- tracing (ADR-0006, ADR-0021)

  @Test
  void aTracedCompileReportsEachSourceQueueOnce() throws Exception {
    Tracer tracer = new Tracer();

    run(threeLinks(), true, THREE_LINKS_INPUT.getBytes(UTF_8), tracer);

    assertThat(tracer.values())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder(id("a1"), id("a2"), id("a3"));
    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of("1"));
    assertThat(tracedOnce(tracer, "a2")).containsExactly(List.of("x", "y"));
    assertThat(tracedOnce(tracer, "a3")).containsExactly(List.of("1"));
  }

  @Test
  void anUntracedCompileNeverCallsTheTracer() throws Exception {
    Tracer untraced = new Tracer();
    Tracer witness = new Tracer();

    run(threeLinks(), false, THREE_LINKS_INPUT.getBytes(UTF_8), untraced);
    run(threeLinks(), true, THREE_LINKS_INPUT.getBytes(UTF_8), witness);

    assertThat(untraced.values()).isEmpty();
    // since #53 an untraced compile builds bare TargetSlots, which report no occurrences either
    assertThat(untraced.occurrences()).isEmpty();
    // the same mapping and input do report when traced, so the silence is the untraced compile's:
    // a queue per box and, per target field mapping, its root's one parent slot
    assertThat(witness.values()).hasSize(3);
    assertThat(witness.occurrences())
        .extracting(o -> o.field().value() + " " + o.parentSlot())
        .containsExactly("t1 1", "t2 1", "t3 1");
  }

  /**
   * A context function (ADR-0025): {@code removeContexts} over {@code Name} in {@code Item}'s
   * context, as lowering gives it, bound by its catalogue name. A traced compile reports its queue
   * once, at its box, beside its input's.
   */
  @Test
  void aTracedFunCallReportsOnceAtItsBox() throws Exception {
    QueueType queue = new QueueType();
    AuthoringId t1 = id("t1");
    FunCall removeContexts =
        new FunCall(
            new FunctionRef("removeContexts", List.of(queue), queue),
            List.of(new SourceQueue(List.of(ORDER, local("Item"), local("Name")), 2, id("a1"))),
            id("r1"));
    TargetDocument names =
        document(
            new TargetSlots(
                removeContexts,
                new VarRef(ROOT_SLOT, AtomicType.INTEGER, t1),
                0,
                UNBOUNDED,
                "slot",
                "v",
                new ElementConstructor(
                    "",
                    local("Name"),
                    List.of(new TextConstructor(new VarRef("v", AtomicType.STRING, t1), t1)),
                    t1),
                t1));
    byte[] input =
        utf8(sourceOrder("<Item><Name>x1</Name><Name>x2</Name></Item><Item><Name>y</Name></Item>"));
    Tracer tracer = new Tracer();

    byte[] traced = run(names, true, input, tracer);
    byte[] untraced = run(names, false, input, new Tracer());

    assertThat(tracedOnce(tracer, "r1")).containsExactly(List.of("x1", "x2", "y"));
    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of("x1", "x2"), List.of("y"));
    // the root's one slot reads context 1, which now holds every Name, not only the first Item's
    String all =
        DECLARATION + ROOT_OPEN + "<Name>x1</Name><Name>x2</Name><Name>y</Name>" + ROOT_CLOSE;
    assertThat(new String(traced, UTF_8)).isEqualTo(all);
    assertThat(untraced).isEqualTo(traced);
  }

  // --- a generating block (#217, F-46): copyValue's result goes into every copy of every parent

  private static final QueueType QUEUE = new QueueType();

  /** A field of the source Orders/Order/Line, in context {@code depth}, at {@code box}. */
  private static SourceQueue line(String field, int depth, String box) {
    return new SourceQueue(
        List.of(local("Orders"), local("Order"), local("Line"), local(field)), depth, id(box));
  }

  private static FunCall copyValue(Expr values, String position, String box) {
    return new FunCall(
        new FunctionRef("copyValue", List.of(QUEUE, AtomicType.STRING), QUEUE),
        List.of(values, new Literal(position, id(box))),
        id(box));
  }

  /** {@code copies} of {@code <V>} per Item, each its block's value: T2. */
  private static TargetRepeat generatedV(long copies, FunCall block) {
    AuthoringId t2 = id("T2");
    return new TargetRepeat(
        copies,
        new VarRef("slot2", AtomicType.INTEGER, t2),
        "slot3",
        Optional.of(block),
        Optional.of("value3"),
        new ElementConstructor(
            "",
            local("V"),
            List.of(new TextConstructor(new VarRef("value3", AtomicType.STRING, t2), t2)),
            t2),
        t2);
  }

  /** {@code <Out>} with one {@code <Item>} per Order (T1), holding {@code content}. */
  private static TargetDocument items(Expr... content) {
    AuthoringId t1 = id("T1");
    return new TargetDocument(
        "",
        local("Out"),
        ROOT_SLOT,
        List.of(
            new TargetSlots(
                new SourceQueue(List.of(local("Orders"), local("Order")), 1, true, id("S1")),
                new VarRef(ROOT_SLOT, AtomicType.INTEGER, t1),
                0,
                UNBOUNDED,
                "slot2",
                "value2",
                new ElementConstructor("", local("Item"), List.of(content), t1),
                t1)));
  }

  /** fn/copyValue/001's Orders: Sku a, b, c | d | e, f, Qty 1 to 6. */
  private static final String SKUS =
      "<Orders><Order><Line><Sku>a</Sku><Qty>1</Qty></Line><Line><Sku>b</Sku><Qty>2</Qty></Line>"
          + "<Line><Sku>c</Sku><Qty>3</Qty></Line></Order>"
          + "<Order><Line><Sku>d</Sku><Qty>4</Qty></Line></Order>"
          + "<Order><Line><Sku>e</Sku><Qty>5</Qty></Line><Line><Sku>f</Sku><Qty>6</Qty></Line>"
          + "</Order></Orders>";

  /**
   * fn/copyValue/001–003: the block's one value goes into every copy under every Item, an
   * attribute's and a structure field's too, whose copies keep their slot numbers {@code (p - 1) *
   * copies + j}: Gen's k-th copy reads Line k's Qty.
   */
  @Test
  void aGeneratingBlockWritesItsFirstSlotIntoEveryCopyOfEveryParent() throws Exception {
    AuthoringId t3 = id("T3");
    AuthoringId t4 = id("T4");
    AuthoringId t5 = id("T5");
    TargetRepeat at =
        new TargetRepeat(
            1,
            new VarRef("slot2", AtomicType.INTEGER, t3),
            "slot3",
            Optional.of(copyValue(line("Sku", 2, "S3"), "3", "F3")),
            Optional.of("value3"),
            new AttributeConstructor(
                "", local("at"), new VarRef("value3", AtomicType.STRING, t3), t3),
            t3);
    TargetSlots qty =
        new TargetSlots(
            line("Qty", 3, "S5"),
            new VarRef("slot3", AtomicType.INTEGER, t5),
            0,
            UNBOUNDED,
            "slot4",
            "value4",
            new ElementConstructor(
                "",
                local("Qty"),
                List.of(new TextConstructor(new VarRef("value4", AtomicType.STRING, t5), t5)),
                t5),
            t5);
    TargetRepeat gen =
        new TargetRepeat(
            2,
            new VarRef("slot2", AtomicType.INTEGER, t4),
            "slot3",
            Optional.of(copyValue(line("Sku", 2, "S4"), "0", "F4")),
            Optional.of("value3"),
            new ElementConstructor("", local("Gen"), List.of(qty), t4),
            t4);
    TargetDocument document =
        items(at, generatedV(2, copyValue(line("Sku", 2, "S2"), "1", "F1")), gen);
    IrVerifier.verify(document);

    byte[] untraced = run(document, false, utf8(SKUS), new Tracer());
    byte[] traced = run(document, true, utf8(SKUS), new Tracer());

    String item =
        "<Item at=\"d\"><V>b</V><V>b</V><Gen><Qty>%s</Qty></Gen><Gen><Qty>%s</Qty></Gen></Item>";
    assertThat(new String(untraced, UTF_8))
        .isEqualTo(
            DECLARATION
                + "<Out>"
                + item.formatted("1", "2")
                + item.formatted("3", "4")
                + item.formatted("5", "6")
                + "</Out>");
    assertThat(traced).isEqualTo(untraced);
  }

  /** fn/copyValue/004 and the edge past the last slot: no copy writes, under any Item. */
  @Test
  void aSuppressedOrMissingFirstSlotWritesNoCopy() throws Exception {
    FunCall tags =
        new FunCall(
            new FunctionRef("createIf", List.of(QUEUE), QUEUE),
            List.of(
                new SourceQueue(
                    List.of(local("Orders"), local("Order"), local("Tag")), 2, id("S2"))),
            id("C1"));
    String input =
        "<Orders><Order><Tag>true</Tag><Tag>false</Tag><Tag>true</Tag></Order>"
            + "<Order><Tag>false</Tag><Tag>true</Tag></Order><Order/></Orders>";
    String empty = DECLARATION + "<Out><Item/><Item/><Item/></Out>";

    // createIf(Tag) is "", SUPPRESS, "" | SUPPRESS, "" | (empty): slot 1 is SUPPRESS
    TargetDocument suppressed = items(generatedV(2, copyValue(tags, "1", "F1")));
    Tracer tracer = new Tracer();
    assertThat(new String(run(suppressed, true, utf8(input), tracer), UTF_8)).isEqualTo(empty);
    assertThat(tracer.reports())
        .filteredOn(Tracer.Repeat.class::isInstance)
        .containsExactly(
            new Tracer.Repeat(id("T2"), 1, 2, true),
            new Tracer.Repeat(id("T2"), 2, 2, true),
            new Tracer.Repeat(id("T2"), 3, 2, true));
    // witness: slot 0 is "", a value, written into every copy
    assertThat(run(items(generatedV(2, copyValue(tags, "0", "F1"))), input))
        .isEqualTo(DECLARATION + "<Out>" + "<Item><V/><V/></Item>".repeat(3) + "</Out>");

    // past the last slot of the queue, and a queue without slots: one empty context
    assertThat(run(items(generatedV(2, copyValue(line("Sku", 2, "S2"), "6", "F1"))), SKUS))
        .isEqualTo(DECLARATION + "<Out>" + "<Item/>".repeat(3) + "</Out>");
    assertThat(run(items(generatedV(2, copyValue(line("Sku", 2, "S2"), "5", "F1"))), SKUS))
        .as("witness: slot 5 is the last one")
        .isEqualTo(DECLARATION + "<Out>" + "<Item><V>f</V><V>f</V></Item>".repeat(3) + "</Out>");
    assertThat(
            run(
                items(generatedV(2, copyValue(line("Sku", 2, "S2"), "0", "F1"))),
                "<Orders><Order/><Order/></Orders>"))
        .isEqualTo(DECLARATION + "<Out><Item/><Item/></Out>");
  }

  /**
   * The block is read once per run, on the first copy's read: its box is traced once with its one
   * context, and a run without a parent occurrence never reads it, so a malformed position raises
   * only when a copy reads the block.
   */
  @Test
  void aGeneratingBlockIsReadOncePerRunAndNeverWithoutAParent() throws Exception {
    TargetDocument document = items(generatedV(2, copyValue(line("Sku", 2, "S2"), "1", "F1")));
    Tracer tracer = new Tracer();
    run(document, true, utf8(SKUS), tracer);
    assertThat(tracedOnce(tracer, "F1")).containsExactly(List.of("b"));
    assertThat(tracedOnce(tracer, "S2"))
        .containsExactly(List.of("a", "b", "c"), List.of("d"), List.of("e", "f"));

    Tracer none = new Tracer();
    assertThat(new String(run(document, true, utf8("<Orders/>"), none), UTF_8))
        .isEqualTo(DECLARATION + "<Out/>");
    assertThat(none.values()).extracting(Map.Entry::getKey).containsExactly(id("S1"));

    TargetDocument malformed = items(generatedV(2, copyValue(line("Sku", 2, "S2"), "-1", "F1")));
    assertThat(run(malformed, "<Orders/>")).isEqualTo(DECLARATION + "<Out/>");
    assertThatThrownBy(() -> run(malformed, "<Orders><Order/></Orders>"))
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo(RuntimeErrors.FORG0001);
              assertThat(e.authoringId()).isEqualTo(id("F1"));
            });
  }

  @Test
  void tracedAndUntracedWriteIdenticalBytes() throws Exception {
    byte[] input = THREE_LINKS_INPUT.getBytes(UTF_8);

    byte[] untraced = run(threeLinks(), false, input, new Tracer());
    byte[] traced = run(threeLinks(), true, input, new Tracer());

    assertThat(traced).isEqualTo(untraced);
    assertThat(new String(untraced, UTF_8)).isEqualTo(THREE_LINKS_OUTPUT);
  }

  // --- refused input

  static Stream<Arguments> doctypes() {
    return Stream.of(
        Arguments.of(
            "an internal subset with an entity",
            "<!DOCTYPE s:Order [<!ENTITY id \"boom\">]>" + sourceOrder("<OrderId>&id;</OrderId>")),
        Arguments.of("a bare DOCTYPE", "<!DOCTYPE s:Order>" + sourceOrder("<OrderId>1</OrderId>")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("doctypes")
  void aDoctypeInTheInputIsRefused(String why, String input) throws Exception {
    assertThatThrownBy(() -> run(orderIdLink(), input)).isInstanceOf(MalformedInputException.class);
    // witness: the same document without the DOCTYPE runs
    assertThat(run(orderIdLink(), sourceOrder("<OrderId>1</OrderId>")))
        .isEqualTo(DECLARATION + ROOT_OPEN + "<OrderId>1</OrderId>" + ROOT_CLOSE);
  }

  static Stream<Arguments> externalDoctypes() {
    return Stream.of(
        Arguments.of("an external DTD", "<!DOCTYPE s:Order SYSTEM \"%s\">"),
        Arguments.of(
            "an external parameter entity",
            "<!DOCTYPE s:Order [<!ENTITY %% ext SYSTEM \"%s\"> %%ext;]>"));
  }

  /**
   * The DOCTYPE refusal comes before anything the DOCTYPE names is read. The file it names is no
   * DTD, so a parser that read it would fail on it, and that failure would be the refusal's cause.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("externalDoctypes")
  void aDoctypeIsRefusedWithoutReadingWhatItNames(String why, String doctype, @TempDir Path dir)
      throws Exception {
    Path notADtd = Files.writeString(dir.resolve("not-a.dtd"), "this file is no DTD <\n");
    String input = doctype.formatted(notADtd.toUri()) + sourceOrder("<OrderId>1</OrderId>");

    assertThatThrownBy(() -> run(orderIdLink(), input))
        .isInstanceOf(MalformedInputException.class)
        // the refusal of the DOCTYPE itself, not a parse error of the file
        .hasNoCause();
  }

  static Stream<Arguments> malformed() {
    return Stream.of(
        Arguments.of("no bytes at all", new byte[0]),
        Arguments.of("text, no element", utf8("not xml")),
        Arguments.of(
            "an unclosed root", utf8("<s:Order xmlns:s=\"" + NS + "\"><OrderId>1</OrderId>")),
        Arguments.of("a mismatched end tag", utf8(sourceOrder("<OrderId>1</Quantity>"))),
        Arguments.of("an unbound prefix", utf8("<x:Order><OrderId>1</OrderId></x:Order>")),
        Arguments.of("two roots", utf8(sourceOrder("") + sourceOrder(""))),
        Arguments.of(
            "a duplicate attribute in a field the mapping skips",
            utf8(sourceOrder("<OrderId>1</OrderId><Note a=\"1\" a=\"2\">n</Note>"))),
        Arguments.of("bad UTF-8 in a value", badUtf8()));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  /** {@code <OrderId>} holding C3 28, a lead byte without its continuation byte. */
  private static byte[] badUtf8() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(utf8("<s:Order xmlns:s=\"" + NS + "\"><OrderId>"));
    out.writeBytes(HexFormat.of().parseHex("c328"));
    out.writeBytes(utf8("</OrderId></s:Order>"));
    return out.toByteArray();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformed")
  void malformedInputThrowsMalformedInputException(String why, byte[] input) {
    assertThatThrownBy(() -> run(orderIdLink(), false, input, new Tracer()))
        .isInstanceOf(MalformedInputException.class)
        // a checked exception (#26)
        .isNotInstanceOf(RuntimeException.class);
  }

  // --- interruption (ADR-0021)

  @Test
  void anInterruptedRunStops() throws Exception {
    TargetDocument notes = document(leaf("Note", rootLeaf("Note", "a1"), 0, UNBOUNDED, "t1"));
    String input = sourceOrder("<Note>a</Note><Note>b</Note><Note>c</Note>");
    // witness: without the interrupt the same run completes
    assertThat(run(notes, input))
        .isEqualTo(
            DECLARATION + ROOT_OPEN + "<Note>a</Note><Note>b</Note><Note>c</Note>" + ROOT_CLOSE);

    Thread.currentThread().interrupt();
    try {
      // no input error: CancellationException (ExecutableMapping.run, ADR-0021)
      assertThatThrownBy(() -> run(notes, input))
          .isNotInstanceOf(MalformedInputException.class)
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }
  }

  /** An input that interrupts the thread reading it at its first read. */
  private static final class InterruptingInput extends InputStream {

    private final ByteArrayInputStream bytes;
    private boolean interrupted;

    InterruptingInput(byte[] content) {
      bytes = new ByteArrayInputStream(content);
    }

    @Override
    public int read() {
      interruptOnce();
      return bytes.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      interruptOnce();
      return bytes.read(buffer, offset, length);
    }

    private void interruptOnce() {
      if (!interrupted) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }
    }

    /** Whether every byte of the input was read. */
    boolean readToTheEnd() {
      return bytes.available() == 0;
    }
  }

  @Test
  void anInterruptWhileTheInputIsParsedStopsTheParseAndWritesNothing() throws Exception {
    // about 4.4 MB of a field the mapping skips, far more than a parser reads at once
    String input = sourceOrder("<Quantity>1</Quantity>".repeat(200_000));
    // witness: without the interrupt the same run reads it all and writes the root; OrderId is
    // optional here, so the run ends without po:MINOCCURS (#52)
    assertThat(run(optionalOrderIdLink(), input)).isEqualTo(DECLARATION + EMPTY_ROOT);
    ExecutableMapping mapping = Interpreter.compile(optionalOrderIdLink(), false);
    InterruptingInput in = new InterruptingInput(input.getBytes(UTF_8));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try {
      assertThatThrownBy(() -> mapping.run(in, out, new Tracer(), NOW))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }

    assertThat(out.toByteArray()).as("output of the cancelled run").isEmpty();
    assertThat(in.readToTheEnd()).as("the parse went on to the end of the input").isFalse();
  }

  /** An output that interrupts the thread writing it at its first write. */
  private static final class InterruptingOutput extends OutputStream {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean interrupted;

    @Override
    public void write(int b) {
      interruptOnce();
      bytes.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
      interruptOnce();
      bytes.write(buffer, offset, length);
    }

    private void interruptOnce() {
      if (!interrupted) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }
    }

    /** The number of bytes written. */
    int size() {
      return bytes.size();
    }
  }

  /**
   * The input is read whole before anything is written, so an interrupt that comes with the first
   * bytes of output finds the run inside its loop over slots: about 1.4 MB of target fields, far
   * more than a writer holds back.
   */
  @Test
  void anInterruptWhileTheTargetIsWrittenStopsTheLoopOverSlots() throws Exception {
    TargetDocument notes = document(leaf("Note", rootLeaf("Note", "a1"), 0, UNBOUNDED, "t1"));
    byte[] input = utf8(sourceOrder("<Note>x</Note>".repeat(100_000)));
    String all = DECLARATION + ROOT_OPEN + "<Note>x</Note>".repeat(100_000) + ROOT_CLOSE;
    // witness: without the interrupt the same run writes every field
    assertThat(new String(run(notes, false, input, new Tracer()), UTF_8)).isEqualTo(all);
    ExecutableMapping mapping = Interpreter.compile(notes, false);
    InterruptingOutput out = new InterruptingOutput();

    try {
      assertThatThrownBy(() -> mapping.run(new ByteArrayInputStream(input), out, new Tracer(), NOW))
          .isInstanceOf(CancellationException.class);
    } finally {
      Thread.interrupted();
    }

    // stopped inside the loop, not after it: most of the fields never reached the output (#49)
    assertThat(out.size()).as("bytes written by the cancelled run").isLessThan(all.length() / 2);
  }
}
