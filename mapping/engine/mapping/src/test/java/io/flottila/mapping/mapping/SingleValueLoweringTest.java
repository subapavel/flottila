package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.LoadResults.errorAtId;
import static io.flottila.mapping.mapping.LoadResults.lowered;
import static io.flottila.mapping.mapping.LoadResults.refused;
import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.id;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.params;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.table;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.text;
import static io.flottila.mapping.mapping.TestStructures.ADDRESS;
import static io.flottila.mapping.mapping.TestStructures.ORDER;
import static io.flottila.mapping.mapping.TestStructures.SKELETON;
import static io.flottila.mapping.mapping.TestStructures.order;
import static io.flottila.mapping.mapping.TestStructures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import io.flottila.mapping.structure.Occurrence;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Lowering of single values and table params (ADR-0006 and ADR-0026 as amended by #83): a {@code
 * constant} box is an atomic {@code FunCall}; at a context function's input it is {@code
 * AtomicToQueue} at the constant's box, and inside a single-value call over a queue it stands in
 * the {@code Lift}'s body, which takes the queue inputs only. A table param is one {@code Literal}
 * of the table's compact canonical JSON, and a string where a table belongs is refused at the box.
 * {@code Note <- f1} throughout, {@code a1} reading {@code Note} and {@code c1} a constant.
 */
class SingleValueLoweringTest {

  private static final String ORDER_ID = ORDER + "/OrderId";
  private static final String NOTE = ORDER + "/Note";
  private static final QueueType QUEUE = new QueueType();
  private static final SourceQueue NOTES =
      new SourceQueue(List.of(new Name(SKELETON, "Order"), new Name("", "Note")), 1, id("a1"));
  private static final FunctionRef CONSTANT =
      new FunctionRef("constant", List.of(AtomicType.STRING), AtomicType.STRING);
  private static final FunctionRef CONCAT =
      new FunctionRef("concat", List.of(AtomicType.STRING, AtomicType.STRING), AtomicType.STRING);

