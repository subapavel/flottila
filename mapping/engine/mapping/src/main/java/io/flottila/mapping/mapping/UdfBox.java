package io.flottila.mapping.mapping;

import io.flottila.mapping.ir.AuthoringId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A call of a UDF declared in the mapping's {@code udfs} ({@code kind: udf}).
 *
 * @param udf the UDF's name in {@code udfs}
 * @param inputs the input slots in the declaration's input order; empty for an unconnected input
 * @param params the box parameters, keyed by the declaration's parameter name
 */
public record UdfBox(String udf, List<Optional<AuthoringId>> inputs, Map<String, ParamValue> params)
    implements Box {

  public UdfBox {
    inputs = List.copyOf(inputs);
    params = Map.copyOf(params);
  }
}
