package io.flottila.mapping.mapping;

import java.util.List;
import java.util.Map;

/**
 * A mapping's entry in {@code udfs} (ADR-0010, ADR-0020). Compiling checks it against the method.
 *
 * @param executionKind what one call of the UDF sees
 * @param inputs the input names in order; empty for a UDF without inputs (F-6)
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
