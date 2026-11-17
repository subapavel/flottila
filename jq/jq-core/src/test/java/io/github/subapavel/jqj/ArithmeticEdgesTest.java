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
 * The #65 operators at {@code JqEngine.compile} where the test-author's red does not reach (implementer's witnesses):
 * an equality of nulls, of booleans, of objects whose keys differ, of zeros and of literals below jq's least exponent
 * and of Infinity literals; a
 * document number that no long holds, or that is not in its canonical form, against a literal; a computed subnormal
 * whose shortest digits are two; and an object + and merge whose result outgrows its first eight entries. Every
 * expected text is jq-ref 1.8.2's, observed 2026-11-13 with {@code printf '<input>\n' | jq-ref -c '<filter>'}.
 */
class ArithmeticEdgesTest {
  private static final JqEngine ENGINE = JqEngine.builder().build();

  /** Input (null when absent), filter, and jq-ref's one compact result. */
  private static final List<String[]> CASES =
      List.of(
          new String[] {null, "[null]-[null]", "[]"},
          new String[] {null, "[true,false,true]-[true]", "[false]"},
          new String[] {null, "[12e-1147483647]-[1e-1147483646]", "[]"},
          new String[] {null, "[0] - [100000000000000000000]", "[0]"},
          new String[] {null, "[100000000000000000000, 0] - [0e-5]", "[100000000000000000000]"},
          new String[] {null, "[{\"a\":1}]-[{\"b\":1}]", "[{\"a\":1}]"},
          new String[] {null, "[{\"a\":1}]-[{\"a\":1,\"b\":2}]", "[{\"a\":1}]"},
          new String[] {null, "[5e-1147483647]-[1e-1147483646]", "[]"},
          new String[] {null, "[1e1000000000]-[2e1000000000]", "[]"},
          new String[] {null, "[1e1000000000]-[-1e1000000000]", "[1.7976931348623157e+308]"},
          new String[] {null, "[-1e1000000000]-[1e1000]", "[-1.7976931348623157e+308]"},
          new String[] {"[100000000000000000000]", ". - [1e20]", "[]"},
          new String[] {"[100000000000000000000]", ". - [100000000000000000001]", "[100000000000000000000]"},
          new String[] {"[1.5e0,15e-1,0.15E1,2.5]", ". - [1.5]", "[2.5]"},
          new String[] {"[1.5e0,15e-1,0.15E1,2.5]", ". - [1.50]", "[2.5]"},
          new String[] {"[100.0e18, 1.0E20]", ". - [100000000000000000000]", "[]"},
          new String[] {null, "[1,2] - [2+0]", "[1]"},
          new String[] {null, "[2+0] - [1+0]", "[2]"},
          new String[] {null, "[1e1000-1e1000] - [1e1000-1e1000]", "[null]"},
          new String[] {null, "[3*5e-324, 2*5e-324, 1e300*1e10, -0*1, 1e17+0, 1e-5+0, 0.001+0, 123456.5+0]", "[1.5e-323,1e-323,1.7976931348623157e+308,0,1e+17,1e-05,0.001,123456.5]"},
          new String[] {
            null,
            "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9} + {\"j\":10}",
            "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":9,\"j\":10}"
          },
          new String[] {
            null,
            "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8} * {\"i\":{\"x\":1}}",
            "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,\"g\":7,\"h\":8,\"i\":{\"x\":1}}"
          },
          new String[] {null, "{\"a\":{\"b\":1}} * {\"a\":{\"b\":{\"c\":2}}}", "{\"a\":{\"b\":{\"c\":2}}}"});

  @Test
  void eachEdgeGivesJqsResult() {
    for (String[] c : CASES) {
      Workspace workspace = ENGINE.newWorkspace();
      JqValue input = c[0] == null ? JqNull.NULL : read(workspace, c[0]).root();
      Results results = new Results();
      ENGINE.compile(c[1]).run(input, NONE, results, workspace);
      assertThat(results.values).as("results of %s", c[1]).singleElement().extracting(JqValue::toJson).isEqualTo(c[2]);
    }
  }
}
