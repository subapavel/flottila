package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.util.function.BiConsumer;

/**
 * An object built at run time (ADR-0003, #64): its key list, distinct keys in their first place, and the last value of
 * each, never written once the object is made. The key list is its construction site's shape when every key there is
 * constant, shared by every object the site builds, or its own for a growable object, whose keys a run computes
 * (GLOSSARY "Shape", "Growable object"). A key is a document's string or a built one. A lookup compares up to 8 keys
 * in order; past 8, either form looks up through an index the object builds on its first lookup (ADR-0003, #83).
 * Public only so the kind can permit it. Equality is the object's, never jq's (ADR-0026).
 */
public final class BuiltObject implements JqObject, Members, Keyed {
  /** The most keys a lookup compares in order; past it, a lookup probes an index (ADR-0003). */
  static final int LINEAR = 8;

  /** The keys, distinct, {@code [0, size)} of it. */
  private final JqString[] keys;

  /** The value of each key, each a {@link JqValue}, {@code [0, size)} of it. */
  private final Object[] values;

  private final int size;

  /**
   * The index of the keys, null until the first lookup past {@link #LINEAR} keys builds it. Two threads looking up at
   * once may each build one; either is whole (Index) and equal to the other.
   */
  private Index index;

  /**
   * The ownership mark of ADR-0022: every built class carries it from the ticket that creates the class, and it stays
   * null until spec 3 (spec-1 grilling round 1, item 9).
   */
  private final Object owner;

  /**
   * The object of the distinct {@code keys} and their {@code values}, in order, which it keeps without a copy: the
   * caller never writes either array again, and a shape's keys are never written at all.
   */
  public BuiltObject(JqString[] keys, Object[] values) {
    this(keys, values, keys.length);
  }

  /** The object of the distinct keys {@code [0, size)} of {@code keys} and their values, kept without a copy. */
  BuiltObject(JqString[] keys, Object[] values, int size) {
    this.keys = keys;
    this.values = values;
    this.size = size;
    this.owner = null;
  }

  /**
   * The growable object of {@code pairs}, a key and its value each, every key a string: a repeated key keeps its first
   * place and its last value, as jq's object insert does (GLOSSARY "Key list").
   */
  public static BuiltObject ofPairs(Object[] pairs) {
    int n = pairs.length >>> 1;
    JqString[] keys = new JqString[n];
    Object[] values = new Object[n];
    int size = 0;
    for (int p = 0; p < pairs.length; p += 2) {
      JqString key = (JqString) pairs[p];
      int at = 0;
      while (at < size && !same(keys[at], key)) {
        at++;
      }
      if (at == size) {
        keys[size++] = key;
      }
      values[at] = pairs[p + 1];
    }
    return new BuiltObject(keys, values, size);
  }

  /** Whether two keys are one: their UTF-8 bytes are equal. */
  private static boolean same(JqString a, JqString b) {
    return a.utf8().mismatch(b.utf8()) == -1;
  }

  /** Whether {@code key} spells {@code name}, compared by code point as a document's keys are (ObjectView). */
  private static boolean spells(JqString key, String name) {
    return switch (key) {
      case BuiltString s -> ObjectView.spells(s.bytes(), 0, s.byteLength(), name);
      case StringView v -> ObjectView.spells(v.doc().bytes(), v.doc().live()[v.index()], name);
    };
  }

  /** jq's compact text (ADR-0015). */
  @Override
  public String toJson() {
    return Printer.json(this);
  }

  @Override
  public int size() {
    return size;
  }

  /**
   * The value of {@code key}, or Java {@code null} when the object has no such key (ADR-0026). Past {@link #LINEAR} keys
   * the first lookup builds the index, which a host's lookup charges to no run.
   */
  @Override
  public JqValue get(String key) {
    return valueAt((int) find(key));
  }

