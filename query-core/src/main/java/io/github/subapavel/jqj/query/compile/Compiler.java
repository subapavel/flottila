package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.internal.interpret.AdvanceOp;
import io.github.subapavel.jqj.query.internal.interpret.AltBindOp;
import io.github.subapavel.jqj.query.internal.interpret.AltOp;
import io.github.subapavel.jqj.query.internal.interpret.Argument;
import io.github.subapavel.jqj.query.internal.interpret.BindOp;
import io.github.subapavel.jqj.query.internal.interpret.BindsOp;
import io.github.subapavel.jqj.query.internal.interpret.BreakOp;
import io.github.subapavel.jqj.query.internal.interpret.CollectOp;
import io.github.subapavel.jqj.query.internal.interpret.CommaOp;
import io.github.subapavel.jqj.query.internal.interpret.ConstOp;
import io.github.subapavel.jqj.query.internal.interpret.ForeachOp;
import io.github.subapavel.jqj.query.internal.interpret.IdentityOp;
import io.github.subapavel.jqj.query.internal.interpret.IfOp;
import io.github.subapavel.jqj.query.internal.interpret.Intrinsic1Op;
import io.github.subapavel.jqj.query.internal.interpret.Intrinsic2Op;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicManyOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicNOp;
import io.github.subapavel.jqj.query.internal.interpret.IntrinsicOp;
import io.github.subapavel.jqj.query.internal.interpret.LabelOp;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.PipeOp;
import io.github.subapavel.jqj.query.internal.interpret.ReduceOp;
import io.github.subapavel.jqj.query.internal.interpret.TryOp;
import io.github.subapavel.jqj.query.internal.interpret.VarOp;
import io.github.subapavel.jqj.query.ir.Advance;
import io.github.subapavel.jqj.query.ir.Alt;
import io.github.subapavel.jqj.query.ir.AltBind;
import io.github.subapavel.jqj.query.ir.Alternative;
import io.github.subapavel.jqj.query.ir.Bind;
import io.github.subapavel.jqj.query.ir.Binds;
import io.github.subapavel.jqj.query.ir.Break;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
import java.util.List;

/** Compiles an IR tree: verifies it, then builds the interpreter tree (ADR-0018). There is no analysis yet. */
public final class Compiler {
  private Compiler() {}

  /**
   * Compiles {@code root}, which has no parameter slot, in {@code mode}: {@link #compile(Node, int, CompileMode)} with
   * none.
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root, CompileMode mode) {
    return compile(root, 0, mode);
  }

  /**
   * Compiles {@code root} in {@code mode}, with slots {@code 0 .. parameters - 1} its parameter slots (ADR-0038): in
   * scope everywhere, bound by no node, and set by each run from its parameters. There is no analysis yet, so both
   * modes build the same tree (ADR-0030).
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root, int parameters, CompileMode mode) {
    int slots = IrVerifier.verify(root, parameters);
    return new Executable(interpreter(root), slots, parameters);
  }

  private static Op interpreter(Node node) {
    return switch (node) {
      case Identity _ -> new IdentityOp();
      case Const c -> new ConstOp(c.value());
      case Pipe p -> new PipeOp(interpreter(p.left()), interpreter(p.right()));
      case Comma c -> new CommaOp(interpreter(c.left()), interpreter(c.right()));
      case Bind b -> new BindOp(interpreter(b.value()), b.slot(), interpreter(b.body()));
      case Binds b -> binds(b);
      case Var v -> new VarOp(v.slot());
      case Intrinsic i -> intrinsic(i);
      case Collect c -> new CollectOp(interpreter(c.body()), c.collector());
      case If i -> new IfOp(interpreter(i.cond()), tail(i.cond()), interpreter(i.then()), interpreter(i.otherwise()), i.truth());
      case Alt a -> new AltOp(interpreter(a.left()), interpreter(a.right()), a.truth());
      case AltBind a -> altBind(a);
      case Try t -> new TryOp(interpreter(t.body()), interpreter(t.handler()));
      case Label l -> new LabelOp(l.slot(), interpreter(l.body()));
      case Break b -> new BreakOp(b.slot());
      case Reduce r -> new ReduceOp(interpreter(r.init()), r.slot(), interpreter(r.each()));
      case Foreach f -> new ForeachOp(interpreter(f.init()), f.slot(), interpreter(f.each()));
      case Advance a -> new AdvanceOp(a.slot(), interpreter(a.update()));
    };
  }

  /** The Binds {@code b}, its values built in a loop, so the build does not go deeper with their number. */
  private static Op binds(Binds b) {
    List<Node> values = b.values();
    Op[] ops = new Op[values.size()];
    boolean[] tails = new boolean[ops.length];
    for (int k = 0; k < ops.length; k++) {
      ops[k] = interpreter(values.get(k));
      tails[k] = tail(values.get(k));
    }
    return new BindsOp(ops, tails, ints(b.slots()), interpreter(b.body()));
  }

