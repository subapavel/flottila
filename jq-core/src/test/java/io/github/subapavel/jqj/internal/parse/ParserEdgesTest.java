package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.internal.parse.ParseAssertions.notGiven;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.parsesToAStub;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.show;
import static io.github.subapavel.jqj.internal.parse.ParseAssertions.syntaxError;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.parse.Token.Kind;
import io.github.subapavel.jqj.query.ir.Source;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The implementer's witnesses of #62 for the parse paths the approved tests do not reach (ADR-0032). Every expected
 * value is jq-ref 1.8.2's, observed 2026-11-12 with {@code jq-ref -n <filter>}: a syntax block's text is jq's with its
 * "expecting" list cut (DEVIATIONS "Syntax-error text"), a recovery or lexical block is jq's exactly, and a filter that
 * parses gives rc 0, or rc 3 with no syntax error ("module not found").
 */
class ParserEdgesTest {
  private static final String TRY_FIELD = "try .[\"field\"] instead of .field for unusually named fields";
  private static final String MAY_NEED_PARENTHESES = "May need parentheses around object key expression";

  /**
   * The kinds no syntax error names (jq's QQSTRING_TEXT, QQSTRING_INTERP_START and QQSTRING_END): the lexer gives one
   * only right after a string's opening quote, text or interpolation end. jq's QQString takes it after such a token
   * once shifted; once dropped, the recovery reports nothing. jq-ref attempts: {@code "\(. a)x"}, {@code "\(1 2)x\(3)y"}
   * and {@code . as "a" | 1} name QQSTRING_INTERP_END, LITERAL and QQSTRING_START.
   */
  private static final Set<Kind> NEVER_UNEXPECTED = EnumSet.of(Kind.TEXT, Kind.ESCAPES, Kind.INVALID_ESCAPES, Kind.INTERP_START, Kind.STRING_END);

  /** A syntax error at the token of {@code kind} at {@code column}, {@code length} bytes, named {@code name} by jq-ref. */
  private record Unexpected(Kind kind, String text, String name, int column, int length) {}

  @Test
  void aMissingNameOrKeyIsASyntaxError() {
    syntaxError("import \"a\" as 1; .", "LITERAL", 1, 15, 1);
    syntaxError("def if: 1; 2", "if", 1, 5, 1);
    syntaxError("def f(): 1; f", "')'", 1, 7, 1);
    syntaxError("label 1 | 2", "LITERAL", 1, 7, 1);
    syntaxError("$$$$__loc__", "$__loc__", 1, 4, 1);
    syntaxError("{(1) 2}", "LITERAL", 1, 6, 1);
    syntaxError("\"abc", "end of file", 1, 2, 1);
  }

  @Test
  void jqjsBlocksAreAPrefixOfJqsWhereItBuildsNoRecovery() {
    // DEVIATIONS "Syntax-error text": jq-ref gives each a second block, from a recovery jqj does not build yet
    // (ADR-0032): its "if", "try", "break" or object pattern message, or a later error after its silent '('.
    blocks("if . then 1", syntax("end of file", 1, 11, "if . then 1", carets(11, 1)));
    blocks("try 1 catch ]", syntax("INVALID_CHARACTER", 1, 13, "try 1 catch ]", carets(13, 1)));
    blocks("break 1", syntax("LITERAL", 1, 7, "break 1", carets(7, 1)));
    blocks(". as {1+2: $x} | 1", syntax("LITERAL", 1, 7, ". as {1+2: $x} | 1", carets(7, 1)));
    blocks("(1 2) | 3 | 4 5", syntax("LITERAL", 1, 4, "(1 2) | 3 | 4 5", carets(4, 1)));
    // #64 closed the gap (ADR-0036): jq's first block is the parser action's "Cannot use number (1) as object key" at
    // column 3, then the syntax block (diff/syntax.rec syntax/object-key-error-then-syntax-error).
    blocks(
        "{(1): 2 3}",
        message("Cannot use number (1) as object key", 1, 3, "{(1): 2 3}", carets(3, 1)),
        syntax("LITERAL", 1, 9, "{(1): 2 3}", carets(9, 1)));
  }

