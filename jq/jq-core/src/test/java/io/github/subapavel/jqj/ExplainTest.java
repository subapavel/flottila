package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.StackUnits;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link JqFilter#explain()} at the API seam (#108, ADR-0016, spec 2b "Seams" 4). A test finds a line by its fields
 * and matches one field at a time, never the whole text, which is unstable. A node line is {@code <function> <id>
 * <kind> <start>..<end>} and its facts as {@code name=value}; a function line is {@code function <function> <name>
 * <start>..<end>} and its facts. The main tree's function is {@code main}.
 */
class ExplainTest {
  /** A recursive definition: the outside call at bytes 43..44, the recursive one at 29..30. */
  private static final String RECURSIVE_F = "def f: if . > 0 then . - 1 | f else . end; f";

  private final JqEngine engine = JqEngine.builder().build();

  private List<Line> lines(String filter) {
    return ExplainLines.lines(engine, filter);
  }

  /** The node lines of {@code function}, in text order. */
  private static List<Line> nodes(List<Line> lines, String function) {
    return lines.stream().filter(l -> !l.isFunction() && l.fields().getFirst().equals(function)).toList();
  }

  /** The facts of the one node line of {@code function} with {@code kind} and {@code span}. */
  private static Map<String, String> node(List<Line> lines, String function, String kind, String span) {
    List<Line> found =
        nodes(lines, function).stream().filter(l -> l.fields().get(2).equals(kind) && l.fields().get(3).equals(span)).toList();
    assertThat(found).as("%s %s at %s in %s", kind, span, function, lines).hasSize(1);
    return found.getFirst().facts();
  }

  /** The function column of the one function line named {@code name}. */
  private static String function(List<Line> lines, String name) {
    List<Line> found = lines.stream().filter(l -> l.isFunction() && l.fields().get(2).equals(name)).toList();
    assertThat(found).as("function %s in %s", name, lines).hasSize(1);
    return found.getFirst().fields().get(1);
  }

  /** The function column of the one function line named {@code name} that is no copy: it shows no {@code copyOf=}. */
  private static String original(List<Line> lines, String name) {
    List<Line> found = lines.stream().filter(l -> l.isFunction() && l.fields().get(2).equals(name) && !l.facts().containsKey("copyOf")).toList();
    assertThat(found).as("the original function %s in %s", name, lines).hasSize(1);
    return found.getFirst().fields().get(1);
  }

  /** The span {@code s..s+1} of the one-byte call at {@code offset} into the first {@code context} in {@code filter}. */
  private static String at(String filter, String context, int offset) {
    int s = filter.indexOf(context);
    assertThat(s).as("%s in %s", context, filter).isNotNegative();
    return (s + offset) + ".." + (s + offset + 1);
  }

  /**
   * The ticket's case: main and f are listed, and each Call shows its kind and its callee. Re-pinned by #118 (ADR-0047
   * #118): f's call of itself is on the right of a Pipe whose left side {@code . - 1} is at most one, in a branch of an
   * If whose condition {@code . > 0} is at most one, with no closure argument, so it is TAIL_SELF.
   */
  @Test
  void explainListsTheProgram() {
    List<Line> lines = lines(RECURSIVE_F);

    String f = function(lines, "f/0");

    assertThat(nodes(lines, "main")).as("main's nodes").isNotEmpty();
    assertThat(nodes(lines, f)).as("f's nodes").isNotEmpty();
    assertThat(node(lines, "main", "Call", "43..44")).as("the outside call").containsEntry("call", "PLAIN").containsEntry("callee", f);
    assertThat(node(lines, f, "Call", "29..30")).as("the recursive call").containsEntry("call", "TAIL_SELF").containsEntry("callee", f);
  }

  /** Each body numbers its nodes from 0 in pre-order: a node, then its parts in their order. */
  @Test
  void eachBodyIsNumberedInPreOrderFromZero() {
    List<Line> main = nodes(lines("1, (2 | 3)"), "main");

    assertThat(main).extracting(l -> l.fields().get(1)).as("ids").containsExactly("0", "1", "2", "3", "4");
    assertThat(main).extracting(l -> l.fields().get(2)).as("kinds").containsExactly("Comma", "Const", "Pipe", "Const", "Const");
    assertThat(main).extracting(l -> l.fields().get(3)).as("spans").containsExactly("0..9", "0..1", "4..9", "4..5", "8..9");

    List<Line> lines = lines(RECURSIVE_F);
    assertThat(nodes(lines, function(lines, "f/0")))
        .extracting(l -> l.fields().get(1))
        .as("f's ids")
        .first()
        .isEqualTo("0");
  }

