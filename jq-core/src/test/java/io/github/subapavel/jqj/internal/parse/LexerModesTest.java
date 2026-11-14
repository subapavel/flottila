package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lexer's modes and edges that the test-author's red does not reach (implementer's witnesses for #61): an
 * interpolation's mode, the text ending inside a string literal, a comment continued over CR LF, nesting deeper than
 * the first mode stack, and a few longest matches. Every split was observed on jq-ref 1.8.2 on 2026-11-12: a syntax
 * error's carets cover exactly the unexpected token, and a text with a CR was given in a file ({@code jq-ref -n -f}).
 */
class LexerModesTest {

  @Test
  void anInterpolationIsAModeThatItsParenthesisCloses() {
    // jq-ref -n '"a\(1)b", "\((1))"' -> "a1b" and "1": the ) after 1 ends the interpolation, and inside it a
    // parenthesis opens a mode of its own.
    assertThat(kinded("\"a\\(1)b\""))
        .containsExactly("STRING_START \"", "TEXT a", "INTERP_START \\(", "NUMBER 1", "INTERP_END )", "TEXT b", "STRING_END \"");
    assertThat(kinded("\"\\((1))\""))
        .containsExactly("STRING_START \"", "INTERP_START \\(", "LPAREN (", "NUMBER 1", "RPAREN )", "INTERP_END )", "STRING_END \"");
    // jq-ref -n '"\(1]"': "unexpected INVALID_CHARACTER, expecting QQSTRING_INTERP_END" at column 5, one caret.
    assertThat(kinded("\"\\(1]\"").get(3)).as("] inside an interpolation").isEqualTo("INVALID_CHARACTER ]");
    // jq-ref -n '"\n\(1)"' -> "\n1": an escape run ends before an interpolation's start.
    assertThat(kinded("\"\\n\\(1)\""))
        .containsExactly("STRING_START \"", "ESCAPES \\n", "INTERP_START \\(", "NUMBER 1", "INTERP_END )", "STRING_END \"");
  }

  @Test
  void theTextMayEndInsideAStringLiteral() {
    // jq-ref -n, each rc 3: '"abc' and '"\n' -> "unexpected end of file, expecting QQSTRING_TEXT ..." (carets over the
    // text and the escape run); '"\' -> "unexpected INVALID_CHARACTER" at column 2 and '"\n\' at column 4, one caret.
    assertThat(kinded("\"abc")).containsExactly("STRING_START \"", "TEXT abc");
    assertThat(kinded("\"\\n")).containsExactly("STRING_START \"", "ESCAPES \\n");
    assertThat(kinded("\"\\")).containsExactly("STRING_START \"", "INVALID_CHARACTER \\");
    assertThat(kinded("\"\\n\\")).containsExactly("STRING_START \"", "ESCAPES \\n", "INVALID_CHARACTER \\");
  }

  @Test
  void aBackslashBeforeCrLfContinuesAComment() {
    // jq-ref -n -f on "1 # a\<CR><LF>+ 1<LF>" -> 1: the + 1 is in the comment. On "1 # a\<CR>x<LF>+ 1<LF>" -> 2: a
    // backslash and a CR before no LF continue nothing, and the LF ends the comment.
    assertThat(kinded("1 # a\\\r\n+ 1\n")).containsExactly("NUMBER 1");
    assertThat(kinded("1 # a\\\rx\n+ 1\n")).containsExactly("NUMBER 1", "PLUS +", "NUMBER 1");
    // jq-ref -n on "1 # a\b<LF>+ 1" -> 2: a backslash before any other byte continues nothing either.
    assertThat(kinded("1 # a\\b\n+ 1")).containsExactly("NUMBER 1", "PLUS +", "NUMBER 1");
    // jq-ref -n -f on "1 # a\\<LF>+ 1<LF>" -> 2: an escaped backslash continues nothing. On "1 # a\<CR>\<LF>+ 1<LF>"
    // -> 1: the backslash before the CR takes no byte along, so the second one continues the comment over the LF.
    assertThat(kinded("1 # a\\\\\n+ 1\n")).containsExactly("NUMBER 1", "PLUS +", "NUMBER 1");
    assertThat(kinded("1 # a\\\r\\\n+ 1\n")).containsExactly("NUMBER 1");
  }

  @Test
  void nestingDeeperThanSixteenModes() {
    // jq-ref -n '((((((((((((((((((((1))))))))))))))))))))' -> 1: twenty parentheses, each closed by its own.
    List<String> tokens = kinded("(".repeat(20) + "1" + ")".repeat(20));
    assertThat(tokens).hasSize(41);
    assertThat(tokens.subList(21, 41)).as("the closers").containsOnly("RPAREN )");
    // jq-ref -n -c '[{}]' -> [{}]: each closer is its own kind.
    assertThat(kinded("[{}]")).containsExactly("LBRACKET [", "LBRACE {", "RBRACE }", "RBRACKET ]");
  }

  @Test
  void longestMatchesThatStopShort() {
    // jq-ref -n '. ?/1' -> "null (null) and number (1) cannot be divided": ? then /, no ?//.
    assertThat(kinded(". ?/1")).containsExactly("DOT .", "QUESTION ?", "SLASH /", "NUMBER 1");
    // jq-ref -n 'a::1' -> "unexpected ':', expecting end of file" at column 2, one caret: :: before no name ends it.
    assertThat(kinded("a::1")).containsExactly("IDENT a", "COLON :", "COLON :", "NUMBER 1");
    // jq-ref -n -c -f on '"\\u00e9a" | explode' -> [233,97]: a \\u takes four letters or digits, no fifth.
    assertThat(kinded("\"\\u00e9a\"")).containsExactly("STRING_START \"", "ESCAPES \\u00e9", "TEXT a", "STRING_END \"");
    // jq-ref -n '1e-2' -> 0.01: an exponent's sign may be a minus.
    assertThat(kinded("1e-2")).containsExactly("NUMBER 1e-2");
  }

  /** Each token before END as its kind, a space and its bytes as text; END must sit at the end of the text. */
  private static List<String> kinded(String filter) {
    byte[] b = filter.getBytes(UTF_8);
    Lexer lexer = new Lexer(filter);
    List<String> out = new ArrayList<>();
    for (int guard = 0; guard <= b.length; guard++) {
      Token t = lexer.next();
      if (t.kind() == Token.Kind.END) {
        assertThat(new int[] {t.start(), t.end()}).as("END of %s", filter).containsExactly(b.length, b.length);
        return out;
      }
      out.add(t.kind() + " " + new String(b, t.start(), t.end() - t.start(), UTF_8));
    }
    throw new AssertionError("no END after " + (b.length + 1) + " tokens of " + filter);
  }
}
