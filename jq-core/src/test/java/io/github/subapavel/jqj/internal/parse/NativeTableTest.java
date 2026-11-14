package io.github.subapavel.jqj.internal.parse;

import static io.github.subapavel.jqj.PreludeSignatures.DEFINITIONS;
import static io.github.subapavel.jqj.PreludeSignatures.REPLACED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042 "Native table" and F-31: every builtin signature jq 1.8.2 writes in C or bytecode, plus jqj's replacing
 * natives, each built or unbuilt (GLOSSARY "Native table", "Unbuilt native"). {@link NativeTable#signatures()} is the
 * table's {@code name/arity} set. The expected lists were derived by the test-author from jq 1.8.2's sources fetched
 * raw from https://raw.githubusercontent.com/jqlang/jq/jq-1.8.2/src/ on 2026-11-13, with the input not counted in the
 * arity (CFUNC's nargs minus one):
 *
 * <ul>
 *   <li>{@code builtin.c} {@code function_list[]} (line 1974): its CFUNC rows, {@code _negate} and the BINOPS of
 *       {@code builtin.h} (line 10), each {@code "_" name} with arity 2;
 *   <li>{@code libm.h}, included at the top of {@code function_list}: LIBM_DD and LIBM_DA give arity 0, LIBM_DDD 2,
 *       LIBM_DDDD 3, the {@code _NO} variants the same;
 *   <li>{@code builtin.c} {@code bind_bytecoded_builtins}: {@code empty}, {@code not}, {@code path/1}, {@code last/1},
 *       {@code range/2}; {@code gen_builtin_list}: {@code builtins/0}.
 * </ul>
 *
 * <p>jq-ref 1.8.2 confirms them: {@code builtins[]} lists 226 signatures, exactly these 142 and builtin.jq's 106
 * without the ones whose name starts with {@code _} (observed 2026-11-13).
 */
class NativeTableTest {

  /** builtin.c's function_list without libm.h and BINOPS: 64 rows. */
  static final List<String> FUNCTION_LIST =
      List.of(
          "_negate/0", "tojson/0", "fromjson/0", "tonumber/0", "toboolean/0", "tostring/0", "keys/0", "keys_unsorted/0",
          "startswith/1", "endswith/1", "split/1", "explode/0", "implode/0", "_strindices/1", "trim/0", "ltrim/0",
          "rtrim/0", "setpath/2", "getpath/1", "delpaths/1", "has/1", "contains/1", "length/0", "utf8bytelength/0",
          "type/0", "isinfinite/0", "isnan/0", "isnormal/0", "infinite/0", "nan/0", "sort/0", "_sort_by_impl/1",
          "_group_by_impl/1", "unique/0", "_unique_by_impl/1", "bsearch/1", "min/0", "max/0", "_min_by_impl/1",
          "_max_by_impl/1", "error/0", "format/1", "env/0", "halt/0", "halt_error/1", "get_search_list/0",
          "get_prog_origin/0", "get_jq_origin/0", "_match_impl/3", "modulemeta/0", "input/0", "debug/0", "stderr/0",
          "strptime/1", "strftime/1", "strflocaltime/1", "mktime/0", "gmtime/0", "localtime/0", "now/0",
          "input_filename/0", "input_line_number/0", "have_decnum/0", "have_literal_numbers/0");

  /** builtin.h's BINOPS, as function_list binds them. */
  static final List<String> BINOPS =
      List.of(
          "_plus/2", "_minus/2", "_multiply/2", "_divide/2", "_mod/2", "_equal/2", "_notequal/2", "_less/2",
          "_lesseq/2", "_greater/2", "_greatereq/2");

  /** libm.h: 61 functions. */
  static final List<String> LIBM =
      List.of(
          "acos/0", "acosh/0", "asin/0", "asinh/0", "atan/0", "atan2/2", "atanh/0", "cbrt/0", "ceil/0", "copysign/2",
          "cos/0", "cosh/0", "drem/2", "erf/0", "erfc/0", "exp/0", "exp10/0", "exp2/0", "expm1/0", "fabs/0", "fdim/2",
          "floor/0", "fma/3", "fmax/2", "fmin/2", "fmod/2", "frexp/0", "gamma/0", "hypot/2", "j0/0", "j1/0", "jn/2",
          "ldexp/2", "lgamma/0", "lgamma_r/0", "log/0", "log10/0", "log1p/0", "log2/0", "logb/0", "modf/0",
          "nearbyint/0", "nextafter/2", "nexttoward/2", "pow/2", "remainder/2", "rint/0", "round/0", "scalb/2",
          "scalbln/2", "significand/0", "sin/0", "sinh/0", "sqrt/0", "tan/0", "tanh/0", "tgamma/0", "trunc/0", "y0/0",
          "y1/0", "yn/2");

  /** bind_bytecoded_builtins and gen_builtin_list. */
  static final List<String> BYTECODED = List.of("empty/0", "not/0", "path/1", "last/1", "range/2", "builtins/0");

  @Test
  void theTableIsJqsCAndBytecodedBuiltinsPlusTheReplacingNatives() {
    Set<String> expected = new HashSet<>();
    Stream.of(FUNCTION_LIST, BINOPS, LIBM, BYTECODED).forEach(expected::addAll);
    assertThat(expected).as("jq 1.8.2's C and bytecoded signatures").hasSize(142);
    expected.addAll(REPLACED);
    assertThat(NativeTable.signatures()).as("NativeTable.signatures()").containsExactlyInAnyOrderElementsOf(expected);
  }

  /** F-31 and ADR-0044: the only prelude definitions the table also has are flatten/0, flatten/1, inputs/0, to_entries/0. */
  @Test
  void theOverlapWithThePreludeIsExactlyTheReplacedSet() {
    Set<String> overlap = new HashSet<>(NativeTable.signatures());
    overlap.retainAll(Prelude.signatures());
    assertThat(overlap).as("NativeTable.signatures() and Prelude.signatures() in common").containsExactlyInAnyOrderElementsOf(REPLACED);
  }

  /** The same overlap against the pinned definitions, so it does not rest on Prelude.signatures() alone. */
  @Test
  void theOverlapWithBuiltinJqsDefinitionsIsExactlyTheReplacedSet() {
    Set<String> overlap = new HashSet<>(NativeTable.signatures());
    overlap.retainAll(DEFINITIONS);
    assertThat(overlap).as("NativeTable.signatures() and builtin.jq's definitions in common").containsExactlyInAnyOrderElementsOf(REPLACED);
  }
}
