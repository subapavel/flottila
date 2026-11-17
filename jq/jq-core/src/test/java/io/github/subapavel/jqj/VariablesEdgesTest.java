package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The edges of #67's variables that no recorded case pins (ADR-0038, ADR-0036, ADR-0016): where jq places an unbound
 * variable's error, the variables jq defines itself, the constant pair {@code {$__loc__}} and the Bindings builder.
 * Expected texts are jq-ref 1.8.2's, observed 2026-11-13 (the implementer's scratchpad probes).
 */
class VariablesEdgesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** The stub mark (ADR-0024). */
  private static final String MARK = "jqj stub: ";

  private static JqCompileException compileError(String text) {
    return assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).actual();
  }

  private static List<String> texts(String text, Set<String> variables, Bindings bindings) {
    Results results = new Results();
    ENGINE.compile(text, variables).run(JqNull.NULL, bindings, results, ENGINE.newWorkspace());
    return results.values.stream().map(JqValue::toJson).toList();
  }

  private static JqValue number(String text) {
    return read(ENGINE.newWorkspace(), text.getBytes(UTF_8)).root();
  }

  @Test
  void anUnboundKeyVariableIsReportedOverItsWholePair() {
    // jq-ref -n '{$x: 1}': rc 3, the carets under "$x: 1"; the shorthand {$x} has them under $x (bind/unbound-*).
    assertThat(compileError("{$x: 1}").blocks())
        .as("blocks of {$x: 1}")
        .containsExactly("jq: error: $x is not defined at <top-level>, line 1, column 2:\n    {$x: 1}\n     ^^^^^");
  }

  @Test
  void theFirstOfSeveralErrorsPlacesTheException() {
    // jq-ref -n '[$b, $a]': two blocks, $b's first (bind/unbound-two); the exception carries $b's line and column.
    JqCompileException e = compileError("[$b, $a]");
    assertThat(e.blocks()).as("blocks of [$b, $a]").hasSize(2);
    assertThat(e.line()).as("line of [$b, $a]").isEqualTo(1);
    assertThat(e.column()).as("column of [$b, $a]").isEqualTo(2);
  }

  @Test
  void theVariablesJqDefinesItselfAreStubs() {
    // jq-ref -n: $ENV | type is "object", $ARGS is {"positional":[],"named":{}}, $JQ_BUILD_CONFIGURATION | type is
    // "string"; none is built (spec 7), so each is a Stub, not jq's "is not defined".
    for (String text : List.of("$ENV", "$ARGS", "$JQ_BUILD_CONFIGURATION")) {
      assertThatExceptionOfType(JqCompileException.class).as("compile of %s", text).isThrownBy(() -> ENGINE.compile(text)).withMessageStartingWith(MARK);
    }
    // Witness: bound by as, the name is a variable like any other (jq-ref -n '1 as $ENV | $ENV' -> 1).
    assertThat(texts("1 as $ENV | $ENV", Set.of(), NONE)).as("results of 1 as $ENV | $ENV").containsExactly("1");
  }

  @Test
  void theLocationPairIsAConstantKey() {
    // jq-ref -n '{({$__loc__}): 1}': rc 3, the object is a constant key that is no string, its carets under the key.
    assertThat(compileError("{({$__loc__}): 1}").blocks())
        .as("blocks of {({$__loc__}): 1}")
        .containsExactly(
            "jq: error: Cannot use object ({\"__loc__\":{\"file\":\"<top-...}) as object key at <top-level>, line 1, column 3:\n"
                + "    {({$__loc__}): 1}\n      ^^^^^^^^^^");
  }

  @Test
  void bindingsTakeTheLastValuePutAndRefuseNulls() {
    Bindings twice = Bindings.builder().put("x", number("1")).put("x", number("2")).build();
    assertThat(texts("$x", Set.of("x"), twice)).as("$x with x put as 1, then 2").containsExactly("2");
    assertThatNullPointerException().as("put of a null name").isThrownBy(() -> Bindings.builder().put(null, number("1")));
    assertThatNullPointerException().as("put of a null value").isThrownBy(() -> Bindings.builder().put("x", null));
  }
}
