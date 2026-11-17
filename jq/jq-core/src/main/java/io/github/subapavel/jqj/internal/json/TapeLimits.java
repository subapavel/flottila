package io.github.subapavel.jqj.internal.json;

/**
 * The largest legal sizes of the tape (ADR-0002, ADR-0015, F-21); one over a limit is a jq error. Only tests read
 * with other values than {@link #ADR_0002}.
 *
 * @param document bytes per document
 * @param string bytes per string, its quotes included
 * @param number bytes per number literal
 * @param container entries per container, nested ones and keys included
 */
public record TapeLimits(int document, int string, int number, int container) {

  /** 2^31-3 bytes (the largest {@code byte[]}, F-12), 2^28-1 bytes, 2^24-1 bytes and 2^28-1 entries. */
  public static final TapeLimits ADR_0002 = new TapeLimits(Integer.MAX_VALUE - 2, 0x0FFF_FFFF, 0x00FF_FFFF, 0x0FFF_FFFF);
}
