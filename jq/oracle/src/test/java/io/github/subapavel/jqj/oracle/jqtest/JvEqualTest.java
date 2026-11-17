package io.github.subapavel.jqj.oracle.jqtest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.oracle.jqtest.JvValue.Arr;
import io.github.subapavel.jqj.oracle.jqtest.JvValue.Constant;
import io.github.subapavel.jqj.oracle.jqtest.JvValue.Num;
import io.github.subapavel.jqj.oracle.jqtest.JvValue.Obj;
import io.github.subapavel.jqj.oracle.jqtest.JvValue.Str;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** jq's jv_equal over the oracle's copies of values (ADR-0020): independent of jqj's {@code ==}. */
class JvEqualTest {

  private static Num literal(String digits) {
    BigDecimal d = new BigDecimal(digits);
    return new Num(d.doubleValue(), d, true);
  }

  private static Num computed(double d) {
    return new Num(d, new BigDecimal(Double.toString(d)), false);
  }

  private static Str str(String s) {
    return new Str(s.getBytes(UTF_8));
  }

  private static Obj obj(Object... keysAndValues) {
    Map<String, JvValue> m = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], (JvValue) keysAndValues[i + 1]);
    return new Obj(m);
  }

  @Test
  void kindsMustMatchNullFalseAndTrueAreThreeKinds() {
    assertThat(JvEqual.equal(Constant.NULL, Constant.NULL)).isTrue();
    assertThat(JvEqual.equal(Constant.TRUE, Constant.TRUE)).isTrue();
    assertThat(JvEqual.equal(Constant.FALSE, Constant.TRUE)).isFalse();
    assertThat(JvEqual.equal(Constant.NULL, literal("0"))).isFalse();
    assertThat(JvEqual.equal(literal("0"), Constant.NULL)).isFalse();
    assertThat(JvEqual.equal(str("[]"), new Arr(List.of()))).isFalse();
    assertThat(JvEqual.equal(new Arr(List.of()), obj())).isFalse();
    assertThat(JvEqual.equal(obj(), new Arr(List.of()))).isFalse();
    assertThat(JvEqual.equal(new Arr(List.of()), str("[]"))).isFalse();
  }

  @Test
  void twoLiteralsCompareAsExactDecimals() {
    assertThat(JvEqual.equal(literal("1.000"), literal("1"))).isTrue();
    // Same double, different decimals: jq with decNumber tells them apart.
    assertThat(JvEqual.equal(literal("100000000000000000001"), literal("100000000000000000000"))).isFalse();
  }

  @Test
  void anyOtherNumberPairComparesAsDoubles() {
    assertThat(JvEqual.equal(literal("100000000000000000001"), computed(1e20))).isTrue();
    assertThat(JvEqual.equal(computed(1e20), literal("100000000000000000001"))).isTrue();
    assertThat(JvEqual.equal(computed(0.1 + 0.2), literal("0.3"))).isFalse();
    assertThat(JvEqual.equal(computed(-0.0), computed(0.0))).isTrue();
  }

  @Test
  void nanIsEqualToNothingNotEvenNan() {
    Num nanLiteral = new Num(Double.NaN, null, true);
    assertThat(JvEqual.equal(nanLiteral, nanLiteral)).isFalse();
    assertThat(JvEqual.equal(computed(1), new Num(Double.NaN, null, false))).isFalse();
  }

  @Test
  void anInfinityLiteralEqualsOnlyTheSameInfinityNotAFiniteLiteralWithAnInfiniteDouble() {
    Num infinity = new Num(Double.POSITIVE_INFINITY, null, true);
    assertThat(JvEqual.equal(infinity, new Num(Double.POSITIVE_INFINITY, null, true))).isTrue();
    assertThat(JvEqual.equal(infinity, new Num(Double.NEGATIVE_INFINITY, null, true))).isFalse();
    assertThat(JvEqual.equal(infinity, literal("1E+1000"))).isFalse();
    assertThat(JvEqual.equal(literal("1E+1000"), infinity)).isFalse();
  }

  @Test
  void stringsCompareByUtf8Bytes() {
    assertThat(JvEqual.equal(str("é"), str("é"))).isTrue();
    // U+00E9 against e and a combining acute: equal text to a human, not to jq.
    assertThat(JvEqual.equal(str("é"), str("é"))).isFalse();
  }

  @Test
  void arraysCompareByPosition() {
    assertThat(JvEqual.equal(new Arr(List.of(literal("1"), str("a"))), new Arr(List.of(literal("1.0"), str("a"))))).isTrue();
    assertThat(JvEqual.equal(new Arr(List.of(literal("1"), str("a"))), new Arr(List.of(str("a"), literal("1"))))).isFalse();
    assertThat(JvEqual.equal(new Arr(List.of(literal("1"))), new Arr(List.of(literal("1"), literal("1"))))).isFalse();
  }

  @Test
  void objectsCompareByKeySetWhateverTheOrder() {
    assertThat(JvEqual.equal(obj("a", literal("1"), "b", Constant.NULL), obj("b", Constant.NULL, "a", literal("1")))).isTrue();
    assertThat(JvEqual.equal(obj("a", literal("1")), obj("a", literal("2")))).isFalse();
    assertThat(JvEqual.equal(obj("a", literal("1")), obj("b", literal("1")))).isFalse();
    assertThat(JvEqual.equal(obj("a", literal("1")), obj("a", literal("1"), "b", literal("1")))).isFalse();
  }

  @Test
  void theTextOfAValueIsForReadingADiff() {
    JvValue v = new Arr(List.of(Constant.NULL, literal("1.10"), new Num(Double.NaN, null, false), obj("k", str("v"))));

    assertThat(v.text()).isEqualTo("[null,1.10,NaN,{\"k\":\"v\"}]");
  }
}
