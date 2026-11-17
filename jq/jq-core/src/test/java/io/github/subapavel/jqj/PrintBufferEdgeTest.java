package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.assertStale;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqValue;
import org.junit.jupiter.api.Test;

/**
 * The 64 KiB print buffer's edge (ADR-0027, F-23): a separator that comes when the buffer is exactly full drains it
 * first, charges its KiB and checks the document again. Below the API only for the step counter {@code steps}.
 */
class PrintBufferEdgeTest {
  /** ADR-0027: the Workspace's print buffer. */
  private static final int BUFFER = 64 * 1024;

  /**
   * {@code ["a…a",1]} whose text up to the comma is exactly one buffer. jq-ref 1.8.2, observed 2026-11-11: jq-ref -c .
   * on these 65 539 bytes prints them and an LF (rc 0).
   */
  private static final String FULL_BEFORE_COMMA = "[\"" + "a".repeat(BUFFER - 3) + "\",1]";

  @Test
  void aSeparatorAfterAFullBufferDrainsItFirst() {
    Workspace workspace = newWorkspace();
    JsonDoc doc = read(workspace, FULL_BEFORE_COMMA);
    // F-23: one step per started KiB read, 65 539 bytes.
    assertThat(workspace.steps()).as("steps after the read").isEqualTo(65L);

    RecordingTarget target = new RecordingTarget();
    assertThat(JsonWriter.sink(workspace, target, OutputOptions.compact()).emit(doc.root())).as("emit's answer").isTrue();
    assertThat(target.text()).as("the writer's output").isEqualTo(FULL_BEFORE_COMMA + "\n");
    assertThat(target.writes).as("drains: the full buffer, then the comma, 1, ] and LF").isEqualTo(2);
    // F-23: one step per started KiB of each drain, 65 536 bytes and then 4.
    assertThat(workspace.steps()).as("steps after the read and the print").isEqualTo(65L + 64L + 1L);

    // The target reads another document during the drain of the full buffer: the comma is never written.
    RecordingTarget reading =
        new RecordingTarget(
            n -> {
              if (n == 1) read(workspace, "[7]");
            });
    JqValue root = read(workspace, FULL_BEFORE_COMMA).root();
    assertStale(() -> JsonWriter.sink(workspace, reading, OutputOptions.compact()).emit(root), "emit after its target read [7]");
    assertThat(reading.text()).as("bytes written before the document went stale").isEqualTo(FULL_BEFORE_COMMA.substring(0, BUFFER));
  }
}
