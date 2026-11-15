package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.xdm.QueueBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The boolean family's bindings (#77, #78), called through {@link SingleValueLibrary} and {@link
 * QueueLibrary} as the interpreter calls them, as PO 7.5 and CPI showed them (obs-boolean, F-44):
 * each value is cast as PO's {@code Bool.toBoolean} casts it, {@code 1} and {@code true} in any
 * case true, {@code 0} and {@code false} in any case false, and any other value, {@code ""}
 * included, is {@code err:FORG0001} at the box; a result is {@code true} or {@code false}.
 * ifWithoutElse's parameter changes nothing. The corpus cases {@code fn/and} to {@code fn/isNil}
 * and {@code fn/ifWithoutElse} pin the same through the facade and against Saxon-HE.
 */
class BooleanFamilyTest {

  private static final AuthoringId BOX = new AuthoringId("F1");
  private static final List<String> TRUE = List.of("true", "TRUE", "True", "1");
  private static final List<String> FALSE = List.of("false", "FALSE", "False", "0");
  private static final List<String> NO_BOOLEAN = List.of("", " true", "keep", "yes", "2");

  private static String call(String name, String... args) {
    List<Node.OfString> nodes = new ArrayList<>();
    for (String arg : args) {
      nodes.add(frame -> arg);
    }
    return SingleValueLibrary.bind(name, BOX, nodes).eval(null);
  }

  /** The data error {@code name(args)} raises: err:FORG0001 at F1, naming the value. */
  private static void assertRefuses(String value, String name, String... args) {
    MappingRunException error =
        catchThrowableOfType(MappingRunException.class, () -> call(name, args));
    assertThat(error).as("%s(%s)", name, String.join(", ", args)).isNotNull();
    assertThat(error.code()).isEqualTo("err:FORG0001");
    assertThat(error.authoringId()).isEqualTo(BOX);
    assertThat(error).hasMessage("the function box F1: Cannot cast '" + value + "' to boolean");
  }

  @Test
  void notNegatesTheCastValue() {
    for (String value : TRUE) {
      assertThat(call("not", value)).as("not(\"%s\")", value).isEqualTo("false");
    }
    for (String value : FALSE) {
      assertThat(call("not", value)).as("not(\"%s\")", value).isEqualTo("true");
    }
    for (String value : NO_BOOLEAN) {
      assertRefuses(value, "not", value);
    }
  }

  @Test
  void andNeedsTwoTrue() {
    for (String t : TRUE) {
      assertThat(call("and", t, "true")).as("and(\"%s\", true)", t).isEqualTo("true");
      for (String f : FALSE) {
        assertThat(call("and", t, f)).as("and(\"%s\", \"%s\")", t, f).isEqualTo("false");
        assertThat(call("and", f, t)).as("and(\"%s\", \"%s\")", f, t).isEqualTo("false");
        assertThat(call("and", f, f)).as("and(\"%1$s\", \"%1$s\")", f).isEqualTo("false");
      }
    }
    for (String value : NO_BOOLEAN) {
      assertRefuses(value, "and", value, "true");
      // the second is cast even when the first is false
      assertRefuses(value, "and", "false", value);
    }
  }

  @Test
  void orNeedsOneTrue() {
    for (String t : TRUE) {
      for (String f : FALSE) {
        assertThat(call("or", t, f)).as("or(\"%s\", \"%s\")", t, f).isEqualTo("true");
        assertThat(call("or", f, t)).as("or(\"%s\", \"%s\")", f, t).isEqualTo("true");
        assertThat(call("or", f, f)).as("or(\"%1$s\", \"%1$s\")", f).isEqualTo("false");
      }
    }
    for (String value : NO_BOOLEAN) {
      // observed (fn/or/001): or(empty, true) raises
      assertRefuses(value, "or", value, "true");
      assertRefuses(value, "or", "true", value);
    }
  }

