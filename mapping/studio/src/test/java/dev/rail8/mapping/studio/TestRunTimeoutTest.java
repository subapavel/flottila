package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.NS;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A test run past its timeout is 200 with the runtime error {@code rail8:TIMEOUT} and no output.
 * The timeout is zero here, so any run that is not done at once exceeds it.
 */
class TestRunTimeoutTest {

  /** A test-run timeout of zero. */
  private static final Calls CALLS = Calls.studio().timeout(Duration.ZERO);

  @Test
  void aRunPastItsTimeoutIs200WithRail8TimeoutAndNoOutput() {
    // 200 000 unmapped elements: the parse alone takes far longer than no time at all
    String input =
        "<src:Order xmlns:src=\""
            + NS
            + "\"><OrderId>4711</OrderId>"
            + "<Note>n</Note>".repeat(200_000)
            + "</src:Order>";

    Calls.Answer reply =
        CALLS.post("test", testRequest(workingCopy("\"a1\""), input), Workspace.mappings());

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("error");
    assertThat(result.path("error").path("code").textValue()).isEqualTo("rail8:TIMEOUT");
    assertThat(result.path("error").path("message").textValue()).isNotBlank();
    // rail8:TIMEOUT has no authoring id: an absent member, never null
    assertThat(result.path("error").has("authoringId")).isFalse();
    assertThat(described(result.path("diagnostics"))).isEmpty();
    assertThat(result.has("output")).as("the buffered output is dropped").isFalse();
    // the trace so far, the snapshot taken at the timeout. A zero timeout falls while the input is
    // parsed, before any box is evaluated, so the snapshot is an empty object; a trace taken once
    // the run had finished would hold a1 and t1.
    JsonNode trace = result.path("trace");
    assertThat(trace.isObject()).as("the trace at timeout: %s", result).isTrue();
    assertThat(trace.size()).as("the trace at timeout: %s", result).isZero();
  }
}
