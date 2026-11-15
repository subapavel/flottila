package io.github.subapavel.jqj.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures a thread's stack from inside it (F-88, #111): the frames of one small recursive method that fit before a
 * StackOverflowError. A thread's stack size cannot be read back from {@link Thread}, so a pin compares the frames on
 * the thread under test with the frames on a fresh thread of a known size, after the method is warm, so both counts
 * are of the same compiled frame.
 */
public final class StackProbe {
  private StackProbe() {}

  /** Frames of {@link #down} that fit the current thread's stack from here. */
  public static int frames() {
    return down(0);
  }

  private static int down(int n) {
    try {
      return down(n + 1);
    } catch (StackOverflowError e) {
      return n;
    }
  }

  /** {@link #frames()} on a fresh thread of {@code stackBytes}. */
  public static int framesOn(long stackBytes) {
    AtomicInteger frames = new AtomicInteger(-1);
    Thread thread = new Thread(null, () -> frames.set(frames()), "probe-" + stackBytes, stackBytes);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    assertThat(frames.get()).as("frames on a fresh thread of %d bytes", stackBytes).isPositive();
    return frames.get();
  }

  /** Runs the probe often enough on a fresh thread of {@code stackBytes} that its frames stop changing. */
  public static void warm(long stackBytes) {
    for (int i = 0; i < 6; i++) {
      framesOn(stackBytes);
    }
  }

  /**
   * Asserts that {@code frames}, measured on the thread of {@code what}, are at least four fifths of the frames on a
   * fresh thread of {@code stackBytes}, measured now: a thread of half that size holds about half as many.
   */
  public static void assertHolds(int frames, long stackBytes, String what) {
    int reference = framesOn(stackBytes);
    assertThat(frames)
        .as("frames of the probe on the thread of %s, against %d on a fresh thread of %d bytes", what, reference, stackBytes)
        .isGreaterThanOrEqualTo(reference / 5 * 4);
  }
}