  @Test
  void equalsAndNotEqualsCompareTheCastValues() {
    assertThat(call("equals", "true", "TRUE")).isEqualTo("true");
    assertThat(call("equals", "1", "true")).isEqualTo("true");
    assertThat(call("equals", "0", "FALSE")).isEqualTo("true");
    assertThat(call("equals", "1", "0")).isEqualTo("false");
    assertThat(call("notEquals", "true", "TRUE")).isEqualTo("false");
    assertThat(call("notEquals", "0", "false")).isEqualTo("false");
    assertThat(call("notEquals", "1", "False")).isEqualTo("true");
    assertThat(call("notEquals", "false", "true")).isEqualTo("true");
    // observed (fn/equals/001, fn/notEquals/001): keep is no boolean
    assertRefuses("keep", "equals", "keep", "keep");
    assertRefuses("keep", "notEquals", "keep", "keep");
    assertRefuses("", "equals", "true", "");
    assertRefuses("", "notEquals", "true", "");
  }

  @Test
  void ifGivesThenWhereTheCastConditionIsTrue() {
    for (String value : TRUE) {
      assertThat(call("if", value, "yes", "no")).as("if(\"%s\")", value).isEqualTo("yes");
    }
    for (String value : FALSE) {
      assertThat(call("if", value, "yes", "no")).as("if(\"%s\")", value).isEqualTo("no");
    }
    for (String value : NO_BOOLEAN) {
      // observed (fn/if/001): an empty condition raises
      assertRefuses(value, "if", value, "yes", "no");
    }
  }

  @Test
  void isNilIsTrueForTheEmptyStringOnly() {
    assertThat(call("isNil", "")).isEqualTo("true");
    assertThat(call("isNil", " ")).isEqualTo("false");
    assertThat(call("isNil", "x")).isEqualTo("false");
  }

  /** {@code condition} against a, b, c in one context, read back, S for SUPPRESS. */
  private static List<String> ifWithoutElse(String keepSuppressValues, String... condition) {
    QueueBlock.Builder conditions = new QueueBlock.Builder();
    conditions.newContext();
    for (String value : condition) {
      conditions.append(value);
    }
    QueueBlock.Builder then = new QueueBlock.Builder();
    then.newContext();
    then.append("a");
    then.append("b");
    then.append("c");
    QueueBlock c = conditions.build();
    QueueBlock t = then.build();
    QueueBlock result =
        QueueLibrary.bind(
                "ifWithoutElse", BOX, List.of(frame -> c, frame -> t), List.of(keepSuppressValues))
            .eval(null);
    List<String> slots = new ArrayList<>();
    for (int i = 0; i < result.slots(); i++) {
      slots.add(result.suppressed(i) ? "S" : result.value(i));
    }
    return slots;
  }

  /** Observed (fn/ifWithoutElse/001 and 002, 003): the parameter changes nothing. */
  @Test
  void ifWithoutElseGivesSuppressAtAFalseSlotWhateverTheParameter() {
    assertThat(ifWithoutElse("false", "true", "FALSE", "1")).containsExactly("a", "S", "c");
    assertThat(ifWithoutElse("true", "true", "FALSE", "1")).containsExactly("a", "S", "c");
  }

  @Test
  void ifWithoutElseRaisesAConditionThatIsNoBooleanAtItsBox() {
    MappingRunException error =
        catchThrowableOfType(
            MappingRunException.class, () -> ifWithoutElse("false", "true", "keep", "true"));
    assertThat(error).isNotNull();
    assertThat(error.code()).isEqualTo("err:FORG0001");
    assertThat(error.authoringId()).isEqualTo(BOX);
    assertThat(error).hasMessage("the function box F1: Cannot cast 'keep' to boolean");
  }

  @Test
  void aKeepSuppressValuesTheCatalogueDoesNotListIsACompilerBug() {
    assertThatThrownBy(() -> ifWithoutElse("yes", "true"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ifWithoutElse has no keepSuppressValues yes");
  }
}
