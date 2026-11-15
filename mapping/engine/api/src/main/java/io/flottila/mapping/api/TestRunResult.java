package io.flottila.mapping.api;

import io.flottila.mapping.ir.AuthoringId;
import java.util.Optional;

/**
 * The result of a test run (ADR-0014, ADR-0015): the target message, or the runtime error that
 * stopped the run, each with the run's trace (since #53).
 */
public sealed interface TestRunResult {

  /**
   * A run that wrote its target message.
   *
   * @param output the target message's bytes
   * @param trace the run's trace; empty for a compile without tracing
   */
  record Output(byte[] output, Trace trace) implements TestRunResult {}

  /**
   * A run that stopped with a runtime error; its buffered output is dropped (ADR-0006).
   *
   * @param code the error's QName, such as {@code po:MINOCCURS} or {@code flottila:TIMEOUT}
   * @param message what stopped the run, in English
   * @param authoringId where the error was raised (ADR-0015 since #52): the target field mapping
   *     for {@code po:MINOCCURS}, the function box for a context function's data error; empty for
   *     {@code flottila:TIMEOUT}
   * @param trace the trace up to the error, at a timeout the trace so far (ADR-0014 since #53);
   *     empty for a compile without tracing
   */
  record RuntimeError(String code, String message, Optional<AuthoringId> authoringId, Trace trace)
      implements TestRunResult {}
}
