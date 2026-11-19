package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;

/**
 * Where an op's path variant on the trampoline sends its results: a {@code PathSink}'s heap form, as {@link HeapSink} is
 * a {@code Sink}'s. It takes one result and its path, null when untracked, with the form that produced it.
 */
@FunctionalInterface
interface HeapPathSink {
  /** Takes the result {@code v} at {@code p} of the form {@code under}: it starts what runs on it, or answers {@code under}. */
  void accept(Trampoline t, HeapForm under, Object v, Path p);
}
