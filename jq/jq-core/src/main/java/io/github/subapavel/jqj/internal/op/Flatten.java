/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): _flatten/1, flatten/1 and flatten/0 in src/builtin.jq.
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
package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.Equality;
import io.github.subapavel.jqj.internal.value.KeyProof;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.internal.value.Numbers;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.internal.value.Order;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayDeque;

/**
 * {@code flatten} and {@code flatten($x)}, jqj's natives in place of builtin.jq's definitions (F-31, ADR-0006
 * amendment, ADR-0042), which they give the results and errors of (jq-ref 1.8.2, recorded: diff/natives.rec flatten-*):
 *
 * <pre>
 * def _flatten($x): reduce .[] as $i ([]; if $i | type == "array" and $x != 0 then . + ($i | _flatten($x - 1)) else . + [$i] end);
 * def flatten($x): if $x &lt; 0 then error("flatten depth must not be negative") else _flatten($x) end;
 * def flatten: _flatten(-1);
 * </pre>
 *
 * So the input's {@code .[]}, its error included, gives the members, a document's object's through its key proof
 * (ADR-0044); a member that is an array is spliced, its own
 * members at the depth less one, while the depth is not 0 by jq's {@code !=}; and the depth less one is jq's {@code -},
 * whose error a depth that is no number raises at the first array member. The depth is checked against 0 in jq's value
 * order first. A walk of nested arrays keeps its open arrays on a stack of its own, never in Java frames, so it is as
 * deep as the data. It ticks once per member it visits, at every level (ADR-0008), and charges the array it builds
 * (ADR-0035).
 */
public final class Flatten {
  /** The literals of builtin.jq's {@code 0}, {@code 1} and {@code -1}. */
  private static final JqValue ZERO = LiteralNumber.ofLexeme("0");

  private static final JqValue ONE = LiteralNumber.ofLexeme("1");

  private static final JqValue MINUS_ONE = Numbers.negate(LiteralNumber.ofLexeme("1"));

  /** jq's {@code -}. */
  private static final Fn2 MINUS = (Fn2) Arithmetic.MINUS.form();

  /** {@code flatten}. */
  public static final Operation ALL = new Operation("flatten", (Fn0) (state, in) -> flatten(state, (JqValue) in, MINUS_ONE));

  /** {@code flatten($x)}, its argument the depth. */
  public static final Operation DEPTH = new Operation("flatten", (Fn1) Flatten::depth);

  private Flatten() {}

  /** {@code flatten($x)}: a depth below 0 in the value order is jq's error, any other the flatten to that depth. */
  private static Object depth(RunState state, Object in, Object depth) {
    JqValue x = (JqValue) depth;
    if (Order.compare(state, x, ZERO) < 0) {
      throw new FilterException(BuiltString.of("flatten depth must not be negative"));
    }
    return flatten(state, (JqValue) in, x);
  }

  /** {@code _flatten(depth)} on {@code in}. */
  private static BuiltArray flatten(RunState state, JqValue in, JqValue depth) {
    Members top =
        switch (in) {
          case ObjectView object -> KeyProof.walk(state, object, false);
          case Members members -> members;
          // Every other value, a scalar or a string, has nothing to iterate; whatever later joins JqValue cannot be
          // iterated unless it is Members or a read object, so one arm covers them all and none is forgotten.
          default -> throw NavigationErrors.cannotIterate(state, in);
        };
    Built.Elements out = new Built.Elements(state);
    ArrayDeque<Level> open = new ArrayDeque<>();
    open.push(new Level(top, depth));
    while (!open.isEmpty()) {
      Level level = open.peek();
      if (level.cursor == -1) {
        open.pop();
        continue;
      }
      JqValue member = level.members.member(state, level.cursor);
      level.cursor = level.members.next(state, level.cursor);
      state.tick();
      if (member instanceof JqArray && !Equality.equal(state, level.depth, ZERO)) {
        open.push(new Level((Members) member, (JqValue) MINUS.apply(state, null, level.depth, ONE)));
      } else {
        out.add(member);
      }
    }
    return out.array();
  }

  /** An open container of the walk: its members from {@link #cursor} on, each at {@link #depth}. */
  private static final class Level {
    final Members members;
    final JqValue depth;
    int cursor;

    Level(Members members, JqValue depth) {
      this.members = members;
      this.depth = depth;
      this.cursor = members.first();
    }
  }
}