  @Test
  void theDirectivesAndPatternsParse() {
    for (String text :
        List.of(
            "import \"a\" as $x; .",
            "import \"a\" as x {a: 1}; .",
            // .a."b"? left with its Stub: #63 lowers it (ResolverTest.navigationAndCommaLowerToTheirIr). #67 lowers
            // as and its patterns, #68 if and elif, and #69 foreach, so those entries end with | .., which still
            // stubs.
            ". as $x | 1 | ..",
            ". as {\"a\": $b} | 1 | ..",
            // Unwrapped at #71. #72 builds tostring/0, so the key is ascii_downcase/0, which reaches spec 4's explode
            // and so still stubs (jq-ref 1.8.2, observed 2026-11-13 with -nc: 1, rc 0).
            ". as {(\"a\"|ascii_downcase): $e} | 1",
            ". as {$a: [$b]} | 1 | ..",
            "foreach . as $x (0; 1; 2) | ..",
            "if 1 then 2 elif 3 then 4 else 5 end | ..")) {
      parsesToAStub(text);
    }
    // A module directive or an import before definitions alone: no top-level filter either.
    notGiven("module {a: 1};");
    notGiven("import \"a\" as x; def f: 1;");
  }

  @Test
  void aLeadingDotRecoversAfterANameOrAKeywordThatNoTermTakes() {
    blocks(". a", syntax("end of file", 1, 3, ". a", "      ^"), message(TRY_FIELD, 1, 1, ". a", "    ^^^"));
    blocks(". if", syntax("if", 1, 3, ". if", "      ^^"), message(TRY_FIELD, 1, 1, ". if", "    ^^^^"));
    // After the inner dot's recovery its token comes back at once: the outer dot's recovery drops it.
    String nested = ".\"\\(. 1)\"";
    blocks(
        nested,
        syntax("LITERAL", 1, 7, nested, "          ^"),
        message(TRY_FIELD, 1, 5, nested, "        ^^^"),
        message(TRY_FIELD, 1, 1, nested, "    ^^^^^^^"));
    // The end of the text right after a recovery ends the parse, with no new block.
    blocks("(. a", syntax("end of file", 1, 4, "(. a", "       ^"), message(TRY_FIELD, 1, 2, "(. a", "     ^^^"));
    // The token the outer recovery drops is the string's quote, so the escape run after it is read, and its block
    // comes last; the parse ends at the run.
    String dropped = ".\"\\(. a \"\\x\")\"";
    blocks(
        dropped,
        syntax("QQSTRING_START", 1, 9, dropped, "            ^"),
        message(TRY_FIELD, 1, 5, dropped, "        ^^^^^"),
        message(TRY_FIELD, 1, 1, dropped, "    ^^^^^^^^^"),
        message("Invalid escape at line 1, column 4 (while parsing '\"\\x\"')", 1, 10, dropped, "             ^^"));
    // The end of the text right after the inner recovery ends the parse before the outer one.
    blocks(".\"\\(. a", syntax("end of file", 1, 7, ".\"\\(. a", "          ^"), message(TRY_FIELD, 1, 5, ".\"\\(. a", "        ^^^"));
    // A lexical block when its token is read, then the syntax error and the recovery after it.
    String lexical = "\"\\x\" + . 1";
    blocks(
        lexical,
        message("Invalid escape at line 1, column 4 (while parsing '\"\\x\"')", 1, 2, lexical, "     ^^"),
        syntax("LITERAL", 1, 10, lexical, "             ^"),
        message(TRY_FIELD, 1, 8, lexical, "           ^^^"));
  }

  @Test
  void aLeadingDotRecoversBeforeEachKindNoTermTakes() {
    // One case per kind of NO_TERM_AFTER: jq's '.' error takes it, so jq's message spans the dot and the token.
    leadingDot(". ..", "..", 3, 2);
    leadingDot(".?//1", "?//", 2, 3);
    leadingDot(".(1)", "'('", 2, 1);
    leadingDot(". {", "'{'", 3, 1);
    leadingDot(". 1", "LITERAL", 3, 1);
    leadingDot(". $x", "BINDING", 3, 2);
    leadingDot(". $__loc__", "$__loc__", 3, 8);
    leadingDot(". $", "'$'", 3, 1);
    leadingDot(". ]", "INVALID_CHARACTER", 3, 1);
  }

