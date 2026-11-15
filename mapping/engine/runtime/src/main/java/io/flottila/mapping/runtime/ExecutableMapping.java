package io.flottila.mapping.runtime;

import io.flottila.mapping.serializer.XmlSerializer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;

/**
 * A compiled mapping: the projection plan and the tree of executable nodes (ADR-0021). It is
 * immutable, and each run has a frame of its own.
 */
public final class ExecutableMapping {

  private final Projection projection;
  private final int rootSlot;
  private final Node.OfContent root;
  private final int integerSlotCount;
  private final int stringSlotCount;
  private final int targetQueueCount;

  ExecutableMapping(
      Projection projection,
      int rootSlot,
      Node.OfContent root,
      int integerSlotCount,
      int stringSlotCount,
      int targetQueueCount) {
    this.projection = projection;
    this.rootSlot = rootSlot;
    this.root = root;
    this.integerSlotCount = integerSlotCount;
    this.stringSlotCount = stringSlotCount;
    this.targetQueueCount = targetQueueCount;
  }

  /**
   * One mapping run: reads the source queues from {@code in} in one pass, then writes the target
   * message to {@code out}, its root created once with slot number 1 (ADR-0006). Nothing is written
   * before the whole input has been read. An error after output was written leaves {@code out}
   * undefined; a caller that needs all or nothing buffers it.
   *
   * @param tracer receives the reports of a traced compile; an untraced compile never calls it
   * @param now the time the run starts at, fixed for the whole run: its offset is the run's time
   *     zone, in which a date without an offset is read and every date is written (#80)
   * @throws MalformedInputException when the input is not well-formed in the mapping's input format
   * @throws IOException when {@code out} cannot be written
   * @throws MappingRunException when the run meets a runtime error in its data (ADR-0015)
   * @throws java.util.concurrent.CancellationException when the thread is interrupted
   */
  public void run(InputStream in, OutputStream out, Tracer tracer, OffsetDateTime now)
      throws MalformedInputException, IOException {
    Frame frame =
        new Frame(
            projection.read(in),
            tracer,
            new XmlSerializer(out),
            integerSlotCount,
            stringSlotCount,
            targetQueueCount,
            now);
    frame.integers[rootSlot] = 1;
    frame.out.startDocument();
    root.write(frame);
    frame.out.endDocument();
  }
}
