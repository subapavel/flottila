package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two corners of the value order (ADR-0037, GLOSSARY "Value order") where the #66 red does not reach (implementer's
 * witnesses): an array whose elements equal the other's and that has more comes after it, a document's array and a
 * built one alike; and a computed number on the left orders by its double, as a literal does. Every expected value is
 * jq-ref 1.8.2's, observed 2026-11-13 with {@code jq-ref -nc} on the same filter, and with {@code jq-ref -c} on the
 * document {@code [1,2]}.
 */
class ValueOrderEdgesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  @Test
  void anArrayWithMoreElementsAfterAnEqualPrefixComesAfter() {
    assertThat(run("[[1,2] > [1], [1,2] < [1], [1,[]] >= [1], [[1,2]] > [[1]], [null] > []]", JqNull.NULL))
        .as("results of the built arrays")
        .containsExactly("[true,false,true,true,true]");
    Workspace workspace = ENGINE.newWorkspace();
    JqValue document = read(workspace, "[1,2]").root();
    Results results = new Results();
    assertThat(ENGINE.compile("[. > .[:1], .[:1] < ., . > [1]]").run(document, NONE, results, workspace)).as("answer on [1,2]").isTrue();
    assertThat(results.values.stream().map(JqValue::toJson).toList()).as("results on [1,2]").containsExactly("[true,true,true]");
  }

  @Test
  void aComputedNumberOnTheLeftOrdersByItsDouble() {
    assertThat(run("[1 + 1 < 3, 1 + 2 > 3, 2 * 1 == 2, 2 / 2 <= 1, 1 - 2 < -1]", JqNull.NULL))
        .as("results of the computed numbers")
        .containsExactly("[true,false,true,true,false]");
  }

  private static List<String> run(String filter, JqValue input) {
    Results results = new Results();
    assertThat(ENGINE.compile(filter).run(input, NONE, results, ENGINE.newWorkspace())).as("answer of %s", filter).isTrue();
    return results.values.stream().map(JqValue::toJson).toList();
  }
}
