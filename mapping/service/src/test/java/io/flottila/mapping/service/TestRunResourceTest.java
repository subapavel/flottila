package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.FINGERPRINT;
import static io.flottila.mapping.service.Fixtures.INPUT;
import static io.flottila.mapping.service.Fixtures.NOTE_AT_10_UTC;
import static io.flottila.mapping.service.Fixtures.NS;
import static io.flottila.mapping.service.Fixtures.OUTPUT;
import static io.flottila.mapping.service.Fixtures.assertBytes;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.notesInTheRunsZone;
import static io.flottila.mapping.service.Fixtures.notesThrough;
import static io.flottila.mapping.service.Fixtures.unwired;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.assertProblem;
import static io.flottila.mapping.service.Wire.described;
import static io.flottila.mapping.service.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/mappings/{id}/test} runs the working copy in the body, never the stored file
 * (ADR-0015): stages 1 to 3 answer 200 with diagnostics, transport errors are problems.
 */
@QuarkusTest
class TestRunResourceTest {

  @TestHTTPResource URI root;

  @Inject Config config;

  private Http.Reply test(String id, String request) {
    return new Http(root).post("api/mappings/" + id + "/test", "application/json", bytes(request));
  }

  /** ADR-0014, "Running": 10 s unless configured; {@code TestRunTimeoutTest} configures zero. */
  @Test
  void theTestRunTimeoutIs10SecondsByDefault() {
    assertThat(config.getValue("flottila.mapping.test-timeout", Duration.class))
        .isEqualTo(Duration.ofSeconds(10));
  }

  /**
   * #80: {@code flottila.mapping.time-zone} is UTC unless configured, so a date with its offset is
   * written as it is read, 10:00 Z; {@code TestRunTimeZoneTest} configures +01:00.
   */
  @Test
  void aTestRunWritesADateInUtcByDefault() {
    TempWorkspace.write("testing-utc/order.mapping.json", corpusMapping());

    Http.Reply reply = test("testing-utc/order", testRequest(notesInTheRunsZone(), NOTE_AT_10_UTC));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    assertThat(result.path("output").textValue()).contains("<Note>10:00 Z</Note>");
  }

