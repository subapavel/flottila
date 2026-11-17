package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;

/**
 * An object whose values {@code .name} and {@code .[k]} read by key (ADR-0034): a document's or a built one, or the
 * walk of a document's (ADR-0044). A switch over the kinds takes them with one case, as it takes {@link Members}.
 */
public sealed interface Keyed permits ObjectView, BuiltObject, ObjectWalk {
  /** The value of {@code key}, or Java {@code null} when there is none; it charges one tick per key it compares. */
  JqValue get(RunState state, String key);

  /** The key at {@code cursor}, a cursor of its {@link Members} walk, which is not -1; a view's is a tape index. */
  JqString key(int cursor);
}
