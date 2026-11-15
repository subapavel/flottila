package io.flottila.mapping.ir;

import java.util.List;

/**
 * A call of a function of the function catalogue (ADR-0006, ADR-0025). A call of a context or queue
 * function takes its inputs in catalogue order, then every param in catalogue order as a {@link
 * Literal}. Lowering fills in a param the box leaves out with its default, so a call has one
 * argument per type of {@link FunctionRef#paramTypes()}.
 *
 * @param function the function and the types of its calls
 * @param args the arguments, in the order of {@code function}'s parameter types
 * @param origin the function box
 */
public record FunCall(FunctionRef function, List<Expr> args, AuthoringId origin) implements Expr {

  public FunCall {
    args = List.copyOf(args);
  }
}