  @Test
  void testRunsAnUnsavedWorkingCopy() {
    // the disk copy is unconnected; only the body wires the field
    TempWorkspace.write("testing/order.mapping.json", unwired());

    Http.Reply reply = test("testing/order", testRequest(workingCopy("\"a1\""), INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("output");
    assertThat(result.path("output").textValue()).isEqualTo(OUTPUT);
    assertThat(described(result.path("diagnostics"))).isEmpty();
    // since #53 the trace of the working copy's box and target field mapping (ADR-0015, "Trace")
    assertThat(names(result.path("trace"))).containsExactlyInAnyOrder("a1", "t1");
    assertBytes(TempWorkspace.read("testing/order.mapping.json"), unwired());
  }

  // --- the trace (ADR-0015 "Trace" since #53): built by the engine, translated by the service

  /** The member names of a JSON object. */
  private static List<String> names(JsonNode object) {
    assertThat(object.isObject()).as("an object: %s", object).isTrue();
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** {@code Note <- c1}, {@code c1} createIf over {@code a2}. */
  private static String createIfNotes() {
    return notesThrough(
        "c1",
        """
        "c1": {"kind": "function", "fn": "createIf", "inputs": ["a2"]},
        "a2": {"kind": "source", "field": "NOTE"}""");
  }

  /** {@code Note <- a2}: the source Notes as they are. */
  static String notesLink() {
    return notesThrough("a2", "\"a2\": {\"kind\": \"source\", \"field\": \"NOTE\"}");
  }

  /**
   * OrderId 4711 and the Notes 1, false, true, all in the root's one context: createIf gives "" for
   * 1 and true and SUPPRESS for false (#2 §4; the second Note was x before #133, on which PO's
   * createIf ends the run, F-37), so t2 creates slots 1 and 3 and suppresses slot 2. On the wire
   * SUPPRESS is JSON null, a queue entry has kind queue and an occurrences entry kind occurrences,
   * and t2's unbounded max is an absent member (ADR-0015, ADR-0024).
   */
  @Test
  void aTestRunAnswersTheTraceKeyedByAuthoringId() {
    TempWorkspace.write("testing-trace/order.mapping.json", corpusMapping());
    String input =
        "<src:Order xmlns:src=\""
            + NS
            + "\"><OrderId>4711</OrderId><Note>1</Note><Note>false</Note><Note>true</Note></src:Order>";

    Http.Reply reply = test("testing-trace/order", testRequest(createIfNotes(), input));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    JsonNode trace = result.path("trace");
    assertThat(trace.path("c1").path("contexts").path(0).path(1).isNull())
        .as("SUPPRESS is JSON null: %s", trace.path("c1"))
        .isTrue();
    // the wire keeps the engine's first-report order, which JSON equality below ignores (#53)
    assertThat(names(trace)).as("first-report order").containsExactly("a1", "t1", "a2", "c1", "t2");
    assertThat(trace)
        .isEqualTo(
            Wire.read(
                bytes(
                    """
                    {"a1": {"kind": "queue", "contexts": [["4711"]], "capped": false,
                            "slotCount": 1, "contextCount": 1},
                     "t1": {"kind": "occurrences", "min": 1, "max": 1, "capped": false,
                            "parents": [{"parentSlot": 1, "created": 1,
                                         "slots": [{"slot": 1, "status": "created"}]}]},
                     "a2": {"kind": "queue", "contexts": [["1", "false", "true"]], "capped": false,
                            "slotCount": 3, "contextCount": 1},
                     "c1": {"kind": "queue", "contexts": [["", null, ""]], "capped": false,
                            "slotCount": 3, "contextCount": 1},
                     "t2": {"kind": "occurrences", "min": 0, "capped": false,
                            "parents": [{"parentSlot": 1, "created": 2,
                                         "slots": [{"slot": 1, "status": "created"},
                                                   {"slot": 2, "status": "suppressed"},
                                                   {"slot": 3, "status": "created"}]}]}}
                    """)));
  }

  /**
   * ADR-0015: a runtime error returns the trace up to the error. The empty source gives OrderId's
   * box one empty context, and t1's one parent slot creates 0, fewer than its min 1: the slot that
   * raised {@code po:MINOCCURS}, with no separate minOccurs record.
   */
  @Test
  void aMinOccursShortfallAnswersErrorWithItsTrace() {
    TempWorkspace.write("testing-trace-minoccurs/order.mapping.json", corpusMapping());

    Http.Reply reply =
        test(
            "testing-trace-minoccurs/order",
            testRequest(workingCopy("\"a1\""), "<src:Order xmlns:src=\"" + NS + "\"/>"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("error");
    assertThat(result.path("error").path("code").textValue()).isEqualTo("po:MINOCCURS");
    assertThat(result.path("trace"))
        .isEqualTo(
            Wire.read(
                bytes(
                    """
                    {"a1": {"kind": "queue", "contexts": [[]], "capped": false,
                            "slotCount": 0, "contextCount": 1},
                     "t1": {"kind": "occurrences", "min": 1, "max": 1, "capped": false,
                            "parents": [{"parentSlot": 1, "created": 0, "slots": []}]}}
                    """)));
  }

  /**
   * ADR-0015 {@code {atomic: {type, value}}} since #83: {@code Note <- c1}, the constant c1, is a
   * generating function, and c1's entry is its single value, kind atomic, of type {@code xs:string}
   * (ADR-0004).
   */
  @Test
  void aConstantsEntryIsItsAtomicValue() {
    TempWorkspace.write("testing-trace-atomic/order.mapping.json", corpusMapping());
    String constant =
        notesThrough(
            "c1",
            """
            "c1": {"kind": "function", "fn": "constant", "params": {"value": "x"}}""");

    Http.Reply reply = test("testing-trace-atomic/order", testRequest(constant, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    assertThat(result.path("trace").path("c1"))
        .isEqualTo(
            Wire.read(bytes("{\"kind\": \"atomic\", \"type\": \"xs:string\", \"value\": \"x\"}")));
  }

  /** {@code OrderId 4711} and the Notes {@code n1} … {@code n<count>}. */
  static String notes(int count) {
    StringBuilder input =
        new StringBuilder("<src:Order xmlns:src=\"" + NS + "\"><OrderId>4711</OrderId>");
    for (int n = 1; n <= count; n++) {
      input.append("<Note>n").append(n).append("</Note>");
    }
    return input.append("</src:Order>").toString();
  }

  /**
   * ADR-0015 and the ticket: the trace limit is 10,000 unless configured. A box with 10,000 values
   * keeps them all; one with 10,001 keeps the first 10,000, is capped and keeps its totals.
   */
  @Test
  void theTraceLimitIs10000ByDefault() {
    TempWorkspace.write("testing-trace-limit/order.mapping.json", corpusMapping());

    JsonNode atTheLimit =
        test("testing-trace-limit/order", testRequest(notesLink(), notes(10_000)))
            .json()
            .path("trace");
    JsonNode aboveIt =
        test("testing-trace-limit/order", testRequest(notesLink(), notes(10_001)))
            .json()
            .path("trace");

    assertThat(atTheLimit.path("a2").path("capped").booleanValue()).isFalse();
    assertThat(atTheLimit.path("a2").path("contexts").path(0).size()).isEqualTo(10_000);
    assertThat(atTheLimit.path("t2").path("capped").booleanValue()).isFalse();
    assertThat(aboveIt.path("a2").path("capped").booleanValue()).as("%s", "a2").isTrue();
    assertThat(aboveIt.path("a2").path("slotCount").intValue()).isEqualTo(10_001);
    assertThat(aboveIt.path("a2").path("contextCount").intValue()).isEqualTo(1);
    assertThat(aboveIt.path("a2").path("contexts").size()).isEqualTo(1);
    assertThat(aboveIt.path("a2").path("contexts").path(0).size()).isEqualTo(10_000);
    assertThat(aboveIt.path("a2").path("contexts").path(0).path(9_999).textValue())
        .isEqualTo("n10000");
    assertThat(aboveIt.path("t2").path("capped").booleanValue()).as("%s", "t2").isTrue();
  }

  /**
   * ADR-0015 since #52 (O2): a runtime error is 200 with {@code error {code, message, authoringId}}
   * and no output. An empty source under the 1..1 OrderId raises {@code po:MINOCCURS} at its target
   * field mapping t1.
   */
  @Test
  void aMinOccursShortfallAnswersErrorWithItsAuthoringId() {
    TempWorkspace.write("testing-minoccurs/order.mapping.json", corpusMapping());
    // witness: with its OrderId the same working copy gives output
    assertThat(
            test("testing-minoccurs/order", testRequest(workingCopy("\"a1\""), INPUT))
                .json()
                .path("kind")
                .textValue())
        .isEqualTo("output");

    Http.Reply reply =
        test(
            "testing-minoccurs/order",
            testRequest(workingCopy("\"a1\""), "<src:Order xmlns:src=\"" + NS + "\"/>"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("error");
    assertThat(result.path("error").path("code").textValue()).isEqualTo("po:MINOCCURS");
    assertThat(result.path("error").path("authoringId").textValue()).isEqualTo("t1");
    assertThat(result.path("error").path("message").textValue()).isNotBlank();
    assertThat(described(result.path("diagnostics"))).isEmpty();
    assertThat(result.has("output")).as("the buffered output is dropped").isFalse();
  }

  @Test
  void compileErrorsAre200WithDiagnosticsAndNoOutput() {
    // the disk copy is wired: a run of it would give output
    TempWorkspace.write("testing-stage3/order.mapping.json", corpusMapping());

    Http.Reply reply = test("testing-stage3/order", testRequest(workingCopy("null"), INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error unconnected-target-field at id t1");
    assertThat(result.has("output")).isFalse();
    // a refused working copy never ran, so it has no trace (#53)
    assertThat(result.has("trace")).isFalse();
  }

  /**
   * #91 (ADR-0009 as amended on 2026-11-05): a working copy whose stored fingerprints are not the
   * ones its bundles give is refused, 200 with {@code fingerprint-mismatch} at each side's
   * fingerprint, and never runs.
   */
  @Test
  void aStaleFingerprintIs200RefusedWithFingerprintMismatch() {
    TempWorkspace.write("testing-fingerprint/order.mapping.json", corpusMapping());
    String stale = workingCopy("\"a1\"").replace(FINGERPRINT, "sha256:" + "0".repeat(64));
    assertThat(stale).as("both sides store the fingerprint").isNotEqualTo(workingCopy("\"a1\""));

    Http.Reply reply = test("testing-fingerprint/order", testRequest(stale, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly(
            "error fingerprint-mismatch at pointer /source/fingerprint",
            "error fingerprint-mismatch at pointer /target/fingerprint");
    assertThat(result.has("output")).isFalse();
    assertThat(result.has("trace")).isFalse();
  }

  @Test
  void stage1ErrorsAre200Refused() {
    TempWorkspace.write("testing-stage1/order.mapping.json", corpusMapping());
    String model = "{\"extra\": 1, " + workingCopy("\"a1\"").substring(1);

    Http.Reply reply = test("testing-stage1/order", testRequest(model, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.json();
    assertThat(result.path("kind").textValue()).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error unknown-member at pointer /extra");
    assertThat(result.has("output")).isFalse();
  }

  @Test
  void malformedInputIs400Problem() {
    TempWorkspace.write("testing-input/order.mapping.json", corpusMapping());
    String element = "<src:Order xmlns:src=\"" + NS + "\"><OrderId>4711</OrderId>";
    for (String input :
        List.of(
            element, // no end tag
            "<!DOCTYPE src:Order []>" + element + "</src:Order>")) {
      assertProblem(
          test("testing-input/order", testRequest(workingCopy("\"a1\""), input)),
          400,
          "malformed-input");
    }
  }

  @Test
  void duplicateKeyInModelIs400() {
    TempWorkspace.write("testing-duplicate/order.mapping.json", corpusMapping());
    String model =
        workingCopy("\"a1\"")
            .replace("\"formatVersion\": 1}", "\"formatVersion\": 1, \"formatVersion\": 1}");

    assertProblem(
        test("testing-duplicate/order", testRequest(model, INPUT)), 400, "malformed-json");
  }
}
