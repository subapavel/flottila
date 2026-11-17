package io.github.subapavel.jqj.value;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;

/** A jq array (ADR-0016): a document's, or one built at run time. */
public sealed interface JqArray extends JqValue permits ArrayView, BuiltArray {
  /** The number of elements. */
  int length();

  /** The element at {@code index}. */
  JqValue get(int index);
}
