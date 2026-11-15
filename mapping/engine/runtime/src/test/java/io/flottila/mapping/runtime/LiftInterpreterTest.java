package io.flottila.mapping.runtime;

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
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetDocument;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The interpreter runs a {@code Lift} (ADR-0006 as refined by #51) and binds the single-value
 * function of its body by name to a string node (ADR-0025); a target leaf skips a SUPPRESS slot,
 * which does not count toward {@code max}. The IR is built by hand as lowering gives it; outputs
 * are byte literals (ADR-0022), and a traced compile shows a queue with its context boundaries and
 * SUPPRESS, which the output cannot.
 */
class LiftInterpreterTest {

  /** The time every run of this class starts at; no function here reads it. */
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");

  private static final String NS = "urn:flottila:corpus:skeleton";
  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final String ROOT_OPEN = "<ns0:Order xmlns:ns0=\"" + NS + "\">";
  private static final String ROOT_CLOSE = "</ns0:Order>";
  private static final Name ORDER = new Name(NS, "Order");
  private static final QueueType QUEUE = new QueueType();
  private static final long UNBOUNDED = Long.MAX_VALUE;

  private static final FunctionRef REMOVE_CONTEXTS =
      new FunctionRef("removeContexts", List.of(QUEUE), QUEUE);
  private static final FunctionRef CREATE_IF = new FunctionRef("createIf", List.of(QUEUE), QUEUE);
  // the sample single-value function with params; replaceValue was it until #216 made it a
  // context function (F-45)
  private static final FunctionRef REPLACE_STRING =
      new FunctionRef(
          "replaceString",
          List.of(AtomicType.STRING, AtomicType.STRING, AtomicType.STRING),
          AtomicType.STRING);

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  private static Name local(String name) {
    return new Name("", name);
  }

  /** {@code <V>{value}</V>} per slot of {@code queue}'s context 1, at most {@code max}. */
  private static TargetDocument leaf(Expr queue, long max) {
    AuthoringId t1 = id("t1");
    return new TargetDocument(
        "ns0",
        ORDER,
        "root",
        List.of(
            new TargetSlots(
                queue,
                new VarRef("root", AtomicType.INTEGER, t1),
                0,
                max,
                "slot",
                "value",
                new ElementConstructor(
                    "",
                    local("V"),
                    List.of(new TextConstructor(new VarRef("value", AtomicType.STRING, t1), t1)),
                    t1),
                t1)));
  }

  /** The queue of {@code field} under {@code Item}, in Item's context. */
  private static SourceQueue underItem(String field, String box) {
    return new SourceQueue(List.of(ORDER, local("Item"), local(field)), 2, id(box));
  }

  /** The queue of {@code field} directly under the root, in the root's context. */
  private static SourceQueue underRoot(String field, String box) {
    return new SourceQueue(List.of(ORDER, local(field)), 1, id(box));
  }

  private static VarRef slotVar(String name, String box) {
    return new VarRef(name, AtomicType.STRING, id(box));
  }

  private static String run(TargetDocument document, boolean traced, String content, Tracer tracer)
      throws Exception {
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String input = "<s:Order xmlns:s=\"" + NS + "\">" + content + "</s:Order>";
    Interpreter.compile(document, traced)
        .run(new ByteArrayInputStream(input.getBytes(UTF_8)), out, tracer, NOW);
    return out.toString(UTF_8);
  }

  /** The block as contexts of slots, {@code null} for SUPPRESS. */
  private static List<List<String>> contexts(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int c = 0; c < block.contexts(); c++) {
      List<String> context = new ArrayList<>();
      for (int i = block.start(c); i < block.end(c); i++) {
        context.add(block.suppressed(i) ? null : block.value(i));
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

  /**
   * {@code Lift([Name, Code], [in1, in2], in2)} in Item's context, flattened by {@code
   * removeContexts} so the root's one slot reads every context. Slot variable 2 reads input 2; a
   * shorter input reuses its last value inside the context; an empty context in either input gives
   * an empty result context.
   */
  @Test
  void twoInputLiftPairsByPositionAndReusesLast() throws Exception {
    Lift lift =
        new Lift(
            List.of(underItem("Name", "a1"), underItem("Code", "a2")),
            List.of("in1", "in2"),
            slotVar("in2", "l1"),
            id("l1"));
    TargetDocument document =
        leaf(new FunCall(REMOVE_CONTEXTS, List.of(lift), id("r1")), UNBOUNDED);
    String items =
        "<Item><Name>n1</Name><Name>n2</Name><Name>n3</Name><Code>c1</Code></Item>"
            + "<Item><Name>m1</Name><Code>d1</Code><Code>d2</Code></Item>"
            + "<Item><Name>z1</Name></Item>"
            + "<Item><Code>e1</Code></Item>"
            + "<Item><Name>y1</Name><Code>f1</Code></Item>";
    Tracer tracer = new Tracer();

    String output = run(document, true, items, tracer);

    assertThat(output)
        .isEqualTo(
            DECLARATION
                + ROOT_OPEN
                + "<V>c1</V><V>c1</V><V>c1</V><V>d1</V><V>d2</V><V>f1</V>"
                + ROOT_CLOSE);
    assertThat(tracedOnce(tracer, "l1"))
        .containsExactly(
            List.of("c1", "c1", "c1"), List.of("d1", "d2"), List.of(), List.of(), List.of("f1"));
  }

  /**
   * A traced compile reports a Lift's queue once, at its box, whatever the number of slots: the
   * body's call has the box's id too, and is not reported per slot. The untraced compile writes the
   * same bytes.
   */
  @Test
  void liftTracedOnceAtItsBox() throws Exception {
    Lift lift =
        new Lift(
            List.of(underRoot("Note", "a1")),
            List.of("in1"),
            new FunCall(
                REPLACE_STRING,
                List.of(
                    slotVar("in1", "l1"), new Literal("a", id("l1")), new Literal("X", id("l1"))),
                id("l1")),
            id("l1"));
    TargetDocument document = leaf(lift, UNBOUNDED);
    String notes = "<Note>a</Note><Note></Note><Note>c</Note>";
    Tracer tracer = new Tracer();

    String traced = run(document, true, notes, tracer);
    String untraced = run(document, false, notes, new Tracer());

    assertThat(traced).isEqualTo(DECLARATION + ROOT_OPEN + "<V>X</V><V/><V>c</V>" + ROOT_CLOSE);
    assertThat(untraced).isEqualTo(traced);
    assertThat(tracer.values())
        .extracting(Map.Entry::getKey)
        .containsExactlyInAnyOrder(id("a1"), id("l1"));
    assertThat(tracedOnce(tracer, "l1")).containsExactly(List.of("X", "", "c"));
    assertThat(tracedOnce(tracer, "a1")).containsExactly(List.of("a", "", "c"));
  }

  /**
   * ADR-0006: a SUPPRESS slot creates nothing, and SUPPRESS slots do not count toward {@code max}.
   * createIf over {@code false, 1, 0, TRUE, true} gives SUPPRESS, "", SUPPRESS, "", "".
   */
  @Test
  void aTargetLeafSkipsSuppressAndItDoesNotCountTowardMax() throws Exception {
    FunCall createIf = new FunCall(CREATE_IF, List.of(underRoot("Note", "a1")), id("c1"));
    String notes =
        "<Note>false</Note><Note>1</Note><Note>0</Note><Note>TRUE</Note><Note>true</Note>";
    Tracer tracer = new Tracer();

    String unbounded = run(leaf(createIf, UNBOUNDED), true, notes, tracer);
    String two = run(leaf(createIf, 2), false, notes, new Tracer());

    // witness: the queue holds two SUPPRESS slots before the first and the second value
    assertThat(tracedOnce(tracer, "c1")).containsExactly(Arrays.asList(null, "", null, "", ""));
    assertThat(unbounded).isEqualTo(DECLARATION + ROOT_OPEN + "<V/><V/><V/>" + ROOT_CLOSE);
    // counting the first SUPPRESS toward max 2 would stop after one V
    assertThat(two).isEqualTo(DECLARATION + ROOT_OPEN + "<V/><V/>" + ROOT_CLOSE);
  }

  /**
   * The same skip with values to see: {@code Lift([createIf(Note), Note], in2)} is SUPPRESS, 1,
   * SUPPRESS, TRUE, true, and max 2 creates the V of {@code 1} and of {@code TRUE}.
   */
  @Test
  void aTargetLeafWithMaxCreatesTheFirstValuesPastSuppress() throws Exception {
    Lift lift =
        new Lift(
            List.of(
                new FunCall(CREATE_IF, List.of(underRoot("Note", "a1")), id("c1")),
                underRoot("Note", "a2")),
            List.of("in1", "in2"),
            slotVar("in2", "l1"),
            id("l1"));
    String notes =
        "<Note>false</Note><Note>1</Note><Note>0</Note><Note>TRUE</Note><Note>true</Note>";

    String two = run(leaf(lift, 2), false, notes, new Tracer());

    assertThat(two).isEqualTo(DECLARATION + ROOT_OPEN + "<V>1</V><V>TRUE</V>" + ROOT_CLOSE);
  }
}
