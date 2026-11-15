package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
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

  /**
   * One line of the text: its fields before the first {@code name=value} one, and its facts by name. SpecialiseExplainTest
   * (#117) reads the text through it too.
   */
  record Line(List<String> fields, Map<String, String> facts) {
    static Line of(String line) {
      List<String> fields = new ArrayList<>();
      Map<String, String> facts = new LinkedHashMap<>();
      for (String field : line.split(" ")) {
        int eq = field.indexOf('=');
        if (eq < 0) {
          fields.add(field);
        } else {
          facts.put(field.substring(0, eq), field.substring(eq + 1));
        }
      }
      return new Line(fields, facts);
    }

    boolean isFunction() {
      return fields.getFirst().equals("function");
    }
  }

  private List<Line> lines(String filter) {
    String text = engine.compile(filter).explain();
    assertThat(text).as("explain of %s", filter).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
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

  /** The span {@code s..s+1} of the one-byte call at {@code offset} into the first {@code context} in {@code filter}. */
  private static String at(String filter, String context, int offset) {
    int s = filter.indexOf(context);
    assertThat(s).as("%s in %s", context, filter).isNotNegative();
    return (s + offset) + ".." + (s + offset + 1);
  }

  /** The ticket's case: main and f are listed, and each Call shows its kind and its callee. */
  @Test
  void explainListsTheProgram() {
    List<Line> lines = lines(RECURSIVE_F);

    String f = function(lines, "f/0");

    assertThat(nodes(lines, "main")).as("main's nodes").isNotEmpty();
    assertThat(nodes(lines, f)).as("f's nodes").isNotEmpty();
    assertThat(node(lines, "main", "Call", "43..44")).as("the outside call").containsEntry("call", "PLAIN").containsEntry("callee", f);
    assertThat(node(lines, f, "Call", "29..30")).as("the recursive call").containsEntry("call", "RECURSIVE").containsEntry("callee", f);
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
   * a and b, written inside a, reach each other, so they are one group and their calls of each other are RECURSIVE; the
   * calls of g, a group of its own, from a and b are PLAIN, and g's call of itself RECURSIVE. b calls g before a does,
   * so a's call meets a function whose group is already found.
   */
  @Test
  void aCallIntoTheCallersGroupIsRecursiveAndAnyOtherPlain() {
    String filter = "def g: if . > 9 then . else . + 1 | g end; def a: def b: g | a; if . > 0 then . - 1 | b else g end; a";
    List<Line> lines = lines(filter);
    String g = function(lines, "g/0");
    String a = function(lines, "a/0");
    String b = function(lines, "b/0");

    assertThat(node(lines, g, "Call", at(filter, "| g end", 2))).as("g in g").containsEntry("call", "RECURSIVE").containsEntry("callee", g);
    assertThat(node(lines, a, "Call", at(filter, "| b else", 2))).as("b in a").containsEntry("call", "RECURSIVE").containsEntry("callee", b);
    assertThat(node(lines, a, "Call", at(filter, "else g end", 5))).as("g in a").containsEntry("call", "PLAIN").containsEntry("callee", g);
    assertThat(node(lines, b, "Call", at(filter, "def b: g | a", 7))).as("g in b").containsEntry("call", "PLAIN").containsEntry("callee", g);
    assertThat(node(lines, b, "Call", at(filter, "def b: g | a", 11))).as("a in b").containsEntry("call", "RECURSIVE").containsEntry("callee", a);
    assertThat(node(lines, "main", "Call", at(filter, "; a", 2))).as("a in main").containsEntry("call", "PLAIN").containsEntry("callee", a);
  }

  /**
   * a calls b, then c, which calls b too: c's call meets b's group already found, which joins neither a's group nor
   * c's, so a's call of c stays PLAIN.
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
    assertThat(node(lines, c, "Call", at(filter, "| c end", 2))).as("c in c").containsEntry("call", "RECURSIVE").containsEntry("callee", c);
  }

  /** a calls b, b calls c and c calls a: one group of three, though no call goes straight back to a's caller. */
  @Test
  void aCycleOfThreeIsOneGroup() {
    String filter = "def a: def b: def c: a; c; if . > 0 then . - 1 | b else . end; a";
    List<Line> lines = lines(filter);
    String a = function(lines, "a/0");
    String b = function(lines, "b/0");
    String c = function(lines, "c/0");

    assertThat(node(lines, a, "Call", at(filter, "| b else", 2))).as("b in a").containsEntry("call", "RECURSIVE").containsEntry("callee", b);
    assertThat(node(lines, b, "Call", at(filter, "a; c;", 3))).as("c in b").containsEntry("call", "RECURSIVE").containsEntry("callee", c);
    assertThat(node(lines, c, "Call", at(filter, "c: a;", 3))).as("a in c").containsEntry("call", "RECURSIVE").containsEntry("callee", a);
  }

  /**
   * A ParamCall shows its parameter, both where the body runs it and where the recursive call passes it on; the
   * function line shows the closure and value parameters.
   */
  @Test
  void aParamCallShowsItsParameter() {
    List<Line> lines = lines("def r(f; $n): if . > $n then . else f | r(f; $n) end; 0 | r(. + 1; 3)");
    String r = function(lines, "r/2");

    List<Line> calls = nodes(lines, r).stream().filter(l -> l.fields().get(2).equals("ParamCall")).toList();
    assertThat(calls).as("ParamCalls in r").hasSize(2).allSatisfy(l -> assertThat(l.facts()).containsEntry("param", "0"));
    Line header = lines.stream().filter(l -> l.isFunction() && l.fields().get(1).equals(r)).findFirst().orElseThrow();
    assertThat(header.facts()).as("r's parameters").containsEntry("closures", "1").containsEntry("values", "1");
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
