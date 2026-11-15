package dev.rail8.mapping.xslt.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rail8.mapping.ir.AtomicToQueue;
import dev.rail8.mapping.ir.AtomicType;
import dev.rail8.mapping.ir.AuthoringId;
import dev.rail8.mapping.ir.ElementConstructor;
import dev.rail8.mapping.ir.Expr;
import dev.rail8.mapping.ir.FunCall;
import dev.rail8.mapping.ir.FunctionRef;
import dev.rail8.mapping.ir.Literal;
import dev.rail8.mapping.ir.Name;
import dev.rail8.mapping.ir.QueueType;
import dev.rail8.mapping.ir.SourceQueue;
import dev.rail8.mapping.ir.TargetDocument;
import dev.rail8.mapping.ir.TargetSlots;
import dev.rail8.mapping.ir.TextConstructor;
import dev.rail8.mapping.ir.VarRef;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A value where content stands is a compiler bug (XsltExporter), for the nodes of a call as for the
 * others: a {@code Literal}, a {@code FunCall} or an {@code AtomicToQueue} (#83), whose origin is
 * {@code x9}, as an element's content beside the field link's text.
 */
class MisplacedValueTest {

  private static final Name ORDER = new Name("urn:rail8:corpus:skeleton", "Order");
  private static final Name ORDER_ID = new Name("", "OrderId");
  private static final AuthoringId T1 = new AuthoringId("t1");
  private static final AuthoringId X9 = new AuthoringId("x9");
  private static final QueueType QUEUE = new QueueType();

  private static SourceQueue orderIds() {
    return new SourceQueue(List.of(ORDER, ORDER_ID), 1, new AuthoringId("a1"));
  }

  /** The field link {@code OrderId <- OrderId}, with {@code misplaced} after its text. */
  private static TargetDocument withContent(Expr misplaced) {
    TextConstructor text = new TextConstructor(new VarRef("value", AtomicType.STRING, T1), T1);
    return new TargetDocument(
        "ns0",
        ORDER,
        "root",
        List.of(
            new TargetSlots(
                orderIds(),
                new VarRef("root", AtomicType.INTEGER, T1),
                1,
                1,
                "slot",
                "value",
                new ElementConstructor("", ORDER_ID, List.of(text, misplaced), T1),
                T1)));
  }

  static Stream<Arguments> values() {
    return Stream.of(
        Arguments.of("a Literal", new Literal("a", X9)),
        Arguments.of(
            "a FunCall",
            new FunCall(
                new FunctionRef("removeContexts", List.of(QUEUE), QUEUE), List.of(orderIds()), X9)),
        Arguments.of("an AtomicToQueue", new AtomicToQueue(new Literal("a", X9), X9)));
  }

  @ParameterizedTest(name = "{0} as an element's content")
  @MethodSource("values")
  void aValueAsContentIsACompilerBug(String why, Expr value) {
    // witness: the same document with the value inside a text in its place exports
    assertThat(XsltExporter.export(withContent(new TextConstructor(value, X9))))
        .contains("<OrderId>");

    assertThatThrownBy(() -> XsltExporter.export(withContent(value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("x9");
  }
}
