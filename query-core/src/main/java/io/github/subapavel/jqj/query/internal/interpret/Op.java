package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A node of the interpreter tree (ADR-0008, ADR-0018): its push form, {@link #run}, its value form, {@link #eval}
 * (ADR-0047 #116), and its heap form on the trampoline, {@link #start} (ADR-0046 and its #109 amendment), which every
 * op has. Each op has one run form, fixed from the facts when the tree is built ({@link #valueForm}, GLOSSARY "Run
 * form"): its parent, the Call or Closure that runs it as a body, or the run entry calls {@code eval} on a value-form
 * op and {@code run} on a push-form one. A push parent emits a value-form child's value itself: {@code v == NONE ||
 * out.emit(v)}. Heap forms never call {@code eval}.
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
        JumpOp,
        ParamCallOp {
  /**
   * Whether this op runs by {@link #eval} (ADR-0047 #116): its cardinality is at most one and it may not break out. A
   * parent reads it to choose the child's form; nothing else does.
   */
  final boolean valueForm;

  /**
   * A sink that drops each result and goes on: a Reduce's {@code each} runs into it, and so does what follows a push
   * part of a value-form op where the facts promise that it gives no result (ADR-0007 #112).
   */
  static final Sink DROP = v -> true;

  Op(boolean valueForm) {
    this.valueForm = valueForm;
  }

  /**
   * Pushes the results for {@code in} into {@code out}, in the run {@code state} belongs to (ADR-0030), with the running
   * frame's cells {@code slots} (ADR-0034, ADR-0045 #107): true when they ran out, false when out stopped.
   */
  public abstract boolean run(RunState state, Object[] slots, Object in, Sink out);

  /**
   * The value form (ADR-0008, ADR-0047 #116): the one result for {@code in}, or {@link CallForm#NONE} when there is
   * none; Java null is a result. Up to the result it ticks, charges and writes slots, label tokens and depth counts
   * exactly as {@link #run} does, then returns it, and it reaches each child and each starter through no more Java
   * frames than {@code run} does (ADR-0046 #116). A child whose results are this op's own is value form whenever it
   * runs: a push child there would be many or break out, and so would this op (ADR-0007 #112, #115).
   *
   * @throws IllegalStateException for a kind that is never eligible: a Break, a ParamCall, an AltBind and a Many
   *     intrinsic
   */
  public abstract Object eval(RunState state, Object[] slots, Object in);

  /**
   * Starts the heap form for {@code in} on the trampoline {@code t}, under the form {@code under}, with the running
   * frame's cells {@code slots}, its results going to {@code out} (GLOSSARY "Heap form"). It does what {@link #run}
   * does, in the same order, with the same ticks, charges, slot writes, label tokens and depth counts; where
   * {@code run} runs a child or emits, it sets the driver's next action instead and returns, and where {@code run}
   * returns, it answers {@code under}.
   */
  abstract void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out);
}
