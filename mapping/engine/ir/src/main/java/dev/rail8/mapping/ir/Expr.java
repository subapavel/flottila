package dev.rail8.mapping.ir;

/**
 * A node of the IR every front end lowers to. The IR is pure data. Each node names the authoring
 * element it came from, for tracing and errors. The nodes are those of a field link, the call of a
 * context function with its literal params, the single-value step {@link Lift}, the fixed
 * occurrences {@link TargetRepeat} and the {@link AttributeConstructor}.
 */
public sealed interface Expr
    permits SourceQueue,
        VarRef,
        Literal,
        FunCall,
        Lift,
        TargetSlots,
        TargetRepeat,
        ElementConstructor,
        AttributeConstructor,
        TextConstructor,
        AtomicToQueue {

  /** The authoring element this node came from. */
  AuthoringId origin();
}
