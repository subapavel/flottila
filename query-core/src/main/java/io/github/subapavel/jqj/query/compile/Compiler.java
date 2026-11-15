package io.github.subapavel.jqj.query.compile;

import io.github.subapavel.jqj.query.analysis.CallGraph;
import io.github.subapavel.jqj.query.analysis.Facts;
import io.github.subapavel.jqj.query.analysis.Numbering;
import io.github.subapavel.jqj.query.internal.interpret.AdvanceOp;
import io.github.subapavel.jqj.query.internal.interpret.AltBindOp;
import io.github.subapavel.jqj.query.internal.interpret.AltOp;
import io.github.subapavel.jqj.query.internal.interpret.Argument;
import io.github.subapavel.jqj.query.internal.interpret.BindOp;
import io.github.subapavel.jqj.query.internal.interpret.BindsOp;
import io.github.subapavel.jqj.query.internal.interpret.BreakOp;
import io.github.subapavel.jqj.query.internal.interpret.CallOp;
import io.github.subapavel.jqj.query.internal.interpret.ClosureArgument;
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
import io.github.subapavel.jqj.query.internal.interpret.LiftedFunction;
import io.github.subapavel.jqj.query.internal.interpret.Op;
import io.github.subapavel.jqj.query.internal.interpret.OuterVarOp;
import io.github.subapavel.jqj.query.internal.interpret.ParamCallOp;
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
import io.github.subapavel.jqj.query.ir.Call;
import io.github.subapavel.jqj.query.ir.Collect;
import io.github.subapavel.jqj.query.ir.Comma;
import io.github.subapavel.jqj.query.ir.Const;
import io.github.subapavel.jqj.query.ir.Foreach;
import io.github.subapavel.jqj.query.ir.Function;
import io.github.subapavel.jqj.query.ir.Identity;
import io.github.subapavel.jqj.query.ir.If;
import io.github.subapavel.jqj.query.ir.Intrinsic;
import io.github.subapavel.jqj.query.ir.Label;
import io.github.subapavel.jqj.query.ir.Node;
import io.github.subapavel.jqj.query.ir.ParamCall;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Program;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.Many;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a program: verifies it, specialises it in the optimised compile and verifies it again, freezes it, finds its
 * facts, then builds the interpreter tree (ADR-0018, ADR-0045 #112 and #117).
 *
 * <p>Each body, the main tree's or a function's, runs in a frame of its own: its slots, then a function's closure
 * cells (ADR-0045 #107). A closure argument that binds no slot runs in the frame it was written in, its defining frame,
 * and compiles there; one that binds slots runs in a frame of its own, cell 0 the link to its defining frame and its
 * slots from cell 1, so a slot of a frame further out is read through as many links as there are closures between.
 */
public final class Compiler {
  /** The shell of each function of the program, made before any body is built, so a call links to its callee. */
  private final LiftedFunction[] shells;

  /** The slots each closure argument binds in its own text, by identity, as the verifier found them. */
  private final Map<Node, List<Integer>> closures;

  /** Where the node being built runs: its body's frame or a closure's. */
  private Region region;

  /**
   * A frame the nodes run in: a body's, whose slot s is cell s and whose closure parameter j is cell {@code base + j},
   * or a closure's, with the cell of each slot it binds and the region it was written in.
   */
  private record Region(Region outer, Map<Integer, Integer> cells, int base) {}

  /** Where a slot or closure parameter is: {@code level} links out from the running frame, at {@code cell}. */
  private record At(int level, int cell) {}

  /** The program's frames: each call's units and each closure argument's leaf-emit depth (ADR-0046 #111). */
  private final Nesting nesting;

  /** The freeze ids of the program's nodes, by which {@link #facts} are kept. */
  private final Numbering numbering;

  /** The facts each node's run form is read from (ADR-0047 #116). */
  private final Facts facts;

  /** The body the node being built is in: -1 for the main tree, a function's place otherwise. */
  private int body;

  private Compiler(LiftedFunction[] shells, Map<Node, List<Integer>> closures, Nesting nesting, Numbering numbering, Facts facts) {
    this.shells = shells;
    this.closures = closures;
    this.nesting = nesting;
    this.numbering = numbering;
    this.facts = facts;
  }

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
   * Compiles {@code root} in {@code mode}, with slots {@code 0 .. parameters - 1} its parameter slots (ADR-0038): the
   * program of {@code root} and no function, {@link #compile(Program, int, CompileMode)}.
   *
   * @throws IllegalStateException when the tree breaks a rule of the IR
   */
  public static Executable compile(Node root, int parameters, CompileMode mode) {
    return compile(new Program(root, List.of()), parameters, mode);
  }

  /**
   * Compiles {@code program} in {@code mode}, with slots {@code 0 .. parameters - 1} of its main tree its parameter
   * slots (ADR-0038): in scope everywhere in the main tree, bound by no node, and set by each run from its parameters.
   * {@link #compile(Program, int, CompileMode, int)} under {@link Specialiser#SPECIALISE_CAP}.
   *
   * @throws IllegalStateException when the program breaks a rule of the IR
   */
  public static Executable compile(Program program, int parameters, CompileMode mode) {
    return compile(program, parameters, mode, Specialiser.SPECIALISE_CAP);
  }

  /**
   * {@link #compile(Program, int, CompileMode)} with the specialisation cap {@code cap}. The order is verify, specialise,
   * verify, freeze, facts, build (ADR-0045 #112 and #117): {@link CompileMode#OPTIMISED} specialises the program under
   * {@code cap}, verifies the result again and analyses it; {@link CompileMode#CONSERVATIVE} makes no copy and keeps
   * every fact at its safe value (ADR-0018). The interpreter reads one thing from the facts, each node's run form
   * (ADR-0047 #116), over the frozen program's numbering, so a copy's nodes take their own: value form only where the
   * optimised facts allow it (ADR-0030). The executable runs the frozen program and carries it ({@link Frozen}).
   *
   * @throws IllegalStateException when the program breaks a rule of the IR
   */
  static Executable compile(Program program, int parameters, CompileMode mode, int cap) {
    IrVerifier.Layout verified = IrVerifier.verify(program, parameters);
    Specialiser.Result pass = mode == CompileMode.OPTIMISED ? Specialiser.specialise(program, parameters, verified, cap) : Specialiser.none(program, verified);
    IrVerifier.Layout layout = pass.layout();
    Program frozen = pass.program();
    Numbering numbering = Numbering.of(frozen);
    CallGraph graph = CallGraph.of(frozen, numbering);
    Facts facts = mode == CompileMode.OPTIMISED ? Facts.optimised(numbering, graph) : Facts.conservative(numbering);
    int[] slots = layout.slots();
    List<Function> functions = frozen.functions();
    LiftedFunction[] shells = new LiftedFunction[functions.size()];
    for (int i = 0; i < shells.length; i++) {
      shells[i] = new LiftedFunction(slots[i + 1], functions.get(i).closures());
    }
    Nesting nesting = Nesting.of(frozen);
    Compiler compiler = new Compiler(shells, layout.closures(), nesting, numbering, facts);
    for (int i = 0; i < shells.length; i++) {
      compiler.region = new Region(null, null, slots[i + 1]);
      compiler.body = i;
      shells[i].define(compiler.interpreter(functions.get(i).body()));
    }
    compiler.region = new Region(null, null, slots[0]);
    compiler.body = -1;
    Frozen record = new Frozen(frozen, numbering, graph, pass.origin(), pass.specialised(), pass.refused());
    return new Executable(compiler.interpreter(frozen.main()), slots[0], parameters, facts, nesting, record);
  }

  /** The op of {@code node}, built in its run form (ADR-0047 #116). */
  private Op interpreter(Node node) {
    boolean value = RunForm.of(facts, body, numbering.id(node)) == RunForm.VALUE;
    return switch (node) {
      case Identity _ -> new IdentityOp(value);
      case Const c -> new ConstOp(value, c.value());
      case Pipe p -> new PipeOp(value, interpreter(p.left()), interpreter(p.right()));
      case Comma c -> new CommaOp(value, interpreter(c.left()), interpreter(c.right()));
      case Bind b -> new BindOp(value, interpreter(b.value()), frameCell(b.slot()), interpreter(b.body()));
      case Binds b -> binds(value, b);
      case Var v -> variable(value, v);
      case Intrinsic i -> intrinsic(value, i);
      case Collect c -> new CollectOp(value, interpreter(c.body()), c.collector());
      case If i -> new IfOp(value, interpreter(i.cond()), tail(i.cond()), interpreter(i.then()), interpreter(i.otherwise()), i.truth());
      case Alt a -> new AltOp(value, interpreter(a.left()), interpreter(a.right()), a.truth());
      case AltBind a -> altBind(a);
      case Try t -> new TryOp(value, interpreter(t.body()), interpreter(t.handler()));
      case Label l -> new LabelOp(value, frameCell(l.slot()), interpreter(l.body()));
      case Break b -> {
        At at = at(b.slot());
        yield new BreakOp(at.level(), at.cell());
      }
      case Reduce r -> new ReduceOp(value, interpreter(r.init()), frameCell(r.slot()), interpreter(r.each()));
      case Foreach f -> new ForeachOp(value, interpreter(f.init()), frameCell(f.slot()), interpreter(f.each()));
      case Advance a -> new AdvanceOp(value, frameCell(a.slot()), interpreter(a.update()));
      case Call c -> call(value, c);
      case ParamCall p -> {
        At at = parameter(p.param());
        yield new ParamCallOp(at.level(), at.cell(), nesting.units(p), nesting.starterUnits());
      }
    };
  }

  /** The cell of {@code slot}, which a node of the running frame binds (the verifier keeps a loop's Advance in it). */
  private int frameCell(int slot) {
    return region.cells() == null ? slot : region.cells().get(slot);
  }

  /** Where {@code slot}, in scope here, is: in the running frame, or in the frame of the innermost region binding it. */
  private At at(int slot) {
    int level = 0;
    Region r = region;
    while (r.cells() != null) {
      Integer cell = r.cells().get(slot);
      if (cell != null) {
        return new At(level, cell);
      }
      r = r.outer();
      level++;
    }
    return new At(level, slot);
  }

  /** Where the closure parameter {@code param} of the function whose body this is lies: a cell after its slots. */
  private At parameter(int param) {
    int level = 0;
    Region r = region;
    while (r.cells() != null) {
      r = r.outer();
      level++;
    }
    return new At(level, r.base() + param);
  }

  /** A Var in the run form {@code value}: its slot in the running frame, or in a frame further out. */
  private Op variable(boolean value, Var v) {
    At at = at(v.slot());
    return at.level() == 0 ? new VarOp(value, at.cell()) : new OuterVarOp(value, at.level(), at.cell());
  }

  /**
   * The Call {@code c} of its function's shell, in the run form {@code value}: its values read here, and each closure
   * written here or, a bare ParamCall, the caller's closure passed on.
   */
  private Op call(boolean value, Call c) {
    List<Node> nodes = c.values();
    Argument[] values = new Argument[nodes.size()];
    for (int k = 0; k < values.length; k++) {
      values[k] = argument(nodes.get(k));
    }
    List<Node> written = c.closures();
    ClosureArgument[] closures = new ClosureArgument[written.size()];
    for (int j = 0; j < closures.length; j++) {
      closures[j] = closure(written.get(j));
    }
    return new CallOp(value, shells[c.fn()], closures, values, nesting.units(c), nesting.starterUnits());
  }

  /**
   * A closure argument: a bare ParamCall passed on; a body that binds no slot built here, in its defining frame; any
   * other built in a region of its own, cell 0 the link and its slots from cell 1.
   */
  private ClosureArgument closure(Node body) {
    if (body instanceof ParamCall p) {
      At at = parameter(p.param());
      return ClosureArgument.passed(at.level(), at.cell());
    }
    List<Integer> own = closures.get(body);
    long emitDepth = nesting.emitDepth(body);
    if (own.isEmpty()) {
      return ClosureArgument.written(interpreter(body), 0, emitDepth);
    }
    Map<Integer, Integer> cells = new HashMap<>();
    for (int slot : own) {
      cells.put(slot, cells.size() + 1);
    }
    Region outer = region;
    region = new Region(outer, cells, 0);
    Op op = interpreter(body);
    region = outer;
    return ClosureArgument.written(op, cells.size() + 1, emitDepth);
  }

  /**
   * The Binds {@code b} in the run form {@code value}, its values built in a loop, so the build does not go deeper with
   * their number.
   */
  private Op binds(boolean value, Binds b) {
    List<Node> values = b.values();
    Op[] ops = new Op[values.size()];
    boolean[] tails = new boolean[ops.length];
    for (int k = 0; k < ops.length; k++) {
      ops[k] = interpreter(values.get(k));
      tails[k] = tail(values.get(k));
    }
    return new BindsOp(value, ops, tails, frameCells(b.slots()), interpreter(b.body()));
  }

  /**
   * Whether {@code node} is a tail emitter (ADR-0043), decided by its kind alone: an Identity, a Const, a Var, an
   * Intrinsic of a one-result call form, or a Pipe, If, Bind or Binds whose parts are all tail emitters. Such a node
   * gives one result at most and holds no Break, so it never breaks out (ADR-0008). Every other kind, a Break and a
   * Call among them, is none.
   */
  static boolean tail(Node node) {
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

  /** The chain {@code a}, its lists as the arrays the run reads, each from-slot read where it is or a constant null. */
  private Op altBind(AltBind a) {
    List<Alternative> alternatives = a.alternatives();
    Op[] matches = new Op[alternatives.size()];
    Argument[][] from = new Argument[matches.length][];
    for (int k = 0; k < matches.length; k++) {
      matches[k] = interpreter(alternatives.get(k).match());
      List<Integer> slots = alternatives.get(k).from();
      from[k] = new Argument[slots.size()];
      for (int g = 0; g < from[k].length; g++) {
        int slot = slots.get(g);
        if (slot == Alternative.NULL) {
          from[k][g] = Argument.constant(null);
        } else {
          At at = at(slot);
          from[k][g] = Argument.slot(at.level(), at.cell());
        }
      }
    }
    return new AltBindOp(interpreter(a.value()), frameCell(a.slot()), matches, from, frameCells(a.slots()), interpreter(a.body()));
  }

  /** The cells of {@code slots}, each bound by a node of the running frame. */
  private int[] frameCells(List<Integer> slots) {
    int[] cells = new int[slots.size()];
    for (int i = 0; i < cells.length; i++) {
      cells[i] = frameCell(slots.get(i));
    }
    return cells;
  }

  /**
   * The call of {@code i} in its call form and the run form {@code value}, with the arity and the atomic arguments the
   * verifier checked; a Many is never value form.
   */
  private Op intrinsic(boolean value, Intrinsic i) {
    List<Node> nodes = i.args();
    Argument[] args = new Argument[nodes.size()];
    for (int k = 0; k < args.length; k++) {
      args[k] = argument(nodes.get(k));
    }
    return switch (i.op().form()) {
      case Fn0 fn -> new IntrinsicOp(value, fn);
      case Fn1 fn -> new Intrinsic1Op(value, fn, args[0]);
      case Fn2 fn -> new Intrinsic2Op(value, fn, args[0], args[1]);
      case FnN fn -> new IntrinsicNOp(value, fn, args);
      case Many many -> new IntrinsicManyOp(many, args);
    };
  }

  /**
   * An atomic argument: a Var's slot where it is, a Const's value, or else an Identity's input, the only other kind the
   * verifier lets through.
   */
  private Argument argument(Node a) {
    return switch (a) {
      case Var v -> {
        At at = at(v.slot());
        yield Argument.slot(at.level(), at.cell());
      }
      case Const c -> Argument.constant(c.value());
      default -> Argument.input();
    };
  }
}
