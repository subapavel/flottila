package dev.rail8.jq.query.internal.interpret;

/**
 * Where an op on the trampoline sends its results: a push {@code Sink}'s heap form. It takes one result with the form
 * that produced it, which the result's answer and every error raised downstream of it go back to, and sets the
 * driver's next action instead of returning an answer.
 */
@FunctionalInterface
interface HeapSink {
  /** Takes the result {@code v} of the form {@code under}: it starts what runs on it, or answers {@code under}. */
  void accept(Trampoline t, HeapForm under, Object v);
}
