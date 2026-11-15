package io.flottila.mapping.xdm;

import java.util.concurrent.CancellationException;

/** The interruption check of every loop over contexts or slots of the queue algebra (ADR-0021). */
final class Interrupts {

  private Interrupts() {}

  /**
   * Stops the caller when its thread is interrupted.
   *
   * @param what the function or step the loop belongs to, which the message names
   * @throws CancellationException "{@code <what>} was running when the thread was interrupted"
   */
  static void check(String what) {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException(what + " was running when the thread was interrupted");
    }
  }
}