  @Test
  void aLeadingDotIsATermBeforeEachKeywordThatFollowsOne() {
    // One case per word of KEYWORDS_AFTER_A_TERM, each rc 0 on jq-ref; any other keyword is '.' error's (". if" above).
    // #66 lowers and and or, #67 as, and #68 if, elif, else and try, so their entries end with | .., which still stubs.
    for (String text :
        List.of(
            ". as $x | 1 | ..",
            "if . then 1 else 2 end | ..",
            "if 1 then . elif 2 then 3 else 4 end | ..",
            "if 1 then . else 2 end | ..",
            "if 1 then 2 else . end | ..",
            ". and true | ..",
            ". or true | ..",
            "try . catch 1 | ..")) {
      parsesToAStub(text);
    }
  }

  @Test
  void aSyntaxErrorNamesItsTokenAsJqDoes() {
    // One case per kind, each jq-ref's only block; jq's name of a keyword is its word.
    List<Unexpected> cases =
        List.of(
            new Unexpected(Kind.DOT, ". as . | 1", "'.'", 6, 1),
            new Unexpected(Kind.FIELD, ". as .a | 1", "FIELD", 6, 2),
            new Unexpected(Kind.REC, ". as .. | 1", "..", 6, 2),
            new Unexpected(Kind.PIPE, "| 1", "'|'", 1, 1),
            new Unexpected(Kind.COMMA, ", 1", "','", 1, 1),
            new Unexpected(Kind.SEMICOLON, ";", "';'", 1, 1),
            new Unexpected(Kind.COLON, ": 1", "':'", 1, 1),
            new Unexpected(Kind.QUESTION, "?", "'?'", 1, 1),
            new Unexpected(Kind.ALTERNATION, "?//", "?//", 1, 3),
            new Unexpected(Kind.ASSIGN, "= 1", "'='", 1, 1),
            new Unexpected(Kind.EQ, "== 1", "==", 1, 2),
            new Unexpected(Kind.NEQ, "!= 1", "!=", 1, 2),
            new Unexpected(Kind.LESS, "< 1", "'<'", 1, 1),
            new Unexpected(Kind.LESSEQ, "<= 1", "<=", 1, 2),
            new Unexpected(Kind.GREATER, "> 1", "'>'", 1, 1),
            new Unexpected(Kind.GREATEREQ, ">= 1", ">=", 1, 2),
            new Unexpected(Kind.PLUS, "+ 1", "'+'", 1, 1),
            new Unexpected(Kind.MINUS, ". as - | 1", "'-'", 6, 1),
            new Unexpected(Kind.STAR, "* 1", "'*'", 1, 1),
            new Unexpected(Kind.SLASH, "/ 1", "'/'", 1, 1),
            new Unexpected(Kind.PERCENT, "% 1", "'%'", 1, 1),
            new Unexpected(Kind.DEFINEDOR, "// 1", "//", 1, 2),
            new Unexpected(Kind.SETPIPE, "|= 1", "|=", 1, 2),
            new Unexpected(Kind.SETPLUS, "+= 1", "+=", 1, 2),
            new Unexpected(Kind.SETMINUS, "-= 1", "-=", 1, 2),
            new Unexpected(Kind.SETMULT, "*= 1", "*=", 1, 2),
            new Unexpected(Kind.SETDIV, "/= 1", "/=", 1, 2),
            new Unexpected(Kind.SETMOD, "%= 1", "%=", 1, 2),
            new Unexpected(Kind.SETDEFINEDOR, "//= 1", "//=", 1, 3),
            new Unexpected(Kind.LPAREN, "1 (", "'('", 3, 1),
            new Unexpected(Kind.RPAREN, "(1 +)", "')'", 5, 1),
            new Unexpected(Kind.LBRACKET, "label [ | 1", "'['", 7, 1),
            new Unexpected(Kind.RBRACKET, "[1 +]", "']'", 5, 1),
            new Unexpected(Kind.LBRACE, "1 {", "'{'", 3, 1),
            new Unexpected(Kind.RBRACE, "{a: }", "'}'", 5, 1),
            new Unexpected(Kind.NUMBER, "1 2", "LITERAL", 3, 1),
            new Unexpected(Kind.IDENT, "1 a", "IDENT", 3, 1),
            new Unexpected(Kind.KEYWORD, "1 reduce", "reduce", 3, 6),
            new Unexpected(Kind.BINDING, "1 $x", "BINDING", 3, 2),
            new Unexpected(Kind.LOC, "1 $__loc__", "$__loc__", 3, 8),
            new Unexpected(Kind.DOLLAR, "1 $", "'$'", 3, 1),
            new Unexpected(Kind.FORMAT, "1 @base64", "FORMAT", 3, 7),
            new Unexpected(Kind.STRING_START, "\"a\" \"b\"", "QQSTRING_START", 5, 1),
            new Unexpected(Kind.INTERP_END, "\"\\(1 +)x\"", "QQSTRING_INTERP_END", 7, 1),
            new Unexpected(Kind.INVALID_CHARACTER, "1 ]", "INVALID_CHARACTER", 3, 1),
            new Unexpected(Kind.END, "1 + # c", "end of file", 7, 1));
    Set<Kind> named = EnumSet.copyOf(NEVER_UNEXPECTED);
    for (Unexpected c : cases) {
      assertThat(kindAt(c.text(), c.column() - 1)).as("kind at column %d of %s", c.column(), show(c.text())).isEqualTo(c.kind());
      blocks(c.text(), syntax(c.name(), 1, c.column(), c.text(), carets(c.column(), c.length())));
      named.add(c.kind());
    }
    assertThat(named).as("kinds with a case or never unexpected").isEqualTo(EnumSet.allOf(Kind.class));
  }

