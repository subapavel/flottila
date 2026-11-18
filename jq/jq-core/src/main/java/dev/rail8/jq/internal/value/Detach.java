package dev.rail8.jq.internal.value;

import dev.rail8.jq.internal.json.JsonStrings;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Copies every view in a value into built values, so the copy outlives its document: a document's string becomes a
 * built string of its decoded bytes, a number a literal of its token, and each container, a document's or a built one,
 * a built container of its members' copies, an object its keys' too, in its members' order. The walk keeps an explicit
 * stack, so a deep value takes no Java stack. It runs as a host's call does, after the run, where no budget, deadline
 * or interrupt reaches it: it charges no bytes built and no tick. Each value is copied once and its copy shared
 * wherever the value is held: a built container known by its identity, and a document's container, string, key or
 * number by its document and tape index. A document's object is walked through its key proof, so a repeated key keeps
 * its first position and takes its last value. So the walk visits each member of each distinct container once, each
 * visit one memo lookup, and its time is the sum of: one step per such member; the bytes of each distinct document
 * string and key, decoded once, and of each distinct document number's token, copied once; and each distinct document
 * object's proof, about linear in its key bytes and at most n log2 n compares of its decoded keys for n key entries.
 */
public final class Detach {
  /** The capacity of a container's first array of copies. */
  private static final int FIRST = 4;

  /** The cursor {@link Members} gives when there is no member (left). */
  private static final int END = -1;

  private static final Object[] NONE = {};

  private Detach() {}

  /**
   * The copy of {@code value} that holds no view.
   *
   * @throws dev.rail8.jq.JqTermination with cause {@code STALE_VIEW} when a view in it is stale
   */
  public static JqValue of(JqValue value) {
    // The walks and key proofs charge a run state no run began: every budget of it is unlimited.
    RunState free = new RunState();
    // A container the value holds at several places is copied once, and the copy is shared: values are immutable.
    Copies copies = new Copies();
    Frame top = null;
    JqValue next = value;
    for (;;) {
      JqValue copied;
      if (next instanceof Members || next instanceof ObjectView) {
        JqValue done = copies.get(next);
        if (done != null) {
          copied = done;
        } else {
          Frame frame = Frame.of(next, top, copies, free);
          if (frame.length > 0) {
            top = frame;
            next = (JqValue) frame.values[0];
            continue;
          }
          copied = frame.build();
        }
      } else {
        copied = copies.leaf(next);
      }
      // Hand the copy to its container; each container whose last member it was is built and handed on in turn.
      for (;;) {
        if (top == null) {
          return copied;
        }
        top.values[top.at++] = copied;
        if (top.at < top.length) {
          next = (JqValue) top.values[top.at];
          break;
        }
        copied = top.build();
        copies.put(top.container, copied);
        top = top.parent;
      }
    }
  }

  /**
   * The bytes built that the copy {@link #of} makes of {@code doc}'s root would charge in a run: per container {@code
   * Charges.CONTAINER}, plus {@code Charges.ELEMENT} per array element and {@code Charges.ENTRY} per object entry; per
   * string and per key {@code Charges.STRING} plus its decoded length; nothing for a number, null or a boolean. An
   * object is walked through its key proof, as {@link #of} walks it, so a repeated key is one entry with its last value.
   * It keeps a stack of its own and charges no run.
   */
  public static long charge(Document doc) {
    RunState free = new RunState();
    ArrayDeque<Members> containers = new ArrayDeque<>();
    ArrayDeque<Integer> cursors = new ArrayDeque<>();
    long total = 0;
    JqValue next = doc.root();
    for (;;) {
      switch (next) {
        case JqString s -> total += Charges.STRING + s.utf8().byteSize();
        case ArrayView a -> {
          total += Charges.CONTAINER;
          containers.push(a);
          cursors.push(a.first());
        }
        case ObjectView o -> {
          total += Charges.CONTAINER;
          ObjectWalk walk = KeyProof.walk(free, o, false);
          containers.push(walk);
          cursors.push(walk.first());
        }
        // A number, null or a boolean: a document holds no built value.
        default -> {}
      }
      for (;;) {
        if (containers.isEmpty()) {
          return total;
        }
        int cursor = cursors.pop();
        if (cursor != END) {
          Members top = containers.peek();
          if (top instanceof Keyed keyed) {
            total += Charges.ENTRY + Charges.STRING + keyed.key(cursor).utf8().byteSize();
          } else {
            total += Charges.ELEMENT;
          }
          next = top.member(free, cursor);
          cursors.push(top.next(free, cursor));
          break;
        }
        containers.pop();
      }
    }
  }

