package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.ir;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042 "Cost" and ADR-0040 "Lowering": an inlined prelude call lowers to its body with the closure substituted, so
 * {@code map(f)} costs what {@code [.[]|f]} costs; and ADR-0042 "Source" (amending ADR-0031): the nodes lowered from
 * the prelude body take the call site's Source, the closure argument keeps its own. Below the API because the API
 * cannot see the IR. The comparison walks the IR's records generically, so a node kind added later needs no change
 * here: a record is its class and its components with every {@link Source} left out, a {@link JqValue} its JSON text,
 * a list its elements, and any other object its class (a {@code Collector}).
 */
class PreludeInlineTest {

  @Test
  void mapLowersToTheIrOfTheArrayOfTheIteration() {
    assertThat(sourceless(ir("map(.a)"))).as("IR of map(.a), Sources aside").isEqualTo(sourceless(ir("[.[]|.a]")));
  }

  @Test
  void selectLowersToTheIrOfItsIf() {
    assertThat(sourceless(ir("select(.a)"))).as("IR of select(.a), Sources aside").isEqualTo(sourceless(ir("if .a then . else empty end")));
  }

  /**
   * {@code map(.a)} is bytes [0,7), its closure {@code .a} bytes [4,6); in {@code [.[]|.a]} the {@code .a} is [5,7).
   * Pairing the two IRs' Sources in pre-order: the node that is {@code .a} carries [4,6), every other node [0,7).
   */
  @Test
  void preludeNodesTakeTheCallSitesSourceAndTheClosureKeepsItsOwn() {
    List<Source> inlined = sources(ir("map(.a)"));
    List<Source> written = sources(ir("[.[]|.a]"));
    assertThat(inlined).as("Sources of map(.a), one per Source of [.[]|.a]").hasSameSizeAs(written);
    List<Source> expected = new ArrayList<>();
    for (Source s : written) expected.add(s.equals(new Source(5, 7)) ? new Source(4, 6) : new Source(0, 7));
    assertThat(written).as("Sources of [.[]|.a] (its .a is [5,7))").contains(new Source(5, 7));
    assertThat(inlined).as("Sources of map(.a) in pre-order").isEqualTo(expected);
  }

  /** The IR without its Sources, as nested lists. */
  static Object sourceless(Object node) {
    return switch (node) {
      case null -> null;
      case JqValue v -> "json " + v.toJson();
      case List<?> list -> {
        List<Object> out = new ArrayList<>();
        for (Object o : list) out.add(sourceless(o));
        yield out;
      }
      case Source _ -> "source";
      case Record r -> {
        List<Object> out = new ArrayList<>();
        out.add(r.getClass().getName());
        for (RecordComponent c : r.getClass().getRecordComponents()) {
          if (c.getType() != Source.class) out.add(sourceless(component(r, c)));
        }
        yield out;
      }
      case String s -> s;
      case Number n -> n;
      case Boolean b -> b;
      case Enum<?> e -> e;
      default -> node.getClass().getName();
    };
  }

  /** Every Source in the IR, pre-order: a record's own Source before those of its components. */
  static List<Source> sources(Node node) {
    List<Source> out = new ArrayList<>();
    collect(node, out);
    return out;
  }

  private static void collect(Object node, List<Source> out) {
    switch (node) {
      case null -> {}
      case JqValue _ -> {}
      case List<?> list -> {
        for (Object o : list) collect(o, out);
      }
      case Source s -> out.add(s);
      case Record r -> {
        for (RecordComponent c : r.getClass().getRecordComponents()) collect(component(r, c), out);
      }
      default -> {}
    }
  }

  private static Object component(Record r, RecordComponent c) {
    try {
      return c.getAccessor().invoke(r);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("reading " + c + " of " + r.getClass(), e);
    }
  }
}
