package io.flottila.mapping.mapping;

import static io.flottila.mapping.mapping.Models.boxes;
import static io.flottila.mapping.mapping.Models.function;
import static io.flottila.mapping.mapping.Models.inputs;
import static io.flottila.mapping.mapping.Models.model;
import static io.flottila.mapping.mapping.Models.noParams;
import static io.flottila.mapping.mapping.Models.source;
import static io.flottila.mapping.mapping.Models.targetFieldMapping;
import static io.flottila.mapping.mapping.Models.targetFieldMappings;
import static io.flottila.mapping.mapping.Models.udf;
import static io.flottila.mapping.mapping.Models.unresolved;
import static io.flottila.mapping.mapping.TestStructures.ORDER;
import static io.flottila.mapping.mapping.TestStructures.order;
import static io.flottila.mapping.mapping.TestStructures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.flottila.mapping.ir.AuthoringId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code unreachable-box} warning (ADR-0011, ADR-0013, ADR-0027): a box that its target field
 * mapping's {@code result} does not reach through the boxes' inputs is kept and ignored by compile,
 * and named by a warning at its authoring id. The warnings never refuse a model.
 */
class UnreachableBoxTest {

  private static final String ORDER_ID = ORDER + "/OrderId";
  private static final String NOTE = ORDER + "/Note";

  private static MappingModel orderToOrder(Map<String, TargetFieldMapping> mappings) {
    return model(ref(order()), ref(order()), mappings, Map.of());
  }

  /** Each warning as {@code "<severity> <code> at <id>"}; the message only has to be there. */
  private static List<String> unreachable(MappingModel model) {
    List<String> described = new ArrayList<>();
    for (Diagnostic d : Lowering.unreachable(model)) {
      assertThat(d.message()).as("message of %s", d.location()).isNotBlank();
      AtAuthoringId at = (AtAuthoringId) d.location();
      described.add(
          d.severity().name().toLowerCase(Locale.ROOT) + " " + d.code() + " at " + at.id().value());
    }
    return described;
  }

  @Test
  void aBoxTheResultDoesNotReachIsAWarningAtItsIdAndTheReachedBoxesAreNot() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    "f1",
                    boxes(
                        "f1",
                        function("concat", inputs("a1", "a1"), noParams()),
                        "a1",
                        source(ORDER_ID),
                        // reads a reached box, but nothing reads it
                        "f9",
                        function("trim", inputs("a1"), noParams()),
                        // only an unreachable box reads it
                        "a9",
                        source(NOTE),
                        "f8",
                        function("trim", inputs("a9"), noParams())))));

    assertThat(unreachable(model))
        .containsExactlyInAnyOrder(
            "warning unreachable-box at f9",
            "warning unreachable-box at a9",
            "warning unreachable-box at f8");
  }

  @Test
  void everyBoxOfAnUnconnectedResultIsUnreachable() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    null,
                    boxes(
                        "a1",
                        source(ORDER_ID),
                        "f1",
                        function("trim", inputs("a1"), noParams())))));

    assertThat(unreachable(model))
        .containsExactlyInAnyOrder(
            "warning unreachable-box at a1", "warning unreachable-box at f1");
  }

  @Test
  void theResultReachesThroughEveryBoxKindAndPastAnUnconnectedInput() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping(
                    "t1",
                    "u1",
                    boxes(
                        "u1",
                        udf("twice", inputs("r1"), noParams()),
                        "r1",
                        unresolved("FormatNum", Origin.PO, inputs(null, "f1"), noParams()),
                        "f1",
                        function("trim", inputs("a1"), noParams()),
                        "a1",
                        source(ORDER_ID)))));

    assertThat(unreachable(model)).isEmpty();
  }

  @Test
  void eachTargetFieldMappingIsJudgedOnItsOwn() {
    MappingModel model =
        orderToOrder(
            targetFieldMappings(
                ORDER_ID,
                targetFieldMapping("t1", "a1", boxes("a1", source(ORDER_ID))),
                NOTE,
                targetFieldMapping("t2", "a2", boxes("a2", source(NOTE), "a3", source(ORDER_ID)))));

    assertThat(unreachable(model)).containsExactly("warning unreachable-box at a3");
  }

  /**
   * Each box is followed once: a chain of 60 boxes whose two inputs both read the next box is
   * walked in linear time, where following every path takes 2^60 steps. 2 s stays below PIT's own
   * timeout, so a mutant that follows a box again is killed here rather than timed out.
   */
  @Test
  void aBoxThatFeedsTwoInputsIsFollowedOnce() {
    Map<AuthoringId, Box> chain = new HashMap<>();
    int length = 60;
    for (int k = 0; k < length; k++) {
      String next = "f" + (k + 1);
      chain.put(Models.id("f" + k), function("concat", inputs(next, next), noParams()));
    }
    chain.put(Models.id("f" + length), source(ORDER_ID));
    MappingModel model =
        orderToOrder(Map.of(ORDER_ID, Models.targetFieldMapping("t1", "f0", chain)));

    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> assertThat(unreachable(model)).isEmpty());
  }
}
