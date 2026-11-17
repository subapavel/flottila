package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqValue;
import java.util.function.BiConsumer;

/**
 * An object of a document (ADR-0003, ADR-0016): its document and tape index. Public only so the kind can permit it.
 * Its entries alternate key and value. The tape keeps repeated keys, and jq keeps a key's first place with its last
 * value (ADR-0002), so the view is no {@link Members}: every walk of it begins with its {@link KeyProof}, whose
 * {@link ObjectWalk} takes each key once (ADR-0044). Keys are one when their decoded bytes are (#35): {@code
 * "\\u0061"} and {@code "a"} are one key. A field read scans every key, since the last one wins, and needs no proof.
 * The host's {@link #size()} and {@link #forEach} prove on a run state of their own and charge no run (ADR-0016).
 */
public record ObjectView(Document doc, int index) implements JqObject, Keyed {

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(doc, index);
  }

  @Override
  public int size() {
    return KeyProof.walk(new RunState(), this, false).size();
  }

  /** The value of the last {@code key}, or Java {@code null} when the object has no such key (ADR-0026). */
  @Override
  public JqValue get(String key) {
    return valueAt((int) find(key));
  }

  /**
   * {@link #get(String)} for a filter: one tick per key scanned, which is every key, since the last one wins (ADR-0034,
   * decision 3 of #63's approval).
   */
  @Override
  public JqValue get(RunState state, String key) {
    long found = find(key);
    state.tick((int) (found >>> 32));
    return valueAt((int) found);
  }

  /** The keys scanned for {@code key} in the high half; in the low half the tape index of its last value, 0 for none. */
  private long find(String key) {
    long[] tape = doc.live();
    byte[] bytes = doc.bytes;
    int end = Tape.end(tape, index);
    int keys = 0;
    int value = 0; // none yet: index 0 is the root, never a member's value
    for (int k = index + 1; k < end; k = Tape.next(tape, k + 1)) {
      keys++;
      if (spells(bytes, tape[k], key)) {
        value = k + 1;
      }
    }
    return (long) keys << 32 | value;
  }

  /** The value at tape index {@code value}, or Java {@code null} for 0. */
  private JqValue valueAt(int value) {
    return value == 0 ? null : doc.valueAt(doc.live(), value);
  }

  /** The key at tape index {@code k}. */
  @Override
  public StringView key(int k) {
    return new StringView(doc, k);
  }

  /**
   * Each key at its first place, decoded, with its last value, through its walk. Each step after a call checks the
   * document again: the action may have read another document onto this tape (ADR-0016, STALE_VIEW).
   */
  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    RunState free = new RunState();
    ObjectWalk walk = KeyProof.walk(free, this, false);
    for (int c = walk.first(); c != -1; c = walk.next(free, c)) {
      long[] tape = doc.live();
      action.accept(StringView.text(doc.bytes, tape[walk.keyAt(c)]), doc.valueAt(tape, walk.valueAt(c)));
    }
  }

  /**
   * Whether the key {@code entry} spells {@code key}: a tag-5 key over the document's bytes, a tag-6 key over its
   * decoded bytes.
   */
  static boolean spells(byte[] bytes, long entry, String key) {
    if (Tape.tag(entry) == Tape.STRING) {
      return spells(bytes, Tape.off(entry) + 1, Tape.off(entry) + Tape.span(entry) - 1, key);
    }
    byte[] decoded = JsonStrings.decode(bytes, entry);
    return spells(decoded, 0, decoded.length, key);
  }

  /**
   * Whether the UTF-8 bytes {@code bytes[p, end)} spell {@code key}, compared by code point. A lone surrogate is no code
   * point, so a key holding one never matches; encoding {@code key} with a charset instead would turn it into {@code ?}
   * and find the key {@code "?"}.
   */
  static boolean spells(byte[] bytes, int p, int end, String key) {
    int n = key.length();
    for (int i = 0; i < n; i++) {
      char c = key.charAt(i);
      int codePoint = c;
      if (Character.isSurrogate(c)) {
        if (!Character.isHighSurrogate(c) || i + 1 == n || !Character.isLowSurrogate(key.charAt(i + 1))) {
          return false;
        }
        codePoint = Character.toCodePoint(c, key.charAt(++i));
      }
      int length = match(bytes, p, end, codePoint);
      if (length == 0) {
        return false;
      }
      p += length;
    }
    return p == end;
  }

  /** The length of the UTF-8 form of {@code codePoint} if {@code bytes[p, end)} starts with it, otherwise 0. */
  private static int match(byte[] bytes, int p, int end, int codePoint) {
    int length = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    if (end - p < length) {
      return 0;
    }
    // The lead byte: the code point alone below 0x80, otherwise 110, 1110 or 11110 and its top bits.
    int shift = 6 * (length - 1);
    int lead = length == 1 ? codePoint : ((0xF00 >> length) & 0xFF) | (codePoint >> shift);
    if ((bytes[p] & 0xFF) != lead) {
      return 0;
    }
    for (int i = 1; i < length; i++) {
      shift -= 6;
      if ((bytes[p + i] & 0xFF) != (0x80 | ((codePoint >> shift) & 0x3F))) {
        return 0;
      }
    }
    return length;
  }
}
