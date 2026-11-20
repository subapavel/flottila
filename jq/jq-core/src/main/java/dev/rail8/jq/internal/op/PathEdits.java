package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.json.ErrorDump;
import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.Edit;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathFormat;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's paths: a path is the array of its keys, as {@code path(f)} emits it, and an update edits its input as
 * builtin.jq's {@code _modify} and {@code _assign} do, through {@link Paths}' {@code getpath}, {@code setpath} and
 * {@code delpaths}, with their errors, ticks and charges. A path of more than 10 000 keys raises jq's "Path too deep":
 * a modify's get and an assign's set at more than 10 000 keys, and a modify's set at 10 000 too, since builtin.jq sets
 * {@code [0] + $p} in its reduce state; a path of 10 000 keys so raises only after its right side gave a result. One
 * instance serves every program.
 */
public final class PathEdits implements PathFormat {
  /** The one instance. */
  public static final PathEdits FORMAT = new PathEdits();

  private PathEdits() {}

  /** The array of {@code p}'s keys, charged as a built array of that many elements. */
  @Override
  public Object value(RunState state, Path p) {
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * p.length());
    return keys(p);
  }

  /** jq's "Invalid path expression with result", the result shown as {@link ErrorDump} shows it. */
  @Override
  public FilterException invalid(RunState state, Object result) {
    return new FilterException(BuiltString.of("Invalid path expression with result " + ErrorDump.of(state, (JqValue) result)));
  }

  @Override
  public Edit modify(RunState state, Object root) {
    return new Modify((JqValue) root);
  }

  @Override
  public Edit assign(RunState state, Object root) {
    return new Assign((JqValue) root);
  }

  /** The keys of {@code p} in order, as an array; making it charges nothing. */
  private static BuiltArray keys(Path p) {
    Object[] keys = new Object[p.length()];
    for (Path at = p; at.length() > 0; at = at.parent()) {
      keys[at.length() - 1] = at.key();
    }
    return new BuiltArray(keys, keys.length);
  }

  /**
   * builtin.jq's {@code _modify}: each path read with {@code getpath} on the current root and set with {@code setpath},
   * or added to the delete list, which is a built array of path arrays charged as it grows; {@code end} is
   * {@code delpaths} of that list on the root.
   */
  private static final class Modify implements Edit {
    private JqValue root;

    /** The paths to delete, as arrays; null until the first. */
    private Built.Elements deletes;

    Modify(JqValue root) {
      this.root = root;
    }

    @Override
    public Object get(RunState state, Path p) {
      return Paths.getpath(state, root, keys(p));
    }

    @Override
    public void set(RunState state, Path p, Object v) {
      if (p.length() >= Paths.MAX_DEPTH) {
        throw Paths.tooDeep();
      }
      root = Paths.setpath(state, root, keys(p), (JqValue) v);
    }

    @Override
    public void delete(RunState state, Path p) {
      Object path = FORMAT.value(state, p);
      if (deletes == null) {
        deletes = new Built.Elements(state);
      }
      deletes.add(path);
    }

    @Override
    public Object end(RunState state) {
      return deletes == null ? root : Paths.delpaths(state, root, deletes.array());
    }
  }

  /** builtin.jq's {@code _assign}: each path set with {@code setpath} on the current root; nothing is deleted. */
  private static final class Assign implements Edit {
    private JqValue root;

    Assign(JqValue root) {
      this.root = root;
    }

    /** Never called: an assign reads no old value. */
    @Override
    public Object get(RunState state, Path p) {
      throw new IllegalStateException("an assign reads no value at its paths");
    }

    @Override
    public void set(RunState state, Path p, Object v) {
      root = Paths.setpath(state, root, keys(p), (JqValue) v);
    }

    /** Never called: an assign deletes nothing. */
    @Override
    public void delete(RunState state, Path p) {
      throw new IllegalStateException("an assign deletes no path");
    }

    @Override
    public Object end(RunState state) {
      return root;
    }
  }
}
