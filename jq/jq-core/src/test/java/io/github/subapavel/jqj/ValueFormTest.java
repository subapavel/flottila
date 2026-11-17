package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.ExplainLines.at;
import static io.github.subapavel.jqj.ExplainLines.lines;
import static io.github.subapavel.jqj.ExplainLines.node;
import static io.github.subapavel.jqj.ExplainLines.nodeLines;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.internal.parse.Parser;
import io.github.subapavel.jqj.internal.parse.Resolver;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.compile.CompileMode;
import io.github.subapavel.jqj.query.compile.Compiler;
import io.github.subapavel.jqj.query.compile.Frozen;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Many;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The run form at the explain seam (#116, ADR-0047 "Amendment (ticket #116)", ADR-0008, GLOSSARY "Run form"): {@link
 * JqFilter#explain()} shows each node's run form as {@code form=<push|value>}, on every node line, an atomic argument's
 * included. In the optimised compile a node is value form exactly where its cardinality is at most one (NONE, ONE or
 * AT_MOST_ONE) and it may not break out; eligibility reads only the facts, and the conservative compile, whose facts are
 * MANY and may break out everywhere, never has a value-form node. In OPTIMISED the value form comes before the tail
 * emitter (C1), so every tail emitter is value form there.
 *
 * <p>The expected values are the design's (issue #116, the architect-approved design comment): the rule, and its pins
 * for F-56, X1, X2 and X2-dot. A pin names its node by its function and freeze id and first checks the node's kind there;
 * the ids and kinds were read from the explain text at dbae0f0 ({@code .logs/116-explain-baseline.log}). The facts the
 * rule reads are pinned by CardinalityFactsTest and MayBreakOutFactsTest. The optimised compile is the default.
 *
 * <p>Seam (the design's): the fact {@code form=push} or {@code form=value} on every node line of {@code explain()}.
 */
class ValueFormTest {

  private static final JqEngine OPTIMISED = JqEngine.builder().build();

  private static final JqEngine CONSERVATIVE = JqEngine.builder().compileMode(CompileMode.CONSERVATIVE).build();

  /** F-56's first probe: the Comma {@code (1, error("x"))}, main 12, runs on after its result. */
  private static final String F56_LIMIT = "[limit(1; (1, error(\"x\")) | .+1)]";

  /** F-56's second probe. */
  private static final String F56_FIRST = "first((1, (repeat(1)|empty)) as $x | $x)";

  /** F-56's third probe: f's root, an If, is many, its recursive call coming after a comma. */
  private static final String F56_RECURSION = "def f: if .>3 then . else ((.+1), error(\"x\"))|f end; 0|f";

  /** X1 (calls/break-through-a-mutual-recursion): od breaks to main's label, ev calls od; ev is fn0, od fn1. */
  private static final String X1 = "[label $l | def ev: def od: if . == 0 then break $l else . - 1 | ev end; if . == 0 then true else . - 1 | od end; (5 | ev), 7]";

  /** X2 (calls/break-through-another-group): g breaks to main's label, f calls g; f is fn0, g fn1. */
  private static final String X2 = "[label $l | def g: if . > 3 then break $l else . + 1 | g end; def f: if . > 1 then g else . + 1 | f end; (0 | f), 7]";

  /** X2-dot (value-form/x2-dot-calls-into-another-group-by-eval): X2 with its break replaced by {@code .}. */
  private static final String X2_DOT = "[label $l|def g: if .>3 then . else .+1|g end; def f: if .>1 then g else .+1|f end; (0|f),7]";

  private static final String RECURSIVE_AT_MOST_ONE = "def f: if .>3 then . else .+1|f end; f";

  private static final String EV_OD = "def ev: def od: if . == 0 then false else . - 1 | ev end; if . == 0 then true else . - 1 | od end; ev";

  private static final String PARAM_CALL = "def r(f): if .>3 then f else .+1|r(f|.) end; r(1)";

  private static final String CLOSURE_LABEL = "def r(f): if .>3 then f else .+1|r(f|.) end; r(label $m|break $m)";

  private static final String OUT_OF_A_RECURSION = "[label $l | def f: if . > 2 then break $l else (. + 1 | f) + 1 end; 5, (0 | f), 6]";

  /** The activation case of the design ({@code valueFormCallEndsItsActivationAtItsResult}). */
  private static final String ACTIVATIONS = "def f: if .==0 then 0 else (.-1|f)|(0|f) end; 3|f";

  /** A value-form recursion (value-form/a-value-form-recursion-3000-deep). */
  private static final String VALUE_RECURSION = "def f: if .==0 then 0 else (.-1|f) as $x|$x+1 end; 3000|f";

  private static final String REDUCE = "reduce .[] as $x (0; . + $x)";

  /** Past {@code Resolver.BIND_RUN} (64) operands, an operator run is one Binds (ADR-0043). */
  private static final String BINDS = String.join(" + ", Collections.nCopies(70, ".a"));

  /** The filters every property here runs over: each construct, the fact tests' cases and the design's cases. */
  static final List<String> CORPUS =
      List.of(
          ".",
          "1",
          ". as $x|$x",
          ".a|.b",
          "if .a then .b else 1 end",
          BINDS,
          ".a + .b",
          "\"x\\(.a)y\"",
          "{a: .b, c: 1}",
          "[.[] | .a + 1]",
          ".[] | if . then 1 else 2 end",
          "label $l | (.a | .b), break $l",
          "try (.a|.b) catch .",
          "try (1,2) catch 3",
          ".a//1",
          "(label $l|break $l),1",
          "label $l|break $l",
          "label $l|1",
          "label $a|label $b|break $a",
          ".[]",
          "1,2",
          ". as [$a] ?// $a|$a",
          "foreach .[] as $x (0;.+$x)",
          REDUCE,
          "[1] | length",
          "empty",
          "def f: f; f",
          "def f: 1, f; f",
          "def r(f): if . > 3 then f else f | r(f) end; 0 | r(. + 1)",
          RECURSIVE_AT_MOST_ONE,
          EV_OD,
          PARAM_CALL,
          CLOSURE_LABEL,
          OUT_OF_A_RECURSION,
          F56_LIMIT,
          F56_FIRST,
          F56_RECURSION,
          X1,
          X2,
          X2_DOT,
          ACTIVATIONS,
          VALUE_RECURSION,
          "[.[]|(.a|select(.>1)) as $x|[$x]]",
          "[.[]|try ((.a|tonumber) as $x|$x+1) catch \"bad\"]",
          "[.[]|try (try . catch \"inner\"|error(\"down\")) catch \"outer\"]",
          "[.[]|(label $f|if .>1 then break $f else .*10 end) as $x|$x]",
          "[(reduce 1 as $x (empty; .)) as $y|$y]",
          "[label $out|(1|.+1) as $x|$x, break $out]");

  static Stream<Arguments> corpus() {
    return CORPUS.stream().map(f -> Arguments.argumentSet(f.length() > 60 ? f.substring(0, 60) + "..." : f, f));
  }

  /** The form the design's rule gives a node line: value where card is not MANY and breaks is false, push elsewhere. */
  private static String byTheRule(Line line) {
    assertThat(line.facts()).as("facts of %s", line.fields()).containsKeys("card", "breaks");
    return !line.facts().get("card").equals("MANY") && line.facts().get("breaks").equals("false") ? "value" : "push";
  }

  /** In OPTIMISED every node line shows {@code form=value} exactly where its card is at most one and breaks is false. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("corpus")
  void valueFormOnlyWhereFactsAllow(String filter) {
    List<Line> lines = nodeLines(OPTIMISED, filter);

    assertThat(lines).as("node lines of %s", filter).isNotEmpty().allSatisfy(l -> assertThat(l.facts()).as("facts of %s in %s", l.fields(), filter).containsEntry("form", byTheRule(l)));
  }

  /**
   * Witness that the rule's property is not vacuous: over the corpus, value form is expected at card NONE, ONE and
   * AT_MOST_ONE, and push form at card MANY with breaks false, at card at most one with breaks true and at card MANY with
   * breaks true, so a rule that read only one fact, or took AT_MOST_ONE alone for "at most one", fails it.
   */
  @Test
  void theCorpusMeetsEveryCaseOfTheRule() {
    Set<String> met = new TreeSet<>();
    for (String filter : CORPUS) {
      for (Line l : nodeLines(OPTIMISED, filter)) {
        met.add(l.facts().get("card") + " breaks=" + l.facts().get("breaks"));
      }
    }

    assertThat(met)
        .as("card and breaks pairs over the corpus")
        .contains("NONE breaks=false", "ONE breaks=false", "AT_MOST_ONE breaks=false", "MANY breaks=false", "NONE breaks=true", "AT_MOST_ONE breaks=true", "MANY breaks=true");
  }

  /** Each pin: its filter, its body ({@code main} or a function's name/arity), the freeze id, the kind there, the form. */
  static Stream<Arguments> pins() {
    return Stream.of(
        // The design's pins.
        Arguments.argumentSet("push: F-56's (1, error(\"x\")) in limit", F56_LIMIT, "main", 12, "Comma", "push"),
        Arguments.argumentSet("push: f's root in F-56's recursion after a comma", F56_RECURSION, "f/0", 0, "If", "push"),
        Arguments.argumentSet("push: X1's ev root", X1, "ev/0", 0, "If", "push"),
        Arguments.argumentSet("push: X1's od root", X1, "od/0", 0, "If", "push"),
        Arguments.argumentSet("push: X2's f root", X2, "f/0", 0, "If", "push"),
        Arguments.argumentSet("value: X2-dot's f root", X2_DOT, "f/0", 0, "If", "value"),
        Arguments.argumentSet("value: X2-dot's g root", X2_DOT, "g/0", 0, "If", "value"),
        // The rule at each kind it excludes or admits.
        Arguments.argumentSet("value: X2-dot's Call of f from main", X2_DOT, "main", 5, "Call", "value"),
        Arguments.argumentSet("push: X2-dot's Label around a Comma", X2_DOT, "main", 1, "Label", "push"),
        Arguments.argumentSet("value: the Pipe of [1] | length", "[1] | length", "main", 0, "Pipe", "value"),
        Arguments.argumentSet("value: the Label of label $l|break $l", "label $l|break $l", "main", 0, "Label", "value"),
        Arguments.argumentSet("push: the Break of label $l|break $l", "label $l|break $l", "main", 1, "Break", "push"),
        Arguments.argumentSet("push: .[]", ".[]", "main", 0, "Intrinsic", "push"),
        Arguments.argumentSet("push: the ParamCall of r(f)", PARAM_CALL, "r/1", 4, "ParamCall", "push"),
        Arguments.argumentSet("push: the AltBind of . as [$a] ?// $a|$a", ". as [$a] ?// $a|$a", "main", 0, "AltBind", "push"));
  }

  /** The node at the pin's id has the pin's kind and shows the pin's form. */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("pins")
  void valueFormPins(String filter, String body, int id, String kind, String form) {
    Line node = node(lines(OPTIMISED, filter), body, id);

    assertThat(node.fields().get(2)).as("kind of node %d of %s in %s", id, body, filter).isEqualTo(kind);
    assertThat(node.facts()).as("facts of the %s at %d of %s in %s", kind, id, body, filter).containsEntry("form", form);
  }

  static Stream<Arguments> conservative() {
    return Stream.of(
        Arguments.argumentSet(".", "."),
        Arguments.argumentSet("[1] | length", "[1] | length"),
        Arguments.argumentSet("a Label that only breaks", "label $l|break $l"),
        Arguments.argumentSet("a reduce", REDUCE),
        Arguments.argumentSet("a recursive f", RECURSIVE_AT_MOST_ONE),
        Arguments.argumentSet("a value-form recursion", VALUE_RECURSION),
        Arguments.argumentSet("X2-dot", X2_DOT));
  }

  /**
   * CONSERVATIVE never runs the value form (ADR-0047 #116: its facts are MANY and may break out, so no node is eligible,
   * with no test of the mode): {@code form=push} on every node line of main and of each function. Witness: the optimised
   * compile of the same filter shows the same node lines and some {@code form=value}, so the push comes from the mode.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("conservative")
  void conservativeNeverRunsTheValueForm(String filter) {
    List<Line> conservative = nodeLines(CONSERVATIVE, filter);
    List<Line> optimised = nodeLines(OPTIMISED, filter);

    assertThat(conservative).as("node lines of %s under CONSERVATIVE", filter).isNotEmpty().allSatisfy(l -> assertThat(l.facts()).as("facts of %s", l.fields()).containsEntry("form", "push"));
    assertThat(conservative).extracting(Line::fields).as("node lines of %s under both modes", filter).isEqualTo(optimised.stream().map(Line::fields).toList());
    assertThat(optimised).extracting(l -> l.facts().get("form")).as("forms of %s under OPTIMISED", filter).contains("value");
  }

  /**
   * Whether {@code node} is a tail emitter by its kind alone (ADR-0043, GLOSSARY "Tail emitter"): an Identity, a Const, a
   * Var, an Intrinsic of a one-result call form, or a Pipe, If, Bind or Binds made of tail emitters.
   */
  private static boolean tailEmitter(Node node) {
    return switch (node) {
      case Identity _ -> true;
      case Const _ -> true;
      case Var _ -> true;
      case Intrinsic i -> !(i.op().form() instanceof Many);
      case Pipe p -> tailEmitter(p.left()) && tailEmitter(p.right());
      case If i -> tailEmitter(i.cond()) && tailEmitter(i.then()) && tailEmitter(i.otherwise());
      case Bind b -> tailEmitter(b.value()) && tailEmitter(b.body());
      case Binds b -> b.values().stream().allMatch(ValueFormTest::tailEmitter) && tailEmitter(b.body());
      // Every other kind is no tail emitter: this selects the nodes the test checks, and a node not selected is not
      // checked here (valueFormOnlyWhereFactsAllow checks every node line).
      default -> false;
    };
  }

  /**
   * In OPTIMISED the value form comes before the tail emitter (ADR-0047 #116, C1): every node that is a tail emitter by
   * its kind, in main and in each function of each filter of the corpus, a specialisation copy among them (ADR-0045
   * #117), shows {@code form=value}. The nodes are read from the frozen program of the optimised compile of the program
   * jq-core resolves ({@code Executable.frozen()}), by its freeze ids, so a copy's nodes are checked as its original's
   * are, and each line's kind is checked against the node's (#117 re-pin ruling V3). Witness: the checked nodes hold
   * every tail-emitter kind, and at least a hundred of them.
   */
  @Test
  void everyTailEmitterIsValueForm() {
    List<String> violations = new ArrayList<>();
    Set<String> kinds = new TreeSet<>();
    int checked = 0;
    for (String filter : CORPUS) {
      Frozen frozen = Compiler.compile(Resolver.resolve(Parser.parse(filter)), 0, CompileMode.OPTIMISED).frozen();
      Program program = frozen.program();
      Numbering numbering = frozen.numbering();
      List<Line> lines = lines(OPTIMISED, filter);
      for (int place = -1; place < program.functions().size(); place++) {
        String body = place < 0 ? "main" : "fn" + place;
        List<Node> nodes = numbering.nodes(place);
        for (int id = 0; id < nodes.size(); id++) {
          Node n = nodes.get(id);
          if (!tailEmitter(n)) {
            continue;
          }
          checked++;
          String kind = n.getClass().getSimpleName();
          kinds.add(kind);
          Line line = at(lines, body, id);
          if (!line.fields().get(2).equals(kind)) {
            violations.add(filter + ": " + body + " " + id + " is a " + kind + " in the IR and a " + line.fields().get(2) + " in explain");
          } else if (!"value".equals(line.facts().get("form"))) {
            violations.add(filter + ": the tail emitter " + body + " " + id + " " + kind + " shows form=" + line.facts().get("form") + ", facts " + line.facts());
          }
        }
      }
    }

    assertThat(kinds).as("tail-emitter kinds checked").containsExactlyInAnyOrder("Bind", "Binds", "Const", "Identity", "If", "Intrinsic", "Pipe", "Var");
    assertThat(checked).as("tail emitters checked").isGreaterThanOrEqualTo(100);
    assertThat(violations).as("tail emitters not in value form under OPTIMISED").isEmpty();
  }
}
