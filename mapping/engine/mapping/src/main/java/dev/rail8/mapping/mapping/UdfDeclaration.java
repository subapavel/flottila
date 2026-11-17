package dev.rail8.mapping.mapping;

import java.util.List;
import java.util.Map;

/**
 * A mapping's entry in {@code udfs}. Compiling checks it against the method.
 *
 * @param executionKind what one call of the UDF sees
 * @param inputs the input names in order; empty for a UDF without inputs
 * @param params the box parameters and their types
 * @param source the Java method, {@code <file reference>#<method>}
 */
public record UdfDeclaration(
    ExecutionKind executionKind,
    List<String> inputs,
    Map<String, UdfParamType> params,
    String source) {

  public UdfDeclaration {
    inputs = List.copyOf(inputs);
    params = Map.copyOf(params);
  }
}
