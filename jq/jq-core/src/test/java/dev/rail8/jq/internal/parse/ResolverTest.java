package dev.rail8.jq.internal.parse;

import static dev.rail8.jq.internal.parse.ParseAssertions.allCompile;
import static dev.rail8.jq.internal.parse.ParseAssertions.ir;
import static dev.rail8.jq.internal.parse.ParseAssertions.parsesToAStub;
import static dev.rail8.jq.internal.parse.ParseAssertions.shape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.rail8.jq.Bindings;
import dev.rail8.jq.JqCompileException;
import dev.rail8.jq.JqEngine;
import dev.rail8.jq.internal.op.StepRun;
import dev.rail8.jq.query.ir.Advance;
import dev.rail8.jq.query.ir.Alt;
import dev.rail8.jq.query.ir.AltBind;
import dev.rail8.jq.query.ir.Alternative;
import dev.rail8.jq.query.ir.Bind;
import dev.rail8.jq.query.ir.Binds;
import dev.rail8.jq.query.ir.Break;
import dev.rail8.jq.query.ir.Call;
import dev.rail8.jq.query.ir.Collect;
import dev.rail8.jq.query.ir.Comma;
import dev.rail8.jq.query.ir.Const;
import dev.rail8.jq.query.ir.Foreach;
import dev.rail8.jq.query.ir.Function;
import dev.rail8.jq.query.ir.Identity;
import dev.rail8.jq.query.ir.If;
import dev.rail8.jq.query.ir.Intrinsic;
import dev.rail8.jq.query.ir.Label;
import dev.rail8.jq.query.ir.Node;
import dev.rail8.jq.query.ir.ParamCall;
import dev.rail8.jq.query.ir.PathOf;
import dev.rail8.jq.query.ir.Pipe;
import dev.rail8.jq.query.ir.Program;
import dev.rail8.jq.query.ir.Reduce;
import dev.rail8.jq.query.ir.Source;
import dev.rail8.jq.query.ir.Try;
import dev.rail8.jq.query.ir.Update;
import dev.rail8.jq.query.ir.Var;
import dev.rail8.jq.value.JqNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parser builds an AST for every construct of jq 1.8.2's grammar, and the resolver lowers only what is built,
 * every other kind a Stub (ADR-0032, GLOSSARY "AST", "Resolver"). Below the API because the API cannot tell a parse
 * that accepts a construct from one that stubs it: both end in the Stub until the construct's ticket lowers it. As a
 * ticket lowers a kind, its entries leave the Stub list for that ticket's own tests (an entry ending with its Stub is
 * not a weakened test).
 */
class ResolverTest {

  /**
   * The precedence/* filters (diff/precedence.rec, recorded on jq-ref 1.8.2: each runs, rc 0 or 5). Each needs a kind
   * #62 does not lower, so each parses and resolves to the Stub; the recording is the owning ticket's red. A filter
   * whose kinds a later ticket lowers (#64 constructions, #65 arithmetic and negation, #66 comparison, {@code and},
   * {@code or}, {@code not} and {@code //}, #67 {@code as}, patterns, {@code ?//}, variables, {@code $__loc__} and
   * format strings, #68 {@code if}, {@code try}, {@code ?}, {@code error}, {@code label} and {@code break}, #69
   * {@code reduce} and {@code foreach}, #70 {@code def}) ended with {@code | ..}, here and in {@link #GRAMMAR}; #110
   * built {@code ..} (ADR-0042 amendment for #110), so those entries moved to {@link #PRECEDENCE_COMPILING} and
   * {@link #GRAMMAR_COMPILING}, which compile. The two assignments stay Stubs until spec 3.
   */
  private static final List<String> PRECEDENCE = List.of(".a = null // 2", ".a = true or false");

  /** The PRECEDENCE entries whose only Stub was the {@code ..} after them: each compiles since #110. */
  private static final List<String> PRECEDENCE_COMPILING =
      List.of(
          "def f: 1; 2 | f | ..",
          "1 | def f: 2; f | ..",
          "1 as $x | 2 | $x | ..",
          "\"x\" as $x | \"y\" as $y | $x + $y | ..",
          "label $out | 1, break $out, 2 | ..",
          "1, 2 | . + 10 | ..",
          "1, null // 2 | ..",
          "true or true and false | ..",
          "false == false and false | ..",
          "1 + 1 == 2 | ..",
          "1 + 2 * 3 | ..",
          "2 * 3 + 1 | ..",
          "- \"a\" * 2 | ..",
          "- 1 + 2 | ..",
          "- 1 | . + 1 | ..",
          "[- \"a\"?] | ..",
          "1 - 2 - 3 | ..",
          "1 - 2 + 3 | ..",
          "8 / 4 / 2 | ..",
          "7 % 4 * 2 | ..",
          "2 * 3 as $x | $x + 1 | ..",
          "1 // 2 as $x | $x + 10 | ..",
          "[1, 2 as $x | $x, 3] | ..",
          "reduce 1 + 2 as $x (0; . + $x) | ..",
          "foreach 1 + 2 as $x (0; . + $x) | ..",
          "try 1 catch 2 + 3 | ..",
          "try 1 + error(\"x\") | ..",
          "{a: 1 | . + 1} | ..",
          "[5,6] [1] | ..",
          "if true then 1 else 2 end + 1 | ..",
          "reduce (1,2) as $x (0; . + $x) * 2 | ..",
          "error(\"x\")? // 1 | ..");

  /**
   * One filter per other construct of the grammar. jq-ref 1.8.2, observed 2026-11-12 (.logs/62-jqref-accept.log of
   * the test-author's run): none is a syntax error; each gives rc 0 or 5, or rc 3 with a resolution error (module not
   * found for import and include). The {@code $$$$v} form is builtin.jq's {@code _modify}. #70 binds every call: the
   * entries that bound nowhere are jq's compile error ({@link #referencesThatBindNowhereAreJqsCompileError}). #70 wrapped
   * a call of a jq builtin in a user definition whose body is {@code ..}; #71's native table and prelude unwrapped them
   * (ADR-0042): an unbuilt native still stubs, and limit/2, which compiles, moved to {@link
   * #preludeCallsRunAsJqRefRunsThem}. The entries whose only Stub was {@code ..} moved to {@link #GRAMMAR_COMPILING}
   * when #110 built it.
   */
  private static final List<String> GRAMMAR =
      List.of(
          ".a |= .b",
          ".a |= 1 | .b += 2 | .d *= 2 | .e /= 1 | .g //= 3 | .h = 4",
          ".f %= 1 | .c -= 1",
          // |= is _modify's, still unbuilt; jq-ref 1.8.2, observed 2026-11-13 with -nc: {"a":1}, rc 0.
          "getpath([\"a\"]) |= 1",
          "1 as $v | $$$$v",
          "module {a:1}; .",
          "import \"nosuch\" as n; .",
          "include \"nosuch\"; .");

  /**
   * The GRAMMAR entries whose only Stub was {@code ..}, the recursion itself or the {@code | ..} after a construct an
   * earlier ticket lowered: each compiles since #110 (ADR-0042 amendment for #110). jq-ref runs each (the GRAMMAR
   * observation above).
   */
  private static final List<String> GRAMMAR_COMPILING =
      List.of(
          "..",
          "..?",
          "$__loc__ | ..",
          ".a and .b | ..",
          ".a // .b | ..",
          "1 as $x | {$x: 2} | ..",
          "{a: -1, b: 1 | . + 1} | ..",
          "[] | ..",
          "{} | ..",
          "[1, 2] | ..",
          "-1 | ..",
          "-(1 + 2) | ..",
          "not | ..",
          "def f(g; $x): g + $x; f(1; 2) | ..",
          "def f: def g: 3; g; f | ..",
          "def f: .; def g: f; g | ..",
          "def f: 1; . | ..",
          "[foreach (1,2,3) as $x (0; . + $x; [$x, .])] | ..",
          "reduce empty as $x (0; .) | ..",
          "if false then 1 elif true then 2 else 3 end | ..",
          "if false then 1 end | ..",
          "try error(\"x\") catch . | ..",
          "try error(\"x\") | ..",
          "label $f | break $f | ..",
          ".[] as [$a, $b] | $a + $b | ..",
          ". as {a: {b: $c}} | $c | ..",
          ". as {$a} | $a | ..",
          ". as [$a, {b: $c}] | $c | ..",
          ".[] as [$a] ?// $a | $a | ..",
          ". as [$a] ?// {$a} ?// $a | $a | ..",
          "reduce .[] as [$a, $b] (0; . + $a * $b) | ..",
          ".. .a",
          "@text \"a\" | ..",
          "1 # c\n+ 2 | ..");

