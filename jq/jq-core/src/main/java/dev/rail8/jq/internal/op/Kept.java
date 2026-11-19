package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.run.RunState;
import java.time.InstantSource;
import java.time.ZoneId;

/**
 * What a Workspace's run state keeps across its runs: the regular expressions they compiled, and the clock and zone
 * of the running run's host.
 */
public final class Kept {
  private final Patterns patterns = new Patterns();
  private final HostTime time = new HostTime();

  /** The Kept of {@code state}: the one it keeps, or a new one it keeps from now on. */
  static Kept of(RunState state) {
    if (state.kept() instanceof Kept kept) {
      return kept;
    }
    Kept kept = new Kept();
    state.keep(kept);
    return kept;
  }

  /**
   * A run begins: each of its searches may take at most {@code matchTimeNanos}, {@code now} reads {@code clock} and
   * the zone readers read {@code zone}.
   */
  public void begin(long matchTimeNanos, InstantSource clock, ZoneId zone) {
    patterns.begin(matchTimeNanos);
    time.begin(clock, zone);
  }

  /** The run ends: the pattern value of its latest lookup is no longer held. */
  public void end() {
    patterns.end();
  }

  Patterns patterns() {
    return patterns;
  }

  HostTime time() {
    return time;
  }
}
