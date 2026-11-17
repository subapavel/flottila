package io.github.subapavel.jqj.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-0003 and ADR-0024: null and the booleans are enums with no document behind them. */
class ValueKindsTest {
  @Test
  void nullAndBooleansPrintAsJsonLiterals() {
    // Reference jq: `jq -nc 'null, true, false'` prints null, true and false.
    assertThat(JqNull.NULL.toJson()).isEqualTo("null");
    assertThat(JqBool.TRUE.toJson()).isEqualTo("true");
    assertThat(JqBool.FALSE.toJson()).isEqualTo("false");
    assertThat(JqBool.TRUE.value()).isTrue();
    assertThat(JqBool.FALSE.value()).isFalse();
  }
}