  /**
   * A call into the caller's recursive group is RECURSIVE unless it is a tail call, and any other call is PLAIN. h's
   * call of itself has {@code + 1} still to run after it, so it is in no tail position: RECURSIVE. a and b, written
   * inside a, reach each other, so they are one group; the calls of g, a group of its own, from a and b are PLAIN. b
   * calls g before a does, so a's call meets a function whose group is already found. Re-pinned by #118 (ADR-0047
   * #118): the other calls into the caller's group are in tail positions with one-valued left contexts and no closure
   * argument, so g's call of itself is TAIL_SELF, and a's call of b and b's call of a are TAIL_GROUP. Reference jq 1.8.2
   * gives 10 and 8 for the filter on 0 (#118's implementer, 2026-11-15).
   */
  @Test
  void aCallIntoTheCallersGroupIsRecursiveAndAnyOtherPlain() {
    String filter =
        "def h: if . > 3 then . else (. + 1 | h) + 1 end; def g: if . > 9 then . else . + 1 | g end;"
            + " def a: def b: g | a; if . > 0 then . - 1 | b else g end; a, h";
    List<Line> lines = lines(filter);
    String h = function(lines, "h/0");
    String g = function(lines, "g/0");
    String a = function(lines, "a/0");
    String b = function(lines, "b/0");

    // The left operand of + 1: not the right of a Pipe, a Comma or an Alt, nor an If branch.
    assertThat(node(lines, h, "Call", at(filter, "| h)", 2))).as("h in h").containsEntry("call", "RECURSIVE").containsEntry("callee", h);

    // An If branch (condition . > 9 at most one), the right of a Pipe whose left . + 1 is at most one.
    assertThat(node(lines, g, "Call", at(filter, "| g end", 2))).as("g in g").containsEntry("call", "TAIL_SELF").containsEntry("callee", g);
    // An If branch (condition . > 0 at most one), the right of a Pipe whose left . - 1 is at most one; b is in a's group.
    assertThat(node(lines, a, "Call", at(filter, "| b else", 2))).as("b in a").containsEntry("call", "TAIL_GROUP").containsEntry("callee", b);
    assertThat(node(lines, a, "Call", at(filter, "else g end", 5))).as("g in a").containsEntry("call", "PLAIN").containsEntry("callee", g);
    assertThat(node(lines, b, "Call", at(filter, "def b: g | a", 7))).as("g in b").containsEntry("call", "PLAIN").containsEntry("callee", g);
    // The right of b's root Pipe, whose left side, the PLAIN call of g, is at most one (card=AT_MOST_ONE).
    assertThat(node(lines, b, "Call", at(filter, "def b: g | a", 11))).as("a in b").containsEntry("call", "TAIL_GROUP").containsEntry("callee", a);
    assertThat(node(lines, "main", "Call", at(filter, "; a", 2))).as("a in main").containsEntry("call", "PLAIN").containsEntry("callee", a);
  }

  /**
   * a calls b, then c, which calls b too: c's call meets b's group already found, which joins neither a's group nor
   * c's, so a's call of c stays PLAIN. Re-pinned by #118 (ADR-0047 #118): c's call of itself is in an If branch
   * (condition {@code . > 5} at most one), on the right of a Pipe whose left {@code . + 1} is at most one: TAIL_SELF.
   */
  @Test
  void aCallIntoAnEarlierGroupJoinsNoGroup() {
    String filter =
        "def b: if . > 9 then . else . + 1 | b end; def c: if . > 5 then b else . + 1 | c end;"
            + " def a: if . > 0 then . - 1 | a else (b, c) end; a";
    List<Line> lines = lines(filter);
    String a = function(lines, "a/0");
    String c = function(lines, "c/0");

    assertThat(node(lines, a, "Call", at(filter, "(b, c)", 4))).as("c in a").containsEntry("call", "PLAIN").containsEntry("callee", c);
    assertThat(node(lines, c, "Call", at(filter, "| c end", 2))).as("c in c").containsEntry("call", "TAIL_SELF").containsEntry("callee", c);
  }