  /**
   * Whether {@code node} is a tail emitter (ADR-0043), decided by its kind alone: an Identity, a Const, a Var, an
   * Intrinsic of a one-result call form, or a Pipe, If, Bind or Binds whose parts are all tail emitters. Such a node
   * gives one result at most and holds no Break, so it never breaks out (ADR-0008). Every other kind, a Break among
   * them, is none.
   */
  private static boolean tail(Node node) {
    return switch (node) {
      case Identity _ -> true;
      case Const _ -> true;
      case Var _ -> true;
      case Intrinsic i -> !(i.op().form() instanceof Many);
      case Pipe p -> tail(p.left()) && tail(p.right());
      case If i -> tail(i.cond()) && tail(i.then()) && tail(i.otherwise());
      case Bind b -> tail(b.value()) && tail(b.body());
      case Binds b -> b.values().stream().allMatch(Compiler::tail) && tail(b.body());
      // False is safe for any kind: a node not taken for a tail emitter runs in push form, which is right for every
      // node and only runs the rest inside its emit, one recursion deeper. True is safe only for the kinds above.
      default -> false;
    };
  }

  /** The chain {@code a}, its lists as the arrays the run reads. */
  private static Op altBind(AltBind a) {
    List<Alternative> alternatives = a.alternatives();
    Op[] matches = new Op[alternatives.size()];
    int[][] from = new int[matches.length][];
    for (int k = 0; k < matches.length; k++) {
      matches[k] = interpreter(alternatives.get(k).match());
      from[k] = ints(alternatives.get(k).from());
    }
    return new AltBindOp(interpreter(a.value()), a.slot(), matches, from, ints(a.slots()), interpreter(a.body()));
  }

  private static int[] ints(List<Integer> list) {
    int[] ints = new int[list.size()];
    for (int i = 0; i < ints.length; i++) {
      ints[i] = list.get(i);
    }
    return ints;
  }

  /** The call of {@code i} in its form, with the arity and the atomic arguments the verifier checked. */
  private static Op intrinsic(Intrinsic i) {
    List<Node> nodes = i.args();
    Argument[] args = new Argument[nodes.size()];
    for (int k = 0; k < args.length; k++) {
      args[k] = argument(nodes.get(k));
    }
    return switch (i.op().form()) {
      case Fn0 fn -> new IntrinsicOp(fn);
      case Fn1 fn -> new Intrinsic1Op(fn, args[0]);
      case Fn2 fn -> new Intrinsic2Op(fn, args[0], args[1]);
      case FnN fn -> new IntrinsicNOp(fn, args);
      case Many many -> new IntrinsicManyOp(many, args);
    };
  }

  /** An atomic argument: a Var's slot, a Const's value, or else an Identity's input, the only other kind the verifier lets through. */
  private static Argument argument(Node a) {
    return switch (a) {
      case Var v -> Argument.slot(v.slot());
      case Const c -> Argument.constant(c.value());
      default -> Argument.input();
    };
  }
}
