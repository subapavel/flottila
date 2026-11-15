package io.flottila.mapping.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicToQueue;
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
import io.flottila.mapping.ir.TargetRepeat;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.TextConstructor;
import io.flottila.mapping.ir.VarRef;
import io.flottila.mapping.xdm.QueueBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The interpreter on single values (ADR-0006 as amended by #83), with IR built by hand as lowering
 * gives it: an atomic {@code FunCall} is a box's single value, which a traced compile reports as
 * {@link Tracer.Atomic} at its box each time it is computed; {@code AtomicToQueue} is one context
 * with one value and reports nothing itself. The facade tests and the corpus cases {@code
 * fn/constant} pin the same end to end.
 */
class SingleValueInterpreterTest {

  private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-04T12:00:00Z");
  private static final QueueType QUEUE = new QueueType();
  private static final Name ORDERS = local("Orders");
  private static final Name ORDER = local("Order");
  private static final Name ID = local("Id");
  private static final FunctionRef CONSTANT =
      new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING);
  private static final String INPUT =
      "<Orders><Order><Id>a</Id></Order><Order><Id>b</Id></Order></Orders>";

  private static Name local(String name) {
    return new Name("", name);
  }

  private static AuthoringId id(String value) {
    return new AuthoringId(value);
  }

  private static FunCall constant(String value, String box) {
    return new FunCall(CONSTANT, List.of(new Literal(value, id(box))), id(box));
  }

  private static ElementConstructor leaf(String name, Expr value, String origin) {
    return new ElementConstructor(
        "", local(name), List.of(new TextConstructor(value, id(origin))), id(origin));
  }

  /** {@code name} per slot of {@code queue} under the root, its value as its text. */
  private static TargetSlots perSlot(String name, Expr queue, String origin) {
    return new TargetSlots(
        queue,
        new VarRef("root", AtomicType.INTEGER, id(origin)),
        0,
        Long.MAX_VALUE,
        "slot2",
        "value2",
        leaf(name, new VarRef("value2", AtomicType.STRING, id(origin)), origin),
        id(origin));
  }

  private static String run(TargetDocument document, boolean traced, Tracer tracer)
      throws Exception {
    IrVerifier.verify(document);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Interpreter.compile(document, traced)
        .run(new ByteArrayInputStream(INPUT.getBytes(UTF_8)), out, tracer, NOW);
    return out.toString(UTF_8);
  }

  /**
   * V from the constant c1 once, under TargetRepeat; N from count over the constant c2 as
   * AtomicToQueue: one context with one value, so count gives 1. The trace holds c1's and c2's
   * values and count's queue, nothing at the AtomicToQueue; an untraced compile reports nothing.
   */
  @Test
  void aConstantIsTracedAtItsBoxAndAtomicToQueueIsOneContextWithOneValue() throws Exception {
    TargetDocument document =
        new TargetDocument(
            "",
            local("Doc"),
            "root",
            List.of(
                new TargetRepeat(
                    1,
                    new VarRef("root", AtomicType.INTEGER, id("t1")),
                    "slot2",
                    leaf("V", constant("x", "c1"), "t1"),
                    id("t1")),
                perSlot(
                    "N",
                    new FunCall(
                        new FunctionRef("count", List.of(QUEUE), QUEUE),
                        List.of(new AtomicToQueue(constant("w", "c2"), id("c2"))),
                        id("n1")),
                    "t2")));
    String expected = DECLARATION + "<Doc><V>x</V><N>1</N></Doc>";
    Tracer untraced = new Tracer();

    assertThat(run(document, false, untraced)).isEqualTo(expected);
    assertThat(untraced.reports()).isEmpty();

    Tracer tracer = new Tracer();
    assertThat(run(document, true, tracer)).isEqualTo(expected);
    assertThat(tracer.reports())
        .filteredOn(Tracer.Atomic.class::isInstance)
        .containsExactly(new Tracer.Atomic(id("c1"), "x"), new Tracer.Atomic(id("c2"), "w"));
    assertThat(tracer.values()).extracting(e -> e.getKey().value()).containsExactly("n1");
    QueueBlock counted = tracer.values().getFirst().getValue();
    assertThat(counted.contexts()).isEqualTo(1);
    assertThat(counted.end(0) - counted.start(0)).isEqualTo(1);
    assertThat(counted.value(counted.start(0))).isEqualTo("1");
  }

  /**
   * In a Lift's body the constant applies to every slot, and is computed and traced per slot; the
   * body's own call is the Lift's box, traced as its queue only.
   */
  @Test
  void aConstantInALiftsBodyIsTracedPerSlot() throws Exception {
    FunCall concat =
        new FunCall(
            new FunctionRef(
                "concat", List.of(AtomicType.STRING, AtomicType.STRING), AtomicType.STRING),
            List.of(new VarRef("in1", AtomicType.STRING, id("l1")), constant("-", "c1")),
            id("l1"));
    TargetDocument document =
        new TargetDocument(
            "",
            local("Doc"),
            "root",
            List.of(
                perSlot(
                    "I",
                    new Lift(
                        List.of(new SourceQueue(List.of(ORDERS, ORDER, ID), 1, id("a1"))),
                        List.of("in1"),
                        concat,
                        id("l1")),
                    "t1")));
    Tracer tracer = new Tracer();

    assertThat(run(document, true, tracer))
        .isEqualTo(DECLARATION + "<Doc><I>a-</I><I>b-</I></Doc>");
    assertThat(tracer.reports())
        .filteredOn(Tracer.Atomic.class::isInstance)
        .containsExactly(new Tracer.Atomic(id("c1"), "-"), new Tracer.Atomic(id("c1"), "-"));
    assertThat(tracer.values()).extracting(e -> e.getKey().value()).containsExactly("a1", "l1");
  }
}
