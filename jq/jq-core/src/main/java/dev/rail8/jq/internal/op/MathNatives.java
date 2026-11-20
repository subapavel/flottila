/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): the LIBM_DD, LIBM_DDD, LIBM_DDDD and LIBM_DA functions
 * of src/builtin.c, over the list of src/libm.h.
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

import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqValue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;

/**
 * jq 1.8.2's math builtins: a C library function on the doubles of numbers. A function of the input ({@code floor})
 * reads the input; one of two or three arguments ({@code pow(a; b)}, {@code fma(a; b; c)}) ignores the input and reads
 * its arguments in order. Each gives one number, but {@code frexp} and {@code modf}, which give an array of two:
 * {@code [mantissa, exponent]} and {@code [fraction, integer part]}. A value that is not a number raises jq's
 * "number required" error, the first argument checked first, before any number is read.
 *
 * <p>The exact functions are {@link Libm}'s and Java's ({@code Math.abs}'s, {@code Math.copySign}'s). The computed
 * ones are {@code StrictMath}'s, {@link Libm}'s definitions over it, {@link Erf}'s and {@link Erfc}'s; their last digits
 * are this engine's own, the same on every platform. Every one declares path NONE, is pure and builds its result
 * afresh; none ticks, and only the read of a non-short view's double charges ({@link Numbers#doubleValue}). The
 * Bessel and gamma families are not here yet.
 */
public final class MathNatives {
  private static final String REQUIRED = "number required";

  /** The operations by {@code name/arity}, the input not counted. */
  private static final Map<String, Operation> OPERATIONS = operations();

  private MathNatives() {}

  /** The operation of the math builtin {@code signature}, {@code name/arity}, or null when there is none. */
  public static Operation operation(String signature) {
    return OPERATIONS.get(signature);
  }

  private static Map<String, Operation> operations() {
    Map<String, Operation> ops = new HashMap<>();
    // Exact.
    unary(ops, "ceil", Libm::ceil);
    unary(ops, "fabs", Math::abs);
    unary(ops, "floor", Libm::floor);
    unary(ops, "logb", Libm::logb);
    unary(ops, "nearbyint", Libm::rint);
    unary(ops, "rint", Libm::rint);
    unary(ops, "round", Libm::round);
    unary(ops, "significand", Libm::significand);
    unary(ops, "sqrt", Libm::sqrt);
    unary(ops, "trunc", Libm::trunc);
    binary(ops, "copysign", Math::copySign);
    binary(ops, "drem", Libm::drem);
    binary(ops, "fdim", Libm::fdim);
    binary(ops, "fmax", Libm::fmax);
    binary(ops, "fmin", Libm::fmin);
    binary(ops, "fmod", Libm::fmod);
    binary(ops, "ldexp", Libm::ldexp);
    binary(ops, "nextafter", Libm::nextafter);
    binary(ops, "nexttoward", Libm::nexttoward);
    binary(ops, "remainder", Libm::remainder);
    binary(ops, "scalb", Libm::scalb);
    binary(ops, "scalbln", Libm::scalbln);
    ops.put("fma/3", new Operation("fma", (FnN) MathNatives::fma, Declared.FRESH));
    pair(ops, "frexp", Libm::frexp);
    pair(ops, "modf", Libm::modf);
    // Computed: StrictMath.
    unary(ops, "acos", StrictMath::acos);
    unary(ops, "asin", StrictMath::asin);
    unary(ops, "atan", StrictMath::atan);
    unary(ops, "cbrt", StrictMath::cbrt);
    unary(ops, "cos", StrictMath::cos);
    unary(ops, "cosh", StrictMath::cosh);
    unary(ops, "exp", StrictMath::exp);
    unary(ops, "expm1", StrictMath::expm1);
    unary(ops, "log", StrictMath::log);
    unary(ops, "log10", StrictMath::log10);
    unary(ops, "sin", StrictMath::sin);
    unary(ops, "sinh", StrictMath::sinh);
    unary(ops, "tan", StrictMath::tan);
    unary(ops, "tanh", StrictMath::tanh);
    // Computed: StrictMath with C's special results and NaNs.
    unary(ops, "log1p", Libm::log1p);
    binary(ops, "atan2", Libm::atan2);
    binary(ops, "hypot", Libm::hypot);
    binary(ops, "pow", Libm::pow);
    // Computed: definitions over StrictMath, and the translated error functions.
    unary(ops, "acosh", Libm::acosh);
    unary(ops, "asinh", Libm::asinh);
    unary(ops, "atanh", Libm::atanh);
    unary(ops, "exp10", Libm::exp10);
    unary(ops, "exp2", Libm::exp2);
    unary(ops, "log2", Libm::log2);
    unary(ops, "erf", Erf::erf);
    unary(ops, "erfc", Erfc::erfc);
    return Map.copyOf(ops);
  }

  /** LIBM_DD: {@code f} of the input. */
  private static void unary(Map<String, Operation> ops, String name, DoubleUnaryOperator f) {
    Fn0 form = (state, in) -> new DoubleNumber(f.applyAsDouble(number(state, (JqValue) in)));
    ops.put(name + "/0", new Operation(name, form, Declared.FRESH));
  }

  /** LIBM_DDD: {@code f} of the two arguments. */
  private static void binary(Map<String, Operation> ops, String name, DoubleBinaryOperator f) {
    Fn2 form =
        (state, in, a, b) -> {
          JqValue x = (JqValue) a;
          JqValue y = (JqValue) b;
          required(state, x);
          required(state, y);
          return new DoubleNumber(f.applyAsDouble(number(state, x), number(state, y)));
        };
    ops.put(name + "/2", new Operation(name, form, Declared.FRESH));
  }

  /** LIBM_DA: the two doubles {@code f} gives for the input, as a new array. */
  private static void pair(Map<String, Operation> ops, String name, DoubleFunction<double[]> f) {
    Fn0 form =
        (state, in) -> {
          double[] parts = f.apply(number(state, (JqValue) in));
          Object[] elements = Built.elements(state, 2);
          elements[0] = new DoubleNumber(parts[0]);
          elements[1] = new DoubleNumber(parts[1]);
          return new BuiltArray(elements, 2);
        };
    ops.put(name + "/0", new Operation(name, form, Declared.FRESH));
  }

  /** LIBM_DDDD's {@code fma(a; b; c)}. */
  private static Object fma(RunState state, Object in, Object[] args) {
    JqValue x = (JqValue) args[0];
    JqValue y = (JqValue) args[1];
    JqValue z = (JqValue) args[2];
    required(state, x);
    required(state, y);
    required(state, z);
    return new DoubleNumber(Libm.fma(number(state, x), number(state, y), number(state, z)));
  }

  /** jq's "number required" error unless {@code v} is a number. */
  private static void required(RunState state, JqValue v) {
    if (!(v instanceof JqNumber)) {
      throw Arithmetic.cannot(state, v, REQUIRED);
    }
  }

  /** The double of {@code v}, or jq's "number required" error. */
  private static double number(RunState state, JqValue v) {
    if (v instanceof JqNumber n) {
      return Numbers.doubleValue(state, n);
    }
    throw Arithmetic.cannot(state, v, REQUIRED);
  }
}
