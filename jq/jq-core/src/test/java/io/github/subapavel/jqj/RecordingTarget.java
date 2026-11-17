package io.github.subapavel.jqj;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A writer's target, the host's port (ADR-0027): keeps every byte it is given and counts the write and flush calls.
 * {@link #onWrite} runs at the start of each write call, as host code would.
 */
class RecordingTarget extends OutputStream {
  /** What the target does when written to; may throw, or read into a Workspace. */
  interface OnWrite {
    void call(int writeNumber) throws IOException;
  }

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final OnWrite onWrite;
  int writes;
  int flushes;

  RecordingTarget() {
    this(n -> {});
  }

  RecordingTarget(OnWrite onWrite) {
    this.onWrite = onWrite;
  }

  @Override
  public void write(int b) throws IOException {
    onWrite.call(++writes);
    bytes.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    onWrite.call(++writes);
    bytes.write(b, off, len);
  }

  @Override
  public void flush() {
    flushes++;
  }

  byte[] bytes() {
    return bytes.toByteArray();
  }

  String text() {
    return new String(bytes.toByteArray(), UTF_8);
  }
}