  /**
   * a calls b, b calls c and c calls a: one group of three, though no call goes straight back to a's caller. Re-pinned
   * by #118 (ADR-0047 #118): every call is into another function of the group, in a tail position, with no closure
   * argument, so each is TAIL_GROUP.
   */
  @Test
  void aCycleOfThreeIsOneGroup() {
    String filter = "def a: def b: def c: a; c; if . > 0 then . - 1 | b else . end; a";
    List<Line> lines = lines(filter);
    String a = function(lines, "a/0");
    String b = function(lines, "b/0");
    String c = function(lines, "c/0");

    // An If branch (condition . > 0 at most one), the right of a Pipe whose left . - 1 is at most one.
    assertThat(node(lines, a, "Call", at(filter, "| b else", 2))).as("b in a").containsEntry("call", "TAIL_GROUP").containsEntry("callee", b);
    // The root of b's body, a tail position with no left context.
    assertThat(node(lines, b, "Call", at(filter, "a; c;", 3))).as("c in b").containsEntry("call", "TAIL_GROUP").containsEntry("callee", c);
    // The root of c's body, a tail position with no left context.
    assertThat(node(lines, c, "Call", at(filter, "c: a;", 3))).as("a in c").containsEntry("call", "TAIL_GROUP").containsEntry("callee", a);
  }

  /**
   * A ParamCall shows its parameter, both where the body runs it and where the recursive call passes it on; the
   * function line shows the closure and value parameters.
   */
  @Test
  void aParamCallShowsItsParameter() {
    List<Line> lines = lines("def r(f; $n): if . > $n then . else f | r(f; $n) end; 0 | r(. + 1; 3)");
    // #117: the optimised compile adds a copy of r/2 for main's closed argument; the original keeps both ParamCalls.
    String r = original(lines, "r/2");

    List<Line> calls = nodes(lines, r).stream().filter(l -> l.fields().get(2).equals("ParamCall")).toList();
    assertThat(calls).as("ParamCalls in r").hasSize(2).allSatisfy(l -> assertThat(l.facts()).containsEntry("param", "0"));
    Line header = lines.stream().filter(l -> l.isFunction() && l.fields().get(1).equals(r)).findFirst().orElseThrow();
    assertThat(header.facts()).as("r's parameters").containsEntry("closures", "1").containsEntry("values", "1");
  }

  /** The conservative compile: no optimisation folds the nesting the units tests below build. */
  private final JqEngine conservative = JqEngine.builder().compileMode(CompileMode.CONSERVATIVE).build();

  private List<Line> conservativeLines(String filter) {
    String text = conservative.compile(filter).explain();
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
  }

  /** The whole-number fact {@code name} of {@code facts}, which must carry it. */
  private static long fact(Map<String, String> facts, String name, String what) {
    assertThat(facts).as("the facts of %s", what).containsKey(name);
    return Long.parseLong(facts.get(name));
  }

  /** The facts of the one closing line, the line that carries {@code callFreeDepth}. */
  private static Map<String, String> closing(List<Line> lines) {
    List<Line> found = lines.stream().filter(l -> l.facts().containsKey("callFreeDepth")).toList();
    assertThat(found).as("the closing line with callFreeDepth in %s", lines).hasSize(1);
    return found.getFirst().facts();
  }

  /** The facts of the one function line named {@code name}. */
  private static Map<String, String> functionFacts(List<Line> lines, String name) {
    return lines.stream().filter(l -> l.isFunction() && l.fields().get(2).equals(name)).findFirst().orElseThrow().facts();
  }

  /** The facts of the one RECURSIVE Call line of {@code function}. */
  private static Map<String, String> recursiveCall(List<Line> lines, String function) {
    List<Line> found = nodes(lines, function).stream().filter(l -> l.fields().get(2).equals("Call") && "RECURSIVE".equals(l.facts().get("call"))).toList();
    assertThat(found).as("the RECURSIVE Call of %s in %s", function, lines).hasSize(1);
    return found.getFirst().facts();
  }

