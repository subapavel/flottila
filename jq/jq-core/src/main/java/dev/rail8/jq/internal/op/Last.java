/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): gen_last_1, the bytecode of last/1, in src/builtin.c.
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
package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;

/**
 * The end of jq 1.8.2's {@code last(f)}: the resolver lowers it to a reduce over {@code f} whose state starts at {@link
 * #UNSET} and takes each result of {@code f}, so it holds the last one, or still {@link #UNSET} when {@code f} gave
 * none; this operation then gives that state, or no result for {@link #UNSET}, as jq's {@code is_empty} flag does. An
 * error or a break in {@code f} passes, as the reduce passes it. It ticks and charges nothing: {@code last(f)} costs
 * what {@code f} costs.
 */
public final class Last implements Fn0 {
  /** {@code last(f)}'s end. */
  public static final Operation OP = new Operation("last", new Last(), Declared.KEEPS);

  /** The state before {@code f}'s first result: no jq value, so no result of {@code f} is it. */
  public static final Object UNSET = new Object();

  private Last() {}

  @Override
  public Object apply(RunState state, Object in) {
    return in == UNSET ? CallForm.NONE : in;
  }
}
