/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): the token names and their aliases in src/parser.y.
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

/**
 * A token of the filter text: its kind and its UTF-8 bytes {@code [start, end)} (ADR-0031). A FIELD's name is its
 * bytes after the dot; a string literal is a STRING_START, its parts and a STRING_END. The kinds are jq 1.8.2's
 * lexer's ({@code src/lexer.l}), named after the parser's token names where it has them.
 */
record Token(Kind kind, int start, int end) {
  /** Each kind with the name jq's parser gives it in a syntax error ({@code parser.y}'s token names and aliases). */
  enum Kind {
    /** {@code .} alone. */
    DOT("'.'"),
    /** {@code .name}. */
    FIELD("FIELD"),
    /** {@code ..}. */
    REC(".."),
    PIPE("'|'"),
    COMMA("','"),
    SEMICOLON("';'"),
    COLON("':'"),
    QUESTION("'?'"),
    /** {@code ?//}. */
    ALTERNATION("?//"),
    /** {@code =}. */
    ASSIGN("'='"),
    EQ("=="),
    NEQ("!="),
    LESS("'<'"),
    LESSEQ("<="),
    GREATER("'>'"),
    GREATEREQ(">="),
    PLUS("'+'"),
    MINUS("'-'"),
    STAR("'*'"),
    SLASH("'/'"),
    PERCENT("'%'"),
    /** {@code //}. */
    DEFINEDOR("//"),
    SETPIPE("|="),
    SETPLUS("+="),
    SETMINUS("-="),
    SETMULT("*="),
    SETDIV("/="),
    SETMOD("%="),
    SETDEFINEDOR("//="),
    LPAREN("'('"),
    RPAREN("')'"),
    LBRACKET("'['"),
    RBRACKET("']'"),
    LBRACE("'{'"),
    RBRACE("'}'"),
    /** A number lexeme, jq's LITERAL. */
    NUMBER("LITERAL"),
    /** A name, {@code a::b} included, that is no keyword; {@code true}, {@code false} and {@code null} are IDENTs. */
    IDENT("IDENT"),
    /** One of jq's keywords, a whole word; jq names it by the word itself. */
    KEYWORD(null),
    /** {@code $name}. */
    BINDING("BINDING"),
    /** {@code $__loc__}. */
    LOC("$__loc__"),
    /** A {@code $} before no name. */
    DOLLAR("'$'"),
    /** {@code @name}. */
    FORMAT("FORMAT"),
    /** The {@code "} that opens a string literal. */
    STRING_START("QQSTRING_START"),
    /** A run of a string literal's bytes that are neither {@code \\} nor {@code "}. */
    TEXT("QQSTRING_TEXT"),
    /** An escape run (GLOSSARY) that jq's checks pass. */
    ESCAPES("QQSTRING_TEXT"),
    /** An escape run that jq's checks reject; the lexer has collected its error block. */
    INVALID_ESCAPES("QQSTRING_TEXT"),
    /** The {@code \(} that opens an interpolation. */
    INTERP_START("QQSTRING_INTERP_START"),
    /** The {@code )} that closes an interpolation. */
    INTERP_END("QQSTRING_INTERP_END"),
    /** The {@code "} that closes a string literal. */
    STRING_END("QQSTRING_END"),
    /** A byte no rule takes, or a closer that closes nothing open, jq's INVALID_CHARACTER. */
    INVALID_CHARACTER("INVALID_CHARACTER"),
    /** The end of the text. */
    END("end of file");

    /** jq's name of the kind after {@code unexpected } in a syntax error; null for a KEYWORD, named by its word. */
    final String jqName;

    Kind(String jqName) {
      this.jqName = jqName;
    }
  }
}
