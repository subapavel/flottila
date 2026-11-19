package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.NS;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The trace limit is configurable: it is 2 here, so an entry above two slots is capped and keeps
 * its totals.
 */
class TestRunTraceLimitTest {

  /** A trace limit of two. */
  private static final Calls CALLS = Calls.studio().traceLimit(2);

  private static JsonNode trace(String input) {
    Calls.Answer reply =
        CALLS.post("test", testRequest(TestRunTest.notesLink(), input), Workspace.mappings());
    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    return result.path("trace");
  }

  /**
   * Three Notes under a limit of 2: the Note box keeps the first two and its totals, three slots in
   * one context. Witness: two Notes, at the limit, are not capped.
   */
  @Test
  void theTraceLimitIsConfigured() {
    String two =
        "<src:Order xmlns:src=\"" + NS + "\"><OrderId>1</OrderId><Note>x</Note><Note>y</Note>";

    assertThat(trace(two + "</src:Order>").path("a2"))
        .isEqualTo(
            Wire.read(
                bytes(
                    """
                    {"kind": "queue", "contexts": [["x", "y"]], "capped": false,
                     "slotCount": 2, "contextCount": 1}
                    """)));

    JsonNode three = trace(two + "<Note>z</Note></src:Order>");

    assertThat(three.path("a2"))
        .isEqualTo(
            Wire.read(
                bytes(
                    """
                    {"kind": "queue", "contexts": [["x", "y"]], "capped": true,
                     "slotCount": 3, "contextCount": 1}
                    """)));
    assertThat(three.path("t2").path("capped").booleanValue()).as("%s", three).isTrue();
  }
}
