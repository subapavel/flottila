package dev.rail8.jq.host;

import java.time.InstantSource;
import java.time.ZoneId;
import java.util.Objects;

/**
 * What the engine may reach outside itself: the clock that {@code now} reads and the zone of the date builtins that
 * read the host zone ({@code localtime}, {@code strflocaltime}, and {@code strftime} and {@code strptime} for
 * {@code %s}). Immutable and thread-safe.
 */
public final class Host {
  /** The zone of a host that sets none. */
  private static final ZoneId UTC = ZoneId.of("UTC");

  private static final Host SANDBOXED = new Host(InstantSource.system(), UTC);

  private final InstantSource clock;
  private final ZoneId zone;

  private Host(InstantSource clock, ZoneId zone) {
    this.clock = clock;
    this.zone = zone;
  }

  /** The default host: the system clock, the zone UTC, no environment, no modules, debug output discarded. */
  public static Host sandboxed() {
    return SANDBOXED;
  }

  /** A builder that starts from {@link #sandboxed()}. */
  public static Builder builder() {
    return new Builder();
  }

  /** The clock {@code now} reads. */
  public InstantSource clock() {
    return clock;
  }

  /** The zone of the builtins that read the host zone. */
  public ZoneId zone() {
    return zone;
  }

  /** Builds a {@link Host}. */
  public static final class Builder {
    private InstantSource clock = SANDBOXED.clock;
    private ZoneId zone = SANDBOXED.zone;

    private Builder() {}

    /**
     * Sets the clock {@code now} reads, the system clock by default. A fixed clock makes {@code now} reproducible; the
     * deadline does not read it.
     *
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder clock(InstantSource clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Sets the zone of the builtins that read the host zone, UTC by default.
     *
     * @throws NullPointerException if {@code zone} is null
     */
    public Builder zone(ZoneId zone) {
      this.zone = Objects.requireNonNull(zone, "zone");
      return this;
    }

    public Host build() {
      return new Host(clock, zone);
    }
  }
}
