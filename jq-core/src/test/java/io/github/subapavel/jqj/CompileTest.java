package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code JqEngine.compile} after #33 (design section 1) and #34: {@code .}, with SP, TAB, CR or LF around it, compiles
 * to the identity and runs in push form (ADR-0008); #34 adds {@code .name}, chains, {@code |} and parentheses, whose
 * jq error leaves run as a JqFilterException. Any other text is a Stub, a JqCompileException with the mark
 * (ADR-0024). Exported types only.
 */
class CompileTest {
  /** ADR-0024, GLOSSARY "Stub": the mark every Stub message starts with. */
  private static final String MARK = "jqj stub: ";

  /** jq-ref 1.8.2, observed 2026-11-11: printf '[1]' | jq-ref -c "$(printf ' \t\r\n.\n\t ')" -> [1] (rc 0). */
  private static final String DOT_IN_WHITESPACE = " \t\r\n.\n\t ";

  @Test
  void stubConstructThrowsCompileException() {
    JqEngine engine = JqEngine.builder().build();
    // jq-ref 1.8.2, observed 2026-11-11, each with printf '[1]' | jq-ref -c <text>:
    //   .[0] -> 1 (rc 0); .. -> [1] 1 (rc 0);
    //   '' and '   ' -> "Top-level program not given (try \".\")" (rc 3);
    //   FF or VT before the dot -> "syntax error, unexpected INVALID_CHARACTER" (rc 3): the filter's whitespace is
    //   SP, TAB, CR and LF only. Neither #33 nor #34 builds them, so each ends in the Stub. The .a entry ended with
    //   its Stub in #34 (design C4) and moved to .[0], which stays a Stub until spec 2. #61 built the literal 1, so its
    //   entry moved to 1, 2 (observed 2026-11-12: 1 2, rc 0), whose comma stays a Stub until #63.
    // #62 built the syntax errors and the not-given message (ADR-0032), so '', '   ', FF and VT left the Stub list and
    // are asserted as jq's errors below (diff/syntax.rec program-empty, program-only-spaces, form-feed-before-dot and
    // vertical-tab-before-dot); none was dropped. #63 built 1, 2 and .[0] (NavigationBudgetTest and diff/comma.rec,
    // index.rec run them), so their entries ended with their Stubs; .. and ..? stay Stubs until spec 2b (ResolverTest's
    // GRAMMAR list: jq-ref runs both).
    for (String text : List.of("..", "..?")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("compile of %s", quoted(text))
          .isThrownBy(() -> engine.compile(text))
          .withMessageStartingWith(MARK);
    }
    for (String text : List.of("", "   ")) {
      assertThatExceptionOfType(JqCompileException.class)
          .as("compile of %s", quoted(text))
          .isThrownBy(() -> engine.compile(text))
          .withMessage("jq: error: Top-level program not given (try \".\")\njq: 1 compile error");
    }
    for (String text : List.of("\f.", "\u000b.")) {
      JqCompileException e =
          assertThatExceptionOfType(JqCompileException.class).as("compile of %s", quoted(text)).isThrownBy(() -> engine.compile(text)).actual();
      assertThat(e.blocks()).as("blocks of %s", quoted(text)).hasSize(1);
      assertThat(e.blocks().getFirst()).as("block of %s", quoted(text)).startsWith("jq: error: syntax error, unexpected INVALID_CHARACTER");
      assertThat(e.line()).as("line of %s", quoted(text)).isEqualTo(1);
      assertThat(e.column()).as("column of %s", quoted(text)).isEqualTo(1);
    }

    // Witness: the built construct compiles and runs, so the Stubs above are not a Stub for every text.
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "[1]").root();
    for (String text : List.of(".", DOT_IN_WHITESPACE)) {
      List<JqValue> results = new ArrayList<>();
      boolean ranOut =
          engine
              .compile(text)
              .run(
                  root,
                  Bindings.builder().build(),
                  v -> {
                    results.add(v);
                    return true;
                  },
                  workspace);
      assertThat(results).as("results of %s on [1]", quoted(text)).containsExactly(root);
      assertThat(ranOut).as("run's answer for %s when the sink never stops", quoted(text)).isTrue();
    }
  }

  @Test
  void dotEmitsTheRootOnceAndPassesTheSinksStopOn() {
    // ADR-0016: run is "true when the results ran out, false when the sink stopped" (ADR-0008's stop).
    Workspace workspace = newWorkspace();
    JqValue root = read(workspace, "{\"a\":[1,2]}").root();
    List<JqValue> results = new ArrayList<>();

    boolean ranOut =
        JqEngine.builder()
            .build()
            .compile(".")
            .run(
                root,
                Bindings.builder().build(),
                v -> {
                  results.add(v);
                  return false;
                },
                workspace);

    assertThat(ranOut).as("run's answer after the sink stopped it").isFalse();
    assertThat(results).as("results handed over before the stop").containsExactly(root);
  }

  @Test
  void filterErrorLeavesRunAsJqFilterException() {
    // #34 design section 8: run turns the operation's FilterException into a JqFilterException with the same value,
    // a string. jq-ref 1.8.2, recorded in diff/field.rec: field/error-array (.a on [1]), field/chain-error-middle
    // (.a.b.c on {"a":{"b":5}}) and field/parens-pipe-error ((.a | .b).c on {"a":{"b":true}}) end with rc 5 and
    // "Cannot index array with string ("a")", "Cannot index number with string ("c")" and "Cannot index boolean with
    // string ("c")".
    JqEngine engine = JqEngine.builder().build();
    String[][] cases = {
      {".a", "[1]", "Cannot index array with string (\"a\")"},
      {".a.b.c", "{\"a\":{\"b\":5}}", "Cannot index number with string (\"c\")"},
      {"(.a | .b).c", "{\"a\":{\"b\":true}}", "Cannot index boolean with string (\"c\")"},
    };
    for (String[] c : cases) {
      JqFilter filter = engine.compile(c[0]);
      Workspace workspace = newWorkspace();
      JqValue root = read(workspace, c[1]).root();
      List<JqValue> results = new ArrayList<>();
      JqFilterException e =
          assertThatExceptionOfType(JqFilterException.class)
              .as("run of %s on %s", c[0], c[1])
              .isThrownBy(
                  () ->
                      filter.run(
                          root,
                          Bindings.builder().build(),
                          v -> {
                            results.add(v);
                            return true;
                          },
                          workspace))
              .actual();
      assertThat(e.value()).as("the error value of %s on %s", c[0], c[1]).isInstanceOf(JqString.class);
      assertThat(((JqString) e.value()).value()).as("the error text of %s on %s", c[0], c[1]).isEqualTo(c[2]);
      assertThat(results).as("results of %s on %s", c[0], c[1]).isEmpty();
    }
  }

  /** The text with its control characters spelled out, for a readable description. */
  private static String quoted(String text) {
    return "'" + text.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n").replace("\f", "\\f").replace("\u000b", "\\v") + "'";
  }
}
