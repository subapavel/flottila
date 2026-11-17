/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): the precedence and associativity declarations in
 * src/parser.y.
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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The binary operators of jq's {@code Expr} with jq 1.8.2's precedence and associativity ({@code parser.y}'s
 * {@code %right}, {@code %nonassoc} and {@code %left} lines), lowest first. A higher level binds tighter. Two operators
 * of one non-associative level in a row are a syntax error at the second (jq-ref 1.8.2, recorded:
 * syntax/compare-nonassoc). The query level's {@code |} (right) and {@code ,} (left) bind looser than every one of
 * them, and {@code def}, {@code as} and {@code label} looser still (ADR-0032).
 */
public enum BinOp {
  ALTERNATIVE(Token.Kind.DEFINEDOR, 1, Assoc.RIGHT),
  ASSIGN(Token.Kind.ASSIGN, 2, Assoc.NONE),
  UPDATE(Token.Kind.SETPIPE, 2, Assoc.NONE),
  ADD_UPDATE(Token.Kind.SETPLUS, 2, Assoc.NONE),
  SUBTRACT_UPDATE(Token.Kind.SETMINUS, 2, Assoc.NONE),
  MULTIPLY_UPDATE(Token.Kind.SETMULT, 2, Assoc.NONE),
  DIVIDE_UPDATE(Token.Kind.SETDIV, 2, Assoc.NONE),
  MODULO_UPDATE(Token.Kind.SETMOD, 2, Assoc.NONE),
  ALTERNATIVE_UPDATE(Token.Kind.SETDEFINEDOR, 2, Assoc.NONE),
  OR("or", 3, Assoc.LEFT),
  AND("and", 4, Assoc.LEFT),
  EQUAL(Token.Kind.EQ, 5, Assoc.NONE),
  NOT_EQUAL(Token.Kind.NEQ, 5, Assoc.NONE),
  LESS(Token.Kind.LESS, 5, Assoc.NONE),
  GREATER(Token.Kind.GREATER, 5, Assoc.NONE),
  LESS_EQUAL(Token.Kind.LESSEQ, 5, Assoc.NONE),
  GREATER_EQUAL(Token.Kind.GREATEREQ, 5, Assoc.NONE),
  ADD(Token.Kind.PLUS, 6, Assoc.LEFT),
  SUBTRACT(Token.Kind.MINUS, 6, Assoc.LEFT),
  MULTIPLY(Token.Kind.STAR, 7, Assoc.LEFT),
  DIVIDE(Token.Kind.SLASH, 7, Assoc.LEFT),
  MODULO(Token.Kind.PERCENT, 7, Assoc.LEFT);

  /** How a run of operators of one level nests. */
  enum Assoc {
    LEFT,
    RIGHT,
    /** Not at all: a second operator of the level is a syntax error. */
    NONE
  }

  private static final Map<Token.Kind, BinOp> BY_KIND = new EnumMap<>(Token.Kind.class);
  private static final Map<String, BinOp> BY_WORD = new HashMap<>();

  static {
    for (BinOp op : values()) {
      if (op.kind != null) {
        BY_KIND.put(op.kind, op);
      } else {
        BY_WORD.put(op.word, op);
      }
    }
  }

  /** The operator's token, or null for a keyword operator. */
  private final Token.Kind kind;

  /** The keyword of {@code and} and {@code or}, null otherwise. */
  private final String word;

  final int level;
  final Assoc assoc;

  BinOp(Token.Kind kind, int level, Assoc assoc) {
    this.kind = kind;
    this.word = null;
    this.level = level;
    this.assoc = assoc;
  }

  BinOp(String word, int level, Assoc assoc) {
    this.kind = null;
    this.word = word;
    this.level = level;
    this.assoc = assoc;
  }

  /** The operator a token of {@code kind} is, or null; {@code word} is a KEYWORD's word, null for other kinds. */
  static BinOp of(Token.Kind kind, String word) {
    return kind == Token.Kind.KEYWORD ? BY_WORD.get(word) : BY_KIND.get(kind);
  }
}
