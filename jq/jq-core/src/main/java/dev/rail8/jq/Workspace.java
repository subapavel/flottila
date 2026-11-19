package dev.rail8.jq;

import dev.rail8.jq.internal.json.JsonReader;
import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.internal.json.TapeLimits;
import dev.rail8.jq.internal.op.Kept;
import dev.rail8.jq.query.run.RunState;
import java.lang.foreign.MemorySegment;

/**
 * The reusable state of one running evaluation at a time: the reader and its reused tape, the writer's print
 * buffer, the run state, the regular expressions its runs compiled and the clock and zone of the running run.
 */
public final class Workspace {
  private final JsonReader reader = new JsonReader(TapeLimits.ADR_0002);

  /**
   * The run state: a main read primes it, {@link JqFilter#run} begins and ends it, and the reader and the
   * writer charge it.
   */
  final RunState state = new RunState();

  /** The writer's printer and its 64 KiB buffer, shared by every sink over this Workspace; its proofs charge state. */
  final Printer printer = new Printer(state);

  /** What the run state keeps across this Workspace's runs: their compiled regular expressions, the running run's host. */
  final Kept kept = new Kept();

  Workspace() {
    state.keep(kept);
  }

  /**
   * Reads one document onto this Workspace's tape, charging one step per started KiB. The previous document of this
   * Workspace goes stale as the read starts, also when the read then fails. Outside a run the read primes the run
   * state, so the next run on this Workspace is charged its steps.
   *
   * @throws JqSyntaxException when the bytes are not exactly one JSON document
   */
  public JsonDoc read(MemorySegment json) {
    // The reset comes before the main document is read, so the read's steps count in the next run.
    state.prime();
    JsonDoc doc = new JsonDoc(reader.read(json));
    // The read passed the document limit, 2^31-3 bytes, so the KiB fit an int.
    tick((int) ((json.byteSize() + 1023) >>> 10));
    return doc;
  }

  /** The byte length of the decode scratch this Workspace's printer keeps after a result, at most 64 KiB. */
  int retainedScratch() {
    return printer.retainedScratch();
  }

  /** The steps charged since the last main read outside a run, or since the last run began without one. */
  long steps() {
    return state.steps();
  }

  /**
   * Charges {@code steps} steps at once: the reader's, one per KiB. The writer charges its own through
   * {@code RunState.tickWritten}, which the next run does not keep from outside a run.
   */
  void tick(int steps) {
    state.tick(steps);
  }
}
