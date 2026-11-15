package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.LoadResults.lowered;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.id;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.params;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.text;
import static io.flottila.mapping.mapping.TestStructures.ORDER;
import static io.flottila.mapping.mapping.TestStructures.SKELETON;
import static io.flottila.mapping.mapping.TestStructures.order;
import static io.flottila.mapping.mapping.TestStructures.ref;
import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AtomicType;
import io.flottila.mapping.ir.Expr;
import io.flottila.mapping.ir.FunCall;
import io.flottila.mapping.ir.FunctionRef;
import io.flottila.mapping.ir.Lift;
import io.flottila.mapping.ir.Literal;
import io.flottila.mapping.ir.Name;
import io.flottila.mapping.ir.QueueType;
import io.flottila.mapping.ir.SourceQueue;
import io.flottila.mapping.ir.TargetSlots;
import io.flottila.mapping.ir.VarRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lowering by execution kind (ADR-0006 as refined by #51, ADR-0025): a single-value function box
 * with a queue input becomes one {@code Lift(queues, slotVars, body, origin)} per box, whose body
 * is the atomic {@code FunCall} over the slot variables {@code in1} … and then the params as {@code
 * Literal}s; a context function such as {@code createIf} stays a {@code FunCall} over queues. The
 * nodes of a box carry the box's id. {@code Note <- f1} throughout, {@code a1} reading {@code
 * Note}.
 */
class LiftLoweringTest {

  private static final String ORDER_ID = ORDER + "/OrderId";
  private static final String NOTE = ORDER + "/Note";
  private static final QueueType QUEUE = new QueueType();
  private static final FunctionRef CREATE_IF = new FunctionRef("createIf", List.of(QUEUE), QUEUE);
  // the sample single-value function with a param; replaceValue was it until #216 made it a
  // context function (F-45)
  private static final FunctionRef REPLACE_STRING =
      new FunctionRef(
          "replaceString",
          List.of(AtomicType.STRING, AtomicType.STRING, AtomicType.STRING),
          AtomicType.STRING);
  private static final SourceQueue NOTES =
      new SourceQueue(List.of(new Name(SKELETON, "Order"), new Name("", "Note")), 1, id("a1"));

  /**
   * {@code Note <- f1}, where the boxes are given as alternating id and box, beside the mandatory
   * {@code OrderId <- a0}, which the target needs (#65): the queue of Note, the second field.
   */
  private static Object loweredQueue(Object... idThenBox) {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t0", "a0", boxes("a0", source(ORDER_ID))),
                NOTE,
                targetFieldMapping("t1", "f1", boxes(idThenBox))),
            Map.of());
    LowerResult result = Lowering.lower(model, order(), order());
    assertThat(lowered(result).content())
        .hasSize(2)
        .allSatisfy(e -> assertThat(e).isInstanceOf(TargetSlots.class))
        .extracting(Expr::origin)
        .containsExactly(id("t0"), id("t1"));
    return ((TargetSlots) lowered(result).content().getLast()).queue();
  }

  /**
   * The body lowering writes for replaceString's box {@code box}: in1, then the params in the
   * catalogue's order, search and replacement.
   */
  private static FunCall replaceStringBody(String box, String search, String replacement) {
    return new FunCall(
        REPLACE_STRING,
        List.of(
            new VarRef("in1", AtomicType.STRING, id(box)),
            new Literal(search, id(box)),
            new Literal(replacement, id(box))),
        id(box));
  }

  @Test
  void singleLowersToLiftOverAtomicFunCall() {
    // the box gives replacement before search; the body takes the catalogue's order
    assertThat(
            loweredQueue(
                "f1",
                function(
                    "replaceString",
                    inputs("a1"),
                    params("replacement", text("X"), "search", text("a"))),
                "a1",
                source(NOTE)))
        .isEqualTo(
            new Lift(List.of(NOTES), List.of("in1"), replaceStringBody("f1", "a", "X"), id("f1")));
    // over a context function's queue: the Lift's input is the call, one Lift per box
    assertThat(
            loweredQueue(
                "f1",
                function(
                    "replaceString",
                    inputs("c1"),
                    params("search", text("x"), "replacement", text("yes"))),
                "c1",
                function("createIf", inputs("a1"), noParams()),
                "a1",
                source(NOTE)))
        .isEqualTo(
            new Lift(
                List.of(new FunCall(CREATE_IF, List.of(NOTES), id("c1"))),
                List.of("in1"),
                replaceStringBody("f1", "x", "yes"),
                id("f1")));
  }

  @Test
  void contextFunctionStaysFunCall() {
    assertThat(
            loweredQueue("f1", function("createIf", inputs("a1"), noParams()), "a1", source(NOTE)))
        .isEqualTo(new FunCall(CREATE_IF, List.of(NOTES), id("f1")));
    // over a single-value box: the call's input is that box's Lift
    assertThat(
            loweredQueue(
                "f1",
                function("createIf", inputs("v1"), noParams()),
                "v1",
                function(
                    "replaceString",
                    inputs("a1"),
                    params("search", text("a"), "replacement", text("1"))),
                "a1",
                source(NOTE)))
        .isEqualTo(
            new FunCall(
                CREATE_IF,
                List.of(
                    new Lift(
                        List.of(NOTES),
                        List.of("in1"),
                        replaceStringBody("v1", "a", "1"),
                        id("v1"))),
                id("f1")));
  }
}
