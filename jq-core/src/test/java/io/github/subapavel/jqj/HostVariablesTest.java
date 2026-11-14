package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.read;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Host variables at the public API (#67's acceptance, ADR-0016, ADR-0038): {@code compile(text, variables)} declares
 * names that the filter reads as {@code $name}, and {@code Bindings.builder().put(name, value)} gives each its value for
 * one run. A declared name left unbound is an IllegalArgumentException; a name in the Bindings that was never declared
 * is ignored (the design approval's O1); a {@code $name} that is neither declared nor bound by {@code as} is jq's compile
 * error. Expected values are jq-ref 1.8.2's: {@code jq-ref -nc --argjson x 1 '$x + 1, ($x, (2 as $x | $x), $x)'} prints
 * 2, 1, 2, 1; {@code jq-ref -nc --argjson a 1 --argjson b 2 '[$b, $a]'} prints [2,1]; {@code jq-ref -nc --argjson x 1
 * '$x + $y'} is rc 3 with one block for {@code $y} at column 6; the unbound {@code $x} is diff/bind.rec's
 * bind/unbound-one.
 *
 * <p>Signatures the test-author fixed from ADR-0016 (no draft text reached the red): {@code JqEngine.compile(String,
 * Set<String>)} and {@code Bindings.Builder.put(String, JqValue)}, which returns the builder.
 */
class HostVariablesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** The number {@code text} read in a Workspace of its own, which nothing reads again (a later read would end the view). */
  private static JqValue number(String text) {
    return read(ENGINE.newWorkspace(), text.getBytes(UTF_8)).root();
  }

  /** The compact texts of {@code filter}'s results on null under {@code bindings}. */
  private static List<String> texts(JqFilter filter, Bindings bindings) {
    Results results = new Results();
    assertThat(filter.run(JqNull.NULL, bindings, results, ENGINE.newWorkspace())).as("run's answer when the sink never stops").isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }

  @Test
  void aDeclaredNameReadsItsBoundValue() {
    JqFilter filter = ENGINE.compile("$x + 1", Set.of("x"));
    assertThat(texts(filter, Bindings.builder().put("x", number("1")).build())).as("$x + 1 with x = 1").containsExactly("2");
  }

  @Test
  void anAsBindingShadowsAHostVariableInItsBodyOnly() {
    JqFilter filter = ENGINE.compile("$x, (2 as $x | $x), $x", Set.of("x"));
    assertThat(texts(filter, Bindings.builder().put("x", number("1")).build())).as("$x, (2 as $x | $x), $x with x = 1").containsExactly("1", "2", "1");
  }

  @Test
  void eachDeclaredNameReadsItsOwnValue() {
    // ADR-0038: the declared names, sorted, take the parameter slots; a swapped slot would print [1,2].
    JqFilter filter = ENGINE.compile("[$b, $a]", Set.of("b", "a"));
    Bindings bindings = Bindings.builder().put("a", number("1")).put("b", number("2")).build();
    assertThat(texts(filter, bindings)).as("[$b, $a] with a = 1, b = 2").containsExactly("[2,1]");
  }

  @Test
  void aDeclaredNameLeftUnboundIsAnIllegalArgument() {
    JqFilter filter = ENGINE.compile("$x", Set.of("x"));
    Results none = new Results();
    assertThatIllegalArgumentException().as("run of $x with no bindings").isThrownBy(() -> filter.run(JqNull.NULL, NONE, none, ENGINE.newWorkspace()));
    assertThat(none.values).as("results of $x with no bindings").isEmpty();

    // One of two declared names bound is still an unbound one.
    JqFilter two = ENGINE.compile("[$a, $b]", Set.of("a", "b"));
    Bindings onlyA = Bindings.builder().put("a", number("1")).build();
    assertThatIllegalArgumentException().as("run of [$a, $b] with only a bound").isThrownBy(() -> two.run(JqNull.NULL, onlyA, new Results(), ENGINE.newWorkspace()));

    // Witness: bound, the same filter runs.
    assertThat(texts(filter, Bindings.builder().put("x", number("1")).build())).as("$x with x = 1").containsExactly("1");
  }

  @Test
  void aNameThatWasNeverDeclaredIsIgnored() {
    JqFilter filter = ENGINE.compile("$x", Set.of("x"));
    Bindings extra = Bindings.builder().put("x", number("1")).put("y", number("2")).build();
    assertThat(texts(filter, extra)).as("$x with x = 1 and an undeclared y = 2").containsExactly("1");

    // A filter that declares nothing takes a binding it never reads.
    JqFilter dot = ENGINE.compile(".");
    assertThat(texts(dot, Bindings.builder().put("y", number("2")).build())).as(". on null with an undeclared y").containsExactly("null");
  }

  @Test
  void anUndeclaredVariableIsJqsCompileError() {
    JqCompileException e = assertThatExceptionOfType(JqCompileException.class).as("compile of $x").isThrownBy(() -> ENGINE.compile("$x")).actual();
    assertThat(e.blocks()).as("blocks of $x").containsExactly("jq: error: $x is not defined at <top-level>, line 1, column 1:\n    $x\n    ^^");
    assertThat(e.getMessage()).as("message of $x").endsWith("\njq: 1 compile error");
    assertThat(e.line()).as("line of $x").isEqualTo(1);
    assertThat(e.column()).as("column of $x").isEqualTo(1);

    // A declared name resolves and an undeclared one beside it does not.
    JqCompileException y =
        assertThatExceptionOfType(JqCompileException.class).as("compile of $x + $y with x declared").isThrownBy(() -> ENGINE.compile("$x + $y", Set.of("x"))).actual();
    assertThat(y.blocks()).as("blocks of $x + $y with x declared").containsExactly("jq: error: $y is not defined at <top-level>, line 1, column 6:\n    $x + $y\n         ^^");
    assertThat(y.column()).as("column of $x + $y").isEqualTo(6);
  }
}