  /**
   * ADR-0046 #111: each Call line shows its {@code units}, its whole {@code w(site)}, each function line its
   * {@code emitDepth}, and a closing line {@code callFreeDepth} ({@code D_f}), {@code starterUnits} ({@code T}),
   * {@code bytesPerUnit} and {@code reserve}. A call holds at least one frame, and {@code w} is descent + rise + the
   * callee's leaf-emit depth, so a call's units are at least the callee's {@code emitDepth}. Main here is one call, which
   * counts as a starter nests it, {@code 1 + T}, so {@code D_f >= 1 + T}; a ParamCall counts {@code 2 + T}.
   */
  @Test
  void unitsAndCallFreeDepth() {
    List<Line> lines = conservativeLines(RECURSIVE_F);
    String f = function(lines, "f/0");
    long emitDepth = fact(functionFacts(lines, "f/0"), "emitDepth", "f's function line");
    long outside = fact(node(lines, "main", "Call", "43..44"), "units", "the outside call");
    long recursive = fact(node(lines, f, "Call", "29..30"), "units", "the recursive call");
    Map<String, String> closing = closing(lines);
    long callFree = fact(closing, "callFreeDepth", "the closing line");
    long starter = fact(closing, "starterUnits", "the closing line");

    assertThat(emitDepth).as("f's leaf-emit depth").isPositive();
    assertThat(outside).as("the outside call's units against f's emitDepth %d", emitDepth).isGreaterThanOrEqualTo(emitDepth);
    assertThat(recursive).as("the recursive call's units against f's emitDepth %d", emitDepth).isGreaterThanOrEqualTo(emitDepth);
    assertThat(starter).as("T").isPositive();
    assertThat(callFree).as("D_f against 1 + T, T = %d", starter).isGreaterThanOrEqualTo(1 + starter);
    assertThat(fact(closing, "bytesPerUnit", "the closing line")).as("b").isPositive();
    assertThat(fact(closing, "reserve", "the closing line")).as("the reserve, the release constant (ADR-0046 #113, K6)").isEqualTo(StackUnits.reserve());

    List<Line> param = conservativeLines("def r(f): if . > 3 then . else f | r(f) end; 0 | r(. + 1)");
    Map<String, String> paramClosing = closing(param);
    long paramStarter = fact(paramClosing, "starterUnits", "the ParamCall filter's closing line");
    assertThat(nodes(param, function(param, "r/1")).stream().filter(l -> l.fields().get(2).equals("ParamCall")).toList())
        .as("r's ParamCalls")
        .isNotEmpty()
        .allSatisfy(l -> assertThat(Long.parseLong(l.facts().getOrDefault("units", "-1"))).as("a ParamCall's units").isPositive());
    assertThat(fact(paramClosing, "callFreeDepth", "the ParamCall filter's closing line")).as("D_f of a body with a ParamCall against 2 + T, T = %d", paramStarter).isGreaterThanOrEqualTo(2 + paramStarter);
  }

  /**
   * The rise: frames a body nests above a call's results up to its own emit. Three Trys on the consumer side of the
   * recursive call's Pipe leave its descent as it is and add at least three frames to its rise, and so to its units;
   * the function's leaf-emit depth (the else branch) is the same in both.
   */
  @Test
  void unitsCountTheRise() {
    List<Line> plain = conservativeLines("def f: if . > 0 then (. - 1 | f) | . else . end; f");
    List<Line> risen = conservativeLines("def f: if . > 0 then (. - 1 | f) | try (try (try .a)) else . end; f");

    long plainUnits = fact(recursiveCall(plain, function(plain, "f/0")), "units", "the recursive call under no Try");
    long risenUnits = fact(recursiveCall(risen, function(risen, "f/0")), "units", "the recursive call under three Trys");
    assertThat(fact(functionFacts(risen, "f/0"), "emitDepth", "f with the Trys"))
        .as("the leaf-emit depth, the else branch in both")
        .isEqualTo(fact(functionFacts(plain, "f/0"), "emitDepth", "f without"));
    assertThat(risenUnits - plainUnits).as("the units three Trys add to the rise (%d against %d)", risenUnits, plainUnits).isGreaterThanOrEqualTo(3);
  }

