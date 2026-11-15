package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A node of the interpreter tree (ADR-0008, ADR-0018): its push form, {@link #run}, and its heap form on the
 * trampoline, {@link #start} (ADR-0046 and its #109 amendment), which every op has.
 */
public abstract sealed class Op
    permits IdentityOp,
        ConstOp,
        PipeOp,
        CommaOp,
        BindOp,
        BindsOp,
        VarOp,
        IntrinsicOp,
        Intrinsic1Op,
        Intrinsic2Op,
        IntrinsicNOp,
        IntrinsicManyOp,
        CollectOp,
        IfOp,
        AltOp,
        AltBindOp,
        TryOp,
        LabelOp,
        BreakOp,
        ReduceOp,
        ForeachOp,
        AdvanceOp,
        OuterVarOp,
        CallOp,
        ParamCallOp {
  Op() {}

  /**
   * Pushes the results for {@code in} into {@code out}, in the run {@code state} belongs to (ADR-0030), with the running
   * frame's cells {@code slots} (ADR-0034, ADR-0045 #107): true when they ran out, false when out stopped.
   */
  public abstract boolean run(RunState state, Object[] slots, Object in, Sink out);

  /**
   * Starts the heap form for {@code in} on the trampoline {@code t}, under the form {@code under}, with the running
   * frame's cells {@code slots}, its results going to {@code out} (GLOSSARY "Heap form"). It does what {@link #run}
   * does, in the same order, with the same ticks, charges, slot writes, label tokens and depth counts; where
   * {@code run} runs a child or emits, it sets the driver's next action instead and returns, and where {@code run}
   * returns, it answers {@code under}.
   */
  abstract void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out);
}
