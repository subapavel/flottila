package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.NS;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A test run past its timeout (ADR-0014, "Running"; ADR-0015, "Diagnostics and errors"; #54) is 200
 * with the runtime error {@code flottila:TIMEOUT} and no output. The timeout is configured to zero
 * here, so any run that is not done at once exceeds it; {@code TestRunResourceTest} runs under the
 * default of 10 s.
 */
@QuarkusTest
@TestProfile(TestRunTimeoutTest.ZeroTimeout.class)
class TestRunTimeoutTest {

  /** A test-run timeout of zero. */
  public static final class ZeroTimeout implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("flottila.mapping.test-timeout", "0s");
    }
  }

  @TestHTTPResource URI root;

  @Test
  void aRunPastItsTimeoutIs200WithFlottilaTimeoutAndNoOutput() {
    TempWorkspace.write("testing-timeout/order.mapping.json", corpusMapping());
    // 200 000 unmapped elements: the parse alone takes far longer than no time at all
    String input =
        "<src:Order xmlns:src=\""
            + NS
            + "\"><OrderId>4711</OrderId>"
            + "<Note>n</Note>".repeat(200_000)
            + "</src:Order>";

    Http.Reply reply =
        new Http(root)
            .post(
                "api/mappings/testing-timeout/order/test",
                "application/json",
                bytes(testRequest(workingCopy("\"a1\""), input)));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("error");
    assertThat(result.path("error").path("code").textValue()).isEqualTo("flottila:TIMEOUT");
    assertThat(result.path("error").path("message").textValue()).isNotBlank();
    // ADR-0015 since #52: flottila:TIMEOUT has no authoring id, an absent member, never null
    assertThat(result.path("error").has("authoringId")).isFalse();
    assertThat(described(result.path("diagnostics"))).isEmpty();
    assertThat(result.has("output")).as("the buffered output is dropped").isFalse();
    // ADR-0014 since #53: the trace so far, the snapshot taken at the timeout. A zero timeout
    // falls while the input is parsed, before any box is evaluated (ExecutableMapping.run), so the
    // snapshot is an empty object; a trace taken once the run had finished would hold a1 and t1.
    // EngineFacadeTraceTest's aTimeoutTraceIsAPrefixOfTheFullTrace has timeouts after the first
    // report.
    JsonNode trace = result.path("trace");
    assertThat(trace.isObject()).as("the trace at timeout: %s", result).isTrue();
    assertThat(trace.size()).as("the trace at timeout: %s", result).isZero();
  }
}