  /**
   * The callee's leaf-emit depth (C1, the adviser's witness): g emits from under a hundred Trys, which stay live under
   * every caller's rise. Each Try is at least one frame, so g's {@code emitDepth} is at least 100 more than with none,
   * and the call of g at the same site is charged exactly that difference, its descent and rise being the same. A
   * closure argument's root line shows its own {@code emitDepth}.
   */
  @Test
  void unitsCountTheEmitDepth() {
    String shape = "def g: if . == 0 then %s else (. - 1 | g) end; def f: if . == 0 then 0 else (. - 1 | f) | (1 | g) end; 9999 | f";
    List<Line> shallow = conservativeLines(shape.formatted("."));
    List<Line> deep = conservativeLines(shape.formatted("try(".repeat(100) + "." + ")".repeat(100)));

    long shallowEmit = fact(functionFacts(shallow, "g/0"), "emitDepth", "g without Trys");
    long deepEmit = fact(functionFacts(deep, "g/0"), "emitDepth", "g under 100 Trys");
    assertThat(deepEmit - shallowEmit).as("the emitDepth of 100 Trys (%d against %d)", deepEmit, shallowEmit).isGreaterThanOrEqualTo(100);
    long shallowCall = fact(plainCallOf(shallow, "f/0", "g/0"), "units", "f's call of g without Trys");
    long deepCall = fact(plainCallOf(deep, "f/0", "g/0"), "units", "f's call of g under 100 Trys");
    assertThat(deepCall - shallowCall).as("the units of f's call of g, against the emitDepth difference").isEqualTo(deepEmit - shallowEmit);

    // A closure argument carries its leaf-emit depth: main's Call of r, then its argument's root, the Try.
    List<Line> closure = conservativeLines("def r(f): if . > 3 then . else f | r(f) end; 0 | r(try (try (try .a)))");
    List<Line> main = nodes(closure, "main");
    int call = main.stream().map(l -> l.fields().get(2)).toList().indexOf("Call");
    assertThat(main.get(call + 1).fields().get(2)).as("the root of r's closure argument").isEqualTo("Try");
    assertThat(fact(main.get(call + 1).facts(), "emitDepth", "the closure argument's root line")).as("the closure argument's leaf-emit depth under three Trys").isGreaterThanOrEqualTo(3);
  }

  /** The facts of the one PLAIN or RECURSIVE Call of {@code caller} whose callee is {@code callee}. */
  private static Map<String, String> plainCallOf(List<Line> lines, String caller, String callee) {
    String from = function(lines, caller);
    String to = function(lines, callee);
    List<Line> found = nodes(lines, from).stream().filter(l -> l.fields().get(2).equals("Call") && to.equals(l.facts().get("callee"))).toList();
    assertThat(found).as("the Call of %s in %s", callee, caller).hasSize(1);
    return found.getFirst().facts();
  }

  static Stream<Arguments> kinds() {
    return Stream.of(
        Arguments.argumentSet("Identity", "Identity", "."),
        Arguments.argumentSet("Const", "Const", "1"),
        Arguments.argumentSet("Pipe", "Pipe", ".[] | .[]"),
        Arguments.argumentSet("Comma", "Comma", "1, 2"),
        Arguments.argumentSet("Bind", "Bind", ".[] as $x | $x"),
        // Past Resolver.BIND_RUN operands, an operator run is one Binds.
        Arguments.argumentSet("Binds", "Binds", String.join(" + ", Collections.nCopies(70, ".a"))),
        Arguments.argumentSet("Var", "Var", ". as $x | $x"),
        Arguments.argumentSet("Intrinsic", "Intrinsic", ".a"),
        Arguments.argumentSet("Collect", "Collect", "[.[]]"),
        Arguments.argumentSet("If", "If", "if .a then 1 else 2 end"),
        Arguments.argumentSet("Alt", "Alt", ".a // 1"),
        Arguments.argumentSet("AltBind", "AltBind", ". as [$a] ?// $a | $a"),
        Arguments.argumentSet("Try", "Try", "try .a catch ."),
        Arguments.argumentSet("Label", "Label", "label $out | .a"),
        Arguments.argumentSet("Break", "Break", "label $out | break $out"),
        Arguments.argumentSet("Reduce", "Reduce", "reduce .[] as $x (0; . + $x)"),
        Arguments.argumentSet("Foreach", "Foreach", "foreach .[] as $x (0; . + $x)"),
        Arguments.argumentSet("Advance", "Advance", "reduce .[] as $x (0; . + $x)"),
        Arguments.argumentSet("Call", "Call", RECURSIVE_F),
        Arguments.argumentSet("ParamCall", "ParamCall", "def r(f): if . > 3 then . else f | r(f) end; 0 | r(. + 1)"));
  }

  /** Every node kind is printed by its name, inside every part of the nodes that hold it. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("kinds")
  void everyKindIsListed(String kind, String filter) {
    assertThat(lines(filter)).extracting(l -> l.fields().get(2)).as("kinds of %s", filter).contains(kind);
  }
}
