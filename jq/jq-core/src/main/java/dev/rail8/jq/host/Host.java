package dev.rail8.jq.host;

/** What the engine may reach outside itself. The sandbox is the only host. */
public final class Host {
  private static final Host SANDBOXED = new Host();

  private Host() {}

  /** The default host: no environment, no modules, debug output discarded. */
  public static Host sandboxed() {
    return SANDBOXED;
  }
}
