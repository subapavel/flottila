package io.github.subapavel.jqj.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Measures a thread's stack from inside it (F-88, #111): the frames of one small recursive method that fit before a
 * StackOverflowError. A thread's stack size cannot be read back from {@link Thread}, so a pin compares the frames on
 * the thread under test with the frames on a fresh thread of a known size. The method's frame changes size as the JIT
 * moves it from the interpreter to C1 and C2, at a round no warm-up fixes (F-113), so {@link #assertHolds} judges the
 * count only between two equal references, one taken just before it and one just after.
 */
public final class StackProbe {

  /** The brackets {@link #assertHolds} takes before it gives up. */
  static final int TRIES = 20;

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

  /**
   * Asserts that the frames {@code count} measures on the thread of {@code what} are at least four fifths of the frames
   * on a fresh thread of {@code stackBytes}: a thread of half that size holds about half as many. {@code count} runs
   * {@link #frames()} on the thread under test and gives its result; it is called once per bracket, as
   * {@link #assertHolds(IntSupplier, IntSupplier, String)} says.
   */
  public static void assertHolds(IntSupplier count, long stackBytes, String what) {
    assertHolds(() -> framesOn(stackBytes), count, what + ", against a fresh thread of " + stackBytes + " bytes");
  }

  /**
   * The judging rule (F-113). Each try takes a {@code reference}, then the {@code count}, then a second
   * {@code reference}. When the two references are equal, the count is judged against four fifths of them and the
   * assertion ends. When they differ, the JIT moved the probe between them, the count is not judged, and the next try
   * takes a new bracket. After {@value #TRIES} tries without two equal references the assertion fails with every
   * bracket it saw, as {@code [reference, count, reference]}.
   */
  static void assertHolds(IntSupplier reference, IntSupplier count, String what) {
    List<List<Integer>> seen = new ArrayList<>();
    for (int i = 1; i <= TRIES; i++) {
      int before = reference.getAsInt();
      int frames = count.getAsInt();
      int after = reference.getAsInt();
      seen.add(List.of(before, frames, after));
      if (before == after) {
        System.out.printf("StackProbe: %s: %d frames between two references of %d, try %d of %d%n", what, frames, before, i, TRIES);
        assertThat(frames)
            .as("frames of the probe on the thread of %s, between two references of %d; brackets seen: %s", what, before, seen)
            .isGreaterThanOrEqualTo(before / 5 * 4);
        return;
      }
    }
    throw new AssertionError(
        "frames of the probe on the thread of " + what + ": no two equal references in " + TRIES + " tries; brackets [reference, count, reference] seen: " + seen);
  }
}
