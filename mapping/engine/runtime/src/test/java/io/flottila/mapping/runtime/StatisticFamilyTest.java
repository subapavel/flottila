package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.QueueBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The statistic family's bindings (#75), called through {@link QueueLibrary} as the interpreter
 * calls them: the algebra's data error becomes {@code err:FORG0001} at the function box, and {@code
 * average}'s division undefined {@code err:FOAR0001} (F-45); the numeric params of {@code index}
 * and {@code counter} are parsed once, a malformed one raising {@code err:FORG0001} at the box on
 * every run. The corpus cases {@code fn/sum} to {@code fn/counter} pin the same through the facade
 * and against Saxon-HE.
 */
class StatisticFamilyTest {

  private static final AuthoringId BOX = new AuthoringId("F1");

  /** A queue of one context per array, a null a SUPPRESS slot. */
  private static QueueBlock queue(String[]... contexts) {
    QueueBlock.Builder builder = new QueueBlock.Builder();
    for (String[] context : contexts) {
      builder.newContext();
      for (String value : context) {
        if (value == null) {
          builder.appendSuppress();
        } else {
          builder.append(value);
        }
      }
    }
    return builder.build();
  }

  private static String[] c(String... values) {
    return values;
  }

  /** The slots of each context of {@code block}, {@code "S"} for SUPPRESS. */
  private static List<List<String>> read(QueueBlock block) {
    List<List<String>> contexts = new ArrayList<>();
    for (int k = 0; k < block.contexts(); k++) {
      List<String> slots = new ArrayList<>();
      for (int i = block.start(k); i < block.end(k); i++) {
        slots.add(block.suppressed(i) ? "S" : block.value(i));
      }
      contexts.add(slots);
    }
    return contexts;
  }

  private static QueueBlock call(String name, QueueBlock input, String... params) {
    Node.OfQueue node = QueueLibrary.bind(name, BOX, List.of(frame -> input), List.of(params));
    return node.eval(null);
  }

  private static void assertDataError(Runnable call, String message) {
    assertDataError(call, "err:FORG0001", message);
  }

  private static void assertDataError(Runnable call, String code, String message) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            MappingRunException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.authoringId()).isEqualTo(BOX);
              assertThat(e.getMessage()).isEqualTo("the function box F1: " + message);
            });
  }

  @Test
  void sumAverageAndCountGiveOneValuePerContext() {
    QueueBlock values = queue(c("1", null, "2"), c(), c("10"));

    assertThat(read(call("sum", values)))
        .containsExactly(List.of("3"), List.of("0"), List.of("10"));
    assertThat(read(call("average", queue(c("1", null, "2"), c("10")))))
        .containsExactly(List.of("1.5"), List.of("10"));
    assertThat(read(call("count", values)))
        .containsExactly(List.of("2"), List.of("0"), List.of("1"));
  }

  /** PO's "Division undefined" (fn/average/003, F-45) is ADR-0005's division error at the box. */
  @Test
  void averageOfAContextWithoutAValueIsFoar0001AtTheBox() {
    assertDataError(
        () -> call("average", queue(c("1"), c((String) null))),
        RuntimeErrors.FOAR0001,
        "average: context 2 holds no value, division undefined");
    assertThat(RuntimeErrors.FOAR0001).isEqualTo("err:FOAR0001");
  }

  @Test
  void aValueThatIsNoDecimalIsForg0001AtTheBox() {
    QueueBlock values = queue(c("1", "1e3"));

    assertDataError(() -> call("sum", values), "sum: \"1e3\" is no xs:decimal");
    assertDataError(() -> call("average", values), "average: \"1e3\" is no xs:decimal");
    // count reads no number
    assertThat(read(call("count", values))).containsExactly(List.of("2"));
  }

  /** index numbers SUPPRESS as a value (fn/index/004, F-45). */
  @Test
  void indexResetsAtEachContextOrNever() {
    QueueBlock values = queue(c("a", null, "b"), c("c"));

    assertThat(read(call("index", values, "1", "1", "eachContext")))
        .containsExactly(List.of("1", "2", "3"), List.of("1"));
    assertThat(read(call("index", values, "10", "-3", "never")))
        .containsExactly(List.of("10", "7", "4"), List.of("1"));
  }

  @Test
  void counterCountsAcrossTheWholeQueue() {
    QueueBlock values = queue(c("a", null, "b"), c(), c("c"));

    assertThat(read(call("counter", values, "0", "2")))
        .containsExactly(List.of("0", "S", "2"), List.of(), List.of("4"));
  }

  /** A malformed param raises on every run, whatever the input holds, as the export does. */
  @Test
  void aNumericParamThatIsNoDecimalIsForg0001AtTheBox() {
    QueueBlock empty = queue(c());

    assertDataError(
        () -> call("index", empty, "one", "1", "eachContext"),
        "index: the parameters initial \"one\" and increment \"1\" are not both xs:decimal");
    assertDataError(
        () -> call("counter", queue(c("a")), "1", "x"),
        "counter: the parameters initial \"1\" and increment \"x\" are not both xs:decimal");
  }

  @Test
  void aResetTheCatalogueDoesNotListIsACompilerBug() {
    assertThatThrownBy(
            () -> QueueLibrary.bind("index", BOX, List.of(frame -> null), List.of("1", "1", "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("index has no reset x");
  }
}