  @Test
  void builtinJqIsItsDefinitionsAlone() throws IOException {
    String text;
    // ADR-0042: the prelude's main resource since #71, moved from the test resource jq/builtin.jq.
    try (InputStream in = ParserEdgesTest.class.getResourceAsStream("/io/github/subapavel/jqj/internal/parse/builtin.jq")) {
      assertThat(in).as("the main resource builtin.jq").isNotNull();
      text = new String(in.readAllBytes(), UTF_8);
    }
    // Each top-level definition of jq's file starts its line; the nested ones are indented.
    List<String> names = Pattern.compile("^def ([A-Za-z_0-9]+)", Pattern.MULTILINE).matcher(text).results().map(m -> m.group(1)).toList();
    Program program = Parser.parse(text);
    assertThat(program.module()).as("module of builtin.jq").isNull();
    assertThat(program.imports()).as("imports of builtin.jq").isEmpty();
    assertThat(program.main()).as("top-level filter of builtin.jq").isNull();
    assertThat(program.defs()).extracting(Ast.FuncDef::name).as("definitions of builtin.jq").hasSize(106).containsExactlyElementsOf(names);
  }

  @Test
  void anObjectKeyErrorStartsAtItsPairOrAtTheFirstPair() {
    // After a comma the error goes to the comma's state: its span starts at the pair, and a closing brace is dropped.
    blocks(
        "{a:1, 1+2:3}",
        syntax("LITERAL", 1, 7, "{a:1, 1+2:3}", "          ^"),
        message(MAY_NEED_PARENTHESES, 1, 7, "{a:1, 1+2:3}", "          ^^^"));
    blocks(
        "{a:1, 1}: 2}",
        syntax("LITERAL", 1, 7, "{a:1, 1}: 2}", "          ^"),
        message(MAY_NEED_PARENTHESES, 1, 7, "{a:1, 1}: 2}", "          ^^"));
    syntaxError("{a:1, b: }", "'}'", 1, 10, 1);
    // After a whole pair the error goes to the brace's state, where jq's silent '{' error '}' takes the closing brace;
    // jqj does not build it, so the parse ends there.
    syntaxError("{a 1}", "LITERAL", 1, 4, 1);
    // jq-ref gives 3 blocks here: its '{' error '}' closes the first object, then the second one recovers. jqj's one
    // block is the first of them; dropping the brace instead would run the recovery on into the second object.
    blocks("{1 2} | {3+4:5}", syntax("LITERAL", 1, 2, "{1 2} | {3+4:5}", "     ^"));
    // A recovered pair's value is the token after it; a dot's recovery inside drops its token for the object.
    blocks(
        "{a: . 1}",
        syntax("LITERAL", 1, 7, "{a: . 1}", "          ^"),
        message(TRY_FIELD, 1, 5, "{a: . 1}", "        ^^^"));
    // The colon right after the dot's recovery is the object's error at once, so it is dropped, not a key's colon.
    blocks(
        "{a: . b: 1}",
        syntax("':'", 1, 8, "{a: . b: 1}", "           ^"),
        message(TRY_FIELD, 1, 5, "{a: . b: 1}", "        ^^^^"));
    // Two tokens shifted after a recovery: the next error is not reported, and its token is dropped.
    blocks(
        "{1+2:3 4}",
        syntax("LITERAL", 1, 2, "{1+2:3 4}", "     ^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 4}", "     ^^^"));
    // There the token is kept, so a colon is the next recovery's.
    blocks(
        "{1+2:3 :4}",
        syntax("LITERAL", 1, 2, "{1+2:3 :4}", "     ^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 :4}", "     ^^^"),
        message(MAY_NEED_PARENTHESES, 1, 2, "{1+2:3 :4}", "     ^^^^^^^"));
    // The outer recovery's block comes after the blocks of its value, an object with its own recovery.
    String nested = "{a: 1 2: 3 | {1+2:3}}";
    blocks(
        nested,
        syntax("LITERAL", 1, 7, nested, "          ^"),
        syntax("LITERAL", 1, 15, nested, "                  ^"),
        message(MAY_NEED_PARENTHESES, 1, 15, nested, "                  ^^^"),
        message(MAY_NEED_PARENTHESES, 1, 2, nested, "     ^^^^^^"));
  }

