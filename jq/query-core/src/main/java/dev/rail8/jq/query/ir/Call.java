package dev.rail8.jq.query.ir;

import java.util.List;

/**
 * A call of function {@code fn} of its {@link Program} on the Call's input: one
 * closure per closure parameter of the function, each a node of the caller's scope, and one value per value parameter,
 * each atomic ({@link Identity}, {@link Var} or {@link Const}) and read on the Call's input. A Var of a {@link Label}'s
 * slot passes that Label's token. The function runs in a frame of its own; the Call ticks one step. Its kind is not in
 * the IR.
 */
public record Call(Source source, int fn, List<Node> closures, List<Node> values) implements Node {
  public Call {
    closures = List.copyOf(closures);
    values = List.copyOf(values);
  }
}
