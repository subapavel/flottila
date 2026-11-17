package dev.rail8.jq;

import dev.rail8.jq.internal.json.Printer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Sinks that write each result as UTF-8 JSON: one line per result, drained into the target before emit returns, never
 * flushed. {@code compact()} prints as jq's {@code -c}, {@code pretty()} as jq's default indent 2.
 */
public final class JsonWriter {
  private JsonWriter() {}

  /** As {@link #sink(Workspace, OutputStream, OutputOptions)}, over {@code Channels.newOutputStream(out)}. */
  public static JqSink sink(Workspace workspace, WritableByteChannel out, OutputOptions options) {
    return sink(workspace, Channels.newOutputStream(out), options);
  }

  /**
   * A sink that prints each result and one LF into {@code out}, charging one step per started KiB of each drain
   * and, in a run, the drain's bytes against its bytes-written budget before they are written. A target's IOException
   * leaves emit as an UncheckedIOException with it as the cause.
   *
   * @throws NullPointerException if {@code options} is null
   */
  public static JqSink sink(Workspace workspace, OutputStream out, OutputOptions options) {
    Objects.requireNonNull(options, "options");
    // OutputOptions has two instances: every options that is not compact() is pretty().
    boolean pretty = options != OutputOptions.compact();
    Printer printer = workspace.printer;
    Printer.Target target =
        (buffer, length) -> {
          // A drain that would pass the budget ends the run unwritten, so the output never exceeds it.
          workspace.state.chargeWritten(length);
          try {
            out.write(buffer, 0, length);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          // Outside a run these ticks count in the steps but not against the next run.
          workspace.state.tickWritten((length + 1023) >>> 10);
        };
    return v -> {
      printer.line(v, pretty, target);
      return true;
    };
  }
}
