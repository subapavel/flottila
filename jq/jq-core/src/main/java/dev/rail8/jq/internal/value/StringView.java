package dev.rail8.jq.internal.value;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.internal.json.JsonStrings;
import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.value.JqString;
import java.lang.foreign.MemorySegment;

/**
 * A string of a document: its document and tape index. Public only so the kind can permit it. A tag-6 string is decoded
 * at each access and never stored.
 */
public record StringView(Document doc, int index) implements JqString {

  /** jq's compact text. */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public String value() {
    long entry = doc.live()[index];
    return text(doc.bytes, entry);
  }

  /** Tag 5: a read-only slice of the document's bytes, without a copy; tag 6: a read-only decoded copy. */
  @Override
  public MemorySegment utf8() {
    long entry = doc.live()[index];
    if (Tape.tag(entry) == Tape.STRING) {
      return MemorySegment.ofArray(doc.bytes).asSlice(Tape.off(entry) + 1, Tape.span(entry) - 2).asReadOnly();
    }
    return MemorySegment.ofArray(JsonStrings.decode(doc.bytes, entry)).asReadOnly();
  }

  /** The text of a string entry, a key included: its decoded bytes (a tag-5 string's are its bytes). */
  static String text(byte[] bytes, long entry) {
    return new String(JsonStrings.decode(bytes, entry), UTF_8);
  }
}
