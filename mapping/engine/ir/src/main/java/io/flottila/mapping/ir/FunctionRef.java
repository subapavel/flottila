package io.flottila.mapping.ir;

import java.util.List;

/**
 * The symbolic target of a {@link FunCall} (ADR-0006, ADR-0025): a function of the function
 * catalogue by its name, which the back ends bind, and the types of its calls. It holds no method
 * handle and no class.
 *
 * @param name the catalogue's function name
 * @param paramTypes the type of each argument, in argument order: the inputs, then the params
 * @param resultType the type of a call's value
 */
public record FunctionRef(String name, List<IrType> paramTypes, IrType resultType) {

  public FunctionRef {
    paramTypes = List.copyOf(paramTypes);
  }
}
