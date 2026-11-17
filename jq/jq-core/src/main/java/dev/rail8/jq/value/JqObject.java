package dev.rail8.jq.value;

import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.internal.value.ObjectView;
import java.util.function.BiConsumer;

/** A jq object (ADR-0016): a document's, or one built at run time. */
public sealed interface JqObject extends JqValue permits ObjectView, BuiltObject {
  /** The number of keys. */
  int size();

  /** The value at {@code key}. */
  JqValue get(String key);

  /** Each key and its value, in jq's key order. */
  void forEach(BiConsumer<? super String, ? super JqValue> action);
}
