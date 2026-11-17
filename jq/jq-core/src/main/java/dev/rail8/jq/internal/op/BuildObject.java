package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.FnN;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqString;

/**
 * jq's object construction {@code {k: v, ...}} once each pair has its key and value: a site whose keys are all constant
 * builds on its shape, the key list it shares, with one argument per key, the value of its last pair; any other site
 * builds a growable object from a key and a value per pair, a key the {@link ObjectKey} check found a string. Either
 * charges a container and an entry per pair before it allocates.
 */
public final class BuildObject implements FnN {
  /** The construction of a site with a computed key: a key and a value per pair. */
  public static final Operation PAIRS = new Operation("{...}", new BuildObject(null));

  /** The shape of the site, or null for one with a computed key. */
  private final JqString[] shape;

  private BuildObject(JqString[] shape) {
    this.shape = shape;
  }

  /** The construction of a site whose distinct constant keys, in their first place, are {@code shape}. */
  public static Operation shaped(JqString[] shape) {
    return new Operation("{...}", new BuildObject(shape));
  }

  @Override
  public Object apply(RunState state, Object in, Object[] args) {
    if (shape != null) {
      state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * shape.length);
      return new BuiltObject(shape, args);
    }
    state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * (args.length >>> 1));
    return BuiltObject.ofPairs(args);
  }
}
