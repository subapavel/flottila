package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * ADR-0016 and ADR-0024: a host's exhaustive switch over a value or an exception needs no default.
 * The switches below are the check: they compile only while JqValue permits exactly the six kinds and
 * JqException is abstract and permits exactly its four subclasses. The run then dispatches the values
 * that exist without a document.
 */
class HostSwitchTest {
  /** jq's {@code type} names (reference jq: {@code null, true | type} gives "null", "boolean"). */
  private static String typeOf(JqValue value) {
    return switch (value) {
      case JqNull _ -> "null";
      case JqBool _ -> "boolean";
      case JqNumber _ -> "number";
      case JqString _ -> "string";
      case JqArray _ -> "array";
      case JqObject _ -> "object";
    };
  }

  private static String handle(JqException e) {
    return switch (e) {
      case JqCompileException _ -> "compile";
      case JqSyntaxException _ -> "syntax";
      case JqFilterException _ -> "filter";
      case JqTermination _ -> "termination";
    };
  }

  @Test
  void switchOverJqValueNeedsNoDefault() {
    assertThat(typeOf(JqNull.NULL)).isEqualTo("null");
    assertThat(typeOf(JqBool.FALSE)).isEqualTo("boolean");
    assertThat(typeOf(JqBool.TRUE)).isEqualTo("boolean");
  }

  @Test
  void switchOverJqExceptionNeedsNoDefault() {
    assertThat(handle(new JqCompileException("compile message", 1, 1))).isEqualTo("compile");
    assertThat(handle(new JqSyntaxException("syntax message", 0, 1, 1))).isEqualTo("syntax");
    assertThat(handle(new JqFilterException(JqNull.NULL))).isEqualTo("filter");
    assertThat(handle(new JqTermination(Cause.HALT, 0, JqNull.NULL, null))).isEqualTo("termination");
  }
}