  /** {@code Note <- f1} with the boxes {@code f1}, {@code a1} and {@code c1}, and OrderId. */
  private static LowerResult lowerNote(StandardFunctionBox f1) {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t0", "a0", boxes("a0", source(ORDER_ID))),
                NOTE,
                targetFieldMapping(
                    "t1",
                    "f1",
                    boxes(
                        "f1",
                        f1,
                        "a1",
                        source(NOTE),
                        "c1",
                        function("constant", inputs(), params("value", text("-x")))))),
            Map.of());
    return Lowering.lower(model, order(), order());
  }

  /** The queue of Note, the second target field, after OrderId. */
  private static Expr noteQueue(LowerResult result) {
    return ((TargetSlots) lowered(result).content().getLast()).queue();
  }

  private static final FunCall C1 =
      new FunCall(CONSTANT, List.of(new Literal("-x", id("c1"))), id("c1"));

  @Test
  void aConstantAtAContextFunctionsInputIsAtomicToQueueAtTheConstantsBox() {
    assertThat(noteQueue(lowerNote(function("count", inputs("c1"), noParams()))))
        .isEqualTo(
            new FunCall(
                new FunctionRef("count", List.of(QUEUE), QUEUE),
                List.of(new AtomicToQueue(C1, id("c1"))),
                id("f1")));
  }

  /**
   * concat over Note and the constant, either way round: a Lift over Note alone, whose slot
   * variable is in1 whichever input Note is, and whose body holds the constant's call itself.
   */
  @Test
  void aConstantBesideAQueueStandsInTheLiftsBody() {
    VarRef in1 = new VarRef("in1", AtomicType.STRING, id("f1"));

    assertThat(noteQueue(lowerNote(function("concat", inputs("a1", "c1"), noParams()))))
        .isEqualTo(
            new Lift(
                List.of(NOTES),
                List.of("in1"),
                new FunCall(CONCAT, List.of(in1, C1), id("f1")),
                id("f1")));
    assertThat(noteQueue(lowerNote(function("concat", inputs("c1", "a1"), noParams()))))
        .isEqualTo(
            new Lift(
                List.of(NOTES),
                List.of("in1"),
                new FunCall(CONCAT, List.of(C1, in1), id("f1")),
                id("f1")));
  }

  /**
   * A table param is one Literal at the box: the table's compact canonical JSON, its keys by UTF-16
   * code unit (worked out by hand from ADR-0010), then the string params, each its default.
   */
  @Test
  void aTableParamIsOneLiteralOfItsCompactCanonicalJson() {
    StandardFunctionBox box =
        function("fixValues", inputs("a1"), params("table", table("b", "2", "a", "\"1\"")));

    assertThat(noteQueue(lowerNote(box)))
        .isInstanceOfSatisfying(
            Lift.class,
            lift ->
                assertThat(((FunCall) lift.body()).args())
                    .containsExactly(
                        new VarRef("in1", AtomicType.STRING, id("f1")),
                        new Literal("{\"a\":\"\\\"1\\\"\",\"b\":\"2\"}", id("f1")),
                        new Literal("key", id("f1")),
                        new Literal("", id("f1"))));
  }

  /** A string where the table belongs, and a table where a string belongs, at the box. */
  @Test
  void aStringForATableParamIsWrongParameterTypeAtTheBox() {
    assertThat(
            refused(lowerNote(function("fixValues", inputs("a1"), params("table", text("a=A"))))))
        .containsExactly(errorAtId("wrong-parameter-type", "f1"));
    assertThat(
            refused(
                lowerNote(function("fixValues", inputs("a1"), params("missing", table("a", "b"))))))
        .containsExactly(errorAtId("wrong-parameter-type", "f1"));
  }

  /**
   * The trace's min and max of a generating function's field are its occurrence (ADR-0006 as
   * amended by #83): OrderId 1..1 and Note 0..unbounded by their mappings' ids, and nothing for a
   * mapping whose path names no target field.
   */
  @Test
  void theOccurrencesAreTheTargetFieldsByTheirMappings() {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t0", "a0", boxes("a0", source(ORDER_ID))),
                NOTE,
                targetFieldMapping(
                    "t1", "c1", boxes("c1", function("constant", inputs(), noParams()))),
                ORDER + "/Missing",
                targetFieldMapping("t2", "a2", boxes("a2", source(ORDER_ID)))),
            Map.of());

    assertThat(Lowering.occurrences(model, order()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                id("t0"), new Occurrence(1, 1),
                id("t1"), new Occurrence(0, Occurrence.UNBOUNDED)));
  }

  // --- the value of a generating field (ADR-0006 as amended by #83)

  private static FunCall constant(String value, String box) {
    return new FunCall(CONSTANT, List.of(new Literal(value, id(box))), id(box));
  }

  /**
   * OrderId from a0, the structure field Address from the constant c1, and Address's leaf Street
   * from the constant c2, so both generating shapes stand in one document.
   */
  private static TargetDocument generatingAddress() {
    MappingModel model =
        model(
            ref(order()),
            ref(order()),
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t0", "a0", boxes("a0", source(ORDER_ID))),
                ADDRESS,
                targetFieldMapping(
                    "t1",
                    "c1",
                    boxes("c1", function("constant", inputs(), params("value", text("g"))))),
                ADDRESS + "/Street",
                targetFieldMapping(
                    "t2",
                    "c2",
                    boxes("c2", function("constant", inputs(), params("value", text("s")))))),
            Map.of());
    return lowered(Lowering.lower(model, order(), order()));
  }

  /** Street's TargetRepeat as lowering gives it: one copy under Address, the value its text. */
  private static TargetRepeat street() {
    return new TargetRepeat(
        1,
        new VarRef("slot2", AtomicType.INTEGER, id("t2")),
        "slot3",
        Optional.empty(),
        new ElementConstructor(
            "",
            new Name("", "Street"),
            List.of(new TextConstructor(constant("s", "c2"), id("t2"))),
            id("t2")),
        id("t2"));
  }

  /** Address's TargetRepeat, under the root after OrderId. */
  private static TargetRepeat address(TargetDocument document) {
    return (TargetRepeat) document.content().getLast();
  }

  /**
   * A structure field has no text, so the constant c1 stays on its TargetRepeat as its value, which
   * each copy evaluates and never writes; Address's element holds its children only, Street's
   * TargetRepeat. The IR verifier accepts it.
   */
  @Test
  void aGeneratingStructureFieldKeepsItsValueOnItsTargetRepeatNotInItsElement() {
    TargetDocument document = generatingAddress();

    assertThat(address(document))
        .isEqualTo(
            new TargetRepeat(
                1,
                new VarRef("root", AtomicType.INTEGER, id("t1")),
                "slot2",
                Optional.of(constant("g", "c1")),
                new ElementConstructor("", new Name("", "Address"), List.of(street()), id("t1")),
                id("t1")));
    assertThatCode(() -> IrVerifier.verify(document)).doesNotThrowAnyException();
  }

  /**
   * A leaf keeps its value in its text, computed once per copy, and its TargetRepeat has none:
   * Street under Address, from c2.
   */
  @Test
  void aGeneratingLeafKeepsItsValueInItsTextOnly() {
    ElementConstructor address = (ElementConstructor) address(generatingAddress()).body();

    assertThat(address.content()).containsExactly(street());
  }

  /** An unmapped 2..2 Pair is created by a TargetRepeat with no value: nothing generates it. */
  @Test
  void anUnmappedRepeatHasNoValue() {
    MappingModel model =
        model(
            ref(TestStructures.orders()),
            ref(TestStructures.repeated()),
            targetFieldMappings(
                "/Doc/Pair/V",
                targetFieldMapping("t1", "a1", boxes("a1", source("/Orders/Order/Tag")))),
            Map.of());

    TargetDocument document =
        lowered(Lowering.lower(model, TestStructures.orders(), TestStructures.repeated()));

    assertThat(document.content())
        .singleElement()
        .isInstanceOfSatisfying(
            TargetRepeat.class,
            pair -> {
              assertThat(pair.origin()).isEqualTo(new AuthoringId("/Doc/Pair"));
              assertThat(pair.value()).isEmpty();
            });
  }
}