  /**
   * The GRAMMAR entries that bound nowhere, moved here when #70 bound every reference (ADR-0040): a constant called
   * with arguments, another name and a variable no {@code as} binds are jq's compile error, thrown before any lowering,
   * so the Stub after one never fires: {@code $ENV}, unbuilt until spec 7 ({@code ..} served here until #110 built it,
   * ADR-0042 amendment for #110). jq-ref 1.8.2, observed 2026-11-13 with -n (2026-11-15 for {@code $x | $ENV},
   * .logs/110-jqref-observe.log): rc 3 and exactly these blocks.
   */
  @Test
  void referencesThatBindNowhereAreJqsCompileError() {
    Map<String, String> blocks =
        Map.of(
            "true(1)", "jq: error: true/1 is not defined at <top-level>, line 1, column 1:\n    true(1)\n    ^^^^",
            "foo", "jq: error: foo/0 is not defined at <top-level>, line 1, column 1:\n    foo\n    ^^^",
            "$x | $ENV", "jq: error: $x is not defined at <top-level>, line 1, column 1:\n    $x | $ENV\n    ^^");
    for (Map.Entry<String, String> e : blocks.entrySet()) {
      assertThatCode(() -> Parser.parse(e.getKey())).as("parse of %s", e.getKey()).doesNotThrowAnyException();
      JqCompileException error =
          assertThatExceptionOfType(JqCompileException.class).as("resolve of %s", e.getKey()).isThrownBy(() -> ir(e.getKey())).actual();
      assertThat(error.blocks()).as("blocks of %s", e.getKey()).containsExactly(e.getValue());
    }
  }

  /**
   * Every form of object key in one construction, the computed one the string "e" from getpath/1. jq-ref 1.8.2,
   * observed 2026-11-13 with -nc.
   */
  @Test
  void everyObjectKeyFormRunsAsJqRefRunsIt() {
    assertThat(results("{if: 1, and: 2, a: 3, \"b\": 4, @base64 \"k\": 5, \"x\\(1)\": 6, ({\"x\":\"e\"}|getpath([\"x\"])): 7, $__loc__, c}"))
        .as("every object key form")
        .containsExactly(
            "{\"if\":1,\"and\":2,\"a\":3,\"b\":4,\"k\":5,\"x1\":6,\"e\":7,\"__loc__\":{\"file\":\"<top-level>\",\"line\":1},\"c\":null}");
  }

  /**
   * The entries #70 wrapped in a user definition whose body is {@code ..} until #71's prelude and native table, unwrapped
   * (ADR-0042): limit/2 and range/1 are builtin.jq's and compile, so they left the Stub lists. jq-ref 1.8.2, observed
   * 2026-11-13 with -nc: {@code [limit(2; 1, 2, 3)]} gives [1,2] and {@code range(5)} gives 0 to 4, rc 0. range/3,
   * a Stub until #110: jq-ref 1.8.2, observed 2026-11-15 with -c on null, {@code [range(0; 10; 3)]} gives [0,3,6,9].
   */
  @Test
  void preludeCallsRunAsJqRefRunsThem() {
    assertThat(results("[limit(2; 1, 2, 3)]")).as("results of [limit(2; 1, 2, 3)]").containsExactly("[1,2]");
    assertThat(results("range(5)")).as("results of range(5)").containsExactly("0", "1", "2", "3", "4");
    assertThat(results("[range(0; 10; 3)]")).as("results of [range(0; 10; 3)]").containsExactly("[0,3,6,9]");
  }

  /** The results of {@code filter} on null as compact JSON, through the exported API; asserts that the run completes. */
  static List<String> results(String filter) {
    JqEngine engine = JqEngine.builder().build();
    List<String> out = new ArrayList<>();
    boolean ranOut =
        engine
            .compile(filter)
            .run(
                JqNull.NULL,
                Bindings.builder().build(),
                v -> {
                  out.add(v.toJson());
                  return true;
                },
                engine.newWorkspace());
    assertThat(ranOut).as("run's answer for %s when the sink never stops", filter).isTrue();
    return out;
  }

  @Test
  void everyUnloweredKindParsesAndResolvesToAStub() {
    for (String text : PRECEDENCE) {
      parsesToAStub(text);
    }
    for (String text : GRAMMAR) {
      parsesToAStub(text);
    }
  }

  /** The entries whose only Stub was {@code ..}: since #110 each parses, resolves and compiles. */
  @Test
  void everyEntryWhoseOnlyStubWasTheRecursionCompiles() {
    allCompile(Stream.concat(PRECEDENCE_COMPILING.stream(), GRAMMAR_COMPILING.stream()).toList());
  }

  @Test
  void formatsAndInterpolationsWithAFormatCompile() {
    // jq-ref 1.8.2, observed 2026-11-12 on null: @base64 "x\(.)" gives "xbnVsbA==", @json "x\(.)" gives "xnull" and
    // @json gives "null" (rc 0). Each interpolation is piped into the format; FormatsTest checks the results.
    allCompile(List.of("@base64 \"x\\(.)\"", "@json \"x\\(.)\"", "@json"));
  }

  @Test
  void anInterpolationWithNoFormatCompiles() {
    // jq-ref 1.8.2, observed 2026-11-12 on null: "a1b", "1", {"a1":2}, null and "3" (rc 0). #67 lowers an
    // interpolation with no format, so these entries ended with | .., which stubbed until #110 and compile since
    // (ADR-0042 amendment for #110).
    allCompile(List.of("\"a\\(1)b\" | ..", "\"\\(\"\\(1)\")\" | ..", "{\"a\\(1)\": 2} | ..", ".\"a\\(1)\" | ..", "\"\\(1 + 2)\" | .."));
    // Witness: an escaped backslash before ( is no interpolation (jq-ref -c '"a\\(1)b"' on null -> "a\\(1)b"), so the
    // string is one Const.
    assertThat(shape(ir("\"a\\\\(1)b\""))).as("IR of \"a\\\\(1)b\"").isEqualTo("\"a\\\\(1)b\"[0,9)");
  }

