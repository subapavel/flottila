package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.NS;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
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
 * The trace limit is configurable (ADR-0015, "Trace"; GLOSSARY "Trace limit"; #53): {@code
 * flottila.mapping.trace-limit} is 2 here, so an entry above two slots is capped and keeps its
 * totals. {@code TestRunResourceTest} runs under the default of 10,000.
 */
@QuarkusTest
@TestProfile(TestRunTraceLimitTest.LimitTwo.class)
class TestRunTraceLimitTest {

  /** A trace limit of two. */
  public static final class LimitTwo implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("flottila.mapping.trace-limit", "2");
    }
  }

  @TestHTTPResource URI root;

  private JsonNode trace(String input) {
    Http.Reply reply =
        new Http(root)
            .post(
                "api/mappings/testing-trace-limit-2/order/test",
                "application/json",
                bytes(testRequest(TestRunResourceTest.notesLink(), input)));
    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    return result.path("trace");
  }

  /**
   * Three Notes under a limit of 2: the Note box keeps the first two and its totals, three slots in
   * one context. Witness: two Notes, at the limit, are not capped.
   */
  @Test
  void theTraceLimitIsConfigured() {
    TempWorkspace.write("testing-trace-limit-2/order.mapping.json", corpusMapping());
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
