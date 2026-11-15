package io.flottila.mapping.ir;

import static io.flottila.mapping.ir.FieldLinkIr.A1;
import static io.flottila.mapping.ir.FieldLinkIr.A2;
import static io.flottila.mapping.ir.FieldLinkIr.NOTE;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER;
import static io.flottila.mapping.ir.FieldLinkIr.ORDER_ID;
import static io.flottila.mapping.ir.FieldLinkIr.T1;
import static io.flottila.mapping.ir.FieldLinkIr.T2;
import static io.flottila.mapping.ir.FieldLinkIr.document;
import static io.flottila.mapping.ir.FieldLinkIr.queue;
import static io.flottila.mapping.ir.FieldLinkIr.rootSlot;
import static io.flottila.mapping.ir.FieldLinkIr.slots;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The IR verifier on {@code Lift(queues, slotVars, body, origin)} (ADR-0006 as refined by #51): a
 * {@code Lift} is a queue; it has at least one input, each a queue, and one slot variable per
 * input; its body sees only its slot variables, each a {@code STRING}, and is a {@code STRING}. A
 * text's value is a {@code STRING}, and an element's or the document's content is content (#51
 * design). Every refused document differs from an accepted one in the one defect it names.
 */
class LiftVerifierTest {

  private static final long UNBOUNDED = Long.MAX_VALUE;
  private static final QueueType QUEUE = new QueueType();
  private static final AuthoringId L1 = new AuthoringId("l1");
  private static final AuthoringId L2 = new AuthoringId("l2");

  private static final FunctionRef REMOVE_CONTEXTS =
      new FunctionRef("removeContexts", List.of(QUEUE), QUEUE);

  /** replaceValue: a single-value function of a value and its param replacement. */
  private static final FunctionRef REPLACE_VALUE =
      new FunctionRef(
          "replaceValue", List.of(AtomicType.STRING, AtomicType.STRING), AtomicType.STRING);

  private static SourceQueue orderIds() {
    return queue(ORDER_ID, 1, A1);
  }

  private static SourceQueue notes() {
    return queue(NOTE, 1, A2);
  }

  /** Slot variable {@code name}, a {@code STRING}. */
  private static VarRef var(String name) {
    return new VarRef(name, AtomicType.STRING, L1);
  }

  private static Lift lift(List<Expr> queues, List<String> slotVars, Expr body) {
    return new Lift(queues, slotVars, body, L1);
  }

  /** The field link with {@code queue} as its target field's queue. */
  private static TargetDocument over(Expr queue) {
    return document(
        slots(ORDER_ID, queue, rootSlot(), 1, 1, "v", new VarRef("v", AtomicType.STRING, T1), T1));
  }

  /**
   * {@code Item} per slot of its queue, its value bound to {@code item}, and inside it {@code Name}
   * per slot of {@code inner}: there {@code item} is a string in scope.
   */
  private static TargetDocument nested(Expr inner) {
    Name item = new Name("", "Item");
    Name name = new Name("", "Name");
    TargetSlots names =
        slots(
            name,
            inner,
            rootSlot(),
            0,
            UNBOUNDED,
            "name",
            new VarRef("name", AtomicType.STRING, T2),
            T2);
    return document(
        new TargetSlots(
            queue(item, 1, A1),
            rootSlot(),
            0,
            UNBOUNDED,
            "slot",
            "item",
            new ElementConstructor("", item, List.of(names), T1),
            T1));
  }

  /** The queue of {@code Name} under {@code Item}, in Item's context. */
  private static SourceQueue names() {
    return new SourceQueue(List.of(ORDER, new Name("", "Item"), new Name("", "Name")), 2, A2);
  }

