package io.github.subapavel.jqj.oracle.diff;

import java.util.Arrays;

/** What one run of a case ends with: the exit code and the bytes of stdout and stderr. */
public record Outcome(int rc, byte[] stdout, byte[] stderr) {

  /** True if both runs gave the same exit code and the same bytes on both streams. */
  public boolean sameAs(Outcome other) {
    return rc == other.rc && Arrays.equals(stdout, other.stdout) && Arrays.equals(stderr, other.stderr);
  }
}
