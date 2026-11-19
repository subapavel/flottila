package dev.rail8.jq.query.ir;

/**
 * A node of the IR. Each node keeps the part of the filter text it came from.
 */
public sealed interface Node
    permits Identity, Const, Pipe, Comma, Bind, Binds, Var, Intrinsic, Collect, If, Alt, AltBind, Try, Label, Break, Reduce, Foreach, Advance, Call, ParamCall, PathOf, Update {
  /** Where the node's text is in the filter. */
  Source source();
}
