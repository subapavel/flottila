/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): the token rules, the start conditions and try_exit in
 * src/lexer.l.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Splits a filter's UTF-8 bytes into tokens as jq 1.8.2's lexer does ({@code src/lexer.l}; #61 design), each the
 * longest match at its byte. Outside a string literal it skips SP, TAB, CR and LF, jq's whitespace (#33), and comments:
 * a {@code #} runs to the next LF, and a backslash before the LF, or before a CR and the LF, continues it unless the
 * backslash is itself escaped by one before it; a CR alone ends nothing.
 *
 * <p>Modes, as jq's start conditions: an opening parenthesis, bracket or brace opens one, and so does an
 * interpolation's {@code \(}; a closer that matches the innermost closes it, any other closer is an INVALID_CHARACTER
 * and closes nothing. Inside a string literal the tokens are runs of plain bytes (TEXT), escape runs, an interpolation's start
 * and the closing quote. An escape run is checked as one JSON string, as jq does: wrapped in quotes, its bad UTF-8
 * replaced, then {@code found_string}'s checks (ADR-0031). A bad run's error block is kept for the parser, which adds it
 * when it reads the run, as jq's {@code yylex} does (ADR-0032).
 *
 * <p>The lexer keeps its last match, as jq's {@code YY_USER_ACTION} keeps it in the location the parser reports the
 * end of the text at: a token, a whole whitespace run, or one piece of a comment (its {@code #}, a byte, a backslash
 * with what it takes along, or the LF or CR LF that ends it).
 */
final class Lexer {
  /** jq's keywords; any other name is an IDENT, {@code __loc__} included. */
  private static final Set<String> KEYWORDS =
      Set.of(
          "as", "def", "module", "import", "include", "if", "then", "elif", "else", "end", "and", "or", "reduce",
          "foreach", "try", "catch", "label", "break");

  /** The name of {@code $__loc__}, a LOC rather than a BINDING. */
  private static final byte[] LOC = "__loc__".getBytes(US_ASCII);

  /** The mode of an open interpolation; the other modes are their closing parenthesis, bracket or brace. */
  private static final byte INTERP = 'I';

  /** The mode of an open string literal. */
  private static final byte STRING = '"';

  private final FilterText text;
  private final byte[] b;
  private int p;

  /** The open modes, innermost last; {@code modes[0]} is the top level's, which nothing closes. */
  private byte[] modes = new byte[16];

  private int depth = 1;

  /** The error blocks of the bad escape runs met so far, in order. */
  private final List<String> blocks = new ArrayList<>();

  /** The bytes {@code [lastStart, lastEnd)} of the last match; both 0 before the first. */
  private int lastStart;

  private int lastEnd;

  Lexer(String text) {
    this.text = new FilterText(text);
    this.b = this.text.bytes();
  }

  /** The filter's text, whose bytes the tokens' offsets index. */
  FilterText text() {
    return text;
  }

  /** The error block of each INVALID_ESCAPES token lexed so far, in order (ADR-0031). */
  List<String> errorBlocks() {
    return blocks;
  }

  /** The first byte of the last match: after the END, where jq reports the end of the text. */
  int lastStart() {
    return lastStart;
  }

  /** The byte after the last match. */
  int lastEnd() {
    return lastEnd;
  }

  /** The next token, {@link Token.Kind#END} at the end of the text. */
  Token next() {
    if (modes[depth - 1] == STRING) {
      return stringPart();
    }
    skipSpace();
    int start = p;
    if (p == b.length) {
      return new Token(Token.Kind.END, start, p);
    }
    Token.Kind kind =
        switch (b[p++]) {
          case '.' -> dot();
          case '?' -> {
            if (at(p) == '/' && at(p + 1) == '/') {
              p += 2;
              yield Token.Kind.ALTERNATION;
            }
            yield Token.Kind.QUESTION;
          }
          case '/' -> take('/') ? orEquals(Token.Kind.DEFINEDOR, Token.Kind.SETDEFINEDOR) : orEquals(Token.Kind.SLASH, Token.Kind.SETDIV);
          case '|' -> orEquals(Token.Kind.PIPE, Token.Kind.SETPIPE);
          case '=' -> orEquals(Token.Kind.ASSIGN, Token.Kind.EQ);
          case '!' -> orEquals(Token.Kind.INVALID_CHARACTER, Token.Kind.NEQ);
          case '<' -> orEquals(Token.Kind.LESS, Token.Kind.LESSEQ);
          case '>' -> orEquals(Token.Kind.GREATER, Token.Kind.GREATEREQ);
          case '+' -> orEquals(Token.Kind.PLUS, Token.Kind.SETPLUS);
          case '-' -> orEquals(Token.Kind.MINUS, Token.Kind.SETMINUS);
          case '*' -> orEquals(Token.Kind.STAR, Token.Kind.SETMULT);
          case '%' -> orEquals(Token.Kind.PERCENT, Token.Kind.SETMOD);
          case ',' -> Token.Kind.COMMA;
          case ';' -> Token.Kind.SEMICOLON;
          case ':' -> Token.Kind.COLON;
          case '$' -> dollar();
          case '@' -> format();
          case '"' -> open(STRING, Token.Kind.STRING_START);
          case '(' -> open((byte) ')', Token.Kind.LPAREN);
          case '[' -> open((byte) ']', Token.Kind.LBRACKET);
          case '{' -> open((byte) '}', Token.Kind.LBRACE);
          case ')', ']', '}' -> close(b[start]);
          default -> word(start);
        };
    return matched(kind, start);
  }

  /** Skips whitespace runs and comments, each run and each comment piece a match of its own. */
  private void skipSpace() {
    for (;;) {
      int start = p;
      if (isSpace(at(p))) {
        do {
          p++;
        } while (isSpace(at(p)));
        match(start);
      } else if (at(p) == '#') {
        comment();
      } else {
        return;
      }
    }
  }

  /**
   * A comment, jq's IN_COMMENT, from its {@code #} up to the LF or CR LF that ends it, or the end of the text: a
   * backslash takes the byte after it along when that is a backslash or an LF, and a CR and LF after it too, so an odd
   * run of backslashes before the line end continues the comment. The {@code #}, each such pair or triple, each other
   * byte and the ending LF or CR LF are one match each.
   */
  private void comment() {
    p++;
    match(p - 1);
    while (p < b.length) {
      int start = p;
      if (b[p] == '\\' && (at(p + 1) == '\\' || at(p + 1) == '\n')) {
        p += 2;
      } else if (b[p] == '\\' && at(p + 1) == '\r' && at(p + 2) == '\n') {
        p += 3;
      } else if (b[p] == '\n' || b[p] == '\r' && at(p + 1) == '\n') {
        p += b[p] == '\n' ? 1 : 2;
        match(start);
        return;
      } else {
        p++;
      }
      match(start);
    }
  }

  /** Records {@code [start, p)} as the last match. */
  private void match(int start) {
    lastStart = start;
    lastEnd = p;
  }

  /** The token of {@code kind} over {@code [start, p)}, recorded as the last match. */
  private Token matched(Token.Kind kind, int start) {
    match(start);
    return new Token(kind, start, p);
  }

  /**
   * After a dot: a second dot is REC ({@code ...} is REC then a dot); a name start makes a FIELD of the name; a digit a
   * NUMBER ({@code .5}); anything else leaves a DOT.
   */
  private Token.Kind dot() {
    if (take('.')) {
      return Token.Kind.REC;
    }
    if (isNameStart(at(p))) {
      name();
      return Token.Kind.FIELD;
    }
    if (isDigit(at(p))) {
      digits();
      exponent();
      return Token.Kind.NUMBER;
    }
    return Token.Kind.DOT;
  }

  /**
   * A token that starts at a digit or a name start: {@code [0-9]+(\.[0-9]*)?} and an exponent, or a qualified name,
   * a KEYWORD when it is one of jq's keywords; any other byte is an INVALID_CHARACTER of its own.
   */
  private Token.Kind word(int start) {
    int c = b[start];
    if (isDigit(c)) {
      digits();
      if (take('.')) {
        digits();
      }
      exponent();
      return Token.Kind.NUMBER;
    }
    if (!isNameStart(c)) {
      return Token.Kind.INVALID_CHARACTER;
    }
    p = start;
    qualifiedName();
    return KEYWORDS.contains(new String(b, start, p - start, US_ASCII)) ? Token.Kind.KEYWORD : Token.Kind.IDENT;
  }

  /** After {@code $}: a qualified name makes a BINDING, LOC when it is {@code __loc__} alone; no name leaves a DOLLAR. */
  private Token.Kind dollar() {
    if (!isNameStart(at(p))) {
      return Token.Kind.DOLLAR;
    }
    int start = p;
    qualifiedName();
    return Arrays.equals(b, start, p, LOC, 0, LOC.length) ? Token.Kind.LOC : Token.Kind.BINDING;
  }

  /** After {@code @}: {@code [a-zA-Z0-9_]+} makes a FORMAT; a lone {@code @} is an INVALID_CHARACTER. */
  private Token.Kind format() {
    if (!isNameChar(at(p))) {
      return Token.Kind.INVALID_CHARACTER;
    }
    do {
      p++;
    } while (isNameChar(at(p)));
    return Token.Kind.FORMAT;
  }

  /** Opens {@code mode} and gives {@code kind}. */
  private Token.Kind open(byte mode, Token.Kind kind) {
    if (depth == modes.length) {
      modes = Arrays.copyOf(modes, depth << 1);
    }
    modes[depth++] = mode;
    return kind;
  }

  /** jq's {@code try_exit}: {@code c} closes the innermost mode when it is its closer, and is an INVALID_CHARACTER otherwise. */
  private Token.Kind close(byte c) {
    byte innermost = modes[depth - 1];
    if (innermost == c) {
      depth--;
      return c == ')' ? Token.Kind.RPAREN : c == ']' ? Token.Kind.RBRACKET : Token.Kind.RBRACE;
    }
    if (innermost == INTERP && c == ')') {
      depth--;
      return Token.Kind.INTERP_END;
    }
    return Token.Kind.INVALID_CHARACTER;
  }

  /**
   * A token inside a string literal: the closing quote; a run of bytes that are neither a backslash nor a quote (TEXT);
   * an interpolation's {@code \(}; an escape run; or a backslash at the end of the text, an INVALID_CHARACTER. The end
   * of the text is the END, inside the literal.
   */
  private Token stringPart() {
    int start = p;
    Token.Kind kind;
    if (p == b.length) {
      return new Token(Token.Kind.END, start, p);
    } else if (b[p] == '"') {
      p++;
      depth--;
      kind = Token.Kind.STRING_END;
    } else if (b[p] != '\\') {
      do {
        p++;
      } while (p < b.length && b[p] != '\\' && b[p] != '"');
      kind = Token.Kind.TEXT;
    } else if (at(p + 1) == '(') {
      p += 2;
      kind = open(INTERP, Token.Kind.INTERP_START);
    } else if (p + 1 == b.length) {
      p++;
      kind = Token.Kind.INVALID_CHARACTER;
    } else {
      escapeRun();
      kind = check(start, p);
    }
    return matched(kind, start);
  }

  /**
   * jq's escape run {@code (\\[^u(]|\\u[a-zA-Z0-9]{0,4})+} from the backslash at p, its longest match: a backslash and
   * any one byte but {@code u} and {@code (}, or a backslash, {@code u} and up to four letters or digits, repeated.
   */
  private void escapeRun() {
    do {
      p += 2;
      if (b[p - 1] == 'u') {
        for (int k = 0; k < 4 && isAlnum(at(p)); k++) {
          p++;
        }
      }
    } while (p + 1 < b.length && b[p] == '\\' && b[p + 1] != '(');
  }

  /**
   * Checks the escape run {@code [start, end)} as jq's lexer does: {@code jv_parse} of the run in quotes, as a string
   * with its bad UTF-8 replaced. A bad run's error block is kept, with jq's message and the position of that
   * string's last byte, its closing quote unless a cut sequence took it, then the string itself (ADR-0031).
   */
  private Token.Kind check(int start, int end) {
    byte[] quoted = new byte[end - start + 2];
    quoted[0] = '"';
    System.arraycopy(b, start, quoted, 1, end - start);
    quoted[quoted.length - 1] = '"';
    byte[] parsed = JsonStrings.replaceBad(quoted, 0, quoted.length);
    // A lead byte of three or four that ends the run is cut by the closing quote, which goes into its U+FFFD; then
    // jv_parse meets the end inside the string (jq-ref 1.8.2, 2026-11-12: "\€" gives "Unfinished string at EOF").
    String error =
        parsed[parsed.length - 1] != '"' ? "Unfinished string at EOF" : JsonStrings.escapeError(parsed, 1, parsed.length - 1);
    if (error == null) {
      return Token.Kind.ESCAPES;
    }
    int line = 1;
    int lineStart = 0;
    for (int i = 0; i < parsed.length; i++) {
      if (parsed[i] == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    String message =
        error + " at line " + line + ", column " + (parsed.length - lineStart) + " (while parsing '" + new String(parsed, UTF_8) + "')";
    blocks.add(text.block(message, start, end));
    return Token.Kind.INVALID_ESCAPES;
  }

  /** A qualified name from p: {@code ([a-zA-Z_][a-zA-Z_0-9]*::)*[a-zA-Z_][a-zA-Z_0-9]*}, p at its first byte. */
  private void qualifiedName() {
    name();
    while (at(p) == ':' && at(p + 1) == ':' && isNameStart(at(p + 2))) {
      p += 2;
      name();
    }
  }

  /** {@code [a-zA-Z_][a-zA-Z_0-9]*} from p, p at its first byte. */
  private void name() {
    do {
      p++;
    } while (isNameChar(at(p)));
  }

  private void digits() {
    while (isDigit(at(p))) {
      p++;
    }
  }

  /** {@code ([eE][+-]?[0-9]+)?}: taken only whole, so {@code 1e} leaves the {@code e} to the next token. */
  private void exponent() {
    int c = at(p);
    if (c == 'e' || c == 'E') {
      int q = at(p + 1) == '+' || at(p + 1) == '-' ? p + 2 : p + 1;
      if (isDigit(at(q))) {
        p = q;
        digits();
      }
    }
  }

  /** Takes {@code =} after an operator byte when it is there: {@code withEquals}, or else {@code alone}. */
  private Token.Kind orEquals(Token.Kind alone, Token.Kind withEquals) {
    return take('=') ? withEquals : alone;
  }

  /** Takes the byte at p when it is {@code c}. */
  private boolean take(char c) {
    if (at(p) == c) {
      p++;
      return true;
    }
    return false;
  }

  /** The byte at {@code i} as 0 to 255, or -1 past the end. */
  private int at(int i) {
    return i < b.length ? b[i] & 0xFF : -1;
  }

  /** jq's whitespace in a filter: SP, TAB, CR and LF (#33). */
  private static boolean isSpace(int c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  private static boolean isNameStart(int c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
  }

  private static boolean isNameChar(int c) {
    return isNameStart(c) || isDigit(c);
  }

  private static boolean isAlnum(int c) {
    return isNameChar(c) && c != '_';
  }

  private static boolean isDigit(int c) {
    return c >= '0' && c <= '9';
  }
}
