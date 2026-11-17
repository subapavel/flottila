package dev.rail8.jq.value;

import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.StringView;
import java.lang.foreign.MemorySegment;

/** A jq string: a document's, or one built at run time. */
public sealed interface JqString extends JqValue permits StringView, BuiltString {
  /** The string as a {@code String}. */
  String value();

  /**
   * The string's UTF-8 bytes, read-only. A slice of a view is valid only while its document is, and reading it later
   * is not checked: after the Workspace's next read it may show other bytes.
   */
  MemorySegment utf8();
}