  @Test
  void builtKindsLowerToTheirIr() {
    // The kinds #33, #34 and #61 built (jq-ref 1.8.2, recorded in diff/dot.rec, field.rec and literal.rec), in UTF-8
    // byte Sources (ADR-0031): the resolver gives the same IR the parser gave before #62.
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry(".", ".[0,1)"),
            Map.entry(".a", ".a[0,2)"),
            Map.entry("1", "1[0,1)"),
            Map.entry("1.10", "1.10[0,4)"),
            Map.entry("\"s\"", "\"s\"[0,3)"),
            Map.entry("true", "true[0,4)"),
            Map.entry("false", "false[0,5)"),
            Map.entry("null", "null[0,4)"),
            Map.entry("@text", "@text[0,5)"),
            Map.entry("(.a)", ".a[1,3)"),
            Map.entry(".a | .b", "(.a[0,2) | .b[5,7))"),
            Map.entry(".a.b", "(.a[0,2) | .b[2,4))"),
            Map.entry("1 | .a", "(1[0,1) | .a[4,6))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(ir("."))
        .as("IR of . (an Identity, not a Stub)")
        .isEqualTo(new Identity(new Source(0, 1)));
  }

  /**
   * The entries #63 lowers left the Stub lists above (their Stub ended, ADR-0032; no input was dropped) and are
   * asserted here with the key-outer and bound orders of ADR-0034, each recorded on jq-ref 1.8.2 (diff/index.rec,
   * iterate.rec, slice.rec, comma.rec; precedence/postfix-chain; ParserTest's and ParserEdgesTest's entries). An
   * operation is named by its jq step with {@code ?} for the quiet variant: {@code .a}, {@code ."a"}, {@code .[k]}
   * (index), {@code .[a:b]} (slice, a missing bound a Const null) and {@code .[]} (iterate). A constant key or bound is
   * a Const argument; a computed one is a Bind around the target (the key outer; a outside b outside t). A navigation
   * {@code ?} is the quiet variant, never a Try.
   */
  @Test
  void navigationAndCommaLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry(".a?", ".a?[0,3)"),
            Map.entry(".\"a\"?", ".\"a\"?[0,5)"),
            Map.entry(".a?.b", "(.a?[0,3) | .b[3,5))"),
            Map.entry(".a.\"b\"?", "(.a[0,2) | .\"b\"?[2,7))"),
            Map.entry(".[0]", ".[k](0)[0,4)"),
            Map.entry(".[\"a\"]", ".[k](\"a\")[0,6)"),
            Map.entry(".a[0]", "(.a[0,2) | .[k](0)[2,5))"),
            Map.entry(".a.[0]", "(.a[0,2) | .[k](0)[2,6))"),
            Map.entry(".a[0].b", "((.a[0,2) | .[k](0)[2,5)) | .b[5,7))"),
            Map.entry(".[]", ".[][0,3)"),
            Map.entry(".[]?.a", "(.[]?[0,4) | .a[4,6))"),
            Map.entry(".[1:2], .[:1], .[1:]", "((.[a:b](1; 2)[0,6), .[a:b](null; 1)[8,13)), .[a:b](1; null)[15,20))"),
            Map.entry(".a[1:]?", "(.a[0,2) | .[a:b]?(1; null)[2,7))"),
            Map.entry(".a, .b", "(.a[0,2), .b[4,6))"),
            Map.entry(".[.a]", "(.a[2,4) as $0 | .[k]($0)[0,5))"),
            Map.entry(".a[.b]", "(.b[3,5) as $0 | (.a[0,2) | .[k]($0)[2,6)))"),
            Map.entry(".[.b]?", "(.b[2,4) as $0 | .[k]?($0)[0,6))"),
            Map.entry(".[.a:.b]", "(.a[2,4) as $0 | (.b[5,7) as $1 | .[a:b]($0; $1)[0,8)))"),
            Map.entry(".a[.s:.e]", "(.s[3,5) as $0 | (.e[6,8) as $1 | (.a[0,2) | .[a:b]($0; $1)[2,9))))"),
            Map.entry(".[(0,1):(2,3)]", "((0[3,4), 1[5,6)) as $0 | ((2[9,10), 3[11,12)) as $1 | .[a:b]($0; $1)[0,14)))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #67's lowerings (ADR-0038), each run on jq-ref 1.8.2 in diff/bind.rec and interpolation.rec: {@code E as $x} is
   * one Bind; a pattern's steps are non-quiet indexes of its level's slot, array elements last first; a Var or Const
   * key, index or operand is an argument without a Bind; an interpolation's Identity and text parts are arguments; a
   * {@code ?//} is an AltBind whose alternatives end in an Identity leaf, a name an alternative never stores reading
   * one Const null bound around the chain.
   */
  @Test
  void variablesPatternsAndInterpolationsLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("1 as $x | $x", "(1[0,1) as $0 | $0)"),
            Map.entry("1 as $i | .[$i]", "(1[0,1) as $0 | .[k]($0)[10,15))"),
            Map.entry("1 as $x | $x + .", "(1[0,1) as $0 | _plus($0; .)[10,16))"),
            Map.entry(". as [$a, $b] | $a", "(.[0,1) as $0 | (($0 | .[k](1)[10,12)) as $1 | (($0 | .[k](0)[6,8)) as $2 | $2)))"),
            Map.entry("\"b\" as $k | . as {($k): $v} | $v", "(\"b\"[0,3) as $0 | (.[12,13) as $1 | (($1 | .[k]($0)[18,26)) as $2 | $2)))"),
            Map.entry(". as {$a: [$b]} | $b", "(.[0,1) as $0 | (($0 | .[k](\"a\")[6,14)) as $1 | (($1 | .[k](0)[11,13)) as $2 | $2)))"),
            Map.entry("\"a\\(.)b\"", "interpolation(\"a\"; .; \"b\")[0,8)"),
            Map.entry("\"\\(1, 2)\\(.a)\"", "(.a[10,12) as $0 | ((1[3,4), 2[6,7)) as $1 | interpolation($1; $0)[0,14)))"),
            Map.entry(". as [$a] ?// $a | $a", "(.[0,1) as $0 | (($0 | .[k](0)[6,8)) as $1 | .[5,9)) -> [$1] ?// .[14,16) -> [$0] as [$2] | $2)"),
            Map.entry(
                ". as [$a] ?// $b | $a",
                "(null[0,21) as $0 | (.[0,1) as $1 | (($1 | .[k](0)[6,8)) as $2 | .[5,9)) -> [$2, $0] ?// .[14,16) -> [$0, $1] as [$3, $4] | $3))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #68's lowerings (ADR-0039), the positive witnesses of the entries that left the Stub lists above: an elif is the If
   * of its else and a missing else an Identity; {@code try} without {@code catch} and {@code f?} take {@code empty} as
   * their handler, a {@code ?} spanning its target; {@code error(f)} is {@code f | error} at the call's Source; a Break
   * names the innermost Label of its name, and a label's name is apart from a variable's. jq-ref 1.8.2, observed
   * 2026-11-13 with -nc: {@code [1 as $f | label $f | $f, break $f, 2]}, {@code [label $f | label $f | 1, break $f, 2]}
   * and {@code [label $a | label $b | 1, break $a, 2]} each give [1], {@code [if false then 1 end]} [null],
   * {@code [(1, 2)?]} [1,2] and {@code [try error("x") catch .]} ["x"].
   */
  @Test
  void controlFlowLowersToItsIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("if true then 1 else 2 end", "(if true[3,7) then 1[13,14) else 2[20,21))"),
            Map.entry("if false then 1 end", "(if false[3,8) then 1[14,15) else .[0,19))"),
            Map.entry(
                "if false then 1 elif true then 2 else 3 end",
                "(if false[3,8) then 1[14,15) else (if true[21,25) then 2[31,32) else 3[38,39)))"),
            Map.entry("try 1 catch 2", "(try 1[4,5) catch 2[12,13))"),
            Map.entry("try 1", "(try 1[4,5) catch empty[0,5))"),
            Map.entry("1?", "(try 1[0,1) catch empty[0,2))"),
            Map.entry("(1, 2)?", "(try (1[1,2), 2[4,5)) catch empty[0,7))"),
            Map.entry("error", "error[0,5)"),
            Map.entry("try error(\"x\") catch .", "(try (\"x\"[10,13) | error[4,14)) catch .[21,22))"),
            Map.entry("label $f | break $f", "(label $0 | break $0[11,19))"),
            Map.entry("label $f | label $f | break $f", "(label $0 | (label $1 | break $1[22,30)))"),
            Map.entry("label $a | label $b | break $a", "(label $0 | (label $1 | break $0[22,30)))"),
            Map.entry("1 as $f | label $f | $f, break $f", "(1[0,1) as $0 | (label $1 | ($0, break $1[25,33))))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #69's lowerings (ADR-0041), the positive witnesses of the entries that left the Stub lists above: a {@code reduce}
   * is a Reduce whose state slot its init sets, around the Bind or AltBind of its source and pattern, whose body is the
   * Advance of the slot on the update, and its result goes through {@code _state}, as the update's input does, which
   * makes an empty state jq's null; a {@code foreach} the same Foreach, with no {@code _state} after it, the Advance
   * piped into the extract when there is one; {@code range(a; b)} an Intrinsic of range on its bounds, {@code a} bound
   * outside {@code b}, an Identity, Const or Var bound an argument. jq-ref 1.8.2, observed 2026-11-13 with -nc: the precedence entries give 3, 3, 6
   * (precedence/reduce-source-takes-a-sum, foreach-source-takes-a-sum, reduce-is-a-term); {@code [foreach (1,2,3) as
   * $x (0; . + $x; [$x, .])]} gives [[1,1],[2,3],[3,6]], {@code reduce empty as $x (0; .)} 0, {@code reduce .[] as
   * [$a, $b] (0; . + $a * $b)} on [[1,2],[3,4]] 14, {@code [foreach . as $x (0; 1; 2)]} [2], {@code reduce . as [$a]
   * ?// $a (0; $a)} on [5] 5, {@code [range(0; 5)]} [0,1,2,3,4], {@code [range(.a; .b)]} on {"a":1,"b":3} [1,2] and
   * {@code [1 as $n | range(.; $n)]} on 0 [0].
   */
  @Test
  void loopsAndRangeLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("reduce 1 + 2 as $x (0; . + $x)", "((reduce $0 = 0[20,21) | (3[7,12) as $1 | (advance $0 | (_state[23,29) | _plus(.; $1)[23,29))))) | _state[0,30))"),
            Map.entry("foreach 1 + 2 as $x (0; . + $x)", "(foreach $0 = 0[21,22) | (3[8,13) as $1 | (advance $0 | (_state[24,30) | _plus(.; $1)[24,30)))))"),
            Map.entry(
                "reduce (1,2) as $x (0; . + $x) * 2",
                "(((reduce $1 = 0[20,21) | ((1[8,9), 2[10,11)) as $2 | (advance $1 | (_state[23,29) | _plus(.; $2)[23,29))))) | _state[0,30)) as $0 | _multiply($0; 2)[0,34))"),
            Map.entry(
                "[foreach (1,2,3) as $x (0; . + $x; [$x, .])]",
                "[(foreach $0 = 0[24,25) | (((1[10,11), 2[12,13)), 3[14,15)) as $1 | ((advance $0 | (_state[27,33) | _plus(.; $1)[27,33))) | [($1, .[40,41))][35,42))))][0,44)"),
            Map.entry("reduce empty as $x (0; .)", "((reduce $0 = 0[20,21) | (empty[7,12) as $1 | (advance $0 | (_state[23,24) | .[23,24))))) | _state[0,25))"),
            Map.entry(
                "reduce .[] as [$a, $b] (0; . + $a * $b)",
                "((reduce $0 = 0[24,25) | (.[][7,10) as $1 | (($1 | .[k](1)[19,21)) as $2 | (($1 | .[k](0)[15,17)) as $3 | (advance $0 | (_state[27,38) | (_multiply($3; $2)[31,38) as $4 | _plus(.; $4)[27,38)))))))) | _state[0,39))"),
            Map.entry("foreach . as $x (0; 1; 2)", "(foreach $0 = 0[17,18) | (.[8,9) as $1 | ((advance $0 | (_state[20,21) | 1[20,21))) | 2[23,24))))"),
            Map.entry(
                "reduce . as [$a] ?// $a (0; $a)",
                "((reduce $0 = 0[25,26) | (.[7,8) as $1 | (($1 | .[k](0)[13,15)) as $2 | .[12,16)) -> [$2] ?// .[21,23) -> [$1] as [$3] | (advance $0 | (_state[28,30) | $3)))) | _state[0,31))"),
            Map.entry("range(0; 5)", "range(0; 5)[0,11)"),
            Map.entry("range(.a; .b)", "(.a[6,8) as $0 | (.b[10,12) as $1 | range($0; $1)[0,13)))"),
            Map.entry("1 as $n | range(.; $n)", "(1[0,1) as $0 | range(.; $0)[10,22))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * #70's lowerings (ADR-0040), the positive witnesses of the entries that left the Stub lists above: a definition
   * makes no node, and a call of it is its body, inlined, at the body's Sources; a filter parameter's call is its
   * argument lowered where the call is written, a parameter passed on as it is; a value parameter's argument is
   * lowered once and bound around the body, its name also callable; a definition sees the definitions before it; a
   * user definition hides the native of its name; a label is seen through a definition; a definition the main filter
   * never calls is not lowered. jq-ref 1.8.2, observed 2026-11-13 with -c, in order: 1 on null, 5 on {"a":{"a":5}}, 2
   * and 2 on null, 1 on {"a":1,"b":2}, 3 on {"a":3}, then on null 5, 7, [] for the label one inside [...], 3, 2, 1 and
   * null. The shadowing of {@code not} and {@code range/2} is also recorded (diff/defs.rec).
   */
  @Test
  void definitionsLowerToTheirIr() {
    Map<String, String> shapes =
        Map.ofEntries(
            Map.entry("def f: 1; f", "1[7,8)"),
            Map.entry("def f(g): g | g; f(.a)", "(.a[19,21) | .a[19,21))"),
            Map.entry("def f($x): $x + $x; f(1)", "(1[22,23) as $0 | _plus($0; $0)[11,18))"),
            Map.entry("def f(g): 1 as $x | g; 2 as $x | f($x)", "(2[23,24) as $0 | (1[10,11) as $1 | $0))"),
            Map.entry("def f: .a; def g: f; def f: .b; g", ".a[7,9)"),
            Map.entry("def f(g): g; def h(g): f(g); h(.a)", ".a[31,33)"),
            Map.entry("def not: 5; not", "5[9,10)"),
            Map.entry("def range(a; b): 7; range(1; 2)", "7[17,18)"),
            Map.entry("label $l | def f: break $l; f", "(label $0 | break $0[18,26))"),
            Map.entry("def f: def g: 3; g; f", "3[14,15)"),
            Map.entry("def f($a): a + $a; f(1)", "(1[21,22) as $0 | _plus(1; $0)[11,17))"),
            // The dead body held .. until #110 built it; $ENV, unbuilt until spec 7, would stub if f were lowered
            // (jq-ref 1.8.2, observed 2026-11-15: 'def f: $ENV; 1' on null -> 1).
            Map.entry("def f: $ENV; 1", "1[13,14)"),
            Map.entry(".?", "(try .[0,1) catch empty[0,2))"));
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      assertThat(shape(ir(e.getKey()))).as("IR of %s", e.getKey()).isEqualTo(e.getValue());
    }
  }

  /**
   * F-42, F-44: a run of {@link Resolver#STEP_RUN} steps of one result or none stays a Pipe of its steps; one more is
   * one StepRun, named by and spanning the steps' text. Field reads, constant indexes and constant slices make such a
   * run; an iterate ends it. LongPathChainTest runs the fused runs.
   */
  @Test
  void aRunOfStepsOfOneResultPastTheParseDepthIsOneStep() {
    assertThat(Resolver.STEP_RUN).as("the longest run kept, the parser's nesting bound").isEqualTo(512);
    String kept = ".a".repeat(Resolver.STEP_RUN);
    assertThat(ir(kept)).as("IR of %d reads", Resolver.STEP_RUN).isInstanceOf(Pipe.class);
    String fused = ".a".repeat(Resolver.STEP_RUN + 1);
    Node run = ir(fused);
    assertThat(run).as("IR of %d reads", Resolver.STEP_RUN + 1).isInstanceOf(Intrinsic.class);
    assertThat(shape(run)).as("shape of %d reads", Resolver.STEP_RUN + 1).isEqualTo(fused + "[0," + fused.length() + ")");
    assertThat(((Intrinsic) run).op().form()).as("step of %d reads", Resolver.STEP_RUN + 1).isInstanceOf(StepRun.class);
    // Constant indexes and slices on the leading dot, which the run's span takes.
    String mixed = "." + "[0][1:2][\"k\"]".repeat(171);
    assertThat(shape(ir(mixed))).as("shape of 513 constant indexes and slices").isEqualTo(mixed + "[0," + mixed.length() + ")");
    // An iterate has many results, so it ends the run.
    assertThat(shape(ir(fused + "[]"))).as("shape of %d reads and an iterate", Resolver.STEP_RUN + 1).isEqualTo("(" + fused + "[0," + fused.length() + ") | .[][" + fused.length() + "," + (fused.length() + 2) + "))");
  }

  /**
   * F-44: a chain of {@link Resolver#PIPE_CHAIN} nodes stays a Pipe chain, as deep as it is long; one more is piped
   * balanced, about log2 of its length deep. Each iterate is a node of its own, and so is an index by a variable.
   */
  @Test
  void aChainOfMoreNodesThanThePipeChainIsPipedBalanced() {
    assertThat(Resolver.PIPE_CHAIN).as("the most nodes kept a Pipe chain, an eighth of the IR depth bound").isEqualTo(64);
    assertThat(depth(ir("." + "[]".repeat(Resolver.PIPE_CHAIN)))).as("depth of %d iterates", Resolver.PIPE_CHAIN).isEqualTo(Resolver.PIPE_CHAIN);
    assertThat(depth(ir("." + "[]".repeat(Resolver.PIPE_CHAIN + 1)))).as("depth of %d iterates", Resolver.PIPE_CHAIN + 1).isEqualTo(8);
    assertThat(depth(ir("." + "[]".repeat(513)))).as("depth of 513 iterates").isEqualTo(11);
    assertThat(depth(ir("1 as $k | ." + "[$k]".repeat(100_000)))).as("depth of 10⁵ indexes by a variable").isEqualTo(19);
  }

  /**
   * F-44: a {@code ?} after another covers the first's Try, so only the last Try is built, around both;
   * LongPathChainTest runs both filters, and {@code [1 | .[0]?? | .a]}, against jq-ref's results.
   */
  @Test
  void aQuietStepAfterAnotherBuildsOneTry() {
    assertThat(shape(ir(".[0]??[0]??"))).as("IR of .[0]??[0]??").isEqualTo("(try (.[k]?(0)[0,5) | .[k]?(0)[6,10)) catch empty[6,11))");
    // A computed key between them binds around the first Try, as the nested form does.
    assertThat(shape(ir(".[0]??[.a]??")))
        .as("IR of .[0]??[.a]??")
        .isEqualTo("(try (.a[7,9) as $0 | ((try .[k]?(0)[0,5) catch empty[1,6)) | .[k]?($0)[6,11))) catch empty[6,12))");
  }

  /**
   * #94 (ADR-0043): past {@code BIND_RUN} nested Binds, a run of one left-associative operator lowers to one Binds,
   * with its values in the order its nested form runs them: the right operands from the top operator down, then the
   * leftmost term, then each operator's result from the bottom up, and the top operator as the body. 70 terms of field
   * reads are 69 operators, 138 nested Binds until #94. The Binds prints as the nested Bind chain it means, so the
   * text gives the order and the slots each operator reads; Sources are left out.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"+, _plus", "-, _minus", "*, _multiply", "/, _divide", "%, _mod"})
  void aRunOfOneOperatorLowersToOneBindsInTheNestedFormsOrder(String operator, String operation) {
    int ops = 69;
    StringJoiner filter = new StringJoiner(" " + operator + " ");
    for (int k = 0; k <= ops; k++) {
      filter.add(".k" + k);
    }
    Node root = ir(filter.toString());
    assertThat(root).as("IR of 70 terms of %s", operator).isInstanceOf(Binds.class);
    assertThat(((Binds) root).values()).as("values of the Binds of 70 terms of %s", operator).hasSize(2 * ops).allSatisfy(v -> assertThat(v).isInstanceOf(Intrinsic.class));
    StringBuilder expected = new StringBuilder();
    // The right operands, from the top operator down: .k69 as $0, …, .k1 as $68.
    for (int k = ops; k >= 1; k--) {
      expected.append("(.k").append(k).append(" as $").append(ops - k).append(" | ");
    }
    // The leftmost term, $69.
    expected.append("(.k0 as $").append(ops).append(" | ");
    // Each operator's result from the bottom up: the first on $69 and $68, the m-th on the one before it and .km.
    for (int m = 1; m < ops; m++) {
      int left = m == 1 ? ops : ops + m - 1;
      expected.append('(').append(operation).append("($").append(left).append("; $").append(ops - m).append(") as $").append(ops + m).append(" | ");
    }
    // The top operator: the last result and .k69.
    expected.append(operation).append("($").append(2 * ops - 1).append("; $0)").append(")".repeat(2 * ops));
    assertThat(stripped(root)).as("IR of 70 terms of %s, Sources left out", operator).isEqualTo(expected.toString());
  }

  /**
   * #94 (ADR-0043, ADR-0034): a variable operand is an argument, so a run of {@code $x+} binds no term, only each
   * operator's result but the top one's: 100 terms are 99 operators, 98 nested Binds until #94, and one Binds of 98.
   */
  @Test
  void aRunOfVariablesBindsOnlyTheOperatorsResults() {
    Node root = ir("1 as $x | " + String.join(" + ", Collections.nCopies(100, "$x")));
    assertThat(root).as("IR of 1 as $x | 100 terms of $x+").isInstanceOf(Bind.class);
    Node run = ((Bind) root).body();
    assertThat(run).as("body of the Bind of $x").isInstanceOf(Binds.class);
    assertThat(((Binds) run).values()).as("values of the Binds of 100 terms of $x+").hasSize(98);
    StringBuilder expected = new StringBuilder("(1 as $0 | (_plus($0; $0) as $1 | ");
    for (int m = 2; m <= 98; m++) {
      expected.append("(_plus($").append(m - 1).append("; $0) as $").append(m).append(" | ");
    }
    expected.append("_plus($98; $0)").append(")".repeat(99));
    assertThat(stripped(root)).as("IR of 1 as $x | 100 terms of $x+, Sources left out").isEqualTo(expected.toString());
  }

  /**
   * ADR-0043 "Below BIND_RUN nothing changes": a short run stays the nested Binds of ADR-0034, the right operand bound
   * outside the left one. A guard: #94 leaves it as it is.
   */
  @Test
  void aShortRunOfOneOperatorStaysNestedBinds() {
    Node root = ir(".k0 + .k1 + .k2");
    assertThat(root).as("IR of .k0 + .k1 + .k2").isInstanceOf(Bind.class);
    assertThat(stripped(root)).as("IR of .k0 + .k1 + .k2, Sources left out").isEqualTo("(.k2 as $0 | ((.k1 as $2 | (.k0 as $3 | _plus($3; $2))) as $1 | _plus($1; $0)))");
  }

  /**
   * #94 (ADR-0043): a run of more than {@code PIPE_CHAIN} terms of {@code or} is built balanced from ADR-0037's
   * lowering, about 2·log2 of its length deep: 20 000 terms, a Stub past the IR bound until #94, are at most 32 levels.
   * A level is counted as IrVerifier recurses: a leaf is one, an Intrinsic with arguments two; {@code .a or .a} has
   * three, the witness that the count counts.
   */
  @Test
  void anOrRunOf20000TermsIsAtMost32LevelsDeep() {
    assertThat(levels(ir(".a or .a"))).as("levels of .a or .a").isEqualTo(3);
    assertThat(levels(ir(String.join(" or ", Collections.nCopies(20_000, ".a"))))).as("levels of 20 000 terms of or").isLessThanOrEqualTo(32);
  }

  /** The same for {@code and}. */
  @Test
  void anAndRunOf20000TermsIsAtMost32LevelsDeep() {
    assertThat(levels(ir(".a and .a"))).as("levels of .a and .a").isEqualTo(3);
    assertThat(levels(ir(String.join(" and ", Collections.nCopies(20_000, ".a"))))).as("levels of 20 000 terms of and").isLessThanOrEqualTo(32);
  }

  /**
   * ADR-0043: {@code BIND_RUN} (64) counts the nested form's Binds, one for each operator's result but the outermost
   * one's and one for each term that is no Identity, Const or Var. 33 terms of {@code .k+} are 31 + 33 = 64, not past
   * it, and stay nested Binds; 34 are 32 + 34 = 66 and lower to one Binds; 34 terms whose first is {@code $x} are 32 +
   * 33 = 65, the first count past it, and lower to one Binds of 65 values.
   */
  @Test
  void aRunOfOneOperatorIsOneBindsJustPast64NestedBinds() {
    assertThat(ir(fields(33, " + "))).as("IR of 33 terms of .k+, 64 nested Binds").isInstanceOf(Bind.class);
    Node past = ir(fields(34, " + "));
    assertThat(past).as("IR of 34 terms of .k+, 66 nested Binds").isInstanceOf(Binds.class);
    assertThat(((Binds) past).values()).as("values of the Binds of 34 terms of .k+").hasSize(66);
    Node first = ir("1 as $x | $x + " + fields(34, " + ").substring(".k0 + ".length()));
    assertThat(((Bind) first).body()).as("body of 1 as $x | $x + .k1 + … + .k33, 65 nested Binds").isInstanceOf(Binds.class);
    assertThat(((Binds) ((Bind) first).body()).values()).as("values of the Binds of $x + .k1 + … + .k33").hasSize(65);
  }

  /**
   * ADR-0043: a run of {@code and} or {@code or} of more than {@code PIPE_CHAIN} (64) terms is balanced. 64 terms are
   * the left-nested chain of ADR-0037, one level a term and one more, 65 levels; 65 terms are the balanced tree, 13
   * levels, the left half taking the middle term.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"' and '", "' or '"})
  void anAndOrRunIsBalancedJustPast64Terms(String operator) {
    assertThat(levels(ir(fields(64, operator)))).as("levels of 64 terms of%s", operator).isEqualTo(65);
    assertThat(levels(ir(fields(65, operator)))).as("levels of 65 terms of%s", operator).isEqualTo(13);
  }

  /**
   * #94: a run of {@code //} of more than {@code PIPE_CHAIN} (64) terms is a balanced Alt tree. 64 terms nest to the
   * right as the parser reads them, a level a term; 65 are 8 levels.
   */
  @Test
  void anAlternativeRunIsBalancedJustPast64Terms() {
    Node nested = ir(fields(64, " // "));
    assertThat(levels(nested)).as("levels of 64 terms of //").isEqualTo(64);
    assertThat(((Alt) nested).right()).as("right side of 64 terms of //").isInstanceOf(Alt.class);
    Node balanced = ir(fields(65, " // "));
    assertThat(levels(balanced)).as("levels of 65 terms of //").isEqualTo(8);
    assertThat(((Alt) balanced).left()).as("left side of 65 terms of //").isInstanceOf(Alt.class);
  }

  /**
   * ADR-0043 lowers a run of one operator to one Binds: a chain that alternates {@code +} and {@code -} is runs of one
   * operator each, so it stays nested, two levels an operator, and 400 terms are past the IR bound's 512 levels, its
   * Stub. A guard of "one operator".
   */
  @Test
  void anAlternatingChainPastTheBoundStaysNestedAndIsTheIrDepthStub() {
    StringBuilder chain = new StringBuilder(".k0");
    for (int k = 1; k < 400; k++) {
      chain.append(k % 2 == 1 ? " + " : " - ").append(".k").append(k);
    }
    assertThatExceptionOfType(JqCompileException.class)
        .as("resolve of 400 terms alternating + and -")
        .isThrownBy(() -> ir(chain.toString()))
        .withMessageStartingWith(ParseAssertions.MARK);
  }

  /** {@code n} field reads {@code .k0}, {@code .k1}, … joined by {@code separator}. */
  private static String fields(int n, String separator) {
    return terms(n, separator, ".k%d");
  }

  /**
   * ADR-0043 (#93): past {@code BIND_RUN} (= {@link Resolver#PIPE_CHAIN}, 64) nested Binds the Resolver builds one
   * Binds whose values are the nested form's, in its order: for an object the key, the value and the key check of each
   * pair (each a value of the Binds, the check bound to a slot no node reads), BuildObject the body; for an
   * interpolation the last part first; for a pattern the value and its steps, and each {@code ?//} alternative's match
   * {@code Binds(steps, Identity)}; for a value parameter's expansion one Binds of its arguments, the first outermost.
   * {@link ParseAssertions#shape} prints a Binds as the chain of Binds it means, so each expected text is the nested
   * form's (ADR-0034, ADR-0038, ADR-0040, as {@link #variablesPatternsAndInterpolationsLowerToTheirIr} and {@link
   * #definitionsLowerToTheirIr} pin it for few parts), Sources left out. jq-ref's results for such constructs of 70
   * parts with two generators are recorded in diff/binds.rec.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("wideConstructs")
  void aWideConstructLowersToOneBindsInJqsOrder(String construct, String filter, String nested, Consumer<Node> oneBinds) {
    Node node = ir(filter);
    assertThat(stripped(node)).as("IR of the %s, Sources left out", construct).isEqualTo(nested);
    oneBinds.accept(node);
  }

  static Stream<Arguments> wideConstructs() {
    // An object of 70 constant keys, each value a field read: the 70 values, BuildObject on their slots the body.
    StringBuilder pairs = new StringBuilder();
    for (int i = 0; i < 70; i++) {
      pairs.append("(.v").append(i).append(" as $").append(i).append(" | ");
    }
    // An object of 70 computed keys: per pair its key, its value and the check of its key, before the next pair.
    StringBuilder checked = new StringBuilder();
    StringJoiner arguments = new StringJoiner("; ");
    for (int i = 0; i < 70; i++) {
      int key = 3 * i;
      checked.append("(.k").append(i).append(" as $").append(key).append(" | (.v").append(i).append(" as $").append(key + 1).append(" | ");
      checked.append("(object key($").append(key).append(") as $").append(key + 2).append(" | ");
      arguments.add("$" + key).add("$" + (key + 1));
    }
    // An interpolation of 70 parts: the last part outermost, the Interpolate on the parts in their order the body.
    StringBuilder lastFirst = new StringBuilder();
    for (int i = 69; i >= 0; i--) {
      lastFirst.append("(.v").append(i).append(" as $").append(69 - i).append(" | ");
    }
    return Stream.of(
        Arguments.of(
            "object of 70 constant keys",
            "{" + terms(70, ", ", "k%d: .v%d") + "}",
            pairs + "{...}(" + names(0, 70, 1) + ")" + ")".repeat(70),
            body(70, "{...}")),
        Arguments.of(
            "object of 70 computed keys",
            "{" + terms(70, ", ", "(.k%d): .v%d") + "}",
            checked + "{...}(" + arguments + ")" + ")".repeat(210),
            body(210, "{...}")),
        Arguments.of(
            "interpolation of 70 parts",
            "\"" + terms(70, "", "\\(.v%d)") + "\"",
            lastFirst + "interpolation(" + names(69, -1, -1) + ")" + ")".repeat(70),
            body(70, "interpolation")),
        // An array pattern of 70 variables: the value, then each element's index, the last element first, in one Binds
        // (the value's own Bind may stay outside it).
        Arguments.of(
            "array pattern of 70 variables",
            ". as [" + terms(70, ", ", "$a%d") + "] | $a0",
            "(. as $0 | " + steps(0, 1) + "$70" + ")".repeat(71),
            (Consumer<Node>)
                node -> {
                  assertThat(count(node, Binds.class)).as("Binds nodes in the IR of the array pattern of 70 variables").isEqualTo(1);
                  assertThat(count(node, Bind.class)).as("Bind nodes in the IR of the array pattern of 70 variables, the value's at most").isLessThanOrEqualTo(1);
                }),
        // Two alternatives of 70 variables each: each match is one Binds of its 70 steps around the Identity leaf.
        Arguments.of(
            "two alternatives of 70 variables",
            ". as [" + terms(70, ", ", "$a%d") + "] ?// [" + terms(70, ", ", "$a%d") + "] | $a0",
            "(. as $0 | " + steps(0, 1) + "." + ")".repeat(70) + " -> [" + names(70, 0, -1).replace(";", ",") + "] ?// " + steps(0, 71) + "." + ")".repeat(70) + " -> ["
                + names(140, 70, -1).replace(";", ",") + "] as [" + names(141, 211, 1).replace(";", ",") + "] | $141)",
            (Consumer<Node>)
                node -> {
                  assertThat(node).as("IR of the two alternatives of 70 variables").isInstanceOf(AltBind.class);
                  for (Alternative alternative : ((AltBind) node).alternatives()) {
                    assertThat(alternative.match()).as("match of an alternative of 70 variables").isInstanceOf(Binds.class);
                    assertThat(((Binds) alternative.match()).values()).as("values of the match of an alternative of 70 variables").hasSize(70);
                    assertThat(((Binds) alternative.match()).body()).as("leaf of the match of an alternative of 70 variables").isInstanceOf(Identity.class);
                  }
                }),
        // An expansion of 70 value parameters: one Binds of its arguments, the first outermost, around the body.
        Arguments.of(
            "expansion of 70 value parameters",
            "def f(" + terms(70, "; ", "$a%d") + "): [$a0, $a69]; f(" + terms(70, "; ", ".v%d") + ")",
            pairs + "[($0, $69)]" + ")".repeat(70),
            body(70, null)));
  }

  /**
   * The check that an IR is one Binds of {@code values} values, whose body is an Intrinsic of the operation named
   * {@code operation} unless it is null.
   */
  private static Consumer<Node> body(int values, String operation) {
    return node -> {
      assertThat(node).as("IR of %d values", values).isInstanceOf(Binds.class);
      Binds binds = (Binds) node;
      assertThat(binds.values()).as("values of the Binds").hasSize(values);
      if (operation != null) {
        assertThat(binds.body()).as("body of the Binds").isInstanceOf(Intrinsic.class);
        assertThat(((Intrinsic) binds.body()).op().name()).as("operation of the body of the Binds").isEqualTo(operation);
      }
    };
  }

  /**
   * ADR-0043: below {@code BIND_RUN} nothing changes, so 64 nested Binds stay a chain of Bind nodes, and 65 are one
   * Binds of 65 values: an object of computed values, an interpolation and a value parameter's expansion, each part a
   * Bind of its own.
   */
  @Test
  void sixtyFourBindsStayNestedAndSixtyFiveAreOneBinds() {
    for (int n : new int[] {64, 65}) {
      List<String> filters =
          List.of(
              "{" + terms(n, ", ", "k%d: .v%d") + "}",
              "\"" + terms(n, "", "\\(.v%d)") + "\"",
              "def f(" + terms(n, "; ", "$a%d") + "): $a0; f(" + terms(n, "; ", ".v%d") + ")");
      for (String filter : filters) {
        Node node = ir(filter);
        String shown = ParseAssertions.show(filter.substring(0, 20)) + "… of " + n + " parts";
        if (n == 64) {
          assertThat(count(node, Binds.class)).as("Binds nodes in the IR of %s", shown).isZero();
          assertThat(count(node, Bind.class)).as("Bind nodes in the IR of %s", shown).isEqualTo(64);
        } else {
          assertThat(node).as("IR of %s", shown).isInstanceOf(Binds.class);
          assertThat(((Binds) node).values()).as("values of the Binds of %s", shown).hasSize(65);
          assertThat(count(node, Bind.class)).as("Bind nodes in the IR of %s", shown).isZero();
        }
      }
    }
  }

  /**
   * The same boundary for the other parts the lowering binds (#93, implementer, the hand mutants of the pattern bound):
   * a pattern of n variables is the value's Bind and n steps, so 63 variables stay 64 Bind nodes and 64 are one Binds
   * of 65 values; each {@code ?//} alternative's match of 64 steps stays nested and of 65 is one Binds; and an
   * interpolation's text parts are passed as arguments, so 64 interpolations between text stay nested and 65 are one
   * Binds of the 65 interpolations alone.
   */
  @Test
  void theBoundCountsThePatternsValueEachMatchAndOnlyTheBoundParts() {
    for (int n : new int[] {64, 65}) {
      Node pattern = ir(". as [" + terms(n - 1, ", ", "$a%d") + "] | 1");
      Node alternatives = ir(". as [" + terms(n, ", ", "$a%d") + "] ?// [" + terms(n, ", ", "$a%d") + "] | 1");
      Node text = ir("\"" + terms(n, "-", "\\(.v%d)") + "\"");
      if (n == 64) {
        assertThat(count(pattern, Binds.class)).as("Binds nodes in the IR of a pattern of 63 variables").isZero();
        assertThat(count(pattern, Bind.class)).as("Bind nodes in the IR of a pattern of 63 variables").isEqualTo(64);
        assertThat(count(alternatives, Binds.class)).as("Binds nodes in the IR of two alternatives of 64 variables").isZero();
        assertThat(count(alternatives, Bind.class)).as("Bind nodes in the IR of two alternatives of 64 variables").isEqualTo(128);
        assertThat(count(text, Binds.class)).as("Binds nodes in the IR of 64 interpolations between text").isZero();
        assertThat(count(text, Bind.class)).as("Bind nodes in the IR of 64 interpolations between text").isEqualTo(64);
      } else {
        assertThat(pattern).as("IR of a pattern of 64 variables").isInstanceOf(Binds.class);
        assertThat(((Binds) pattern).values()).as("values of the Binds of a pattern of 64 variables").hasSize(65);
        assertThat(count(pattern, Bind.class)).as("Bind nodes in the IR of a pattern of 64 variables").isZero();
        assertThat(count(alternatives, Binds.class)).as("Binds nodes in the IR of two alternatives of 65 variables").isEqualTo(2);
        assertThat(count(alternatives, Bind.class)).as("Bind nodes in the IR of two alternatives of 65 variables").isZero();
        assertThat(text).as("IR of 65 interpolations between text").isInstanceOf(Binds.class);
        assertThat(((Binds) text).values()).as("values of the Binds of 65 interpolations between text").hasSize(65);
      }
    }
  }

  /**
   * The steps of an array pattern of 70 variables on the level {@code $<level>}, named from {@code $<first>}, as the
   * nested form prints them: the element 69 first, each {@code ($level | .[k](i)) as $name | }.
   */
  private static String steps(int level, int first) {
    StringBuilder chain = new StringBuilder();
    for (int i = 69; i >= 0; i--) {
      chain.append("(($").append(level).append(" | .[k](").append(i).append(")) as $").append(first + 69 - i).append(" | ");
    }
    return chain.toString();
  }

  /** {@code $from; …} up to {@code to}, exclusive, by {@code by}. */
  private static String names(int from, int to, int by) {
    StringJoiner joined = new StringJoiner("; ");
    for (int i = from; i != to; i += by) {
      joined.add("$" + i);
    }
    return joined.toString();
  }

  /** The levels of {@code node} as IrVerifier recurses on it: a leaf is one, and an Intrinsic's arguments count. */
  static int levels(Node node) {
    List<Node> parts =
        switch (node) {
          case Identity _ -> List.of();
          case Const _ -> List.of();
          case Var _ -> List.of();
          case Break _ -> List.of();
          case Intrinsic i -> i.args();
          case Pipe p -> List.of(p.left(), p.right());
          case Comma c -> List.of(c.left(), c.right());
          case Collect c -> List.of(c.body());
          case If i -> List.of(i.cond(), i.then(), i.otherwise());
          case Alt a -> List.of(a.left(), a.right());
          case Bind b -> List.of(b.value(), b.body());
          case Binds b -> {
            List<Node> all = new ArrayList<>(b.values());
            all.add(b.body());
            yield all;
          }
          case AltBind a -> {
            List<Node> all = new ArrayList<>(List.of(a.value()));
            a.alternatives().forEach(alternative -> all.add(alternative.match()));
            all.add(a.body());
            yield all;
          }
          case Try t -> List.of(t.body(), t.handler());
          case Label l -> List.of(l.body());
          case Reduce r -> List.of(r.init(), r.each());
          case Foreach f -> List.of(f.init(), f.each());
          case Advance a -> List.of(a.update());
          case PathOf p -> List.of(p.body());
          case Update u -> List.of(u.lhs(), u.rhs());
          case ParamCall _ -> List.of();
          case Call c -> {
            List<Node> all = new ArrayList<>(c.closures());
            all.addAll(c.values());
            yield all;
          }
        };
    int deepest = 0;
    for (Node part : parts) {
      deepest = Math.max(deepest, levels(part));
    }
    return 1 + deepest;
  }

  /** ADR-0040: {@code Resolver.INLINE_CAP}, stated as a literal as InlineCapTest states it. */
  private static final int CAP = 65_536;

  /**
   * ADR-0040 #114 amendment, "No call is expanded twice. A compile lowers at most {@code INLINE_CAP} nodes inside
   * expansions, plus each text once": {@code def f0: .; def f1: f0,f0; …; def f60: f59,f59; f60} would lower 2^61 - 1
   * nodes inlined. Its text is 182 nodes (f0's one, three in each of the 60 bodies, the main call), so its program,
   * main and functions, has at most 182 + 65 536 nodes, and the resolution ends within 20 s. Until #114 it was the
   * cap's Stub.
   */
  @Test
  void doublingChainToF60LowersAtMostSourcePlusCap() {
    StringBuilder text = new StringBuilder("def f0: .; ");
    for (int i = 1; i <= 60; i++) {
      text.append("def f").append(i).append(": f").append(i - 1).append(",f").append(i - 1).append("; ");
    }
    String filter = text.append("f60").toString();
    Program program = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> Resolver.resolve(Parser.parse(filter)), "the resolution of f60");
    assertThat(nodes(program)).as("nodes of the program of the doubling chain to f60").isLessThanOrEqualTo(182 + CAP);
  }

  /**
   * ADR-0040 #114 "Considered and rejected": an exhaustion switch would lower 2^k nodes for {@code def fi(g):
   * f(i-1)(g|g)}, since an expansion in progress and its closure chain go on past the cap. {@code def f1(g): g|g; def
   * f2(g): f1(g|g); …; f40(.)}: its text is 161 nodes (three in f1's body, four in each of the 39 others, two in the
   * main call), so its program has at most 161 + 65 536 nodes, and the resolution ends within 20 s. Reference jq 1.8.2 runs
   * the chain to f20 and prints null (diff/over-cap.rec {@code over-cap/closure-doubling-20}).
   */
  @Test
  void closureDoublingChainToF40LowersAtMostSourcePlusCap() {
    StringBuilder text = new StringBuilder("def f1(g): g|g; ");
    for (int i = 2; i <= 40; i++) {
      text.append("def f").append(i).append("(g): f").append(i - 1).append("(g|g); ");
    }
    String filter = text.append("f40(.)").toString();
    Program program = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> Resolver.resolve(Parser.parse(filter)), "the resolution of f40(.)");
    assertThat(nodes(program)).as("nodes of the program of the closure doubling chain to f40").isLessThanOrEqualTo(161 + CAP);
  }

  /**
   * ADR-0040 #114 amendment: "A PLAIN call's definition is lifted once and lowered from the worklist. Every later call
   * of it is a Call, even one whose expansion would fit." {@code def f1: .; def f2: f1; …; def f512: f511; f512, f1}:
   * the call of f1 inside f512's chain is the 512th nested expansion, so it is a PLAIN Call and the chain above it,
   * inlined, is that Call. The second call, {@code f1} at the Comma's right, would be the first expansion and fit, but
   * is a Call of the same function. Until #114 the filter was the nesting Stub. (A depth witness cannot be written in
   * one body: the parser's nesting bound is far below 512 levels, DEVIATIONS "Parse depth"; and a closure written in
   * the main filter spends no node of the cap, so no node witness at the main level lifts.)
   */
  @Test
  void aLiftedDefinitionStaysACallWhereItWouldFit() {
    StringBuilder chain = new StringBuilder("def f1: .; ");
    for (int i = 2; i <= 512; i++) {
      chain.append("def f").append(i).append(": f").append(i - 1).append("; ");
    }
    String filter = chain.append("f512, f1").toString();
    Program program = Resolver.resolve(Parser.parse(filter));
    assertThat(program.main()).as("main tree of %s", shown(filter)).isInstanceOf(Comma.class);
    assertThat(((Comma) program.main()).left()).as("f512's chain, inlined down to the 512th nested expansion").isInstanceOf(Call.class);
    assertThat(((Comma) program.main()).right()).as("the call of f1 at the Comma's right, where it would fit").isInstanceOf(Call.class);
    assertThat(((Call) ((Comma) program.main()).right()).fn()).as("function of the second Call").isEqualTo(((Call) ((Comma) program.main()).left()).fn());
  }

  /**
   * K1 of #114: depth counts closures, so a chain that passes a closure on, one level more per call, lifts by depth
   * before 512 nested expansions. {@code def g1(f): f; def gi(f): g(i-1)(f|.+1); …; 0|g511(.)} nests 511 expansions,
   * one fewer than the nesting trigger, and its closure would be past {@code MAX_IR_DEPTH}: it was the IR depth Stub
   * until #114 (F-54). Now some call is a PLAIN Call, every body is within 512 levels, and it gives reference jq 1.8.2's 510
   * (observed 2026-11-15, -nc -f, .logs/114-jqref-probe.log; 599 for g600, diff/over-cap.rec).
   */
  @Test
  void aParameterChainLiftsByDepth() {
    StringBuilder text = new StringBuilder("def g1(f): f; ");
    for (int i = 2; i <= 511; i++) {
      text.append("def g").append(i).append("(f): g").append(i - 1).append("(f|.+1); ");
    }
    String filter = text.append("0|g511(.)").toString();
    Program program = Resolver.resolve(Parser.parse(filter));
    List<Call> calls = new ArrayList<>(calls(program.main()));
    assertThat(levels(program.main())).as("levels of the main tree of g511").isLessThanOrEqualTo(512);
    for (Function f : program.functions()) {
      calls.addAll(calls(f.body()));
      assertThat(levels(f.body())).as("levels of a lifted body of g511").isLessThanOrEqualTo(512);
    }
    assertThat(calls).as("Calls in the program of g511").isNotEmpty();
    assertThat(results(filter)).as("results of 0|g511(.)").containsExactly("510");
  }

  /** {@code filter}, or its first 60 characters and its length when it is longer. */
  private static String shown(String filter) {
    return filter.length() <= 80 ? filter : filter.substring(0, 60) + "… (" + filter.length() + " chars)";
  }

  /** The nodes of {@code program}: its main tree's and each function body's. */
  static int nodes(Program program) {
    int all = nodes(program.main());
    for (Function f : program.functions()) {
      all += nodes(f.body());
    }
    return all;
  }

  /** The nodes of {@code node}'s tree, as {@link #levels} walks it, by a loop. */
  static int nodes(Node node) {
    int count = 0;
    List<Node> pending = new ArrayList<>(List.of(node));
    while (!pending.isEmpty()) {
      Node next = pending.removeLast();
      count++;
      pending.addAll(parts(next));
    }
    return count;
  }

  /** The Calls of {@code node}'s tree, in pre-order. */
  static List<Call> calls(Node node) {
    List<Call> found = new ArrayList<>();
    List<Node> pending = new ArrayList<>(List.of(node));
    while (!pending.isEmpty()) {
      Node next = pending.removeLast();
      if (next instanceof Call c) {
        found.add(c);
      }
      List<Node> parts = new ArrayList<>(parts(next));
      Collections.reverse(parts);
      pending.addAll(parts);
    }
    return found;
  }

  /** The parts of {@code node} that {@link #levels} recurses on, in its order. */
  static List<Node> parts(Node node) {
    return switch (node) {
      case Identity _ -> List.of();
      case Const _ -> List.of();
      case Var _ -> List.of();
      case Break _ -> List.of();
      case ParamCall _ -> List.of();
      case Intrinsic i -> i.args();
      case Pipe p -> List.of(p.left(), p.right());
      case Comma c -> List.of(c.left(), c.right());
      case Collect c -> List.of(c.body());
      case If i -> List.of(i.cond(), i.then(), i.otherwise());
      case Alt a -> List.of(a.left(), a.right());
      case Bind b -> List.of(b.value(), b.body());
      case Binds b -> {
        List<Node> all = new ArrayList<>(b.values());
        all.add(b.body());
        yield all;
      }
      case AltBind a -> {
        List<Node> all = new ArrayList<>(List.of(a.value()));
        a.alternatives().forEach(alternative -> all.add(alternative.match()));
        all.add(a.body());
        yield all;
      }
      case Try t -> List.of(t.body(), t.handler());
      case Label l -> List.of(l.body());
      case Reduce r -> List.of(r.init(), r.each());
      case Foreach f -> List.of(f.init(), f.each());
      case Advance a -> List.of(a.update());
      case PathOf p -> List.of(p.body());
      case Update u -> List.of(u.lhs(), u.rhs());
      case Call c -> {
        List<Node> all = new ArrayList<>(c.closures());
        all.addAll(c.values());
        yield all;
      }
    };
  }

  /** {@link ParseAssertions#shape} of {@code node} without its Sources. */
  private static String stripped(Node node) {
    return shape(node).replaceAll("\\[\\d+,\\d+\\)", "");
  }

  /** The nodes of {@code kind} in {@code node}'s tree, over the kinds the wide constructs build. */
  private static int count(Node node, Class<? extends Node> kind) {
    int own = kind.isInstance(node) ? 1 : 0;
    return own
        + switch (node) {
          case Pipe p -> count(p.left(), kind) + count(p.right(), kind);
          case Comma c -> count(c.left(), kind) + count(c.right(), kind);
          case Collect c -> count(c.body(), kind);
          case Bind b -> count(b.value(), kind) + count(b.body(), kind);
          case Binds b -> b.values().stream().mapToInt(v -> count(v, kind)).sum() + count(b.body(), kind);
          case AltBind a -> count(a.value(), kind) + a.alternatives().stream().mapToInt(m -> count(m.match(), kind)).sum() + count(a.body(), kind);
          default -> 0;
        };
  }

  /** {@code n} copies of {@code term}, each {@code %d} in it the copy's number from 0, joined by {@code separator}. */
  private static String terms(int n, String separator, String term) {
    StringJoiner joined = new StringJoiner(separator);
    for (int i = 0; i < n; i++) {
      joined.add(term.replace("%d", Integer.toString(i)));
    }
    return joined.toString();
  }

  /** The depth of {@code node}: the most nodes on a path from it to a leaf, an Intrinsic's arguments not counted. */
  private static int depth(Node node) {
    return 1
        + switch (node) {
          case Pipe p -> Math.max(depth(p.left()), depth(p.right()));
          case Bind b -> Math.max(depth(b.value()), depth(b.body()));
          case Try t -> Math.max(depth(t.body()), depth(t.handler()));
          default -> 0;
        };
  }
}
