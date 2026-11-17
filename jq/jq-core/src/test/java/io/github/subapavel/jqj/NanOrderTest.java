package io.github.subapavel.jqj;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * NaN in the value order (ADR-0037, ADR-0012): against a number a NaN side is read as null, at the same depth, so it is
 * below every number, itself included, and never equal; against another kind it is a number, above null and false and
 * below a string. NaN is computed with #65's arithmetic (1e1000 is a literal whose double is Infinity), since nan/0 is
 * #72's; the census NaN rows stay #72's (design approval O4). The expected value is jq-ref 1.8.2's recording of
 * diff/compare.cases compare/nan-read-as-null.
 */
class NanOrderTest {
  @Test
  void nanIsReadAsNullAgainstANumber() {
    JqEngine engine = JqEngine.builder().build();
    String filter = "(1e1000 - 1e1000) | [. < 1, . > null, . < null, . == ., . < ., . > -1e1000, . < false, . < \"a\"]";
    Results results = new Results();
    assertThat(engine.compile(filter).run(JqNull.NULL, RunBudgets.NONE, results, engine.newWorkspace())).as("answer of the NaN comparisons").isTrue();
    assertThat(results.values.stream().map(JqValue::toJson).toList())
        .as("results of the NaN comparisons")
        .containsExactly("[true,true,false,false,true,false,false,true]");
  }
}
