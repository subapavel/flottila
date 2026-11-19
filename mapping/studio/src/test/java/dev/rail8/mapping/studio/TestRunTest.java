package dev.rail8.mapping.studio;

import static dev.rail8.mapping.studio.Fixtures.FINGERPRINT;
import static dev.rail8.mapping.studio.Fixtures.INPUT;
import static dev.rail8.mapping.studio.Fixtures.NOTE_AT_10_UTC;
import static dev.rail8.mapping.studio.Fixtures.NS;
import static dev.rail8.mapping.studio.Fixtures.OUTPUT;
import static dev.rail8.mapping.studio.Fixtures.bytes;
import static dev.rail8.mapping.studio.Fixtures.notesInTheRunsZone;
import static dev.rail8.mapping.studio.Fixtures.notesThrough;
import static dev.rail8.mapping.studio.Fixtures.workingCopy;
import static dev.rail8.mapping.studio.Wire.assertProblem;
import static dev.rail8.mapping.studio.Wire.described;
import static dev.rail8.mapping.studio.Wire.testRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code test} runs the working copy in the request: stages 1 to 3 answer 200 with diagnostics,
 * transport errors are problems.
 */
class TestRunTest {

  private static final Calls CALLS = Calls.studio();

  /** A test run whose bundle references are read against the corpus mapping's directory. */
  private static Calls.Answer test(String request) {
    return CALLS.post("test", request, Workspace.mappings());
  }

