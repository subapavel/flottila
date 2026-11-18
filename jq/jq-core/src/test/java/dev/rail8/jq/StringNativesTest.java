package dev.rail8.jq;

import static dev.rail8.jq.RangeStepsTest.completes;
import static dev.rail8.jq.RangeStepsTest.ends;
import static dev.rail8.jq.RunBudgets.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rail8.jq.RunBudgets.Results;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The string natives jq 1.8.2 writes in C: {@code startswith}, {@code endswith}, {@code utf8bytelength}, {@code trim},
 * {@code ltrim}, {@code rtrim}, {@code explode}, {@code implode}, {@code split/1} and {@code _strindices}. Their results
 * and errors on the edges jq's own test files leave out, and their ticks, each with an adjacent pair as in
 * {@link RangeStepsTest}: N steps complete, N - 1 end. Every run is on {@code null}; a constant string, a string repeat
 * of constants included, costs no step, a constant array of n elements n - 1. The expected values follow jq 1.8.2's
 * {@code src/builtin.c} and {@code src/jv.c}, as its test files show them for the other inputs.
 */
class StringNativesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void startswithAndEndswithCompareBytes() {
    assertThat(results("[\"aa\", \"\", \"é\"] | map(startswith(\"\"), startswith(\"aaa\"), endswith(\"\"), endswith(\"a\"))"))
        .as("startswith and endswith on short, empty and multibyte strings")
        .containsExactly("[true,false,true,true,true,false,true,false,true,false,true,false]");
    assertThat(results("\"aé\" | startswith(\"a\"), endswith(\"é\"), endswith(\"aé\"), startswith(\"aéx\")"))
        .as("startswith and endswith of whole and longer strings")
        .containsExactly("true", "true", "true", "false");
  }

  @Test
  void startswithAndEndswithNameNeitherValueInTheirErrors() {
    assertThat(results("try (1 | startswith(\"a\")) catch ., try (\"a\" | startswith(null)) catch ."))
        .as("startswith's errors")
        .containsExactly("\"startswith() requires string inputs\"", "\"startswith() requires string inputs\"");
    assertThat(results("try ([] | endswith(\"a\")) catch ., try (\"a\" | endswith({})) catch ."))
        .as("endswith's errors")
        .containsExactly("\"endswith() requires string inputs\"", "\"endswith() requires string inputs\"");
  }

  @Test
  void startswithEndswithAndUtf8bytelengthTickPerKibibyteOfTheInput() {
    for (String filter : List.of("startswith(\"a\")", "endswith(\"a\")")) {
      assertThat(completes("\"a\" * 1024 | " + filter, 1)).as("%s of a KiB with 1 step", filter).containsExactly("true");
      assertThat(ends("\"a\" * 1024 | " + filter, 0)).as("%s of a KiB with 0 steps", filter).isEmpty();
      assertThat(completes("\"a\" * 1025 | " + filter, 2)).as("%s of 1025 bytes with 2 steps", filter).containsExactly("true");
      assertThat(ends("\"a\" * 1025 | " + filter, 1)).as("%s of 1025 bytes with 1 step", filter).isEmpty();
    }
    assertThat(completes("\"é\" * 512 | utf8bytelength", 1)).as("utf8bytelength of a KiB with 1 step").containsExactly("1024");
    assertThat(ends("\"é\" * 512 | utf8bytelength", 0)).as("utf8bytelength of a KiB with 0 steps").isEmpty();
    assertThat(completes("\"a\" * 1025 | utf8bytelength", 2)).as("utf8bytelength of 1025 bytes with 2 steps").containsExactly("1025");
    assertThat(ends("\"a\" * 1025 | utf8bytelength", 1)).as("utf8bytelength of 1025 bytes with 1 step").isEmpty();
  }

  @Test
  void utf8bytelengthCountsBytes() {
    assertThat(results("\"\", \"aé😀\" | utf8bytelength")).as("utf8bytelength of an empty and a multibyte string").containsExactly("0", "7");
  }

  @Test
  void theTrimFamilyTrimsWhitespaceCodePointsOnly() {
    assertThat(results("\"\\u00a0x\\u200by\\u3000\" | trim, ltrim, rtrim"))
        .as("trim, ltrim and rtrim keep U+200B, which is no White_Space")
        .containsExactly("\"x\u200by\"", "\"x\u200by\u3000\"", "\"\u00a0x\u200by\"");
    assertThat(results("\"\\u2028\" | trim, ltrim, rtrim")).as("a string of one multibyte space").containsExactly("\"\"", "\"\"", "\"\"");
    assertThat(results("\"é\" | trim, ltrim, rtrim")).as("a multibyte string with nothing to trim").containsExactly("\"é\"", "\"é\"", "\"é\"");
  }

  @Test
  void theTrimFamilyTicksPerKibibyteOfTheInput() {
    for (String filter : List.of("trim", "ltrim", "rtrim")) {
      assertThat(completes("\" \" * 1024 | " + filter, 1)).as("%s of a KiB with 1 step", filter).containsExactly("\"\"");
      assertThat(ends("\" \" * 1024 | " + filter, 0)).as("%s of a KiB with 0 steps", filter).isEmpty();
      assertThat(completes("\" \" * 1025 | " + filter, 2)).as("%s of 1025 bytes with 2 steps", filter).containsExactly("\"\"");
      assertThat(ends("\" \" * 1025 | " + filter, 1)).as("%s of 1025 bytes with 1 step", filter).isEmpty();
    }
  }

  @Test
  void explodeAndImplodeRoundTripCodePoints() {
    assertThat(results("\"aé😀\" | explode")).as("explode of a multibyte string").containsExactly("[97,233,128512]");
    assertThat(results("\"\" | explode")).as("explode of the empty string").containsExactly("[]");
    assertThat(results("[97,233,128512] | implode")).as("implode of one, two and four bytes").containsExactly("\"aé😀\"");
    assertThat(results("[1e10, -1.5, 65.9, 2047, 2048, 65535, 65536] | implode | explode"))
        .as("implode cuts toward zero and replaces what is no scalar value")
        .containsExactly("[65533,65533,65,2047,2048,65535,65536]");
  }

  @Test
  void explodeAndImplodeRaiseJqsErrors() {
    assertThat(results("try ([1] | explode) catch ., try (\"a\" | implode) catch ., try ([65, true] | implode) catch ."))
        .as("explode's and implode's errors")
        .containsExactly(
            "\"explode input must be a string\"",
            "\"implode input must be an array\"",
            "\"boolean (true) can't be imploded, unicode codepoint needs to be numeric\"");
  }

  @Test
  void explodeAndImplodeTickPerCodePoint() {
    assertThat(completes("\"aé😀\" | explode", 3)).as("explode of three code points with 3 steps").hasSize(1);
    assertThat(ends("\"aé😀\" | explode", 2)).as("explode of three code points with 2 steps").isEmpty();
    // The constant array costs its 2 commas.
    assertThat(completes("[65,66,67] | implode", 5)).as("implode of three code points with 5 steps").containsExactly("\"ABC\"");
    assertThat(ends("[65,66,67] | implode", 4)).as("implode of three code points with 4 steps").isEmpty();
  }

  @Test
  void splitWithOneArgumentSplitsAsTheOperator() {
    assertThat(results("\"a, b,c\" | split(\", \"), split(\"\"), (\"\" | split(\",\"))"))
        .as("split on a separator, on the empty one and of the empty string")
        .containsExactly("[\"a\",\"b,c\"]", "[\"a\",\",\",\" \",\"b\",\",\",\"c\"]", "[]");
    assertThat(results("try (1 | split(\"a\")) catch ., try (\"a\" | split(1)) catch ."))
        .as("split's errors")
        .containsExactly("\"split input and separator must be strings\"", "\"split input and separator must be strings\"");
  }

  @Test
  void splitTicksAsTheOperator() {
    assertThat(completes("\"a,b\" | split(\",\")", 3)).as("split of two pieces with 3 steps").containsExactly("[\"a\",\"b\"]");
    assertThat(ends("\"a,b\" | split(\",\")", 2)).as("split of two pieces with 2 steps").isEmpty();
  }

  @Test
  void strindicesFindsOverlappingPlacesByCodePoint() {
    assertThat(results("\"aaaa\" | _strindices(\"aa\")")).as("overlapping places").containsExactly("[0,1,2]");
    assertThat(results("\"é😀é😀\" | _strindices(\"😀\"), _strindices(\"é\")")).as("places counted in code points").containsExactly("[1,3]", "[0,2]");
    assertThat(results("\"abc\" | _strindices(\"\"), _strindices(\"abcd\")")).as("an empty and a longer needle").containsExactly("[]", "[]");
    assertThat(results("\"a,b, cd, efg\" | indices(\", \"), index(\", \"), rindex(\", \")")).as("indices, index and rindex on strings").containsExactly("[3,7]", "3", "7");
  }

  @Test
  void strindicesTicksAsSplitAndOncePerIndex() {
    assertThat(completes("\"aaa\" | _strindices(\"aa\")", 3)).as("two places with 3 steps").containsExactly("[0,1]");
    assertThat(ends("\"aaa\" | _strindices(\"aa\")", 2)).as("two places with 2 steps").isEmpty();
  }

  @Test
  void thePreludeOverTheNativesRuns() {
    assertThat(results("\"xay\" | ltrimstr(\"x\"), rtrimstr(\"y\"), (\"xax\" | trimstr(\"x\")), (\"aB1é\" | ascii_downcase, ascii_upcase)"))
        .as("ltrimstr, rtrimstr, trimstr, ascii_downcase and ascii_upcase")
        .containsExactly("\"ay\"", "\"xa\"", "\"a\"", "\"ab1é\"", "\"AB1é\"");
  }

  /** The result texts of {@code filter} on null, with no budget. */
  private static List<String> results(String filter) {
    Results all = new Results();
    ENGINE.compile(filter).run(JqNull.NULL, NONE, all, ENGINE.newWorkspace());
    return all.values.stream().map(JqValue::toJson).toList();
  }
}
