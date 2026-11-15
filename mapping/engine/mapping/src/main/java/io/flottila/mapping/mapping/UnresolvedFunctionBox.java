package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A function box whose function is neither in the function catalogue nor among the mapping's UDFs
 * ({@code kind: unresolved}). It is stored as found; compiling it is a design-time error.
 *
 * @param name the original function name
 * @param origin where the name comes from
 * @param inputs the input slots as found; empty for an unconnected input
 * @param params the box parameters as found
 */
public record UnresolvedFunctionBox(
    String name, Origin origin, List<Optional<AuthoringId>> inputs, Map<String, ParamValue> params)
    implements Box {

  public UnresolvedFunctionBox {
    inputs = List.copyOf(inputs);
    params = Map.copyOf(params);
  }
}
