package io.github.subapavel.jqj;

import io.github.subapavel.jqj.internal.value.Document;
import io.github.subapavel.jqj.value.JqValue;

/** A document that was read (ADR-0016). Closing it makes its views stale (ADR-0026). */
public final class JsonDoc implements AutoCloseable {
  private final Document document;

  JsonDoc(Document document) {
    this.document = document;
  }

  /**
   * The document's root value, the run input.
   *
   * @throws JqTermination with cause {@code STALE_VIEW} once the document is closed or superseded
   */
  public JqValue root() {
    return document.root();
  }

  /** Makes the document and its views stale; closing it again, or after a later read, does nothing more. */
  @Override
  public void close() {
    document.drop();
  }
}
