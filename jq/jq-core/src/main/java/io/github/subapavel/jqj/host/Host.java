package io.github.subapavel.jqj.host;

/** What the engine may reach outside itself (ADR-0017). Only the sandbox exists so far. */
public final class Host {
  private static final Host SANDBOXED = new Host();

  private Host() {}

  /** The default host: no environment, no modules, debug output discarded (ADR-0017). */
  public static Host sandboxed() {
    return SANDBOXED;
  }
}
