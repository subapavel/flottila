package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.json.JsonReader;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.json.TapeLimits;
import io.github.subapavel.jqj.query.run.RunState;
import java.lang.foreign.MemorySegment;

/**
 * The reusable state of one running evaluation at a time (ADR-0016): so far the reader and its reused tape, the
 * writer's print buffer and the run state.
 */
public final class Workspace {
  private final JsonReader reader = new JsonReader(TapeLimits.ADR_0002);

  /**
   * The run state (ADR-0030): a main read primes it, {@link JqFilter#run} begins and ends it, and the reader and the
   * writer charge it.
   */
  final RunState state = new RunState();

  /** The writer's printer and its 64 KiB buffer, shared by every sink over this Workspace; its proofs charge state. */
  final Printer printer = new Printer(state);

  Workspace() {}

  /**
   * Reads one document onto this Workspace's tape (ADR-0015), charging one step per started KiB. The previous
   * document of this Workspace goes stale as the read starts, also when the read then fails (ADR-0026). Outside a run
   * the read primes the run state, so the next run on this Workspace is charged its steps (ADR-0030).
   *
   * @throws JqSyntaxException when the bytes are not exactly one JSON document
   */
  public JsonDoc read(MemorySegment json) {
    // ADR-0015: the reset comes before the main document is read.
    state.prime();
    JsonDoc doc = new JsonDoc(reader.read(json));
    // The read passed the document limit, 2^31-3 bytes, so the KiB fit an int.
    tick((int) ((json.byteSize() + 1023) >>> 10));
    return doc;
  }

  /** The byte length of the decode scratch this Workspace's printer keeps after a result, at most 64 KiB (#35). */
  int retainedScratch() {
    return printer.retainedScratch();
  }

  /** The steps charged since the last main read outside a run, or since the last run began without one (F-23). */
  long steps() {
    return state.steps();
  }

  /**
   * Charges {@code steps} steps at once (ADR-0008 as amended by ADR-0015: the reader, per KiB). The writer charges its
   * own through {@code RunState.tickWritten}, which the next run does not keep from outside a run (ADR-0030).
   */
  void tick(int steps) {
    state.tick(steps);
  }
}
