package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The cardinality facts at the explain seam (#112, ADR-0007 "Amendment (ticket #112)", F-56, GLOSSARY "Cardinality",
 * "Freeze id"): {@link JqFilter#explain()} shows each node's fact as {@code card=<NONE|ONE|AT_MOST_ONE|MANY>}. A case
 * names its node by its function and its freeze id, and first checks the node's kind at that id, so a renumbering
 * cannot move a case onto another node: the ids and kinds were read from #108's explain text at 9faa3bf.
 *
 * <p>The expected values are the design's (issue #112, the architect-approved design comment), one case per
 * construct: "one" and "at most one" also promise that nothing runs after the result, so a comma after a result, a try
 * of a body with several results, an alternative binding and a closure parameter are "many"; the bottom NEVER (a Break,
 * a body that only recurses) is stored and shown as NONE. The optimised compile is the default.
 */
class CardinalityFactsTest {

  /** Past {@code Resolver.BIND_RUN} (64) value parameters, the Resolver binds their arguments in one Binds (ADR-0043). */
  private static final int PAST_BIND_RUN = 65;

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

    /**
     * A node line: its first field is its body's column, {@code main} or {@code fn<place>}; neither a function line nor
     * #111's closing {@code stack} line.
     */
    boolean isNode() {
      return fields.getFirst().matches("main|fn[0-9]+");
    }
  }

  private static List<Line> lines(JqEngine engine, String filter) {
    String text = engine.compile(filter).explain();
    assertThat(text).as("explain of %s", filter).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
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

  /** The text of a recursive definition of {@code n} value parameters, and a call of it on 1 for each (LiftedFunctionsTest). */
  private static String valueParameters(int n) {
    StringJoiner params = new StringJoiner("; ");
    StringJoiner passed = new StringJoiner("; ");
    StringJoiner ones = new StringJoiner("; ");
    for (int i = 0; i < n; i++) {
      params.add("$a" + i);
      passed.add(i == 0 ? "$a0 - 1" : "$a" + i);
      ones.add("1");
    }
    return "def r(" + params + "): if $a0 == 0 then 0 else r(" + passed + ") end; r(" + ones + ")";
  }

  private static final String RECURSIVE_AT_MOST_ONE = "def f: if .>3 then . else .+1|f end; f";

  private static final String EV_OD = "def ev: def od: if . == 0 then false else . - 1 | ev end; if . == 0 then true else . - 1 | od end; ev";

  /** F-56's third probe: the recursive call runs after a comma, so f may run on after its result. */
  private static final String F56 = "def f: if .>3 then . else ((.+1), error(\"x\"))|f end; 0|f";

  private static final String PARAM_CALL = "def r(f): if .>3 then f else .+1|r(f|.) end; r(1)";

  private static final String ONLY_RECURSES = "def f: f; f";

  private static final String REDUCE = "reduce .[] as $x (0;.+$x)";

  /** Each case: its filter, its body ({@code main} or a function's name/arity), the freeze id, the kind there, the fact. */
  static Stream<Arguments> cases() {
    return Stream.of(
        // ONE: a leaf, a Bind and an If of ONE parts, a Collect, an Alt whose right side is ONE, a Comma after NONE.
        Arguments.argumentSet("ONE: .", ".", "main", 0, "Identity", "ONE"),
        Arguments.argumentSet("ONE: 1", "1", "main", 0, "Const", "ONE"),
        Arguments.argumentSet("ONE: . as $x|$x", ". as $x|$x", "main", 0, "Bind", "ONE"),
        Arguments.argumentSet("ONE: [.[]]", "[.[]]", "main", 0, "Collect", "ONE"),
        Arguments.argumentSet("ONE: if . then 1 else 2 end", "if . then 1 else 2 end", "main", 0, "If", "ONE"),
        Arguments.argumentSet("ONE: .a//1", ".a//1", "main", 0, "Alt", "ONE"),
        Arguments.argumentSet("ONE: the Comma of (label $l|break $l),1", "(label $l|break $l),1", "main", 0, "Comma", "ONE"),
        // AT_MOST_ONE: an operation, a Pipe of two, a Try of an at-most-one body, a Label of a result, a recursive group.
        Arguments.argumentSet("AT_MOST_ONE: .a", ".a", "main", 0, "Intrinsic", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: .a|.b", ".a|.b", "main", 0, "Pipe", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: try .a catch 1", "try .a catch 1", "main", 0, "Try", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: label $l|1", "label $l|1", "main", 0, "Label", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: the Call of a recursive f", RECURSIVE_AT_MOST_ONE, "main", 0, "Call", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: the root of a recursive f", RECURSIVE_AT_MOST_ONE, "f/0", 0, "If", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: ev of the ev/od group", EV_OD, "ev/0", 0, "If", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: od of the ev/od group", EV_OD, "od/0", 0, "If", "AT_MOST_ONE"),
        Arguments.argumentSet("AT_MOST_ONE: the root Binds of 65 value parameters", valueParameters(PAST_BIND_RUN), "main", 0, "Binds", "AT_MOST_ONE"),
        // MANY: an iteration, a Comma after a result, a Try of a MANY body, an AltBind, a Foreach, a recursion after a
        // comma, a ParamCall and the calls of a function that runs one.
        Arguments.argumentSet("MANY: .[]", ".[]", "main", 0, "Intrinsic", "MANY"),
        Arguments.argumentSet("MANY: 1,2", "1,2", "main", 0, "Comma", "MANY"),
        Arguments.argumentSet("MANY: 1,error(\"x\")", "1,error(\"x\")", "main", 0, "Comma", "MANY"),
        Arguments.argumentSet("MANY: try (1,2) catch 3", "try (1,2) catch 3", "main", 0, "Try", "MANY"),
        Arguments.argumentSet("MANY: . as [$a] ?// $a|$a", ". as [$a] ?// $a|$a", "main", 0, "AltBind", "MANY"),
        Arguments.argumentSet("MANY: foreach .[] as $x (0;.+$x)", "foreach .[] as $x (0;.+$x)", "main", 0, "Foreach", "MANY"),
        Arguments.argumentSet("MANY: def f: 1, f; f", "def f: 1, f; f", "main", 0, "Call", "MANY"),
        Arguments.argumentSet("MANY: f of F-56's probe", F56, "f/0", 0, "If", "MANY"),
        Arguments.argumentSet("MANY: the ParamCall of r(f)", PARAM_CALL, "r/1", 4, "ParamCall", "MANY"),
        Arguments.argumentSet("MANY: the recursive Call of r(f)", PARAM_CALL, "r/1", 9, "Call", "MANY"),
        Arguments.argumentSet("MANY: the outside Call of r(f)", PARAM_CALL, "main", 0, "Call", "MANY"),
        // NONE: a Label whose body only breaks, the Break, and a function that only recurses (NEVER, shown as NONE).
        Arguments.argumentSet("NONE: the Label of label $l|break $l", "label $l|break $l", "main", 0, "Label", "NONE"),
        Arguments.argumentSet("NONE: the Break of label $l|break $l", "label $l|break $l", "main", 1, "Break", "NONE"),
        Arguments.argumentSet("NONE: the Call of def f: f", ONLY_RECURSES, "main", 0, "Call", "NONE"),
        Arguments.argumentSet("NONE: the recursive Call in def f: f", ONLY_RECURSES, "f/0", 0, "Call", "NONE"),
        // Mixed: the Reduce is seq(init, ONE), its Advance its update's.
        Arguments.argumentSet("ONE: the Reduce of reduce .[] as $x (0;.+$x)", REDUCE, "main", 1, "Reduce", "ONE"),
        Arguments.argumentSet("AT_MOST_ONE: the Advance of reduce .[] as $x (0;.+$x)", REDUCE, "main", 5, "Advance", "AT_MOST_ONE"));
  }

  /** The node at the case's id has the case's kind and shows the case's cardinality. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("cases")
  void cardinalityFacts(String filter, String body, int id, String kind, String card) {
    List<Line> lines = lines(JqEngine.builder().build(), filter);
    Line node = node(lines, body, id);

    assertThat(node.fields().get(2)).as("kind of node %d of %s in %s", id, body, filter).isEqualTo(kind);
    assertThat(node.facts()).as("facts of the %s at %d of %s in %s", kind, id, body, filter).containsEntry("card", card);
  }

  static Stream<Arguments> conservative() {
    return Stream.of(
        Arguments.argumentSet(".", "."),
        Arguments.argumentSet("a reduce", REDUCE),
        Arguments.argumentSet("a recursive f", RECURSIVE_AT_MOST_ONE),
        Arguments.argumentSet("a Label that only breaks", "label $l|break $l"),
        Arguments.argumentSet("a function that only recurses", ONLY_RECURSES));
  }

  /**
   * CONSERVATIVE keeps every fact at its safe value (ADR-0018, ADR-0030): MANY on every node line of main and of each
   * function. Witness: the optimised compile of the same filter shows the same node lines and some fact other than MANY,
   * so the MANY comes from the mode.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("conservative")
  void conservativeCardIsMany(String filter) {
    List<Line> conservative = lines(JqEngine.builder().compileMode(CompileMode.CONSERVATIVE).build(), filter).stream().filter(Line::isNode).toList();
    List<Line> optimised = lines(JqEngine.builder().build(), filter).stream().filter(Line::isNode).toList();

    assertThat(conservative).as("node lines of %s under CONSERVATIVE", filter).isNotEmpty().allSatisfy(l -> assertThat(l.facts()).as("facts of %s", l.fields()).containsEntry("card", "MANY"));
    assertThat(conservative).extracting(Line::fields).as("node lines of %s under both modes", filter).isEqualTo(optimised.stream().map(Line::fields).toList());
    assertThat(optimised).extracting(l -> l.facts().get("card")).as("cards of %s under OPTIMISED", filter).isNotEmpty().anyMatch(c -> c != null && !c.equals("MANY"));
  }
}