  static void assertAccepted(TargetDocument document) {
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  static void assertRefused(TargetDocument document) {
    assertThatThrownBy(() -> IrVerifier.verify(document))
        .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
  }

  /** The witnesses: Lifts as lowering writes them, one per box (ADR-0006). */
  @Test
  void acceptsALiftWhereAQueueStands() {
    // one input; the body reads its slot variable
    assertAccepted(over(lift(List.of(orderIds()), List.of("in1"), var("in1"))));
    // two inputs; the body reads the second
    assertAccepted(over(lift(List.of(orderIds(), notes()), List.of("in1", "in2"), var("in2"))));
    // a box's body: the atomic FunCall, the input as a VarRef and then the param as a Literal
    assertAccepted(
        over(
            lift(
                List.of(orderIds()),
                List.of("in1"),
                new FunCall(REPLACE_VALUE, List.of(var("in1"), new Literal("X", L1)), L1))));
    // a Lift is a queue: a context function's input, and another Lift's input
    assertAccepted(
        over(
            new FunCall(
                REMOVE_CONTEXTS,
                List.of(lift(List.of(orderIds()), List.of("in1"), var("in1"))),
                L2)));
    assertAccepted(
        over(
            new Lift(
                List.of(lift(List.of(orderIds()), List.of("in1"), var("in1"))),
                List.of("in1"),
                var("in1"),
                L2)));
    // inside a nested target field
    assertAccepted(nested(lift(List.of(names()), List.of("in1"), var("in1"))));
  }

  @Test
  void verifierRefusesLiftWithoutQueues() {
    assertRefused(over(lift(List.of(), List.of(), new Literal("a", L1))));
  }

  static Stream<Arguments> varCountMismatches() {
    return Stream.of(
        Arguments.of(
            "two slot variables for one input",
            over(lift(List.of(orderIds()), List.of("in1", "in2"), var("in1")))),
        Arguments.of(
            "one slot variable for two inputs",
            over(lift(List.of(orderIds(), notes()), List.of("in1"), var("in1")))),
        Arguments.of(
            "no slot variable for one input",
            over(lift(List.of(orderIds()), List.of(), new Literal("a", L1)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("varCountMismatches")
  void verifierRefusesLiftVarCountMismatch(String why, TargetDocument document) {
    assertRefused(document);
  }

  static Stream<Arguments> outerVarsInBody() {
    return Stream.of(
        Arguments.of(
            "the enclosing target field's value",
            nested(
                lift(List.of(names()), List.of("in1"), new VarRef("item", AtomicType.STRING, L1)))),
        Arguments.of(
            "the enclosing value inside the body's call",
            nested(
                lift(
                    List.of(names()),
                    List.of("in1"),
                    new FunCall(
                        REPLACE_VALUE,
                        List.of(var("in1"), new VarRef("item", AtomicType.STRING, L1)),
                        L1)))),
        Arguments.of(
            "a slot variable of the Lift it is an input of",
            over(
                new Lift(
                    List.of(lift(List.of(orderIds()), List.of("in1"), var("in2")), notes()),
                    List.of("in1", "in2"),
                    var("in1"),
                    L2))));
  }

  /** The body sees only its own slot variables (ADR-0006 since #51). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("outerVarsInBody")
  void verifierRefusesLiftOuterVarInBody(String why, TargetDocument document) {
    // witness: the same documents reading their own slot variable are accepted
    assertAccepted(nested(lift(List.of(names()), List.of("in1"), var("in1"))));
    assertAccepted(
        over(
            new Lift(
                List.of(lift(List.of(orderIds()), List.of("in1"), var("in1")), notes()),
                List.of("in1", "in2"),
                var("in2"),
                L2)));

    assertRefused(document);
  }

  static Stream<Arguments> bodiesThatAreNoString() {
    return Stream.of(
        Arguments.of("a source queue", over(lift(List.of(orderIds()), List.of("in1"), notes()))),
        Arguments.of(
            "a context function's call",
            over(
                lift(
                    List.of(orderIds()),
                    List.of("in1"),
                    new FunCall(REMOVE_CONTEXTS, List.of(notes()), L1)))),
        Arguments.of(
            "a Lift",
            over(
                lift(
                    List.of(orderIds()),
                    List.of("in1"),
                    new Lift(List.of(notes()), List.of("in2"), var("in2"), L2)))),
        Arguments.of(
            "content",
            over(lift(List.of(orderIds()), List.of("in1"), new TextConstructor(var("in1"), L1)))));
  }

  /** The body is of type STRING (ADR-0006 since #51). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("bodiesThatAreNoString")
  void verifierRefusesLiftQueueBody(String why, TargetDocument document) {
    assertRefused(document);
  }

  static Stream<Arguments> atomicInputs() {
    return Stream.of(
        Arguments.of(
            "a literal", over(lift(List.of(new Literal("a", L1)), List.of("in1"), var("in1")))),
        Arguments.of(
            "the root's slot number", over(lift(List.of(rootSlot()), List.of("in1"), var("in1")))),
        Arguments.of(
            "a queue and an atomic call",
            over(
                lift(
                    List.of(
                        orderIds(),
                        new FunCall(
                            REPLACE_VALUE,
                            List.of(new Literal("a", L1), new Literal("b", L1)),
                            L1)),
                    List.of("in1", "in2"),
                    var("in1")))));
  }

  /** A Lift's inputs are queues; a single-value call without one stays an atomic FunCall. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("atomicInputs")
  void verifierRefusesLiftOverAnAtomicInput(String why, TargetDocument document) {
    assertRefused(document);
  }

  /** The field link whose text is {@code value}. */
  private static TargetDocument text(Expr value) {
    return document(
        new TargetSlots(
            orderIds(),
            rootSlot(),
            1,
            1,
            "slot",
            "v",
            new ElementConstructor("", ORDER_ID, List.of(new TextConstructor(value, T1)), T1),
            T1));
  }

  static Stream<Arguments> textsOverNoString() {
    return Stream.of(
        Arguments.of("a source queue", text(notes())),
        Arguments.of(
            "a context function's call", text(new FunCall(REMOVE_CONTEXTS, List.of(notes()), T1))),
        Arguments.of("a Lift", text(lift(List.of(notes()), List.of("in1"), var("in1")))),
        Arguments.of("the root's slot number, an INTEGER", text(rootSlot())));
  }

  /** A text's value is a STRING (#51 design): a queue, or an integer, is refused. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("textsOverNoString")
  void verifierRefusesTextOverQueue(String why, TargetDocument document) {
    // witness: a text over the slot's value, a literal, or an atomic call is accepted
    assertAccepted(text(new VarRef("v", AtomicType.STRING, T1)));
    assertAccepted(text(new Literal("a", T1)));
    assertAccepted(
        text(
            new FunCall(
                REPLACE_VALUE,
                List.of(new VarRef("v", AtomicType.STRING, T1), new Literal("X", T1)),
                T1)));

    assertRefused(document);
  }

  static Stream<Arguments> valuesWhereContentStands() {
    TextConstructor value = new TextConstructor(new VarRef("v", AtomicType.STRING, T1), T1);
    return Stream.of(
        Arguments.of(
            "a literal as an element's content",
            document(
                new TargetSlots(
                    orderIds(),
                    rootSlot(),
                    1,
                    1,
                    "slot",
                    "v",
                    new ElementConstructor("", ORDER_ID, List.of(value, new Literal("a", T1)), T1),
                    T1))),
        Arguments.of("a source queue as the document's content", document(notes())),
        Arguments.of(
            "a Lift as the document's content",
            document(lift(List.of(notes()), List.of("in1"), var("in1")))));
  }

  /** An element's and the document's content is content, never a value (#51 design). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("valuesWhereContentStands")
  void verifierRefusesAValueWhereContentStands(String why, TargetDocument document) {
    assertRefused(document);
  }
}