  @Test
  void anUnderscoreOrASpaceEndsTheDigitsOfAUnicodeEscape() {
    // The escape run takes letters and digits after the u, so an underscore or a space after it is text.
    blocks("\"\\u_\"", message("Invalid \\uXXXX escape at line 1, column 4 (while parsing '\"\\u\"')", 1, 2, "\"\\u_\"", "     ^^"));
    blocks("\"\\u1 \"", message("Invalid \\uXXXX escape at line 1, column 5 (while parsing '\"\\u1\"')", 1, 2, "\"\\u1 \"", "     ^^^"));
  }

  @Test
  void aCommentEndsAtCrLf() {
    // The CR LF is the comment's last piece, so the end of the text is reported at the CR.
    syntaxError(".a | # c\r\n", "end of file", 1, 9, 1);
    blocks(".a | # c\r\n", syntax("end of file", 1, 9, ".a | # c\r", "            ^"));
  }

  @Test
  void theOperatorsNestByTheirLevels() {
    // jq-ref 1.8.2, observed 2026-11-12 and ADR-0032's facts: 2 * 3 as $x | $x + 1 gives 7, - 1 + 2 gives 1, try 1
    // catch 2 + 3 gives 4; the precedence/* recordings fix the levels of //, =, or and and.
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("2 * 3 as $x | $x + 1", "((2 MULTIPLY 3) as $x | ($x ADD 1))"),
            Map.entry("- 1 + 2", "(-1 ADD 2)"),
            Map.entry("try 1 catch 2 + 3", "(try 1 catch 2 ADD 3)"),
            Map.entry("try 1 + 2", "(try 1 ADD 2)"),
            Map.entry("1, 2 | 3", "((1, 2) | 3)"),
            Map.entry("1 | 2 | 3", "(1 | (2 | 3))"),
            Map.entry("1, 2, 3", "((1, 2), 3)"),
            Map.entry("1 - 2 - 3", "((1 SUBTRACT 2) SUBTRACT 3)"),
            Map.entry("1 // 2 // 3", "(1 ALTERNATIVE (2 ALTERNATIVE 3))"),
            Map.entry(".a = null // 2", "((.a ASSIGN null) ALTERNATIVE 2)"),
            Map.entry("1 or 2 and 3", "(1 OR (2 AND 3))"),
            Map.entry("1 == 2 + 3 * 4", "(1 EQUAL (2 ADD (3 MULTIPLY 4)))"),
            Map.entry("1 < 2 or 3 >= 4", "((1 LESS 2) OR (3 GREATER_EQUAL 4))"),
            Map.entry("1 / 2 % 3 * 4", "(((1 DIVIDE 2) MODULO 3) MULTIPLY 4)"),
            Map.entry(".a |= 1 // 2", "((.a UPDATE 1) ALTERNATIVE 2)"),
            Map.entry("1 | def f: 2; f, 3", "(1 | def f: 2; (f, 3))"),
            Map.entry("[1, 2 as $x | $x, 3]", "[(1, (2 as $x | ($x, 3)))]"),
            Map.entry("label $out | 1, 2", "label $out | (1, 2)"),
            Map.entry("{a: 1 | 2, b: 3}", "{a: (1 | 2), b: 3}"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(tree(Parser.parse(e.getKey()).main())).as("AST of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void aPostfixNodeSpansItsOwnBytes() {
    // ADR-0032: from the start of the suffix to its end, a ? after it included.
    Ast.Index index = (Ast.Index) Parser.parse(".a[0]?").main();
    assertThat(index.source()).as("source of [0]? in .a[0]?").isEqualTo(new Source(2, 6));
    assertThat(index.optional()).as("[0]? is optional").isTrue();
    Ast.Slice slice = (Ast.Slice) Parser.parse(".a.[1:]").main();
    assertThat(slice.source()).as("source of .[1:] in .a.[1:]").isEqualTo(new Source(2, 7));
    assertThat(slice.to()).as("the open end of .[1:]").isNull();
    Ast.Optional optional = (Ast.Optional) Parser.parse("1?").main();
    assertThat(optional.source()).as("source of ? in 1?").isEqualTo(new Source(1, 2));
    Ast.Field field = (Ast.Field) Parser.parse(". .b?").main();
    assertThat(field.source()).as("source of .b? in . .b?").isEqualTo(new Source(2, 5));
  }

  /** The parse of {@code text} throws exactly {@code expected}, in order. */
  private static void blocks(String text, String... expected) {
    JqCompileException e =
        assertThatExceptionOfType(JqCompileException.class).as("parse of %s", show(text)).isThrownBy(() -> Parser.parse(text)).actual();
    assertThat(e.blocks()).as("blocks of %s", show(text)).containsExactly(expected);
  }

  /** A leading dot before {@code unexpected} at {@code column}: the syntax error, then jq's message from the dot on. */
  private static void leadingDot(String text, String unexpected, int column, int length) {
    blocks(text, syntax(unexpected, 1, column, text, carets(column, length)), message(TRY_FIELD, 1, 1, text, carets(1, column - 1 + length)));
  }

  /** The caret line under {@code length} bytes from {@code column} of a one-line text, after jq's indent. */
  private static String carets(int column, int length) {
    return "    " + " ".repeat(column - 1) + "^".repeat(length);
  }

  /** The kind of the token at byte {@code offset} of {@code text}, or the END where the lexer's last match is there. */
  private static Kind kindAt(String text, int offset) {
    Lexer lexer = new Lexer(text);
    for (Token t = lexer.next(); t.kind() != Kind.END; t = lexer.next()) {
      if (t.start() == offset) {
        return t.kind();
      }
    }
    return lexer.lastStart() == offset ? Kind.END : null;
  }

  /** jqj's syntax block: jq's with its expecting list cut. */
  private static String syntax(String unexpected, int line, int column, String sourceLine, String carets) {
    return message("syntax error, unexpected " + unexpected, line, column, sourceLine, carets);
  }

  private static String message(String message, int line, int column, String sourceLine, String carets) {
    return "jq: error: " + message + " at <top-level>, line " + line + ", column " + column + ":\n    " + sourceLine + "\n" + carets;
  }

  /** A compact text of the AST kinds above: operators by their BinOp name, prefix forms with their bodies. */
  private static String tree(Ast ast) {
    return switch (ast) {
      case Ast.Binary x -> "(" + tree(x.left()) + " " + x.op() + " " + tree(x.right()) + ")";
      case Ast.Pipe x -> "(" + tree(x.left()) + " | " + tree(x.right()) + ")";
      case Ast.Comma x -> "(" + tree(x.left()) + ", " + tree(x.right()) + ")";
      case Ast.Neg x -> "-" + tree(x.operand());
      case Ast.Try x -> "try " + tree(x.body()) + (x.handler() == null ? "" : " catch " + tree(x.handler()));
      case Ast.Bind x -> "(" + tree(x.value()) + " as $" + ((Ast.VarPattern) x.patterns().getFirst()).name() + " | " + tree(x.body()) + ")";
      case Ast.Def x -> "def " + x.def().name() + ": " + tree(x.def().body()) + "; " + tree(x.body());
      case Ast.Label x -> "label $" + x.name() + " | " + tree(x.body());
      case Ast.ArrayCons x -> "[" + tree(x.body()) + "]";
      case Ast.ObjectCons x ->
          x.pairs().stream().map(p -> ((Ast.NameKey) p.key()).name() + ": " + tree(p.value())).collect(joining(", ", "{", "}"));
      case Ast.Field x -> (x.target() == null ? "" : tree(x.target())) + "." + x.name();
      case Ast.Num x -> x.lexeme();
      case Ast.Var x -> "$" + x.name();
      case Ast.Call x -> x.name();
      default -> ast.getClass().getSimpleName();
    };
  }
}