  /** 10 s unless configured. */
  @Test
  void theTestRunTimeoutIs10SecondsByDefault() {
    assertThat(Studio.TEST_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
  }

  /** Under a clock in UTC, a date with its offset is written as it is read, 10:00 Z. */
  @Test
  void aTestRunWritesADateInUtcByDefault() {
    Calls.Answer reply = test(testRequest(notesInTheRunsZone(), NOTE_AT_10_UTC));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    assertThat(result.path("output").textValue()).contains("<Note>10:00 Z</Note>");
  }

  @Test
  void testRunsAnUnsavedWorkingCopy() {
    Calls.Answer reply = test(testRequest(workingCopy("\"a1\""), INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("output");
    assertThat(result.path("output").textValue()).isEqualTo(OUTPUT);
    assertThat(described(result.path("diagnostics"))).isEmpty();
    // the trace of the working copy's box and target field mapping
    assertThat(names(result.path("trace"))).containsExactlyInAnyOrder("a1", "t1");
  }

  // --- the trace: built by the engine, translated by the studio

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
   * 1 and true and SUPPRESS for false, so t2 creates slots 1 and 3 and suppresses slot 2. On the
   * wire SUPPRESS is JSON null, a queue entry has kind queue and an occurrences entry kind
   * occurrences, and t2's unbounded max is an absent member.
   */
  @Test
  void aTestRunAnswersTheTraceKeyedByAuthoringId() {
    String input =
        "<src:Order xmlns:src=\""
            + NS
            + "\"><OrderId>4711</OrderId><Note>1</Note><Note>false</Note><Note>true</Note></src:Order>";

    Calls.Answer reply = test(testRequest(createIfNotes(), input));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).as("%s", result).isEqualTo("output");
    JsonNode trace = result.path("trace");
    assertThat(trace.path("c1").path("contexts").path(0).path(1).isNull())
        .as("SUPPRESS is JSON null: %s", trace.path("c1"))
        .isTrue();
    // the wire keeps the engine's first-report order, which JSON equality below ignores
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
   * A runtime error returns the trace up to the error. The empty source gives OrderId's box one
   * empty context, and t1's one parent slot creates 0, fewer than its min 1: the slot that raised
   * {@code po:MINOCCURS}, with no separate minOccurs record.
   */
  @Test
  void aMinOccursShortfallAnswersErrorWithItsTrace() {
    Calls.Answer reply =
        test(testRequest(workingCopy("\"a1\""), "<src:Order xmlns:src=\"" + NS + "\"/>"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
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
   * {@code Note <- c1}, the constant c1, is a generating function, and c1's entry is its single
   * value, kind atomic, of type {@code xs:string}.
   */
  @Test
  void aConstantsEntryIsItsAtomicValue() {
    String constant =
        notesThrough(
            "c1",
            """
            "c1": {"kind": "function", "fn": "constant", "params": {"value": "x"}}""");

    Calls.Answer reply = test(testRequest(constant, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
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
   * The trace limit is 10,000 unless configured. A box with 10,000 values keeps them all; one with
   * 10,001 keeps the first 10,000, is capped and keeps its totals.
   */
  @Test
  void theTraceLimitIs10000ByDefault() {
    JsonNode atTheLimit = test(testRequest(notesLink(), notes(10_000))).body().path("trace");
    JsonNode aboveIt = test(testRequest(notesLink(), notes(10_001))).body().path("trace");

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
   * A runtime error is 200 with {@code error {code, message, authoringId}} and no output. An empty
   * source under the 1..1 OrderId raises {@code po:MINOCCURS} at its target field mapping t1.
   */
  @Test
  void aMinOccursShortfallAnswersErrorWithItsAuthoringId() {
    // witness: with its OrderId the same working copy gives output
    assertThat(test(testRequest(workingCopy("\"a1\""), INPUT)).body().path("kind").textValue())
        .isEqualTo("output");

    Calls.Answer reply =
        test(testRequest(workingCopy("\"a1\""), "<src:Order xmlns:src=\"" + NS + "\"/>"));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("error");
    assertThat(result.path("error").path("code").textValue()).isEqualTo("po:MINOCCURS");
    assertThat(result.path("error").path("authoringId").textValue()).isEqualTo("t1");
    assertThat(result.path("error").path("message").textValue()).isNotBlank();
    assertThat(described(result.path("diagnostics"))).isEmpty();
    assertThat(result.has("output")).as("the buffered output is dropped").isFalse();
  }

  @Test
  void compileErrorsAre200WithDiagnosticsAndNoOutput() {
    Calls.Answer reply = test(testRequest(workingCopy("null"), INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error unconnected-target-field at id t1");
    assertThat(result.has("output")).isFalse();
    // a refused working copy never ran, so it has no trace
    assertThat(result.has("trace")).isFalse();
  }

  /**
   * A working copy whose stored fingerprints are not the ones its bundles give is refused, 200 with
   * {@code fingerprint-mismatch} at each side's fingerprint, and never runs.
   */
  @Test
  void aStaleFingerprintIs200RefusedWithFingerprintMismatch() {
    String stale = workingCopy("\"a1\"").replace(FINGERPRINT, "sha256:" + "0".repeat(64));
    assertThat(stale).as("both sides store the fingerprint").isNotEqualTo(workingCopy("\"a1\""));

    Calls.Answer reply = test(testRequest(stale, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
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
    String model = "{\"extra\": 1, " + workingCopy("\"a1\"").substring(1);

    Calls.Answer reply = test(testRequest(model, INPUT));

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("refused");
    assertThat(described(result.path("diagnostics")))
        .containsExactly("error unknown-member at pointer /extra");
    assertThat(result.has("output")).isFalse();
  }

  @Test
  void malformedInputIs400Problem() {
    String element = "<src:Order xmlns:src=\"" + NS + "\"><OrderId>4711</OrderId>";
    for (String input :
        List.of(
            element, // no end tag
            "<!DOCTYPE src:Order []>" + element + "</src:Order>")) {
      assertProblem(test(testRequest(workingCopy("\"a1\""), input)), 400, "malformed-input");
    }
  }

  @Test
  void duplicateKeyInModelIs400() {
    String model =
        workingCopy("\"a1\"")
            .replace("\"formatVersion\": 1}", "\"formatVersion\": 1, \"formatVersion\": 1}");

    assertProblem(test(testRequest(model, INPUT)), 400, "malformed-json");
  }

  // --- the output's bound

  /**
   * An Order whose OrderId the output writes in {@code bytes} bytes: its text is a CDATA section of
   * {@code <}, which the output writes as {@code &lt;}, four bytes for each, and {@code x} for the
   * rest.
   */
  private static String orderIdWrittenIn(long bytes) {
    int lt = Math.toIntExact(bytes / 4);
    return "<src:Order xmlns:src=\""
        + NS
        + "\"><OrderId><![CDATA["
        + "<".repeat(lt)
        + "x".repeat(Math.toIntExact(bytes - 4L * lt))
        + "]]></OrderId></src:Order>";
  }

  /**
   * A test run whose output would pass {@link Studio#MAX_OUTPUT_BYTES}, 16 MiB, is 200 with the
   * runtime error {@code rail8:OUTPUT-TOO-LARGE}: no authoring id, as an error of the test run
   * itself, and no output. The copy mapping writes the OrderId it reads, one byte above the bound
   * with the rest of the output, from an input a quarter of that size, so the call fits in a
   * request to the node. Witness: an OrderId whose output is exactly the bound gives that output.
   */
  @Test
  void aTestRunWhoseOutputPassesItsBoundIs200WithRail8OutputTooLarge() {
    long overhead = bytes(OUTPUT).length - "4711".length();
    String request =
        testRequest(
            workingCopy("\"a1\""), orderIdWrittenIn(Studio.MAX_OUTPUT_BYTES + 1 - overhead));
    assertThat(Base64.getEncoder().encodeToString(bytes(request)).length())
        .as("the request, in base64, is below the node's largest request")
        .isLessThan(16 << 20);

    // the answers hold strings of megabytes, so a description shows the answer only where it is a
    // problem: when the status is not 200
    Calls.Answer reply = test(request);

    assertThat(reply.status()).as("%s", reply).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("kind").textValue()).isEqualTo("error");
    JsonNode error = result.path("error");
    assertThat(error.path("code").textValue()).isEqualTo("rail8:OUTPUT-TOO-LARGE");
    assertThat(error.path("message").textValue())
        .isEqualTo("the test run's output is larger than 16777216 bytes");
    assertThat(error.has("authoringId")).as("an error of the test run itself").isFalse();
    assertThat(result.has("output")).as("the buffered output is dropped").isFalse();

    Calls.Answer atTheBound =
        test(
            testRequest(
                workingCopy("\"a1\""), orderIdWrittenIn(Studio.MAX_OUTPUT_BYTES - overhead)));

    assertThat(atTheBound.status()).as("%s", atTheBound).isEqualTo(200);
    JsonNode output = atTheBound.body();
    assertThat(output.path("kind").textValue()).isEqualTo("output");
    assertThat((long) bytes(output.path("output").textValue()).length)
        .isEqualTo(Studio.MAX_OUTPUT_BYTES);
  }
}
