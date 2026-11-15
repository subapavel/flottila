package io.flottila.mapping.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flottila.mapping.ir.AuthoringId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The text family's bindings (#76), called through {@link SingleValueLibrary} as the interpreter
 * calls them: code point counts, indexes and order, the {@code xs:decimal} params of {@code
 * substring} and the {@code err:FORG0001} at the box. The corpus cases {@code fn/concat} to {@code
 * fn/compare} pin the same through the facade and against Saxon-HE.
 */
class TextFamilyTest {

  private static final AuthoringId BOX = new AuthoringId("F1");
  private static final String EMOJI = "😀";
  private static final String CLEF = "𝄞";

  private static String call(String name, String... args) {
    List<Node.OfString> nodes = new ArrayList<>();
    for (String arg : args) {
      nodes.add(frame -> arg);
    }
    return SingleValueLibrary.bind(name, BOX, nodes).eval(null);
  }

  @Test
  void concatJoinsAndEqualsSComparesCaseSensitively() {
    assertThat(call("concat", "ab", "cd")).isEqualTo("abcd");
    assertThat(call("concat", "", "")).isEmpty();
    assertThat(call("equalsS", "keep", "keep")).isEqualTo("true");
    assertThat(call("equalsS", "keep", "Keep")).isEqualTo("false");
  }

  @Test
  void startsWithAndEndsWithAreTrueForAnEmptyAffix() {
    assertThat(call("startsWith", "abc", "ab")).isEqualTo("true");
    assertThat(call("startsWith", "abc", "bc")).isEqualTo("false");
    assertThat(call("startsWith", "abc", "")).isEqualTo("true");
    assertThat(call("endsWith", "abc", "bc")).isEqualTo("true");
    assertThat(call("endsWith", "abc", "ab")).isEqualTo("false");
    assertThat(call("endsWith", "abc", "")).isEqualTo("true");
  }

  @Test
  void lengthCountsCodePoints() {
    assertThat(call("length", "")).isEqualTo("0");
    assertThat(call("length", "abc")).isEqualTo("3");
    assertThat(call("length", "a" + CLEF + "b")).isEqualTo("3");
    assertThat(call("length", EMOJI)).isEqualTo("1");
  }

  @Test
  void indexOfAndLastIndexOfAreZeroBasedInCodePoints() {
    assertThat(call("indexOf", "hello", "l")).isEqualTo("2");
    assertThat(call("indexOf", "hello", "z")).isEqualTo("-1");
    assertThat(call("indexOf", "hello", "")).isEqualTo("0");
    assertThat(call("indexOf", "a" + EMOJI + "b", "b")).isEqualTo("2");
    assertThat(call("lastIndexOf", "hello", "l")).isEqualTo("3");
    assertThat(call("lastIndexOf", "hello", "z")).isEqualTo("-1");
    assertThat(call("lastIndexOf", "hello", "")).isEqualTo("5");
    assertThat(call("lastIndexOf", "aaa", "aa")).isEqualTo("1");
    assertThat(call("lastIndexOf", EMOJI + "a" + EMOJI + "a", "a")).isEqualTo("3");
  }

  @Test
  void compareOrdersByCodePoint() {
    assertThat(call("compare", "a", "b")).isEqualTo("-1");
    assertThat(call("compare", "b", "a")).isEqualTo("1");
    assertThat(call("compare", "a", "a")).isEqualTo("0");
    assertThat(call("compare", "", "a")).isEqualTo("-1");
    assertThat(call("compare", "ab", "a")).isEqualTo("1");
    assertThat(call("compare", "ab", "ac")).isEqualTo("-1");
    assertThat(call("compare", "a", "B")).isEqualTo("1");
    // U+1F600 is after U+FF61 by code point, before it by UTF-16 unit.
    assertThat(call("compare", EMOJI, "｡")).isEqualTo("1");
    assertThat(call("compare", "｡", EMOJI)).isEqualTo("-1");
    assertThat(call("compare", CLEF, EMOJI)).isEqualTo("-1");
    assertThat(call("compare", EMOJI + "a", EMOJI + "b")).isEqualTo("-1");
  }

  @Test
  void replaceStringIsLiteralAndLeavesTheValueForAnEmptySearch() {
    assertThat(call("replaceString", "banana", "a", "bb")).isEqualTo("bbbnbbnbb");
    assertThat(call("replaceString", "a$b$", "$", "\\$1")).isEqualTo("a\\$1b\\$1");
    assertThat(call("replaceString", "abc", "", "-")).isEqualTo("abc");
    assertThat(call("replaceString", "aaa", "aa", "b")).isEqualTo("ba");
  }

  @Test
  void caseFunctionsUseTheFullMapping() {
    assertThat(call("toUpperCase", "straße")).isEqualTo("STRASSE");
    assertThat(call("toLowerCase", "ÉA")).isEqualTo("éa");
  }

  @Test
  void trimLeavesOutXmlWhitespaceAtBothEndsOnly() {
    assertThat(call("trim", "  a b  ")).isEqualTo("a b");
    assertThat(call("trim", "\t\nx\r")).isEqualTo("x");
    assertThat(call("trim", "   ")).isEmpty();
    assertThat(call("trim", "")).isEmpty();
    assertThat(call("trim", " a ")).isEqualTo(" a ");
  }

  @Test
  void substringIsZeroBasedRoundedAndCutToTheValue() {
    assertThat(call("substring", "abcdef", "1", "3")).isEqualTo("bcd");
    assertThat(call("substring", "ab", "1", "3")).isEqualTo("b");
    assertThat(call("substring", "a", "1", "3")).isEmpty();
    assertThat(call("substring", "abcdef", "-1", "3")).isEqualTo("ab");
    assertThat(call("substring", "abcdef", "0.5", "1.5")).isEqualTo("bc");
    assertThat(call("substring", "abcdef", "-0.5", "1")).isEqualTo("a");
    assertThat(call("substring", "abcdef", "2", "0")).isEmpty();
    assertThat(call("substring", "abcdef", "2", "-1")).isEmpty();
    assertThat(call("substring", "abcdef", "0", "100")).isEqualTo("abcdef");
    assertThat(call("substring", "abcdef", "1000000000000000000000", "1")).isEmpty();
    assertThat(call("substring", "abcdef", "+1", ".5")).isEqualTo("b");
    assertThat(call("substring", "abcdef", " 1 ", "\t2\n")).isEqualTo("bc");
    assertThat(call("substring", "a" + EMOJI + "b" + EMOJI + "c", "1", "2")).isEqualTo(EMOJI + "b");
  }

  @Test
  void substringWithAParamThatIsNoDecimalRaisesForg0001AtTheBox() {
    for (String[] params :
        new String[][] {{"1e1", "2"}, {"1", "abc"}, {"", "2"}, {"1", "."}, {"1", "1.2.3"}}) {
      assertThatThrownBy(() -> call("substring", "abcdef", params[0], params[1]))
          .isInstanceOfSatisfying(
              MappingRunException.class,
              e -> {
                assertThat(e.code()).isEqualTo("err:FORG0001");
                assertThat(e.authoringId()).isEqualTo(BOX);
                assertThat(e.getMessage())
                    .isEqualTo(
                        "the function box F1: substring: the parameters start \""
                            + params[0]
                            + "\" and length \""
                            + params[1]
                            + "\" are not both xs:decimal");
              });
    }
  }
}
