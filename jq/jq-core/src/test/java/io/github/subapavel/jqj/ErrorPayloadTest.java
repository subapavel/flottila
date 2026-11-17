package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.RunBudgets.NONE;
import static io.github.subapavel.jqj.RunBudgets.bytesBuilt;
import static io.github.subapavel.jqj.RunBudgets.read;
import static io.github.subapavel.jqj.RunBudgets.unlimited;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.JqTermination.Cause;
import io.github.subapavel.jqj.RunBudgets.Results;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The detached error payload at the public API (ADR-0016, ADR-0039; design approval C2 and O1): an error raised with a
 * view of the input leaves {@code JqFilter.run} as a {@code JqFilterException} whose value no longer views the
 * document, so it still reads after the Workspace's next read, which makes every earlier view stale (StaleViewTest).
 * The detach charges nothing. The payloads are jq-ref 1.8.2's (diff/control.rec): control/error-view-payload prints
 * {@code {"b":1}}, control/error-view-string {@code from the input}, control/error-big-literal-view
 * {@code 100000000000000000000000001} and control/error-null-uncaught {@code null}.
 */
class ErrorPayloadTest {

  /** The exception {@code filter} raises on {@code json}, read into {@code workspace}. */
  private static JqFilterException raised(JqEngine engine, Workspace workspace, String filter, String json) {
    JqValue root = read(workspace, json.getBytes(UTF_8)).root();
    return assertThatExceptionOfType(JqFilterException.class)
        .as("%s on %s", filter, json)
        .isThrownBy(() -> engine.compile(filter).run(root, NONE, new Results(), workspace))
        .actual();
  }

  /** Reads a document over the same tape, so a view of the earlier one is stale. */
  private static void readAnother(Workspace workspace) {
    read(workspace, "[2,3,4,5,6,7,8,9]".getBytes(UTF_8));
  }

  @Test
  void aKeptResultViewIsStaleAfterTheNextRead() {
    // Witness: without a detach, a view kept past the next read throws STALE_VIEW.
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, "{\"a\":{\"b\":1}}".getBytes(UTF_8)).root();
    Results kept = new Results();
    assertThat(engine.compile(".a").run(root, NONE, kept, workspace)).as("run's answer for .a").isTrue();
    readAnother(workspace);
    JqValue a = kept.values.getFirst();
    assertThatExceptionOfType(JqTermination.class)
        .as("toJson() of a kept .a after the next read")
        .isThrownBy(a::toJson)
        .satisfies(t -> assertThat(t.cause()).as("cause").isEqualTo(Cause.STALE_VIEW));
  }

  @Test
  void aViewObjectPayloadReadsAfterTheNextRead() {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqFilterException e = raised(engine, workspace, ".a | error", "{\"a\":{\"b\":1}}");
    readAnother(workspace);
    JqObject payload = (JqObject) e.value();
    assertThat(payload.size()).as("size of the payload").isEqualTo(1);
    assertThat(((JqNumber) payload.get("b")).longValue()).as("payload.b").isEqualTo(1L);
    assertThat(payload.toJson()).as("the payload's text").isEqualTo("{\"b\":1}");
  }

  @Test
  void aNestedViewPayloadReadsAfterTheNextRead() {
    // A payload with an array and a non-ASCII string inside: every level is detached, not only the top.
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqFilterException e = raised(engine, workspace, ".a | error", "{\"a\":{\"k\":[1,\"é\",{\"z\":null}]}}");
    readAnother(workspace);
    JqArray k = (JqArray) ((JqObject) e.value()).get("k");
    assertThat(k.length()).as("length of payload.k").isEqualTo(3);
    assertThat(((JqString) k.get(1)).value()).as("payload.k[1]").isEqualTo("é");
    assertThat(((JqObject) k.get(2)).get("z")).as("payload.k[2].z").isEqualTo(JqNull.NULL);
  }

  @Test
  void aViewStringPayloadReadsAfterTheNextRead() {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqFilterException e = raised(engine, workspace, ".a | error", "{\"a\":\"from the input\"}");
    readAnother(workspace);
    assertThat(((JqString) e.value()).value()).as("the payload string").isEqualTo("from the input");
  }

  @Test
  void aViewNumberPayloadKeepsItsLiteralAfterTheNextRead() {
    // A number that does not fit a double keeps its digits (ADR-0012).
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqFilterException e = raised(engine, workspace, ".a | error", "{\"a\":100000000000000000000000001}");
    readAnother(workspace);
    assertThat(e.value().toString()).as("the payload number").isEqualTo("100000000000000000000000001");
  }

  @Test
  void aNullPayloadIsJqNull() {
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    assertThat(raised(engine, workspace, "error(null)", "1").value()).as("the payload of error(null)").isEqualTo(JqNull.NULL);
  }

  @Test
  void theDetachChargesNoBytesBuilt() {
    // ADR-0039: the detach is at no charge. Under 0 bytes built the run ends with the error, not with BYTES_BUILT.
    JqEngine engine = bytesBuilt(0);
    Workspace workspace = engine.newWorkspace();
    JqFilterException e = raised(engine, workspace, ".a | error", "{\"a\":{\"b\":[1,2]}}");
    readAnother(workspace);
    assertThat(e.value().toJson()).as("the payload's text").isEqualTo("{\"b\":[1,2]}");
  }

  @Test
  void aCaughtViewPayloadIsTheHandlerInput() {
    // jq-ref: control/error-view-caught, try (.a | error) catch .b on {"a":{"b":[1,2]}} gives [1,2].
    JqEngine engine = unlimited();
    Workspace workspace = engine.newWorkspace();
    JqValue root = read(workspace, "{\"a\":{\"b\":[1,2]}}".getBytes(UTF_8)).root();
    Results all = new Results();
    assertThat(engine.compile("try (.a | error) catch .b").run(root, NONE, all, workspace)).as("run's answer").isTrue();
    assertThat(all.values.stream().map(JqValue::toJson).toList()).as("results").containsExactly("[1,2]");
  }
}