  /**
   * {@link #get(String)} for a filter. Up to {@link #LINEAR} keys, one tick per key compared, up to the one that spells
   * {@code key}, or every key when none does (ADR-0034); past it, one tick per slot of the index probed. The lookup that
   * builds the index first charges it to bytes built, before it allocates (ADR-0035), as a container of an entry per
   * key: its slots take less than an entry's 16 B per key and its two headers less than a container's 64 B, so it
   * needs no rate of its own. Placing the keys then charges one tick per slot it probes (ADR-0008).
   */
  @Override
  public JqValue get(RunState state, String key) {
    if (size > LINEAR && index == null) {
      state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * size);
      Index built = Index.of(keys, size);
      state.tick(built.probes);
      index = built;
    }
    long found = find(key);
    state.tick((int) (found >>> 32));
    return valueAt((int) found);
  }

  /**
   * The ticks of the lookup of {@code key} in the high half; in the low half the index of its key, or {@code size} for
   * none. Up to {@link #LINEAR} keys it compares them in order; past it, it probes the index, built here when no lookup
   * has built it yet.
   */
  private long find(String key) {
    if (size <= LINEAR) {
      int i = 0;
      while (i < size && !spells(keys[i], key)) {
        i++;
      }
      return (long) Math.min(i + 1, size) << 32 | i;
    }
    Index built = index;
    if (built == null) {
      built = Index.of(keys, size);
      index = built;
    }
    return built.find(keys, size, key);
  }

  /** The value at {@code i}, or Java {@code null} for {@code size}. */
  private JqValue valueAt(int i) {
    return i < size ? (JqValue) values[i] : null;
  }

  /** Each key, decoded, with its value, in the key list's order. */
  @Override
  public void forEach(BiConsumer<? super String, ? super JqValue> action) {
    for (int i = 0; i < size; i++) {
      action.accept(keys[i].value(), (JqValue) values[i]);
    }
  }

  /** The key at {@code i}, {@code 0 <= i < size()}, for the writer, and at the cursor {@code i} of {@link #first}. */
  @Override
  public JqString key(int i) {
    return keys[i];
  }

  /** The value at {@code i}, {@code 0 <= i < size()}, for the writer. */
  public JqValue value(int i) {
    return (JqValue) values[i];
  }

  /** The cursor of the first entry, its index, or -1 for an empty object. */
  @Override
  public int first() {
    return size > 0 ? 0 : -1;
  }

  /** The cursor after the entry at {@code i}, or -1 after the last: the keys are distinct, so there is no scan. */
  @Override
  public int next(RunState state, int i) {
    return i + 1 < size ? i + 1 : -1;
  }

  /** The value of the entry at {@code i}. */
  @Override
  public JqValue member(RunState state, int i) {
    return (JqValue) values[i];
  }

  /**
   * The index of a key list past {@link #LINEAR} keys (ADR-0003): open addressing over the least power of two of slots
   * at least twice the keys, so fewer than four int slots, 16 B, per key. A key's slot is its decoded text's
   * {@code String.hashCode()} masked to the table, or the next free one after it; a slot holds the key's index plus
   * one, and 0 when free. Its final fields publish the slots to any thread that reads the reference, so a lookup on
   * another thread never sees a table half filled.
   */
  private static final class Index {
    private final int[] slots;

    /** The slots probed to place every key, each key's own included. */
    private final int probes;

    private Index(int[] slots, int probes) {
      this.slots = slots;
      this.probes = probes;
    }

    /** The index of {@code keys[0, size)}, distinct, {@code size} more than {@link #LINEAR}. */
    static Index of(JqString[] keys, int size) {
      int[] slots = new int[Integer.highestOneBit(2 * size - 1) << 1];
      int mask = slots.length - 1;
      int probes = 0;
      for (int i = 0; i < size; i++) {
        int s = keys[i].value().hashCode() & mask;
        probes++;
        while (slots[s] != 0) {
          s = (s + 1) & mask;
          probes++;
        }
        slots[s] = i + 1;
      }
      return new Index(slots, probes);
    }

    /**
     * The slots probed for {@code key} in the high half, up to its key's or a free one; in the low half the index of
     * its key, or {@code size} for none. A key that spells {@code key} decodes to {@code key}, so it hashes as
     * {@code key} does, and no free slot lies between its own slot and that hash's.
     */
    long find(JqString[] keys, int size, String key) {
      int mask = slots.length - 1;
      int s = key.hashCode() & mask;
      int probes = 1;
      int at = slots[s];
      while (at != 0 && !spells(keys[at - 1], key)) {
        s = (s + 1) & mask;
        probes++;
        at = slots[s];
      }
      return (long) probes << 32 | (at == 0 ? size : at - 1);
    }
  }
}
