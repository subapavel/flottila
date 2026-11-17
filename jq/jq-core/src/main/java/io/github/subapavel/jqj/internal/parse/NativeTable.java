/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): function_list, bind_bytecoded_builtins and
 * gen_builtin_list in src/builtin.c, BINOPS in src/builtin.h, and the function list of src/libm.h.
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0042's native table (GLOSSARY "Native table"): every builtin signature jq 1.8.2 writes in C or bytecode, plus
 * F-31's and ADR-0044's replacing natives, by {@code name/arity}, the input not counted. The {@link Binder} looks it up after the
 * user's definitions and before the prelude; an entry the {@link Binder.Native} enum does not build yet binds as an
 * unbuilt native, whose lowering is a Stub (GLOSSARY "Unbuilt native"). Translated from jq 1.8.2's {@code
 * function_list} (src/builtin.c), its BINOPS (src/builtin.h) and the functions of src/libm.h it includes, {@code
 * bind_bytecoded_builtins} and {@code gen_builtin_list} (src/builtin.c); jq-ref 1.8.2's {@code builtins[]} lists the
 * same signatures but those whose name starts with {@code _} (NativeTableTest).
 */
final class NativeTable {
  /** How a native's arguments are compile levels (ADR-0040 "Errors", amended at #71). */
  enum Kind {
    /** A C function: its arguments stay in the caller's level. */
    C,
    /**
     * A bytecoded builtin, or a native that replaces a jq definition (F-31): its arguments are inner levels, as a
     * jq-defined function's are.
     */
    CLOSURES
  }

  /** function_list's CFUNC rows and {@code _negate}. */
  private static final List<String> FUNCTION_LIST =
      List.of(
          "_negate/0", "tojson/0", "fromjson/0", "tonumber/0", "toboolean/0", "tostring/0", "keys/0", "keys_unsorted/0",
          "startswith/1", "endswith/1", "split/1", "explode/0", "implode/0", "_strindices/1", "trim/0", "ltrim/0",
          "rtrim/0", "setpath/2", "getpath/1", "delpaths/1", "has/1", "contains/1", "length/0", "utf8bytelength/0",
          "type/0", "isinfinite/0", "isnan/0", "isnormal/0", "infinite/0", "nan/0", "sort/0", "_sort_by_impl/1",
          "_group_by_impl/1", "unique/0", "_unique_by_impl/1", "bsearch/1", "min/0", "max/0", "_min_by_impl/1",
          "_max_by_impl/1", "error/0", "format/1", "env/0", "halt/0", "halt_error/1", "get_search_list/0",
          "get_prog_origin/0", "get_jq_origin/0", "_match_impl/3", "modulemeta/0", "input/0", "debug/0", "stderr/0",
          "strptime/1", "strftime/1", "strflocaltime/1", "mktime/0", "gmtime/0", "localtime/0", "now/0",
          "input_filename/0", "input_line_number/0", "have_decnum/0", "have_literal_numbers/0",
          // BINOPS, each "_" name with two arguments.
          "_plus/2", "_minus/2", "_multiply/2", "_divide/2", "_mod/2", "_equal/2", "_notequal/2", "_less/2",
          "_lesseq/2", "_greater/2", "_greatereq/2",
          // libm.h: LIBM_DD and LIBM_DA take no argument, LIBM_DDD two, LIBM_DDDD three.
          "acos/0", "acosh/0", "asin/0", "asinh/0", "atan/0", "atan2/2", "atanh/0", "cbrt/0", "ceil/0", "copysign/2",
          "cos/0", "cosh/0", "drem/2", "erf/0", "erfc/0", "exp/0", "exp10/0", "exp2/0", "expm1/0", "fabs/0", "fdim/2",
          "floor/0", "fma/3", "fmax/2", "fmin/2", "fmod/2", "frexp/0", "gamma/0", "hypot/2", "j0/0", "j1/0", "jn/2",
          "ldexp/2", "lgamma/0", "lgamma_r/0", "log/0", "log10/0", "log1p/0", "log2/0", "logb/0", "modf/0",
          "nearbyint/0", "nextafter/2", "nexttoward/2", "pow/2", "remainder/2", "rint/0", "round/0", "scalb/2",
          "scalbln/2", "significand/0", "sin/0", "sinh/0", "sqrt/0", "tan/0", "tanh/0", "tgamma/0", "trunc/0", "y0/0",
          "y1/0", "yn/2");

  /**
   * bind_bytecoded_builtins, gen_builtin_list's {@code builtins/0}, F-31's natives that replace builtin.jq's {@code
   * flatten/0}, {@code flatten/1} and {@code inputs/0}, and ADR-0044's that replaces its {@code to_entries/0}.
   */
  private static final List<String> CLOSURE_LIST =
      List.of(
          "empty/0", "not/0", "path/1", "last/1", "range/2", "builtins/0", "flatten/0", "flatten/1", "inputs/0",
          "to_entries/0");

  private static final Map<String, Kind> TABLE = table();

  private NativeTable() {}

  private static Map<String, Kind> table() {
    Map<String, Kind> table = new HashMap<>();
    for (String signature : FUNCTION_LIST) {
      table.put(signature, Kind.C);
    }
    for (String signature : CLOSURE_LIST) {
      table.put(signature, Kind.CLOSURES);
    }
    return Map.copyOf(table);
  }

  /** The table's signatures, {@code name/arity}. */
  static Set<String> signatures() {
    return Set.copyOf(TABLE.keySet());
  }

  /** The kind of the native {@code signature}, or null when the table has no such entry. */
  static Kind kind(String signature) {
    return TABLE.get(signature);
  }
}
