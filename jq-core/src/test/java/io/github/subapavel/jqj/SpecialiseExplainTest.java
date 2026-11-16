package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.ExplainLines.at;
import static io.github.subapavel.jqj.Inlining.shown;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.ExplainLines.Line;
import io.github.subapavel.jqj.query.compile.CompileMode;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Specialisation at the explain seam (#117, ADR-0045 "Amendment (ticket #117)", ADR-0006, GLOSSARY "Specialisation",
 * "Outside call", "Invariant parameter", "Lifted function"): in the optimised compile a lifted function's group gets a
 * copy for each outside argument tuple of its invariant parameters, appended after the originals, and the outside call
 * runs the copy. {@link JqFilter#explain()} shows a copy's function line with {@code copyOf=fn<o>}, the original it was
 * made from, and one closing line with {@code specialised=<spent>}, the nodes the built copies hold, and {@code
 * refused=<keys>}. The conservative compile makes no copy (ADR-0018). Lines are read with ExplainTest's {@link Line},
 * one field at a time; a node line is one whose first field is its body's column, so #111's closing {@code stack} line
 * is none.
 *
 * <p>Every expected result is reference jq 1.8.2's, recorded in {@code diff/specialise.rec} (the 5 000 000-step results
 * are #118's). The deep runs use the design's limits: {@code recursionDepth} {@value #DEPTH}, which a copy's 30 000
 * levels fit and real closures, one more live activation per level and per closure, do not (the pipe ladder has a
 * budget of its own, {@link #PIPE_LADDER_DEPTH}); each run is on a thread of
 * the oracle runners' size (F-88, ADR-0046 #111), {@link #RUNNER_STACK} bytes, with {@link Limits#cli()}'s declared
 * stack.
 */
class SpecialiseExplainTest {
  /** The oracle runners' thread (F-88, oracle's RunnerThread of #111): {@code max(4 × Limits.cli().stackBytes(), 2 MiB)}. */
  static final long RUNNER_STACK = Math.max(4 * Limits.cli().stackBytes(), 2L << 20);

  /** The design's recursion-depth budget for the 30 000-level cases. */
  static final int DEPTH = 40_000;

  /** Reference jq 1.8.2 (diff/specialise.rec, specialise/free-break): {@code [0,1,2,3]}. */
  static final String FREE_BREAK = "[label $f | 0 | recurse(if . >= 3 then break $f else .+1 end)]";

  /** Reference jq 1.8.2 (specialise/literal-arguments): {@code [0,1]} twice. */
  static final String LITERAL_ARGUMENTS = "[limit(2;0|recurse(.+1))],[limit(2;0|recurse(.+1))]";

  /** Reference jq 1.8.2 ({@code jq-ref -nc}, .logs/117-repin-jqref.log): {@code 5}. */
  static final String INVARIANT_RECURSION = "def r(f): if . > 3 then f else f | r(f) end; 0 | r(. + 1)";

  /**
   * The design's ladder past INLINE_CAP: {@code def f0: .; def f1: f0|f0; … def f15: f14|f14; f15|f14|…|f1|f0|f0|f0 |
   * last(limit(30000; 0|recurse(.+1)))}; reference jq 1.8.2 gives {@code 29999} (specialise/over-inline-cap-recurse).
   */
  static final String OVER_CAP = ladder('|', "", "");

  /**
   * The same ladder of commas, collected: {@code def f0: .; def f1: f0,f0; … def f15: f14,f14; [f15,f14,…,f1,f0,f0,f0]
   * | last(limit(30000; 0|recurse(.+1)))}; reference jq 1.8.2 gives {@code 29999}
   * (specialise/over-inline-cap-comma-recurse).
   */
  static final String COMMA_LADDER = ladder(',', "[", "]");

  /** The pipe ladder's recursion-depth budget: between its edges, 95 522 in OPTIMISED and 125 522 in CONSERVATIVE. */
  static final int PIPE_LADDER_DEPTH = 110_000;

  /** {@code f1} to {@code f15}, each two of the one below joined by {@code op}, then the ladder's chain in {@code open} and {@code close}. */
  private static String ladder(char op, String open, String close) {
    StringBuilder f = new StringBuilder("def f0: .; ");
    for (int i = 1; i <= 15; i++) {
      f.append("def f").append(i).append(": f").append(i - 1).append(op).append('f').append(i - 1).append("; ");
    }
    f.append(open);
    for (int i = 15; i >= 1; i--) {
      f.append('f').append(i).append(op);
    }
    return f.append("f0").append(op).append("f0").append(op).append("f0").append(close).append(" | last(limit(30000; 0|recurse(.+1)))").toString();
  }

  /**
   * The order case (F-94 "Open", F-96): over-cap/param-600's filter, {@code def g1(f): f; def g2(f): g1(f|.+1); … def g600(f):
   * g599(f|.+1); 0|g600(.)}, then {@code | last(limit(30000; 0|recurse(.+1)))}; reference jq 1.8.2 gives {@code 29999}
   * (specialise/order-param-chain-then-recurse).
   */
  static final String ORDER = order();

  private static String order() {
    StringBuilder f = new StringBuilder("def g1(f): f; ");
    for (int i = 2; i <= 600; i++) {
      f.append("def g").append(i).append("(f): g").append(i - 1).append("(f|.+1); ");
    }
    return f.append("0|g600(.) | last(limit(30000; 0|recurse(.+1)))").toString();
  }

  private static JqEngine engine(CompileMode mode) {
    return RunBudgets.recursionDepth(DEPTH, mode);
  }

  /** How {@code filter} on null ends in {@code mode} on the runner thread: its result texts, then how it ended. */
  private static String ended(CompileMode mode, String filter) {
    return TrampolineTest.ended(engine(mode), filter, "null", RUNNER_STACK, false);
  }

  /** {@link #ended(CompileMode, String)} under the recursion-depth budget {@code depth}. */
  private static String ended(CompileMode mode, String filter, int depth) {
    return TrampolineTest.ended(RunBudgets.recursionDepth(depth, mode), filter, "null", RUNNER_STACK, false);
  }

  private static List<Line> lines(CompileMode mode, String filter) {
    String text = engine(mode).compile(filter).explain();
    assertThat(text).as("explain of %s under %s", shown(filter), mode).endsWith("\n");
    return Arrays.stream(text.split("\n")).map(Line::of).toList();
  }

  /** A node line: its first field is its body's column, {@code main} or {@code fn<place>}. */
  private static boolean isNode(Line l) {
    return !l.fields().isEmpty() && l.fields().getFirst().matches("main|fn[0-9]+");
  }

  private static boolean isFunction(Line l) {
    return !l.fields().isEmpty() && l.isFunction();
  }

  /** The node lines of the body {@code column}, by freeze id. */
  private static List<Line> body(List<Line> lines, String column) {
    return lines.stream().filter(l -> isNode(l) && l.fields().getFirst().equals(column)).toList();
  }

  private static List<String> kinds(List<Line> body) {
    return body.stream().map(l -> l.fields().get(2)).toList();
  }

  /** The Call lines of the body {@code column}. */
  private static List<Line> calls(List<Line> lines, String column) {
    return body(lines, column).stream().filter(l -> l.fields().get(2).equals("Call")).toList();
  }

  /** The one function line of {@code column}. */
  private static Line function(List<Line> lines, String column) {
    List<Line> found = lines.stream().filter(l -> isFunction(l) && l.fields().get(1).equals(column)).toList();
    assertThat(found).as("the function line of %s", column).hasSize(1);
    return found.getFirst();
  }

  /** The function lines that show {@code copyOf=}. */
  private static List<Line> copies(List<Line> lines) {
    return lines.stream().filter(l -> isFunction(l) && l.facts().containsKey("copyOf")).toList();
  }

  /** The facts of the closing line: the one line that shows {@code refused=}, which shows {@code specialised=} too. */
  private static Map<String, String> closing(List<Line> lines) {
    List<Line> found = lines.stream().filter(l -> l.facts().containsKey("refused")).toList();
    assertThat(found).as("the closing line, the one line with refused=").hasSize(1);
    assertThat(found.getFirst().facts()).as("the closing line").containsKey("specialised");
    return found.getFirst().facts();
  }

  /** {@code column} and every function it reaches through a Call into its own group (any call kind but PLAIN). */
  private static Set<String> group(List<Line> lines, String column) {
    Set<String> group = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(List.of(column));
    while (!pending.isEmpty()) {
      String c = pending.pop();
      if (group.add(c)) {
        for (Line call : calls(lines, c)) {
          if (!"PLAIN".equals(call.facts().get("call"))) {
            pending.push(call.facts().get("callee"));
          }
        }
      }
    }
    return group;
  }

  /** The callee of each Call of main, which must be a copy, each checked as {@link #assertCopy}. */
  private static List<String> outsideCopies(List<Line> lines, String filter) {
    List<String> callees = calls(lines, "main").stream().map(l -> l.facts().get("callee")).toList();
    assertThat(callees).as("the callees of main's Calls in %s", shown(filter)).isNotEmpty();
    callees.forEach(c -> assertCopy(lines, c, filter));
    return callees;
  }

  /**
   * {@code copy} is a copy whose group holds no ParamCall, while the original it shows in {@code copyOf=} runs a
   * ParamCall (the witness that the check below could fail).
   */
  private static void assertCopy(List<Line> lines, String copy, String filter) {
    Map<String, String> header = function(lines, copy).facts();
    assertThat(header).as("the function line of %s, which main calls in %s", copy, shown(filter)).containsKey("copyOf");
    String original = header.get("copyOf");
    assertThat(kinds(body(lines, original))).as("witness: the original %s of %s runs a ParamCall", original, copy).contains("ParamCall");
    assertThat(group(lines, copy))
        .as("the group of the copy %s", copy)
        .allSatisfy(
            member -> {
              assertThat(function(lines, member).facts()).as("the function line of %s, in the group of %s", member, copy).containsKey("copyOf");
              assertThat(kinds(body(lines, member))).as("the node kinds of %s", member).isNotEmpty().doesNotContain("ParamCall");
            });
  }

  /** The closing line: no key refused, and {@code specialised=} the nodes the copies hold, one node line each. */
  private static void assertSpentEveryCopy(List<Line> lines, String filter) {
    Map<String, String> closing = closing(lines);
    long held = copies(lines).stream().mapToLong(c -> body(lines, c.fields().get(1)).size()).sum();
    assertThat(closing).as("the closing line of %s", shown(filter)).containsEntry("refused", "0");
    assertThat(closing.get("specialised")).as("specialised= of %s against the %d node lines of its copies", shown(filter), held).isEqualTo(Long.toString(held));
  }

  static Stream<Arguments> closed() {
    return Stream.of(
        Arguments.argumentSet("until", "0|until(.>=3; .+1)", "[3], ran out"),
        Arguments.argumentSet("while", "[0|while(.<3; .+1)]", "[[0,1,2]], ran out"),
        Arguments.argumentSet("recurse(f)", "[limit(3; 0|recurse(.+1))]", "[[0,1,2]], ran out"),
        Arguments.argumentSet("repeat", "[limit(3; 0|repeat(.+1))]", "[[1,1,1]], ran out"));
  }

  /**
   * Closed arguments: each prelude loop's closures read nothing outside themselves, so the outside call from main runs a
   * copy whose group has no ParamCall, and with no ParamCall and no Break the copy's root may not break out (ADR-0007
   * #115). Every copy is built whole: the closing line refuses none and has spent the copies' nodes.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("closed")
  void closedArgumentsCallACopyWithoutParamCall(String filter, String expected) {
    assertThat(ended(CompileMode.OPTIMISED, filter)).as("OPTIMISED run of %s", filter).isEqualTo(expected);

    List<Line> lines = lines(CompileMode.OPTIMISED, filter);
    for (String copy : outsideCopies(lines, filter)) {
      assertThat(body(lines, copy).getFirst().facts()).as("the root of the copy %s in %s", copy, filter).containsEntry("breaks", "false");
    }
    assertSpentEveryCopy(lines, filter);
  }

  static Stream<Arguments> copied() {
    return Stream.concat(
        closed(),
        Stream.of(
            Arguments.argumentSet("a free Break", FREE_BREAK, "[[0,1,2,3]], ran out"),
            Arguments.argumentSet("literal arguments", LITERAL_ARGUMENTS, "[[0,1], [0,1]], ran out")));
  }

  /**
   * CONSERVATIVE skips the pass (ADR-0018, ADR-0045): no copy, main's Calls run functions that run their real
   * closures, and the closing line shows nothing spent and nothing refused. Witness: the optimised compile of the same
   * filter shows a copy.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("copied")
  void conservativeMakesNoCopy(String filter, String expected) {
    assertThat(ended(CompileMode.CONSERVATIVE, filter)).as("CONSERVATIVE run of %s", filter).isEqualTo(expected);

    List<Line> lines = lines(CompileMode.CONSERVATIVE, filter);
    assertThat(copies(lines)).as("copies of %s under CONSERVATIVE", filter).isEmpty();
    assertThat(calls(lines, "main"))
        .as("main's Calls in %s under CONSERVATIVE", filter)
        .isNotEmpty()
        .allSatisfy(call -> assertThat(kinds(body(lines, call.facts().get("callee")))).as("the node kinds of the callee of %s", call.fields()).contains("ParamCall"));
    assertThat(closing(lines)).as("the closing line of %s under CONSERVATIVE", filter).containsEntry("specialised", "0").containsEntry("refused", "0");
    assertThat(copies(lines(CompileMode.OPTIMISED, filter))).as("witness: copies of %s under OPTIMISED", filter).isNotEmpty();
  }

  static Stream<Arguments> ladders() {
    return Stream.of(
        Arguments.argumentSet("the comma ladder at recursionDepth 40 000", COMMA_LADDER, DEPTH),
        Arguments.argumentSet("the pipe ladder at recursionDepth 110 000", OVER_CAP, PIPE_LADDER_DEPTH));
  }

  /**
   * Over INLINE_CAP (ADR-0045's reason for a cap of its own): each ladder lifts recurse/1, which main's text calls
   * PLAIN, and the optimised compile still copies it, so each of its 30 000 levels holds one activation, and the
   * conservative compile's real closure one more. Each ladder gives reference jq's {@code 29999} in OPTIMISED and ends
   * at the budget in CONSERVATIVE, so each needs the copy (#117 re-pin rulings W1, W2).
   *
   * <ul>
   *   <li>The comma ladder's calls have ended when recurse runs, its results collected first, so the copy fits {@link
   *       #DEPTH} and the real closure does not.
   *   <li>The pipe ladder runs every call on the driver, its {@code callFreeDepth} above the runner's unit limit, and on
   *       the driver a call stays live while its consumer runs (ADR-0046 #109 and "Amendment (ticket #116)", C2). So its
   *       PLAIN calls stay live under recurse's levels: it ends at recursionDepth 95 522 in OPTIMISED and 125 522 in
   *       CONSERVATIVE, each ending at the budget one below (.logs/117-repin2-edges.log at fede05d), and is pinned at
   *       {@link #PIPE_LADDER_DEPTH}, between them.
   * </ul>
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("ladders")
  void overInlineCapStillSpecialisesRecurse(String filter, int depth) {
    List<Line> plain = lines(CompileMode.CONSERVATIVE, filter);
    List<String> recurse = plain.stream().filter(l -> isFunction(l) && l.fields().get(2).equals("recurse/1")).map(l -> l.fields().get(1)).toList();
    assertThat(recurse).as("premise: the function lines of recurse/1 under CONSERVATIVE").hasSize(1);
    assertThat(plain.stream().filter(l -> isNode(l) && recurse.getFirst().equals(l.facts().get("callee"))).toList())
        .as("premise: the Calls of recurse/1 (%s)", recurse.getFirst())
        .isNotEmpty()
        .allSatisfy(l -> assertThat(l.facts()).as("the Call %s", l.fields()).containsEntry("call", "PLAIN"));

    assertThat(ended(CompileMode.OPTIMISED, filter, depth)).as("OPTIMISED run of the ladder with recursionDepth %d", depth).isEqualTo("[29999], ran out");
    assertThat(ended(CompileMode.CONSERVATIVE, filter, depth)).as("CONSERVATIVE run of the ladder with recursionDepth %d", depth).isEqualTo("[], termination BUDGET RECURSION_DEPTH");

    List<Line> lines = lines(CompileMode.OPTIMISED, filter);
    String original = lines.stream().filter(l -> isFunction(l) && l.fields().get(2).equals("recurse/1") && !l.facts().containsKey("copyOf")).map(l -> l.fields().get(1)).findFirst().orElseThrow();
    assertThat(copies(lines)).extracting(l -> l.facts().get("copyOf")).as("the copyOf= of the copies under OPTIMISED").contains(original);
  }

  /**
   * Order (ADR-0045 #117 "Order and cap", S2; F-94 "Open", F-96): keys are spent by Source, so param-600's chain, whose
   * demand is above INLINE_CAP, is decided before the recurse key at the end of main and refuses keys, while what is
   * spent stays within SPECIALISE_CAP, 16 384 (ADR-0045). The order case is refused by design; whether recurse's r/0
   * gets a copy is not pinned (F-96). At {@link Limits#cli()}'s recursion depth the result is reference jq's {@code
   * 29999} in both modes.
   */
  @Test
  void parameterChainBeforeRecurseGivesJqsResultAtDefaultLimits() {
    Map<String, String> closing = closing(lines(CompileMode.OPTIMISED, ORDER));
    assertThat(Integer.parseInt(closing.get("refused"))).as("refused= of the order case: keys of param-600's chain").isPositive();
    assertThat(Integer.parseInt(closing.get("specialised"))).as("specialised= of the order case, against SPECIALISE_CAP").isLessThanOrEqualTo(16_384);

    for (CompileMode mode : CompileMode.values()) {
      String ended = TrampolineTest.ended(RunBudgets.recursionDepth(Limits.cli().recursionDepth(), mode), ORDER, "null", RUNNER_STACK, false);
      assertThat(ended).as("%s run of the order case with Limits.cli()'s recursionDepth %d", mode, Limits.cli().recursionDepth()).isEqualTo("[29999], ran out");
    }
  }

  static Stream<Arguments> freeSlots() {
    return Stream.of(
        Arguments.argumentSet("range/3", "last(range(0;30000;1))", "[29999], ran out"),
        Arguments.argumentSet("until with $n", "30000 as $n|0|until(.>=$n; .+1)", "[30000], ran out"));
  }

  /**
   * Free references (S4): {@code $upto}, {@code $by} and {@code $n} are slots of main that the closures read, so they
   * become trailing values of the copy instead of keeping the real closures; the copy's 30 000 levels fit the depth
   * budget, the real closures' do not.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("freeSlots")
  void freeSlotsBecomeValueParameters(String filter, String expected) {
    assertThat(ended(CompileMode.OPTIMISED, filter)).as("OPTIMISED run of %s with recursionDepth %d", filter, DEPTH).isEqualTo(expected);
    assertThat(ended(CompileMode.CONSERVATIVE, filter)).as("CONSERVATIVE run of %s with recursionDepth %d", filter, DEPTH).isEqualTo("[], termination BUDGET RECURSION_DEPTH");
  }

  static Stream<Arguments> deep() {
    return Stream.of(
        Arguments.argumentSet("range/3 to 5 000 000", "last(range(0;5000000;1))"),
        Arguments.argumentSet("until $n of 5 000 000", "5000000 as $n|0|until(.>=$n; .+1)"));
  }

  /**
   * The required deep cases (#118 owns their results, 4999999 and 5000000 on reference jq): each outside call runs a
   * copy that takes its free slots as values, holds no ParamCall and calls itself TAIL_SELF. Re-pinned by #118 (ADR-0047
   * #118): in the copy the self-call sits in an If branch whose condition, the inlined {@code . < $upto} or {@code . >=
   * $n}, is at most one, on the right of a Pipe whose left {@code . + $by} or {@code . + 1} is at most one (while's call
   * also on the right of a Comma), and its only arguments are the copy's trailing values, which pass condition 4.
   */
  @ParameterizedTest(name = "{argumentSetName}")
  @MethodSource("deep")
  void requiredDeepCasesRunACopy(String filter) {
    List<Line> lines = lines(CompileMode.OPTIMISED, filter);
    for (String copy : outsideCopies(lines, filter)) {
      assertThat(Integer.parseInt(function(lines, copy).facts().get("values"))).as("values= of the copy %s in %s", copy, filter).isGreaterThanOrEqualTo(1);
      assertThat(calls(lines, copy))
          .as("the Calls of the copy %s in %s", copy, filter)
          .anySatisfy(l -> assertThat(l.facts()).as("the Call %s", l.fields()).containsEntry("callee", copy).containsEntry("call", "TAIL_SELF"));
    }
  }

  /**
   * A free Break (S4, ADR-0045 #107's label captures): the closure breaks to main's label, so the copy takes the label's
   * token as its one value, holds the Break, and may break out. The result is reference jq's in both modes.
   */
  @ParameterizedTest
  @EnumSource(CompileMode.class)
  void freeBreakPassesItsToken(CompileMode mode) {
    assertThat(ended(mode, FREE_BREAK)).as("%s run of %s", mode, FREE_BREAK).isEqualTo("[[0,1,2,3]], ran out");

    List<Line> lines = lines(CompileMode.OPTIMISED, FREE_BREAK);
    for (String copy : outsideCopies(lines, FREE_BREAK)) {
      assertThat(function(lines, copy).facts()).as("the function line of the copy %s", copy).containsEntry("values", "1");
      assertThat(kinds(body(lines, copy))).as("the node kinds of the copy %s", copy).contains("Break");
      assertThat(body(lines, copy).getFirst().facts()).as("the root of the copy %s", copy).containsEntry("breaks", "true");
    }
    assertSpentEveryCopy(lines, FREE_BREAK);
  }

  /**
   * Literal arguments (S3): constants compare by identity, so the two calls of r, whose closures each hold their own
   * literal 1, get a copy each, of the same original.
   */
  @Test
  void literalArgumentsGetACopyEach() {
    assertThat(ended(CompileMode.OPTIMISED, LITERAL_ARGUMENTS)).as("OPTIMISED run of %s", LITERAL_ARGUMENTS).isEqualTo("[[0,1], [0,1]], ran out");

    List<Line> lines = lines(CompileMode.OPTIMISED, LITERAL_ARGUMENTS);
    List<String> callees = outsideCopies(lines, LITERAL_ARGUMENTS);
    assertThat(callees).as("the callees of main's Calls").hasSize(2).doesNotHaveDuplicates();
    assertThat(callees.stream().map(c -> function(lines, c).facts().get("copyOf")).distinct().toList()).as("the originals of the two copies").hasSize(1);
    assertSpentEveryCopy(lines, LITERAL_ARGUMENTS);
  }

  /**
   * A copy of a recursion is value form where its original is push (#117 re-pin ruling V4; ADR-0047 "Amendment (ticket
   * #116)", ADR-0045 "Amendment (ticket #117)"): r's f is invariant, so main's Call runs a copy of r/1 for {@code . + 1},
   * which holds no ParamCall. The original r/1 stays, though no call reaches it, and runs its closure, so its root If is
   * push form; the copy's root If and main's Call are value form. The two columns are read from r/1's function lines,
   * the copy's by its {@code copyOf=}, and each root by its column and freeze id 0, its kind checked first. Witness:
   * under CONSERVATIVE main's Call runs the original and is push form. The result is reference jq's in both modes.
   */
  @Test
  void aCopyOfARecursionIsValueFormWhereItsOriginalIsPush() {
    for (CompileMode mode : CompileMode.values()) {
      assertThat(ended(mode, INVARIANT_RECURSION)).as("%s run of %s", mode, INVARIANT_RECURSION).isEqualTo("[5], ran out");
    }

    List<Line> lines = lines(CompileMode.OPTIMISED, INVARIANT_RECURSION);
    List<Line> r = lines.stream().filter(l -> isFunction(l) && l.fields().get(2).equals("r/1")).toList();
    List<String> originals = r.stream().filter(l -> !l.facts().containsKey("copyOf")).map(l -> l.fields().get(1)).toList();
    assertThat(originals).as("the columns of r/1's function lines without copyOf=").hasSize(1);
    String original = originals.getFirst();
    List<Line> copies = r.stream().filter(l -> l.facts().containsKey("copyOf")).toList();
    assertThat(copies).as("r/1's function lines with copyOf=").hasSize(1);
    assertThat(copies.getFirst().facts()).as("the function line of r/1's copy").containsEntry("copyOf", original);
    String copy = copies.getFirst().fields().get(1);

    Line originalRoot = at(lines, original, 0);
    assertThat(originalRoot.fields().get(2)).as("kind of node 0 of the original %s", original).isEqualTo("If");
    assertThat(originalRoot.facts()).as("facts of the root of the original %s", original).containsEntry("form", "push");
    Line copyRoot = at(lines, copy, 0);
    assertThat(copyRoot.fields().get(2)).as("kind of node 0 of the copy %s", copy).isEqualTo("If");
    assertThat(copyRoot.facts()).as("facts of the root of the copy %s", copy).containsEntry("form", "value");
    List<Line> main = calls(lines, "main");
    assertThat(main).as("main's Calls").hasSize(1);
    assertThat(main.getFirst().facts()).as("facts of main's Call").containsEntry("callee", copy).containsEntry("form", "value");

    List<Line> conservative = calls(lines(CompileMode.CONSERVATIVE, INVARIANT_RECURSION), "main");
    assertThat(conservative).as("main's Calls under CONSERVATIVE").hasSize(1);
    assertThat(conservative.getFirst().facts()).as("witness: facts of main's Call under CONSERVATIVE").containsEntry("callee", original).containsEntry("form", "push");
  }
}
