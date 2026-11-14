package io.github.subapavel.jqj;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The pinned signatures of #71's prelude tests (ADR-0042, GLOSSARY "Prelude", "Native table", "Replaced definition"):
 * builtin.jq's definitions in file order, the prelude definitions whose call is a Stub at #71, and F-31's replaced
 * set. Derived by the test-author from jq 1.8.2's {@code src/builtin.jq}, fetched raw from
 * https://raw.githubusercontent.com/jqlang/jq/jq-1.8.2/src/builtin.jq (SHA-256 b8a5fd95…f2a4, the value of
 * {@code jq/SOURCES.sha256}): one entry per top-level {@code def}, its arity the count of its parameters. Cross-check on
 * jq-ref 1.8.2 (2026-11-13): these 106 signatures without the three that start with {@code _}, together with
 * {@code NativeTableTest}'s 142 jq natives without the 19 that start with {@code _}, are exactly the 226 lines of
 * {@code jq-ref -n -r 'builtins[]'}, and no signature is in both lists.
 */
public final class PreludeSignatures {
  private PreludeSignatures() {}

  /** builtin.jq's 106 top-level definitions, as {@code name/arity}, in file order. */
  public static final List<String> DEFINITIONS =
      List.of(
          "halt_error/0", "error/1", "map/1", "select/1", "sort_by/1", "group_by/1", "unique_by/1", "max_by/1",
          "min_by/1", "add/1", "add/0", "del/1", "abs/0", "_assign/2", "_modify/2", "map_values/1",
          "recurse/1", "recurse/2", "recurse/0", "to_entries/0", "from_entries/0", "with_entries/1", "reverse/0",
          "indices/1", "index/1", "rindex/1", "paths/0", "paths/1", "isfinite/0", "arrays/0", "objects/0",
          "iterables/0", "booleans/0", "numbers/0", "normals/0", "finites/0", "strings/0", "nulls/0", "values/0",
          "scalars/0", "join/1", "_flatten/1", "flatten/1", "flatten/0", "range/1", "fromdateiso8601/0",
          "todateiso8601/0", "fromdate/0", "todate/0", "ltrimstr/1", "rtrimstr/1", "trimstr/1", "match/2", "match/1",
          "test/2", "test/1", "capture/2", "capture/1", "scan/2", "scan/1", "splits/2", "splits/1", "split/2", "sub/3",
          "sub/2", "gsub/3", "gsub/2", "while/2", "until/2", "limit/2", "skip/2", "range/3", "first/1", "isempty/1",
          "all/2", "any/2", "all/1", "any/1", "all/0", "any/0", "nth/2", "first/0", "last/0", "nth/1",
          "combinations/0", "combinations/1", "transpose/0", "in/1", "inside/1", "repeat/1", "inputs/0",
          "ascii_downcase/0", "ascii_upcase/0", "truncate_stream/1", "fromstream/1", "tostream/0", "walk/1", "pick/1",
          "debug/1", "INDEX/2", "INDEX/1", "JOIN/2", "JOIN/3", "JOIN/4", "IN/1", "IN/2");

  /** F-31 (ADR-0006 amendment) and ADR-0044 (#85, to_entries/0): the prelude definitions a jqj native replaces. */
  public static final Set<String> REPLACED = Set.of("flatten/0", "flatten/1", "inputs/0", "to_entries/0");

  /**
   * The definitions whose call {@code name(.;…)} compiles to a Stub at #71, each for the first reason its body reaches
   * (read from builtin.jq by the test-author):
   *
   * <ul>
   *   <li>a recursive group (ADR-0040's SCC rule; the spec's nine and {@code ..}): recurse/0, recurse/1, recurse/2,
   *       _flatten/1, while/2, until/2, repeat/1, combinations/0, tostream/0, walk/1, and every definition calling
   *       one: paths/0, paths/1, range/3, combinations/1;
   *   <li>a replaced definition, whose native is unbuilt: inputs/0 (spec 5);
   *   <li>spec 4's {@code _strindices}, past the natives #72 built (indices, index, rindex), and {@code _match_impl}
   *       (match/1, test/1, capture/1);
   *   <li>spec 3's paths: {@code path/1}, {@code getpath}, {@code setpath}, {@code delpaths} and the update and
   *       assignment operators (del, _assign, _modify, map_values, truncate_stream, fromstream, pick, INDEX/1,
   *       INDEX/2);
   *   <li>spec 4's families: {@code _match_impl}, {@code startswith}, {@code endswith}, {@code contains},
   *       {@code explode}, {@code implode}, the date functions (match/2, test/2, capture/2, scan/1, scan/2, splits/1,
   *       splits/2, split/2, sub/2, sub/3, gsub/2, gsub/3, ltrimstr, rtrimstr, trimstr, inside, ascii_downcase,
   *       ascii_upcase, fromdateiso8601, todateiso8601, fromdate, todate);
   *   <li>spec 7's host builtins: {@code halt_error/1}, {@code debug/0} (halt_error/0, debug/1).
   * </ul>
   *
   * <p>The 52 others compile: error/1, map, select, add/0, add/1, abs, nulls, values, range/1, limit, skip, first/0,
   * first/1, isempty, all/0..2, any/0..2, nth/1, nth/2, last/0, JOIN/2..4, IN/1, IN/2 with the natives built before
   * #71 ({@code empty}, {@code not}, {@code error/0}, {@code range/2}) and the constructs of #63 to #70; since #72's
   * natives ({@code type}, {@code length}, {@code tostring}, {@code has}, {@code keys_unsorted}, {@code isinfinite},
   * {@code isnormal}, flatten's two), to_entries, from_entries, with_entries, reverse, isfinite, arrays, objects,
   * iterables, booleans, numbers, normals, finites, strings, scalars, join, in, flatten/0 and flatten/1; and since
   * #73's ({@code max} and the {@code _…_by_impl} family), sort_by, group_by, unique_by, max_by, min_by and transpose.
   * #72 and #73 may move entries out of this set, and only out (design approval, O3); an entry leaving it with its Stub
   * is not a weakened test.
   */
  public static final Set<String> STUBS =
      Set.of(
          "halt_error/0", "del/1", "_assign/2", "_modify/2", "map_values/1", "recurse/1", "recurse/2", "recurse/0", "indices/1", "index/1", "rindex/1",
          "paths/0", "paths/1", "_flatten/1", "fromdateiso8601/0", "todateiso8601/0", "fromdate/0", "todate/0",
          "ltrimstr/1", "rtrimstr/1", "trimstr/1", "match/2", "match/1", "test/2", "test/1",
          "capture/2", "capture/1", "scan/2", "scan/1", "splits/2", "splits/1", "split/2", "sub/3", "sub/2", "gsub/3",
          "gsub/2", "while/2", "until/2", "range/3", "combinations/0", "combinations/1", "inside/1", "repeat/1", "inputs/0", "ascii_downcase/0", "ascii_upcase/0", "truncate_stream/1",
          "fromstream/1", "tostream/0", "walk/1", "pick/1", "debug/1", "INDEX/2", "INDEX/1");

  /** The call of {@code signature} with {@code .} for every argument: {@code map(.)}, {@code limit(.; .)}, {@code add}. */
  public static String call(String signature) {
    int slash = signature.lastIndexOf('/');
    String name = signature.substring(0, slash);
    int arity = Integer.parseInt(signature.substring(slash + 1));
    return arity == 0 ? name : name + "(" + String.join("; ", Collections.nCopies(arity, ".")) + ")";
  }
}