  /**
   * The copies made so far: a built container's by its identity, and a document's value's, a container, a string, a
   * key or a number, by its document's identity and its tape index, since each access to a document's value makes a new
   * view of it. Views of two documents never share a copy.
   */
  private static final class Copies {
    private final Map<JqValue, JqValue> built = new IdentityHashMap<>();
    private final Map<Document, Map<Integer, JqValue>> views = new IdentityHashMap<>();

    /** The copy of a value that is no container: a document's string or number copied once, any other as it is. */
    JqValue leaf(JqValue v) {
      return switch (v) {
        case StringView s -> string(s.doc(), s.index());
        case NumberView n -> of(n.doc()).computeIfAbsent(n.index(), i -> LiteralNumber.copyOf(n));
        // Null, the booleans, built strings and the filter's and computed numbers view no document.
        default -> v;
      };
    }

    /** A key as a built string: a document's copied once, a built one as it is. */
    JqString key(JqString key) {
      return key instanceof StringView s ? string(s.doc(), s.index()) : key;
    }

    /**
     * The built string of the string or key at tape index {@code index} of {@code doc}, of its decoded bytes, decoded
     * once; its {@link BuiltString#bytes()} are exactly those bytes.
     */
    BuiltString string(Document doc, int index) {
      return (BuiltString)
          of(doc)
              .computeIfAbsent(
                  index,
                  i -> {
                    long entry = doc.live()[i];
                    return BuiltString.of(JsonStrings.decode(doc.bytes(), entry));
                  });
    }

    /** The copy of {@code container} made before, or null. */
    JqValue get(JqValue container) {
      if (container instanceof ArrayView v) {
        return of(v.doc()).get(v.index());
      }
      if (container instanceof ObjectView v) {
        return of(v.doc()).get(v.index());
      }
      // A built array or object.
      return built.get(container);
    }

    void put(JqValue container, JqValue copy) {
      if (container instanceof ArrayView v) {
        of(v.doc()).put(v.index(), copy);
      } else if (container instanceof ObjectView v) {
        of(v.doc()).put(v.index(), copy);
      } else {
        built.put(container, copy);
      }
    }

    /** The copies of {@code doc}'s containers by tape index. */
    private Map<Integer, JqValue> of(Document doc) {
      return views.computeIfAbsent(doc, d -> new HashMap<>());
    }
  }

  /**
   * A container on the stack: its members, each replaced by its copy in turn, and for an object their keys' copies.
   */
  private static final class Frame {
    final JqValue container;
    final Frame parent;

    /** The members, {@code [0, length)}: the copies before {@link #at}, the members still to copy from it on. */
    Object[] values = NONE;

    /** The copied keys, for an object; null for an array. */
    JqString[] keys;

    int length;

    /** The member being copied. */
    int at;

    private Frame(JqValue container, Frame parent) {
      this.container = container;
      this.parent = parent;
      this.keys = container instanceof JqObject ? new JqString[0] : null;
    }

    /**
     * The frame of {@code container}'s members, each object key once, at its first position with its last value, and
     * each key copied through {@code copies}: a document's object through its walk, whose keys are distinct as a built
     * object's are.
     */
    static Frame of(JqValue container, Frame parent, Copies copies, RunState free) {
      Frame frame = new Frame(container, parent);
      Members members = container instanceof ObjectView object ? KeyProof.walk(free, object, false) : (Members) container;
      for (int c = members.first(); c != END; c = members.next(free, c)) {
        frame.add(members instanceof Keyed keyed ? copies.key(keyed.key(c)) : null, members.member(free, c));
      }
      return frame;
    }

    /** Adds a member, and for an object the copy of its key. */
    private void add(JqString key, JqValue member) {
      if (length == values.length) {
        int capacity = Math.max(FIRST, 2 * length);
        values = Arrays.copyOf(values, capacity);
        if (keys != null) {
          keys = Arrays.copyOf(keys, capacity);
        }
      }
      if (keys != null) {
        keys[length] = key;
      }
      values[length++] = member;
    }

    /** The built container of the copies; an object's keys are distinct, as {@link #of} gives them. */
    JqValue build() {
      return keys == null ? new BuiltArray(values, length) : new BuiltObject(keys, values, length);
    }
  }
}
