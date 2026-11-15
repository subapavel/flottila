package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The may-break-out facts at the explain seam (#115, ADR-0007 "Amendment (ticket #115)", GLOSSARY "May break out"):
 * {@link JqFilter#explain()} shows each node's fact as {@code breaks=<true|false>}. A case names its node by its
 * function and its freeze id, and first checks the node's kind at that id, so a renumbering cannot move a case onto
 * another node: the ids and kinds were read from the explain text at 05f042d.
 *
 * <p>The expected values are the design's (issue #115, the architect-approved design comment): a node may break out
 * when its lowest break target is below its own freeze id; a Break's target is the Label that binds it in its body, or
 * -1 for a token passed in as a value; a ParamCall's is -1; a Call's is -1 when its callee may break out. A Label whose
 * body breaks only to it through a Call (main's Label and Collect in the recursion cases) is left unpinned, so a later
 * refinement needs no test change. The optimised compile is the default.
 */
class MayBreakOutFactsTest {

  /** One line of the text: its fields before the first {@code name=value} one, and its facts by name. */
  private record Line(List<String> fields, Map<String, String> facts) {
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

    /** A node line: its first field is its body's column, {@code main} or {@code fn<place>}; the stack units line is not. */
    boolean isNode() {
      return fields.getFirst().matches("main|fn[0-9]+");
    }
  }

  private static List<Line> lines(JqEngine engine, String filter) {
    String text = engine.compile(filter).explain();
    assertThat(text).as("explain of %s", filter).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
  }

  private static List<Line> nodeLines(JqEngine engine, String filter) {
    return lines(engine, filter).stream().filter(Line::isNode).toList();
  }

  /** The column of the body {@code body}: {@code main}, or the function line's {@code fn<place>} of a jq name/arity. */
  private static String column(List<Line> lines, String body) {
    if (body.equals("main")) {
      return "main";
    }
    List<Line> found = lines.stream().filter(l -> l.isFunction() && l.fields().get(2).equals(body)).toList();
    assertThat(found).as("function %s in %s", body, lines).hasSize(1);
    return found.getFirst().fields().get(1);
  }

  /** The one node line of {@code body} with the freeze id {@code id}. */
  private static Line node(List<Line> lines, String body, int id) {
    String column = column(lines, body);
    List<Line> found =
        lines.stream().filter(l -> !l.isFunction() && l.fields().get(0).equals(column) && l.fields().get(1).equals(Integer.toString(id))).toList();
    assertThat(found).as("node %d of %s in %s", id, body, lines).hasSize(1);
    return found.getFirst();
  }

  private static void assertBreaks(String filter, String body, int id, String kind, boolean breaks) {
    Line node = node(lines(JqEngine.builder().build(), filter), body, id);

    assertThat(node.fields().get(2)).as("kind of node %d of %s in %s", id, body, filter).isEqualTo(kind);
    assertThat(node.facts()).as("facts of the %s at %d of %s in %s", kind, id, body, filter).containsEntry("breaks", Boolean.toString(breaks));
  }

  private static final String RECURSIVE_AT_MOST_ONE = "def f: if .>3 then . else .+1|f end; f";

  private static final String EV_OD = "def ev: def od: if . == 0 then false else . - 1 | ev end; if . == 0 then true else . - 1 | od end; ev";

  /** #112's case: r runs its closure parameter, so r and every Call of it may break out, whatever closure it passes. */
  private static final String PARAM_CALL = "def r(f): if .>3 then f else .+1|r(f|.) end; r(1)";

  /** The closure's Label catches its own Break; the Call still may break out, since r runs a ParamCall. */
  private static final String CLOSURE_LABEL = "def r(f): if .>3 then f else .+1|r(f|.) end; r(label $m|break $m)";

  /** calls/break-out-of-a-recursion: f breaks to main's label through the token it captures as a value. */
  private static final String OUT_OF_A_RECURSION = "[label $l | def f: if . > 2 then break $l else (. + 1 | f) + 1 end; 5, (0 | f), 6]";

  /** X1 (calls/break-through-a-mutual-recursion): od breaks, ev only calls od. ev is fn0, od fn1. */
  static final String X1 = "[label $l | def ev: def od: if . == 0 then break $l else . - 1 | ev end; if . == 0 then true else . - 1 | od end; (5 | ev), 7]";

  /**
   * X1 with the Break moved into ev, fn0: the call groups close od before ev, so od, read first, sees ev still false in
   * the group's first round and needs its second.
   */
  static final String X1_BREAK_IN_EV = "[label $l | def ev: def od: if . == 0 then true else . - 1 | ev end; if . == 0 then break $l else . - 1 | od end; (4 | ev), 7]";

  /** X2 (calls/break-through-another-group): g breaks, f calls g from another group. f is fn0, g fn1. */
  static final String X2 = "[label $l | def g: if . > 3 then break $l else . + 1 | g end; def f: if . > 1 then g else . + 1 | f end; (0 | f), 7]";

  /** X2 with {@code break $l} replaced by {@code .}: nothing breaks. */
  static final String X2_NO_BREAK = "[label $l | def g: if . > 3 then . else . + 1 | g end; def f: if . > 1 then g else . + 1 | f end; (0 | f), 7]";

  /** Each case: its filter, its body ({@code main} or a function's name/arity), the freeze id, the kind there, the fact. */
  static Stream<Arguments> cases() {
    return Stream.of(
        // false: a leaf, a Label that catches its own Break, a Comma after it, a recursion with no Break, a closure Label.
        Arguments.argumentSet("false: .", ".", "main", 0, "Identity", false),
        Arguments.argumentSet("false: the Label of label $l|break $l", "label $l|break $l", "main", 0, "Label", false),
        Arguments.argumentSet("false: label $l|1", "label $l|1", "main", 0, "Label", false),
        Arguments.argumentSet("false: the Comma of (label $l|break $l),1", "(label $l|break $l),1", "main", 0, "Comma", false),
        Arguments.argumentSet("false: the Call of a recursive f", RECURSIVE_AT_MOST_ONE, "main", 0, "Call", false),
        Arguments.argumentSet("false: the root of a recursive f", RECURSIVE_AT_MOST_ONE, "f/0", 0, "If", false),
        Arguments.argumentSet("false: ev of the ev/od group", EV_OD, "ev/0", 0, "If", false),
        Arguments.argumentSet("false: od of the ev/od group", EV_OD, "od/0", 0, "If", false),
        Arguments.argumentSet("false: the closure's Label in r(label $m|break $m)", CLOSURE_LABEL, "main", 1, "Label", false),
        Arguments.argumentSet("false: the outer Label of label $a|label $b|break $a", "label $a|label $b|break $a", "main", 0, "Label", false),
        // true: a Break, the nodes between it and its Label, a Try (no catch set holds a break), a ParamCall and the
        // calls of a function that runs one, a Break to a captured token and every node and Call above it.
        Arguments.argumentSet("true: the Break of label $l|break $l", "label $l|break $l", "main", 1, "Break", true),
        Arguments.argumentSet("true: the Comma of label $l|(1, break $l)", "label $l|(1, break $l)", "main", 1, "Comma", true),
        Arguments.argumentSet("true: the inner Label of label $a|label $b|break $a", "label $a|label $b|break $a", "main", 1, "Label", true),
        Arguments.argumentSet("true: the Try of label $l|try (break $l) catch 1", "label $l|try (break $l) catch 1", "main", 1, "Try", true),
        Arguments.argumentSet("true: the ParamCall of r(f)", PARAM_CALL, "r/1", 4, "ParamCall", true),
        Arguments.argumentSet("true: the recursive Call of r(f)", PARAM_CALL, "r/1", 9, "Call", true),
        Arguments.argumentSet("true: the root of r(f)", PARAM_CALL, "r/1", 0, "If", true),
        Arguments.argumentSet("true: the outside Call of r(f)", PARAM_CALL, "main", 0, "Call", true),
        Arguments.argumentSet("true: f's Break to the captured label", OUT_OF_A_RECURSION, "f/0", 4, "Break", true),
        Arguments.argumentSet("true: f's root", OUT_OF_A_RECURSION, "f/0", 0, "If", true),
        Arguments.argumentSet("true: f's recursive Call", OUT_OF_A_RECURSION, "f/0", 10, "Call", true),
        Arguments.argumentSet("true: main's Call of f", OUT_OF_A_RECURSION, "main", 7, "Call", true),
        Arguments.argumentSet("true: the closure's Break in r(label $m|break $m)", CLOSURE_LABEL, "main", 2, "Break", true),
        Arguments.argumentSet("true: main's Call in r(label $m|break $m)", CLOSURE_LABEL, "main", 0, "Call", true));
  }

  /** The node at the case's id has the case's kind and shows the case's may-break-out fact. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void mayBreakOutFacts(String filter, String body, int id, String kind, boolean breaks) {
    assertBreaks(filter, body, id, kind, breaks);
  }

  static Stream<Arguments> groups() {
    return Stream.of(
        // X1: the fact reaches ev only through the group's shared value.
        Arguments.argumentSet("X1: ev's root", X1, "ev/0", 0, "If", true),
        Arguments.argumentSet("X1: od's root", X1, "od/0", 0, "If", true),
        Arguments.argumentSet("X1: od's Break", X1, "od/0", 4, "Break", true),
        Arguments.argumentSet("X1: ev's Call of od", X1, "ev/0", 9, "Call", true),
        Arguments.argumentSet("X1: main's Call of ev", X1, "main", 5, "Call", true),
        // X1 with the Break in ev: od is read first and is true only after the group's second round.
        Arguments.argumentSet("X1, the Break in ev: ev's root", X1_BREAK_IN_EV, "ev/0", 0, "If", true),
        Arguments.argumentSet("X1, the Break in ev: od's root", X1_BREAK_IN_EV, "od/0", 0, "If", true),
        // X2: f's own group has no Break; its Call into g's group brings it.
        Arguments.argumentSet("X2: f's root", X2, "f/0", 0, "If", true),
        Arguments.argumentSet("X2: f's Call of g", X2, "f/0", 4, "Call", true),
        Arguments.argumentSet("X2: f's recursive Call", X2, "f/0", 10, "Call", true),
        Arguments.argumentSet("X2: g's root", X2, "g/0", 0, "If", true),
        Arguments.argumentSet("X2: main's Call of f", X2, "main", 5, "Call", true),
        // A part of f that holds no Call stays false: the true is not given to a whole body.
        Arguments.argumentSet("X2: f's condition", X2, "f/0", 1, "Intrinsic", false));
  }

  /** The group fixpoint: a fact reached through a recursive group, and through a Call into another group. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("groups")
  void groupFixpoint(String filter, String body, int id, String kind, boolean breaks) {
    assertBreaks(filter, body, id, kind, breaks);
  }

  /** X2 with its Break replaced by {@code .}: every node of main, f and g is false, the unused Label among them. */
  @Test
  void groupFixpointWithNoBreakIsFalseEverywhere() {
    List<Line> lines = nodeLines(JqEngine.builder().build(), X2_NO_BREAK);

    assertThat(lines).as("node lines of %s", X2_NO_BREAK).hasSizeGreaterThan(20).allSatisfy(l -> assertThat(l.facts()).as("facts of %s", l.fields()).containsEntry("breaks", "false"));
  }

  static Stream<Arguments> conservative() {
    return Stream.of(
        Arguments.argumentSet(".", "."),
        Arguments.argumentSet("a Label that only breaks", "label $l|break $l"),
        Arguments.argumentSet("a recursive f", RECURSIVE_AT_MOST_ONE),
        Arguments.argumentSet("X2", X2));
  }

  /**
   * CONSERVATIVE keeps every fact at its safe value (ADR-0018): true on every node line of main and of each function.
   * Witness: the optimised compile of the same filter shows the same node lines and some false, so the true comes from
   * the mode.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("conservative")
  void conservativeBreaksIsTrue(String filter) {
    List<Line> conservative = nodeLines(JqEngine.builder().compileMode(CompileMode.CONSERVATIVE).build(), filter);
    List<Line> optimised = nodeLines(JqEngine.builder().build(), filter);

    assertThat(conservative).as("node lines of %s under CONSERVATIVE", filter).isNotEmpty().allSatisfy(l -> assertThat(l.facts()).as("facts of %s", l.fields()).containsEntry("breaks", "true"));
    assertThat(conservative).extracting(Line::fields).as("node lines of %s under both modes", filter).isEqualTo(optimised.stream().map(Line::fields).toList());
    assertThat(optimised).extracting(l -> l.facts().get("breaks")).as("breaks of %s under OPTIMISED", filter).contains("false");
  }
}
