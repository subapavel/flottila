package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The full filter lexer of #61 at its seam: {@code new Lexer(text)}, then {@code next()} until {@link Token.Kind#END},
 * each token's span in UTF-8 bytes (ADR-0031). The parser's Stubs hide most of these tokens from the oracles (and, or,
 * $name, @format, operators, closers), so this test pins them here.
 *
 * <p>Every expected split was observed on jq-ref 1.8.2 (2026-11-12): a syntax error's carets cover exactly the
 * unexpected token ({@code ?//= 1} gives "unexpected ?//" with three carets), and "x/0 is not defined" or "$x is not
 * defined" shows an identifier or a binding whole. The command is quoted beside each group as {@code jq-ref -n <text>}.
 */
class LexerTest {

  @Test
  void operatorsLongestMatch() {
    // jq-ref -n '<op> 1' for each: "unexpected ?//" (3 carets) for ?//= and ?//; "unexpected //=", "|=", "==", "!=",
    // "<=", ">=", "+=", "-=", "*=", "/=", "%=", "//" with their whole width; '1 === 1', '. |== 1', '1 !== 1' give
    // "unexpected '='" at column 5, the byte after the two-byte operator; '... 1' is ".." then "." ("unexpected
    // LITERAL" at column 5).
    assertThat(texts("?//= //= |= == != <= >= += -= *= /= %= ?// // === |== !== ..."))
        .containsExactly(
            "?//", "=", "//=", "|=", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "?//", "//", "==", "=", "|=", "=",
            "!=", "=", "..", ".");
    // jq-ref -n '.a?//1': "unexpected ?//" at column 3, three carets.
    assertThat(texts(".a?//1")).as("tokens of .a?//1").containsExactly(".a", "?//", "1");
    // jq-ref -n '<c> 1': "unexpected '<c>'" with one caret for each of these.
    assertThat(texts(";:,=<>*/%|?")).as("single-byte operators").containsExactly(";", ":", ",", "=", "<", ">", "*", "/", "%", "|", "?");
    // jq-ref -n '! 1': "unexpected INVALID_CHARACTER" at column 1; '1 ! = 2' too, at column 3: ! is no operator.
    assertThat(kinded("! =")).as("tokens of '! ='").first().isEqualTo("INVALID_CHARACTER !");
  }

  @Test
  void keywordsAreWholeWords() {
    // jq-ref -n '<kw>': each is a syntax error at column 1 whose carets cover the word ("unexpected and", "unexpected
    // then", "unexpected end of file" after if, def, reduce, ...), never "<kw>/0 is not defined".
    for (String kw : List.of("as", "def", "module", "import", "include", "if", "then", "elif", "else", "end", "and", "or",
        "reduce", "foreach", "try", "catch", "label", "break")) {
      assertThat(kinded(kw)).as("tokens of %s", kw).containsExactly("KEYWORD " + kw);
    }
    // jq-ref -n: andx, and1, _and, and_ and __loc__ give "<name>/0 is not defined" with carets over the whole name,
    // a::b gives "a::b/0 is not defined"; '1 or1' gives "unexpected IDENT" over or1.
    for (String ident : List.of("andx", "and1", "_and", "and_", "or1", "__loc__", "a::b")) {
      assertThat(kinded(ident)).as("tokens of %s", ident).containsExactly("IDENT " + ident);
    }
    // jq-ref -n '1 and. 1': "unexpected LITERAL" at column 8, so and. is the keyword then a dot; '1 and(1)' -> true.
    assertThat(kinded("and.")).as("tokens of and.").containsExactly("KEYWORD and", "DOT .");
    assertThat(kinded("and(").getFirst()).as("first token of and(").isEqualTo("KEYWORD and");
    // A keyword after a dot is a field name (diff/field.rec field/keyword-and: .and on {"and":1} -> 1).
    assertThat(kinded(".and")).as("tokens of .and").containsExactly("FIELD .and");
  }

  @Test
  void bindingLocAndDollar() {
    // jq-ref -n: $a -> "$a is not defined" (2 carets); $a::b -> "$a::b is not defined" (5 carets); $__loc__ ->
    // {"file":"<top-level>","line":1}; $__loc__x -> "$__loc__x is not defined" (9 carets).
    assertThat(kinded("$a")).as("tokens of $a").containsExactly("BINDING $a");
    assertThat(kinded("$a::b")).as("tokens of $a::b").containsExactly("BINDING $a::b");
    assertThat(kinded("$__loc__")).as("tokens of $__loc__").containsExactly("LOC $__loc__");
    assertThat(kinded("$__loc__x")).as("tokens of $__loc__x").containsExactly("BINDING $__loc__x");
    // jq-ref -n: '$' -> "unexpected end of file, expecting '$'"; '$1' -> "unexpected LITERAL, expecting '$'" at
    // column 2; '$ __loc__' -> "unexpected IDENT, expecting '$'" at column 3 (7 carets). A $ before no name is a token.
    assertThat(kinded("$")).as("tokens of $").containsExactly("DOLLAR $");
    assertThat(kinded("$1")).as("tokens of $1").containsExactly("DOLLAR $", "NUMBER 1");
    assertThat(kinded("$ __loc__")).as("tokens of '$ __loc__'").containsExactly("DOLLAR $", "IDENT __loc__");
  }

  @Test
  void formatToken() {
    // jq-ref -n: @text -> "null"; @base64d runs; @foo, @1 and @_x -> "<name> is not a valid format" (rc 5, so each
    // lexed and parsed as a format).
    for (String format : List.of("@text", "@base64d", "@foo", "@1", "@_x")) {
      assertThat(kinded(format)).as("tokens of %s", format).containsExactly("FORMAT " + format);
    }
    // jq-ref -n: '@text.a' -> "Cannot index string with string ("a")"; '@text-1' -> "string ("null") and number (1)
    // cannot be subtracted".
    assertThat(kinded("@text.a")).as("tokens of @text.a").containsExactly("FORMAT @text", "FIELD .a");
    assertThat(texts("@text-1")).as("tokens of @text-1").containsExactly("@text", "-", "1");
    // jq-ref -n: '@' and '@ text' -> "unexpected INVALID_CHARACTER" at column 1, one caret.
    assertThat(kinded("@")).as("tokens of @").containsExactly("INVALID_CHARACTER @");
    assertThat(kinded("@ text")).as("tokens of '@ text'").containsExactly("INVALID_CHARACTER @", "IDENT text");
  }

  @Test
  void numberBoundaries() {
    // jq-ref -n, each "unexpected <kind>" at the column of the second token, with its width in carets:
    //   1e: IDENT at 2 | 1.e: IDENT at 3 | 1ee2: IDENT at 2, 3 carets | .5e: IDENT at 3 | .5a: IDENT at 3
    //   .5.5: LITERAL at 3, 2 carets | 1.5.3: LITERAL at 4, 2 carets | 1.5e3x: IDENT at 6 | 1e1x: IDENT at 4
    //   1.2.: end of file after a dot at 4 | ..a: IDENT at 3
    assertThat(kinded("1e")).as("tokens of 1e").containsExactly("NUMBER 1", "IDENT e");
    assertThat(kinded("1.e")).as("tokens of 1.e").containsExactly("NUMBER 1.", "IDENT e");
    assertThat(kinded("1ee2")).as("tokens of 1ee2").containsExactly("NUMBER 1", "IDENT ee2");
    assertThat(kinded(".5e")).as("tokens of .5e").containsExactly("NUMBER .5", "IDENT e");
    assertThat(kinded(".5a")).as("tokens of .5a").containsExactly("NUMBER .5", "IDENT a");
    assertThat(kinded(".5.5")).as("tokens of .5.5").containsExactly("NUMBER .5", "NUMBER .5");
    assertThat(kinded("1.5.3")).as("tokens of 1.5.3").containsExactly("NUMBER 1.5", "NUMBER .3");
    assertThat(kinded("1.5e3x")).as("tokens of 1.5e3x").containsExactly("NUMBER 1.5e3", "IDENT x");
    assertThat(kinded("1e1x")).as("tokens of 1e1x").containsExactly("NUMBER 1e1", "IDENT x");
    assertThat(kinded("1.2.")).as("tokens of 1.2.").containsExactly("NUMBER 1.2", "DOT .");
    assertThat(texts("..a")).as("tokens of ..a").containsExactly("..", "a");
    // Whole numbers, recorded in diff/literal.rec: 1.e2 -> 1E+2, 007 -> 7, 00.50 -> 0.50, .0e1 -> 0,
    // 100000000000000000001 as is; jq-ref -n '1E+2x' -> "unexpected IDENT" at column 5; .e0 is a field
    // (literal/access-field-e0).
    for (String number : List.of("1.e2", "007", "00.50", ".0e1", "1E+2", "100000000000000000001")) {
      assertThat(kinded(number)).as("tokens of %s", number).containsExactly("NUMBER " + number);
    }
    assertThat(kinded(".e0")).as("tokens of .e0").containsExactly("FIELD .e0");
  }

  @Test
  void closerMismatchIsInvalid() {
    // jq-ref -n, "unexpected INVALID_CHARACTER" at the closer: (1] at 3, [1) at 3, {a:1) at 5, (1} at 3, "a"] at 4,
    // [(1]) at 4, and ), ] and } alone at 1.
    assertThat(kinded("(1]").getLast()).as("] closing (").isEqualTo("INVALID_CHARACTER ]");
    assertThat(kinded("[1)").getLast()).as(") closing [").isEqualTo("INVALID_CHARACTER )");
    assertThat(kinded("{a:1)").getLast()).as(") closing {").isEqualTo("INVALID_CHARACTER )");
    assertThat(kinded("(1}").getLast()).as("} closing (").isEqualTo("INVALID_CHARACTER }");
    assertThat(kinded("\"a\"]").getLast()).as("] after a string, nothing open").isEqualTo("INVALID_CHARACTER ]");
    assertThat(kinded("[(1])").get(3)).as("] closing ( inside [").isEqualTo("INVALID_CHARACTER ]");
    for (String closer : List.of(")", "]", "}")) {
      assertThat(kinded(closer)).as("%s with nothing open", closer).containsExactly("INVALID_CHARACTER " + closer);
    }
    // Witnesses: matching closers are no INVALID_CHARACTER (jq-ref -n: (1) -> 1, [(1)] -> [1], {a:[(1)]} ->
    // {"a":[1]}).
    assertThat(kinded("(1)")).as("tokens of (1)").last().isEqualTo("RPAREN )");
    for (String text : List.of("[(1)]", "{a:[(1)]}")) {
      assertThat(kinded(text)).as("tokens of %s", text).noneMatch(t -> t.startsWith("INVALID_CHARACTER"));
    }
  }

  /** One case per seed (docs/agents/environment.md, "Property tests"). */
  static IntStream seeds() {
    return IntStream.range(0, 500);
  }

  /**
   * Every byte is in exactly one token, whitespace or comment. A text is pieces joined by non-empty separators of
   * whitespace and comments; each piece is one token, or a string literal the lexer may split into several tokens that
   * cover it without a gap. No token may lie in a separator, and END is at the text's last byte. A lexer that took the
   * whole text as one token fails, as does one that dropped or merged a piece.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void spansTileTheText(int seed) {
    Random r = new Random(seed);
    StringBuilder text = new StringBuilder();
    List<int[]> spans = new ArrayList<>();
    List<Boolean> strings = new ArrayList<>();
    if (r.nextBoolean()) {
      text.append(SEPARATORS.get(r.nextInt(SEPARATORS.size())));
    }
    int pieces = 1 + r.nextInt(12);
    for (int i = 0; i < pieces; i++) {
      boolean string = r.nextInt(4) == 0;
      String piece = string ? STRINGS.get(r.nextInt(STRINGS.size())) : TOKENS.get(r.nextInt(TOKENS.size()));
      int start = bytes(text);
      text.append(piece);
      spans.add(new int[] {start, bytes(text)});
      strings.add(string);
      if (i + 1 < pieces) {
        text.append(SEPARATORS.get(r.nextInt(SEPARATORS.size())));
      }
    }
    if (r.nextBoolean()) {
      text.append(r.nextBoolean() ? "# end" : SEPARATORS.get(r.nextInt(SEPARATORS.size())));
    }
    String filter = text.toString();
    String shown = shown(filter);
    List<Token> tokens = tokens(filter);

    int k = 0;
    for (int i = 0; i < spans.size(); i++) {
      int start = spans.get(i)[0];
      int end = spans.get(i)[1];
      assertThat(k).as("a token for piece %d [%d, %d) of %s", i, start, end, shown).isLessThan(tokens.size());
      if (!strings.get(i)) {
        Token t = tokens.get(k++);
        assertThat(new int[] {t.start(), t.end()}).as("span of piece %d of %s", i, shown).containsExactly(start, end);
        continue;
      }
      int at = start;
      do {
        Token t = tokens.get(k++);
        assertThat(t.start()).as("start of a token of string piece %d [%d, %d) of %s", i, start, end, shown).isEqualTo(at);
        assertThat(t.end()).as("end of a token of string piece %d [%d, %d) of %s", i, start, end, shown).isBetween(at, end);
        at = t.end();
      } while (at < end && k < tokens.size());
      assertThat(at).as("end of the tokens of string piece %d of %s", i, shown).isEqualTo(end);
    }
    assertThat(tokens.size()).as("tokens of %s: one run per piece, none in a separator", shown).isEqualTo(k);
  }

  /** Non-string pieces: each is one token of jq-ref's lexer by the observations of the tests above. */
  private static final List<String> TOKENS =
      List.of(
          ".", "..", ".a", ".and", ".e0", "a", "a::b", "andx", "and", "or", "if", "reduce", "__loc__", "$x", "$a::b",
          "$__loc__", "$", "@text", "@base64d", "1", "1.", ".5", "007", "1.e2", "1.5e3", "100000000000000000001", "?//",
          "//=", "|=", "==", "!=", "<=", ">=", "+=", "%=", "//", "|", ",", ";", ":", "=", "<", ">", "+", "-", "*", "/",
          "%", "?", "(", ")", "[", "]", "{", "}", "!", "@");

  /**
   * String pieces without interpolation, each recorded or observed as one literal: escapes, multi-byte UTF-8, a #
   * that starts no comment (jq-ref -n '"#a"' -> "#a"), a raw LF (literal/string-raw-lf) and an escaped quote.
   */
  private static final List<String> STRINGS =
      List.of(
          "\"a\"", "\"\"", "\"\\n\\t\"", "\"é😀\"", "\"a\\u00e9b\"", "\"#a\"", "\"a b\"", "\"\\\"\"", "\"x\ny\"",
          "\"\\\\\"");

  /**
   * Separators: SP, TAB, LF, CR (jq's whitespace, #33) and comments to the LF, one of them continued by a backslash
   * before the LF (recorded in diff/literal.rec: literal/comment-lf, literal/comment-continued).
   */
  private static final List<String> SEPARATORS = List.of(" ", "\t", "\n", "\r", "  ", "# c\n", "#\n", "# x\\\ny\n", " \t\r\n");

  /** The tokens before END; END must sit at the end of the text. */
  private static List<Token> tokens(String filter) {
    int length = filter.getBytes(UTF_8).length;
    Lexer lexer = new Lexer(filter);
    List<Token> out = new ArrayList<>();
    for (int guard = 0; guard <= length + 1; guard++) {
      Token t = lexer.next();
      if (t.kind() == Token.Kind.END) {
        assertThat(new int[] {t.start(), t.end()}).as("END of %s", shown(filter)).containsExactly(length, length);
        return out;
      }
      out.add(t);
    }
    throw new AssertionError("no END after " + (length + 2) + " tokens of " + shown(filter));
  }

  /** Each token's bytes as text. */
  private static List<String> texts(String filter) {
    byte[] b = filter.getBytes(UTF_8);
    return tokens(filter).stream().map(t -> new String(b, t.start(), t.end() - t.start(), UTF_8)).toList();
  }

  /** Each token as its kind, a space and its bytes as text. */
  private static List<String> kinded(String filter) {
    byte[] b = filter.getBytes(UTF_8);
    return tokens(filter).stream().map(t -> t.kind() + " " + new String(b, t.start(), t.end() - t.start(), UTF_8)).toList();
  }

  private static int bytes(StringBuilder text) {
    return text.toString().getBytes(UTF_8).length;
  }

  /** The text with its control characters spelled out, for a readable description. */
  private static String shown(String text) {
    return "'" + text.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") + "'";
  }
}
