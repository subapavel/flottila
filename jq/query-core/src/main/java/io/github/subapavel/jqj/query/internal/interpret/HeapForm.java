package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.FilterException;

/**
 * The heap form of one activation of an op on the trampoline (GLOSSARY "Heap form", ADR-0046 and its #109 amendment):
 * what the op's push form keeps in its Java frame while a child runs or a result it emitted is answered, kept on the
 * heap. Each form lies on the form it was started under, {@link #under}: its own run answers there, and an error it
 * does not catch passes there, as the push form's return value and exception leave its frame for its caller's.
 */
abstract class HeapForm {
  /** The form this one was started under: where its run's answer and its uncaught errors go. */
  final HeapForm under;

  HeapForm(HeapForm under) {
    this.under = under;
  }

  /**
   * What this form waits for answered: the child it started ran out (true) or was stopped or broken out of (false), or
   * the result it emitted is answered to go on (true) or to stop (false). It sets the driver's next action.
   */
  abstract void resume(Trampoline t, boolean more);

  /**
   * An error raised at this form, by a form above it or by what runs downstream of a result it produced: it passes on
   * to {@link #under}, as through a push frame without a catch. A form that catches overrides it.
   */
  void fail(Trampoline t, FilterException e) {
    t.raise(under, e);
  }
}
