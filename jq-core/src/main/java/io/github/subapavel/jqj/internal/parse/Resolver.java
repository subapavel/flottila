package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.Stub;
import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.Basics;
import io.github.subapavel.jqj.internal.op.BuildObject;
import io.github.subapavel.jqj.internal.op.Empty;
import io.github.subapavel.jqj.internal.op.Field;
import io.github.subapavel.jqj.internal.op.Flatten;
import io.github.subapavel.jqj.internal.op.Formats;
import io.github.subapavel.jqj.internal.op.Index;
import io.github.subapavel.jqj.internal.op.Interpolate;
import io.github.subapavel.jqj.internal.op.Iterate;
import io.github.subapavel.jqj.internal.op.JqTruth;
import io.github.subapavel.jqj.internal.op.Keys;
import io.github.subapavel.jqj.internal.op.Last;
import io.github.subapavel.jqj.internal.op.LoopState;
import io.github.subapavel.jqj.internal.op.NumberTests;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.op.Raise;
import io.github.subapavel.jqj.internal.op.Range;
import io.github.subapavel.jqj.internal.op.Slice;
import io.github.subapavel.jqj.internal.op.Sort;
import io.github.subapavel.jqj.internal.op.StepRun;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
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
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Pipe;
import io.github.subapavel.jqj.query.ir.Reduce;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.ir.Try;
import io.github.subapavel.jqj.query.ir.Var;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Lowers a parsed filter to IR (GLOSSARY "Resolver", ADR-0032), pass 2 after the {@link Binder} (ADR-0040). Built so
 * far (#33, #34, #61, #63): {@code .}; a field by name or by a plain string, {@code .[k]}, {@code .[a:b]} and {@code
 * .[]}, each with its quiet variant {@code ?} (ADR-0034), and a chain of them and {@code |}, which nest as {@code
 * Pipe(term, Intrinsic)}; {@code ,}; parentheses, which make no node; a number or plain string literal, and {@code
 * true}, {@code false} and {@code null}, each a Const; a bare {@code @text}; {@code empty}; and (#64) {@code [e]}, a
 * Collect, and {@code {k: v, ...}}, its pairs bound key-outer around one construction; and (#65) {@code + - * / %},
 * right operand outer, and {@code -t}, each result of {@code t} negated; and (#66) {@code == != < > <= >=}, right
 * operand outer, {@code and}, {@code or} and {@code not}, each an If, and {@code //}, an Alt, both on jq's truth
 * (ADR-0037); and (#67, ADR-0038) {@code E as p | body}, its pattern lowered to Binds in jq's matcher order, and {@code
 * E as p1 ?// p2 ... | body}, an AltBind; {@code $name}, a Var of its slot, a host variable's a parameter slot; {@code
 * $__loc__}, a Const; and a string with interpolations, an Intrinsic of Interpolate over its parts; and (#68, ADR-0039)
 * {@code if ... elif ... else ... end}, nested Ifs, {@code try}, {@code catch} and a postfix {@code ?} on a term that is
 * no step, a Try, {@code error} and {@code error(f)}, and {@code label $name | ...} and {@code break $name}, a Label
 * and a Break of its slot; and (#69, ADR-0041) {@code reduce} and {@code foreach}, a Reduce or Foreach around its
 * pattern's Binds and the Advance of its state slot, and {@code range(a; b)}, an Intrinsic of Range; and (#70,
 * ADR-0040) {@code def}, which makes no node: a call of a definition is its body, inlined; and (#71, ADR-0042) a call
 * of a prelude definition, {@code ..} among them, inlined so too, and an unbuilt native, a Stub; and (#72) the language
 * natives, each an Intrinsic of its operation, {@code last(f)} a Reduce over f. Every other kind is a Stub.
 *
 * <p>A key or bound that is a Const or a Var is the step's argument; any other is bound around the step's target, the
 * key outside the target and {@code a} outside {@code b} (ADR-0034, jq's {@code gen_index} and {@code
 * gen_slice_index}). A comma chain is lowered without recursion over its length: the parser's left-nested spine is
 * flattened by a loop and built again balanced, so its depth is about log2 of its length, with the same results and
 * ticks (F-32). A chain of postfix steps and a run of definitions are lowered by loops too (F-42, #70): a chain's
 * nodes are piped balanced past {@link #PIPE_CHAIN} of them, and a run of more than {@link #STEP_RUN} steps of one
 * result or none is one step that calls them in turn ({@link StepRun}), so a chain's depth does not grow with its
 * length (F-44), with the same results and ticks.
 *
 * <p>The Binder has bound every reference and thrown jq's binding errors, so each name found here has its declaration
 * in the environment ({@link Env}): a slot, a closure or a definition's body. A call of a definition lowers its body in
 * the environment the definition was written in, plus its parameters, with fresh slots for each copy; a filter
 * parameter is the caller's closure, lowered in the caller's environment on the callee's input at each use, and a value
 * parameter {@code $a} is also a Bind of it around the body, the first parameter outermost (ADR-0040), and past {@link
 * #BIND_RUN} of them their one Binds (ADR-0043), as an object's, an interpolation's and a pattern's Binds are.
 * Lowering a call of a recursive definition is a Stub until spec 2b. One compile spends at most {@link #INLINE_CAP} nodes inside
 * expansions, and nests at most {@link #EXPANSIONS} - 1 of them; past either the filter is a Stub (GLOSSARY "Inline
 * cap"), and so is a filter of more than {@link #MAX_IR_DEPTH} levels of IR (F-44). A Stub is thrown at once.
 *
 * <p>A prelude definition's body is written in no environment of the filter's, and every node lowered from it takes
 * the Source of the filter's call that reached the prelude, while a closure argument of the filter keeps its own
 * (ADR-0042 "Source", amending ADR-0031): {@link #here} gives each new node its Source.
 */
public final class Resolver {
  /** jq's message for a program with no top-level filter, which is no syntax error (ADR-0032). */
  private static final String NOT_GIVEN = "jq: error: Top-level program not given (try \".\")";

  /** ADR-0040: how many nodes one compile lowers inside expansions, definition bodies and closures, at most. */
  static final int INLINE_CAP = 65_536;

  /** ADR-0040: the nesting of expansions that is a Stub, the 512th (#70 red ruling: 511 nested compile). */
  static final int EXPANSIONS = 512;

  /**
   * ADR-0040 "IR depth" and "What counts" (F-44): the most levels of built IR a filter may have, the leaf counted
   * ({@code [[.]]} has three); past it the filter is a Stub, as the 512th nested expansion is, since nested expansions
   * and chains of computed keys would otherwise stack the IR, and the Resolver's, IrVerifier's and the Compiler's
   * recursion on it, as deep as the inline cap lets them. The depth is the whole built IR's, as IrVerifier recurses on
   * it ({@link #within}), so every node counts wherever the Resolver built it: in an Ast's lowering, as a value
   * parameter's Bind around an expansion, as a chain's Bind of a computed key, or in a balanced Pipe or Comma tree. The
   * lowering also stops when it is inside more levels than this ({@link #levels}), so that its own recursion ends
   * before it builds the IR that would be measured too deep.
   */
  static final int MAX_IR_DEPTH = 512;

  /**
   * What the lowering and the measure throw past {@link #MAX_IR_DEPTH}: one instance, made with the class, with no
   * message, cause or stack trace, so the throw allocates and loads nothing at depth; {@link #resolve(Program, List)}
   * turns it into the Stub where the compile has unwound (F-44).
   */
  private static final TooDeep TOO_DEEP = new TooDeep();

  /** The signal of {@link #TOO_DEEP}. */
  private static final class TooDeep extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private TooDeep() {
      super(null, null, false, false);
    }
  }

  /**
   * The longest run of steps of one result or none that stays steps of their own: the parser's nesting bound, so a
   * run nests no more calls when it runs than the nesting the parser takes. A longer run is one {@link StepRun} (F-42,
   * F-44).
   */
  static final int STEP_RUN = Parser.MAX_DEPTH;

  /**
   * The most nodes of a chain that stay a Pipe chain, as deep as they are many; more are piped balanced, about log2 of
   * their number deep (F-44). An eighth of {@link #MAX_IR_DEPTH}, so a chain's own nesting leaves most of the bound
   * to what it is inside: a Pipe chain of {@link #STEP_RUN} steps with arguments would be past the bound alone.
   */
  static final int PIPE_CHAIN = MAX_IR_DEPTH / 8;

  /**
   * ADR-0043: the most nested Binds a construct lowers to: an object, an interpolation, a pattern, the value parameters
   * of an expansion, or a run of one arithmetic or comparison operator ({@link #binds}). Past it the construct is one
   * Binds, a level however wide, its values in the order the nested form runs them. It counts Binds, not parts: a term,
   * key or value passed as it is takes none. {@link #PIPE_CHAIN}, so a construct below it keeps the nested form and its
   * pins.
   */
  static final int BIND_RUN = PIPE_CHAIN;

  /** A key or bound that is passed as a Const argument, so it takes no slot and no Bind. */
  private static final int NO_SLOT = -1;

  /** The slots taken so far: the parameters', then each Bind's and AltBind's in turn (ADR-0034, ADR-0038). */
  private int slots;

  /** The compile's fold budget, carried from the parser (ADR-0035). */
  private final ConstantKey folds;

  /** The filter's text: it gives each {@code $__loc__} its line. */
  private final FilterText text;

  /** What pass 1 bound each reference to. */
  private final Binder binder;

  /** The declarations in scope where the lowering is, innermost first. */
  private Env env;

  /** How many expansions the lowering is inside. */
  private int depth;

  /** The nodes lowered inside expansions so far. */
  private int inlined;

  /**
   * The levels of IR the lowering is inside ({@link #MAX_IR_DEPTH}): one for each Ast that builds a node of its own and
   * one for each value parameter Bound around an expansion's body, or one for their Binds past {@link #BIND_RUN} of
   * them. Every throw ends the compile, so none restores it.
   */
  private int levels;

  /**
   * The Source of every node the lowering builds now: the filter's call that reached the prelude, while the lowering is
   * in text of the prelude's; null in the filter's own text, where each node takes its own (ADR-0042 "Source").
   */
  private Source site;

  /**
   * A declaration the Binder bound a reference to, in scope where the lowering is: a variable's, label's or value
   * parameter's slot, and a filter parameter's, value parameter's or definition's closure; then the declarations
   * outside it.
   */
  private record Env(Object declaration, int slot, Closure closure, Env outer) {}

  /**
   * A body, the environment it was written in and the {@link #site} it was written at: a filter argument, lowered in
   * its caller's environment (GLOSSARY "Closure"), or a definition's body, lowered in its definition's.
   */
  private record Closure(Ast body, Env env, Source site) {}

  /** A Bind a pattern's match lowers to: its value, its slot, and the part of the pattern it spans. */
  private record Step(Node value, int slot, Source source) {}

  /** A pattern's match so far: its steps in jq's matcher order, and for each name the slot of its latest store. */
  private static final class Match {
    final List<Step> steps = new ArrayList<>();
    final Map<String, Integer> latest = new HashMap<>();
  }

  /**
   * A postfix step of a chain, its keys and bounds lowered: a step on its target's results, one whose computed keys are
   * bound around it and its target, or a {@code ?} on its target (F-44, ADR-0040 "Chains").
   */
  private sealed interface Peeled {}

  /**
   * A step on its target's results: {@code op} on the atomic {@code args}, spanning {@code own}, or {@code alone} when
   * no node comes before it (a bracket step on the leading dot of {@code .[...]} spans the dot too). {@code read} is
   * the step as a function of its input alone when it has one result or none and constant arguments, a field read or
   * a constant index or slice, which a long run of them fuses ({@link StepRun}); else null.
   */
  private record Stage(Source own, Source alone, Operation op, List<Node> args, Fn0 read) implements Peeled {}

  /** A step whose keys or bounds are no Const or Var: each value bound to its slot around it and its target, the first outermost. */
  private record Bound(List<Node> values, List<Integer> slots, Stage stage) implements Peeled {}

  /** A {@code ?} on a term that is no step, spanning {@code at}: a Try with {@code empty} as its handler around its target. */
  private record Quiet(Source at) implements Peeled {}

  private Resolver(ConstantKey folds, FilterText text, Binder binder) {
    this.folds = folds;
    this.text = text;
    this.binder = binder;
  }

  /**
   * The IR of {@code program}, which declares no host variable.
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's binding errors, or a
   *     Stub
   */
  public static Node resolve(Program program) {
    return resolve(program, List.of());
  }

  /**
   * The IR of {@code program} with the host variables {@code parameters}, each in scope in the whole filter, in the
   * parameter slot of its place in the list (ADR-0038: the engine passes the declared names sorted).
   *
   * @throws JqCompileException jq's not-given error when the program has only definitions, jq's binding errors, or a
   *     Stub
   */
  public static Node resolve(Program program, List<String> parameters) {
    if (program.main() == null) {
      throw new JqCompileException(List.of(NOT_GIVEN), 1, 1);
    }
    if (program.module() != null || !program.imports().isEmpty()) {
      throw Stub.unbuilt("the module directive and imports");
    }
    Binder binder = Binder.bind(program.main(), parameters, program.text());
    Resolver resolver = new Resolver(program.folds(), program.text(), binder);
    for (Object host : binder.hosts()) {
      resolver.env = new Env(host, resolver.slots++, null, resolver.env);
    }
    try {
      Node root = resolver.lower(program.main());
      // The whole IR, measured once at the top of the stack (ADR-0040 "What counts").
      within(root, MAX_IR_DEPTH);
      return root;
    } catch (TooDeep e) {
      throw Stub.unbuilt("a filter of more than " + MAX_IR_DEPTH + " levels of IR");
    }
  }

  /**
   * The IR of {@code ast}. Every level of the lowering's recursion has a frame of this method, so it only tests and
   * passes {@code ast} on, and its frame stays small in any JIT state; {@link #built} and {@link #stub} keep their
   * frames small too (#86: a C1-compiled lowering ran past a 512 KiB stack before the bound's 513th level could stub,
   * IrDepthTest).
   */
  private Node lower(Ast ast) {
    if (ast instanceof Ast.Paren) {
      return lower(((Ast.Paren) ast).body());
    }
    if (ast instanceof Ast.Def) {
      return definitions((Ast.Def) ast);
    }
    if (ast instanceof Ast.Call) {
      return call(ast, (Ast.Call) ast);
    }
    if (ast instanceof Ast.Recurse) {
      return recurse(ast);
    }
    return built(ast);
  }

  /** {@code ..}: jq's parser.y lowers it to a call of recurse/0, which the Binder bound (ADR-0042 "Recursion"). */
  private Node recurse(Ast dots) {
    return call(dots, new Ast.Call(dots.source(), "recurse", List.of()));
  }

  /**
   * The node of its own that {@code ast}, of a kind {@link #lower} does not pass on, builds: an expansion pays for it
   * (ADR-0040 "Inline cap"), and it is one level deeper.
   */
  private Node built(Ast ast) {
    spend();
    if (++levels > MAX_IR_DEPTH) {
      throw TOO_DEEP;
    }
    // Unnamed patterns and casts: javac 25 gives each named binding of a switch a local slot of its own (javap -v: a
    // switch of four named record patterns has 7 locals, of four unnamed ones 3; this method has 5), and every one of
    // them is in each interpreted frame of this method.
    Node node =
        switch (ast) {
          case Ast.Dot _ -> new Identity(here(ast.source()));
          case Ast.Pipe _ -> pipe(lower(((Ast.Pipe) ast).left()), lower(((Ast.Pipe) ast).right()));
          case Ast.Comma _ -> comma((Ast.Comma) ast);
          // One arm per kind, as in parts.
          case Ast.Field _ -> chain(ast);
          case Ast.StringField _ -> chain(ast);
          case Ast.Index _ -> chain(ast);
          case Ast.Each _ -> chain(ast);
          case Ast.Slice _ -> chain(ast);
          case Ast.Optional _ -> chain(ast);
          case Ast.Num _ -> new Const(here(ast.source()), LiteralNumber.ofLexeme(((Ast.Num) ast).lexeme()));
          case Ast.Str _ -> string((Ast.Str) ast);
          case Ast.Var _ -> variable(ast, ast.source());
          case Ast.Loc _ -> location(ast.source());
          case Ast.Bind _ -> binding((Ast.Bind) ast);
          case Ast.Format _ -> format((Ast.Format) ast);
          case Ast.ArrayCons _ -> array((Ast.ArrayCons) ast);
          case Ast.ObjectCons _ -> object((Ast.ObjectCons) ast);
          case Ast.Binary _ -> binary((Ast.Binary) ast);
          case Ast.Neg _ -> pipe(lower(((Ast.Neg) ast).operand()), new Intrinsic(here(ast.source()), Arithmetic.NEGATE, List.of()));
          case Ast.If _ -> conditional((Ast.If) ast);
          case Ast.Try _ -> attempt(here(ast.source()), ((Ast.Try) ast).body(), ((Ast.Try) ast).handler());
          case Ast.Label _ -> label((Ast.Label) ast);
          case Ast.Break _ -> new Break(here(ast.source()), find(binder.binding(ast)).slot());
          case Ast.Reduce _ -> reduce((Ast.Reduce) ast);
          case Ast.Foreach _ -> foreach((Ast.Foreach) ast);
          // Each other kind belongs to a later ticket (spec 2a and 2b).
          default -> throw unbuilt(ast);
        };
    levels--;
    return node;
  }

  /** A bare {@code @name}: an Intrinsic of its format when it is built, else a Stub. */
  private Node format(Ast.Format f) {
    if (Formats.lookup(f.name()) == null) {
      throw unbuilt(f);
    }
    return new Intrinsic(here(f.source()), Formats.lookup(f.name()), List.of());
  }

  /** A {@link #chained} operator, else a Stub. */
  private Node binary(Ast.Binary b) {
    if (!chained(b.op())) {
      throw unbuilt(b);
    }
    return operators(b);
  }

  /** The Stub of a kind, or of a format or an operator, that belongs to a later ticket (spec 2a and 2b). */
  private JqCompileException unbuilt(Ast ast) {
    return stub("the ", ast.getClass().getSimpleName(), " at byte ", here(ast.source()).start());
  }

  /**
   * The Stub of {@code what}, its parts joined. Every Stub of the lowering is made here, by a loop, so this method is
   * too large for C1 to inline (35 bytes of bytecode, C1MaxInlineSize): a string concatenation's linked call site, or
   * {@link Stub#unbuilt}'s, inlined into a method the lowering recurses through put its force-inlined code into that
   * method's frame, and the 500 nested value parameters of IrDepthTest past a C1-compiled 512 KiB stack (#86).
   */
  private static JqCompileException stub(Object... what) {
    StringBuilder message = new StringBuilder();
    for (Object part : what) {
      message.append(part);
    }
    return Stub.unbuilt(message.toString());
  }

  /**
   * Throws {@link #TOO_DEEP} when a path from {@code node} to a leaf, as IrVerifier recurses on it, has more than
   * {@code room} nodes: a leaf is one level, and an Intrinsic with arguments two. The walk stops there, so it is never
   * more than {@code room} nodes deep.
   */
  private static void within(Node node, int room) {
    if (room == 0) {
      throw TOO_DEEP;
    }
    for (Node part : parts(node)) {
      within(part, room - 1);
    }
  }

  /** The nodes IrVerifier visits right below {@code node}: an Intrinsic's arguments too. */
  private static List<Node> parts(Node node) {
    // One arm per kind: javac compiles a case of two patterns to a second dispatch for a node that matches neither,
    // a path no node can take (IrVerifier).
    return switch (node) {
      case Identity _ -> List.of();
      case Const _ -> List.of();
      case Var _ -> List.of();
      case Break _ -> List.of();
      case Intrinsic i -> i.args();
      case Pipe p -> List.of(p.left(), p.right());
      case Comma c -> List.of(c.left(), c.right());
      case Collect c -> List.of(c.body());
      case If i -> List.of(i.cond(), i.then(), i.otherwise());
      case Alt a -> List.of(a.left(), a.right());
      case Bind b -> List.of(b.value(), b.body());
      case Binds b -> {
        List<Node> parts = new ArrayList<>(b.values());
        parts.add(b.body());
        yield parts;
      }
      case AltBind a -> {
        List<Node> parts = new ArrayList<>();
        parts.add(a.value());
        for (Alternative alternative : a.alternatives()) {
          parts.add(alternative.match());
        }
        parts.add(a.body());
        yield parts;
      }
      case Try t -> List.of(t.body(), t.handler());
      case Label l -> List.of(l.body());
      case Reduce r -> List.of(r.init(), r.each());
      case Foreach f -> List.of(f.init(), f.each());
      case Advance a -> List.of(a.update());
    };
  }

  /**
   * The Source of a node built now for the text at {@code own}: {@code own} in the filter's text, and the {@link #site}
   * of the call that reached the prelude in text of the prelude's (ADR-0042 "Source").
   */
  private Source here(Source own) {
    return site == null ? own : site;
  }

  /** Spends one node of the compile's inline cap when the lowering is inside an expansion; past the cap, a Stub. */
  private void spend() {
    if (depth > 0 && ++inlined > INLINE_CAP) {
      throw stub("a filter that inlines more than ", INLINE_CAP, " nodes");
    }
  }

  /**
   * The call {@code c}, the {@code reference} the Binder bound: a parse constant, a native, the body of a definition or
   * of a prelude one, or a parameter's closure; an unbuilt native is a Stub.
   */
  private Node call(Ast reference, Ast.Call c) {
    return switch ((Binder.Callee) binder.binding(reference)) {
      case Binder.Constant k -> {
        spend();
        yield new Const(here(c.source()), k.value());
      }
      case Binder.Native n -> {
        spend();
        yield natively(n, c);
      }
      case Binder.Unbuilt u -> throw stub("the native ", u.signature(), " at byte ", here(c.source()).start());
      case Binder.Defined d -> {
        Closure body = find(d.def()).closure();
        yield expand(d.def(), c, body.env(), body.site());
      }
      // A prelude body is written in no environment of the filter's, and its nodes take the Source of the filter's
      // call that reached the prelude: this call's, or the one a prelude body that calls it was reached by.
      case Binder.Library l -> expand(l.def(), c, null, site == null ? c.source() : site);
      case Binder.Parameter p -> closure(find(p.param()).closure());
    };
  }

  /**
   * A call of the native {@code n} (ADR-0040's native table, ADR-0041's {@code range/2}, #72's language natives, #73's
   * sort and its kin), one arm a line: an operation on the input, or on one argument ({@link #withArgument}), or {@link #last}.
   */
  private Node natively(Binder.Native n, Ast.Call c) {
    Source at = here(c.source());
    return switch (n) {
      case EMPTY -> new Intrinsic(at, Empty.OP, List.of());
      case ERROR -> new Intrinsic(at, Raise.OP, List.of());
      case NOT -> not(at);
      case RANGE -> range(c);
      case LENGTH -> new Intrinsic(at, Basics.LENGTH, List.of());
      case TYPE -> new Intrinsic(at, Basics.TYPE, List.of());
      case TOSTRING -> new Intrinsic(at, Basics.TOSTRING, List.of());
      case TOJSON -> new Intrinsic(at, Basics.TOJSON, List.of());
      case TONUMBER -> new Intrinsic(at, Basics.TONUMBER, List.of());
      case LAST -> last(c);
      case HAS -> withArgument(c, Keys.HAS);
      case KEYS -> new Intrinsic(at, Keys.KEYS, List.of());
      case KEYS_UNSORTED -> new Intrinsic(at, Keys.KEYS_UNSORTED, List.of());
      case TO_ENTRIES -> new Intrinsic(at, Keys.TO_ENTRIES, List.of());
      case FLATTEN -> new Intrinsic(at, Flatten.ALL, List.of());
      case FLATTEN_DEPTH -> withArgument(c, Flatten.DEPTH);
      case NAN -> new Intrinsic(at, NumberTests.NAN, List.of());
      case INFINITE -> new Intrinsic(at, NumberTests.INFINITE, List.of());
      case ISNAN -> new Intrinsic(at, NumberTests.ISNAN, List.of());
      case ISINFINITE -> new Intrinsic(at, NumberTests.ISINFINITE, List.of());
      case ISNORMAL -> new Intrinsic(at, NumberTests.ISNORMAL, List.of());
      case HAVE_DECNUM -> new Intrinsic(at, NumberTests.HAVE_DECNUM, List.of());
      case HAVE_LITERAL_NUMBERS -> new Intrinsic(at, NumberTests.HAVE_LITERAL_NUMBERS, List.of());
      case SORT -> new Intrinsic(at, Sort.SORT, List.of());
      case SORT_BY -> withArgument(c, Sort.SORT_BY);
      case GROUP_BY -> withArgument(c, Sort.GROUP_BY);
      case UNIQUE -> new Intrinsic(at, Sort.UNIQUE, List.of());
      case UNIQUE_BY -> withArgument(c, Sort.UNIQUE_BY);
      case MIN -> new Intrinsic(at, Sort.MIN, List.of());
      case MAX -> new Intrinsic(at, Sort.MAX, List.of());
      case MIN_BY -> withArgument(c, Sort.MIN_BY);
      case MAX_BY -> withArgument(c, Sort.MAX_BY);
    };
  }

  /**
   * The call {@code c} of a native of one argument, the Fn1 {@code op} on each result of it: an Identity, Const or Var
   * argument passed as it is, any other bound around the call, as an operator's operand is (ADR-0034 amendment for
   * #65). {@code has(k)}'s key, {@code flatten($x)}'s depth and the keys of {@code _sort_by_impl} and its kin lower
   * so.
   */
  private Node withArgument(Ast.Call c, Operation op) {
    Node arg = lower(c.args().getFirst());
    int slot = operandSlot(arg);
    return bind(arg, slot, new Intrinsic(here(c.source()), op, List.of(argument(arg, slot))));
  }

  /**
   * {@code last(f)}, jq's bytecoded {@code gen_last_1} (#72): {@code Reduce(UNSET, s, Bind(f, v, Advance(s, $v)))}
   * piped into {@link Last}, so the state slot holds f's last result, or {@link Last#UNSET} when f gave none, which
   * {@link Last} drops (ADR-0041's Reduce and Advance).
   */
  private Node last(Ast.Call c) {
    Source at = here(c.source());
    int state = slots++;
    Node values = lower(c.args().getFirst());
    int value = slots++;
    Node each = new Bind(at, values, value, new Advance(at, state, new Var(at, value)));
    return pipe(new Reduce(at, new Const(at, Last.UNSET), state, each), new Intrinsic(at, Last.OP, List.of()));
  }

  /**
   * The call {@code c} of {@code def}, inlined (ADR-0040): its body lowered in {@code defining}, its definition's
   * environment, at {@code bodySite}, plus its parameters, each bound to its argument's closure in the caller's
   * environment, a closure that is a parameter of the caller passed on as it is; each value parameter's closure is also
   * lowered here, on the call's input, and bound to a slot of its own around the body, the first parameter outermost.
   * A recursive definition is a Stub until spec 2b, and so is the expansion nested {@link #EXPANSIONS} deep.
   */
  private Node expand(Ast.FuncDef def, Ast.Call c, Env defining, Source bodySite) {
    if (binder.recursive(def)) {
      throw stub("the call of the recursive ", def.name(), "/", def.params().size(), " at byte ", here(c.source()).start());
    }
    if (depth + 1 >= EXPANSIONS) {
      throw stub("a filter that nests ", EXPANSIONS, " expansions");
    }
    depth++;
    Env caller = env;
    Source callerSite = site;
    Env inner = defining;
    List<Ast.Param> params = def.params();
    Node[] values = new Node[params.size()];
    int[] valueSlots = new int[params.size()];
    int binds = 0;
    for (int i = 0; i < params.size(); i++) {
      Ast.Param p = params.get(i);
      Closure argument = argument(c.args().get(i));
      valueSlots[i] = NO_SLOT;
      if (p.value()) {
        values[i] = closure(argument);
        valueSlots[i] = slots++;
        binds++;
      }
      inner = new Env(p, valueSlots[i], argument, inner);
    }
    // Each value parameter's Bind is a level around the body, or their one Binds is one level past BIND_RUN of them
    // (ADR-0043), so the body's first node of its own, which every body builds, stops the lowering past MAX_IR_DEPTH
    // as early as its levels do.
    int around = binds > BIND_RUN ? 1 : binds;
    levels += around;
    env = inner;
    site = bodySite;
    Node body = lower(def.body());
    env = caller;
    levels -= around;
    // The value parameters' Binds are the body's, at its site: one Binds past BIND_RUN of them, the first outermost.
    if (binds > BIND_RUN) {
      body = oneBinds(here(body.source()), values, valueSlots, body);
    } else {
      for (int i = params.size() - 1; i >= 0; i--) {
        body = bind(values[i], valueSlots[i], body);
      }
    }
    site = callerSite;
    depth--;
    return body;
  }

  /** The closure of the argument {@code arg} here: the caller's own closure when {@code arg} is a parameter's call. */
  private Closure argument(Ast arg) {
    if (binder.binding(arg) instanceof Binder.Parameter p) {
      return find(p.param()).closure();
    }
    return new Closure(arg, env, site);
  }

  /** {@code closure}'s body lowered in its environment, at its site. */
  private Node closure(Closure closure) {
    Env at = env;
    Source outer = site;
    env = closure.env();
    site = closure.site();
    Node node = lower(closure.body());
    env = at;
    site = outer;
    return node;
  }

  /**
   * A run of definitions, {@code def f: ...; def g: ...; body}, by a loop, since the parser nests a long top-level run
   * as deep as it is long: each definition makes no node, only its body's closure in the environment where it is
   * written, which its calls lower (ADR-0040).
   */
  private Node definitions(Ast.Def d) {
    Env outer = env;
    Ast rest = d;
    while (rest instanceof Ast.Def def) {
      env = new Env(def.def(), NO_SLOT, new Closure(def.def().body(), env, site), env);
      rest = def.body();
    }
    Node node = lower(rest);
    env = outer;
    return node;
  }

  /** The innermost entry of the environment for {@code declaration}, which the Binder found in scope. */
  private Env find(Object declaration) {
    Env e = env;
    while (e.declaration() != declaration) {
      e = e.outer();
    }
    return e;
  }

  /**
   * A chain of postfix steps, by a loop (F-42): first each step from the outermost in, its keys and bounds lowered
   * before its target as jq places them; then the term the chain starts from; then each step around the node so far,
   * from the innermost out. A step without a target stands alone; a bracket step on the leading dot of {@code .[...]}
   * stands alone, spanning the dot too. {@code t."a\(f)"} is {@code t[k]} with the string as its key (jq's {@code
   * gen_index}); a postfix {@code ?} on a term that is no step is a Try with {@code empty} as its handler (ADR-0039).
   * Each step but the outermost, which {@link #lower} paid for, spends a node of the inline cap.
   */
  private Node chain(Ast outermost) {
    ArrayDeque<Peeled> steps = new ArrayDeque<>();
    Ast innermost = outermost;
    Ast node = outermost;
    Ast term = null;
    while (node != null && term == null) {
      Ast target =
          switch (node) {
            case Ast.Field f -> {
              steps.push(read(here(f.source()), "." + f.name() + (f.optional() ? "?" : ""), new Field(f.name(), f.optional())));
              yield f.target();
            }
            case Ast.StringField f when plain(f.name()) -> {
              BuiltString name = BuiltString.of(bytes(f.name()));
              steps.push(read(here(f.source()), "." + name.toJson() + (f.optional() ? "?" : ""), new Field(name.value(), f.optional())));
              yield f.target();
            }
            case Ast.StringField f -> {
              steps.push(keyed(here(f.source()), here(f.source()), f.name(), f.optional() ? Index.QUIET : Index.PLAIN));
              yield f.target();
            }
            case Ast.Index i -> {
              steps.push(keyed(here(i.source()), here(dotted(i.target(), i.source())), i.index(), i.optional() ? Index.QUIET : Index.PLAIN));
              yield i.target();
            }
            case Ast.Each e -> {
              Operation op = e.optional() ? Iterate.QUIET : Iterate.PLAIN;
              steps.push(new Stage(here(e.source()), here(dotted(e.target(), e.source())), op, List.of(), null));
              yield e.target();
            }
            case Ast.Slice s -> {
              steps.push(slice(s));
              yield s.target();
            }
            case Ast.Optional o -> {
              steps.push(new Quiet(here(new Source(o.target().source().start(), o.source().end()))));
              yield o.target();
            }
            // The term the chain starts from, which is no step.
            default -> {
              term = node;
              yield null;
            }
          };
      if (term == null) {
        innermost = node;
        node = target;
      }
    }
    for (int i = 1; i < steps.size(); i++) {
      spend();
    }
    // The leading dot of .[...] is the bracket step's own (bracket).
    boolean own = term instanceof Ast.Dot && (innermost instanceof Ast.Index || innermost instanceof Ast.Each || innermost instanceof Ast.Slice);
    Links links = new Links(term == null || own ? null : lower(term));
    for (Peeled step : steps) {
      switch (step) {
        case Stage s -> links.add(s);
        case Bound b -> links.bound(b);
        case Quiet q -> links.quiet(q.at());
      }
    }
    return links.node();
  }

  /**
   * The IR of a chain, its steps added innermost first, so its depth does not grow with its length (F-44): its nodes
   * piped, a Pipe chain while there are at most {@link #PIPE_CHAIN} of them and balanced past that, as Pipe is
   * associative; a run of more than {@link #STEP_RUN} steps of one result or none is one {@link StepRun}, which calls
   * them in turn; and a {@code ?} covers every link before it. A {@code ?} after another covers the first's Try too, so
   * only the last is built: {@code try (try X catch empty | S) catch empty} gives what {@code try (X | S) catch empty}
   * gives, as both end at X's first error or S's, and a Try charges nothing (TryOp). A step with computed keys binds
   * them around the links so far and itself, which then are one node, so each such step nests one Bind deeper, and its
   * depth counts toward {@link #MAX_IR_DEPTH} (F-44).
   */
  private final class Links {
    /** The chain so far, first to last: a node (the term, or a Bind of computed keys) or a step not built yet. */
    private List<Object> links = new ArrayList<>();

    /** How many of the first links the last {@code ?} covers, 0 for none, and its Source. */
    private int covered;

    private Source quiet;

    Links(Node term) {
      if (term != null) {
        links.add(term);
      }
    }

    void add(Stage s) {
      links.add(s);
    }

    void bound(Bound b) {
      links.add(b.stage());
      Node body = node();
      for (int i = b.values().size() - 1; i >= 0; i--) {
        body = bind(b.values().get(i), b.slots().get(i), body);
      }
      links = new ArrayList<>(List.of(body));
      covered = 0;
    }

    void quiet(Source at) {
      covered = links.size();
      quiet = at;
    }

    /** The chain so far as one node: the covered links in a Try, then the others. */
    Node node() {
      List<Node> nodes = new ArrayList<>();
      if (covered > 0) {
        nodes.add(new Try(quiet, pipes(built(links.subList(0, covered), new ArrayList<>())), new Intrinsic(quiet, Empty.OP, List.of())));
      }
      return pipes(built(links.subList(covered, links.size()), nodes));
    }

    /**
     * {@code nodes} with {@code part}'s links after them, each step a node spanning its own text, or alone as the
     * first; a run of more than {@link #STEP_RUN} steps of one result or none is one node, named by its text.
     */
    private List<Node> built(List<Object> part, List<Node> nodes) {
      int i = 0;
      while (i < part.size()) {
        int end = i;
        while (end < part.size() && part.get(end) instanceof Stage s && s.read() != null) {
          end++;
        }
        if (end - i > STEP_RUN) {
          Stage first = (Stage) part.get(i);
          Source span = new Source((nodes.isEmpty() ? first.alone() : first.own()).start(), ((Stage) part.get(end - 1)).own().end());
          List<Fn0> reads = new ArrayList<>(end - i);
          for (Object step : part.subList(i, end)) {
            reads.add(((Stage) step).read());
          }
          String name = new String(text.bytes(), span.start(), span.end() - span.start(), StandardCharsets.UTF_8);
          nodes.add(new Intrinsic(span, new Operation(name, new StepRun(reads)), List.of()));
        } else {
          // A short run, or one link that is no such step.
          end = Math.max(end, i + 1);
          for (Object link : part.subList(i, end)) {
            nodes.add(link instanceof Stage s ? new Intrinsic(nodes.isEmpty() ? s.alone() : s.own(), s.op(), s.args()) : (Node) link);
          }
        }
        i = end;
      }
      return nodes;
    }
  }

  /** {@code nodes} piped in order: a Pipe chain of at most {@link #PIPE_CHAIN} of them, else balanced. */
  private Node pipes(List<Node> nodes) {
    if (nodes.size() > PIPE_CHAIN) {
      return pipes(nodes, 0, nodes.size());
    }
    Node node = nodes.getFirst();
    for (int i = 1; i < nodes.size(); i++) {
      node = pipe(node, nodes.get(i));
    }
    return node;
  }

  /** {@code nodes[from, to)} piped as a balanced tree, about log2 of their number deep. */
  private Node pipes(List<Node> nodes, int from, int to) {
    if (to - from == 1) {
      return nodes.get(from);
    }
    int mid = from + (to - from + 1) / 2;
    return pipe(pipes(nodes, from, mid), pipes(nodes, mid, to));
  }

  /** A field read: one result from its input alone. */
  private static Stage read(Source own, String name, Field field) {
    return new Stage(own, own, new Operation(name, field), List.of(), field);
  }

  /** The Source of a bracket step that stands alone on the leading dot of {@code .[...]}: the dot too. */
  private static Source dotted(Ast target, Source own) {
    return new Source(target.source().start(), own.end());
  }

  /**
   * A step {@code op} of one key, {@code t[k]} or {@code t."a\(f)"}, spanning {@code own}, or {@code alone} as the
   * chain's first: the key lowered now and bound outside the target unless it is a Const or a Var; a Const key makes
   * the step one of one result or none.
   */
  private Peeled keyed(Source own, Source alone, Ast keyAst, Operation op) {
    Node key = lower(keyAst);
    int slot = slotFor(key);
    Fn0 read = null;
    if (key instanceof Const k) {
      Fn1 fn = (Fn1) op.form();
      Object constant = k.value();
      read = (state, in) -> fn.apply(state, in, constant);
    }
    Stage stage = new Stage(own, alone, op, List.of(argument(key, slot)), read);
    return slot == NO_SLOT ? stage : new Bound(List.of(key), List.of(slot), stage);
  }

  /**
   * {@code t[a:b]}: {@code a} outside {@code b} outside the target, lowered now; a missing bound is a Const null; two
   * Const bounds make the step one of one result or none.
   */
  private Peeled slice(Ast.Slice s) {
    Node from = s.from() == null ? new Const(here(s.source()), JqNull.NULL) : lower(s.from());
    int fromSlot = slotFor(from);
    Node to = s.to() == null ? new Const(here(s.source()), JqNull.NULL) : lower(s.to());
    int toSlot = slotFor(to);
    Operation op = s.optional() ? Slice.QUIET : Slice.PLAIN;
    Fn0 read = null;
    if (from instanceof Const a && to instanceof Const b) {
      Fn2 fn = (Fn2) op.form();
      Object first = a.value();
      Object last = b.value();
      read = (state, in) -> fn.apply(state, in, first, last);
    }
    Stage stage = new Stage(here(s.source()), here(dotted(s.target(), s.source())), op, List.of(argument(from, fromSlot), argument(to, toSlot)), read);
    List<Node> values = new ArrayList<>();
    List<Integer> bound = new ArrayList<>();
    if (fromSlot != NO_SLOT) {
      values.add(from);
      bound.add(fromSlot);
    }
    if (toSlot != NO_SLOT) {
      values.add(to);
      bound.add(toSlot);
    }
    return values.isEmpty() ? stage : new Bound(values, bound, stage);
  }

  /**
   * Whether {@code op} is lowered by {@link #operators}: {@code + - * / %} (#65), {@code == != < > <= >=}, {@code
   * and}, {@code or} and {@code //} (#66).
   */
  private static boolean chained(BinOp op) {
    return ConstantKey.arithmetic(op) != null || ConstantKey.comparison(op) != null || op == BinOp.AND || op == BinOp.OR || op == BinOp.ALTERNATIVE;
  }

  /**
   * {@code top}, a {@link #chained} operator, and the chained operators down its left operands, by a loop (#86): the
   * parser builds a left-associative chain in a loop, as long as the filter. Each operator spends a node, as its own
   * {@link #lower} would; {@link #built} has spent {@code top}'s and its level. An arithmetic one whose operands fold
   * ({@link ConstantKey#constant}) ends the chain: it is the term the chain starts from, and {@link #lower} makes it
   * that Const. That term is lowered first, then the chain is lowered run by run from the innermost operator out, a run
   * being the operators of one {@link BinOp} next to each other ({@link #run}). No operator but {@code top} adds a level
   * while its operands are lowered: the chain adds no frame to the lowering's recursion, and the measure of the whole IR
   * ({@link #within}) stubs a chain whose nested runs are too deep (#94).
   */
  private Node operators(Ast.Binary top) {
    if (folded(top) instanceof Const whole) {
      return whole;
    }
    List<Ast.Binary> chain = new ArrayList<>();
    chain.add(top);
    while (chain.getLast().left() instanceof Ast.Binary next && chained(next.op()) && folded(next) == null) {
      spend();
      chain.add(next);
    }
    Node node = lower(chain.getLast().left());
    for (int end = chain.size(); end > 0; ) {
      int start = end - 1;
      while (start > 0 && chain.get(start - 1).op() == chain.get(end - 1).op()) {
        start--;
      }
      node = run(chain.subList(start, end), node);
      end = start;
    }
    return node;
  }

  /**
   * The {@code operators} of one {@link BinOp}, outermost first, on {@code left}, the innermost one's lowered left
   * operand: each right operand is lowered from the innermost operator out, then the run is built (ADR-0043). A {@code
   * //} is a run's only operator, the outermost of its chain, and nests to the right ({@link #definedOr}). A run of
   * {@code and} or {@code or} of more than {@link #PIPE_CHAIN} terms is joined balanced ({@link #tree}), both being
   * associative under ADR-0037's lowering; one of an arithmetic or comparison operator whose nested form has more than
   * {@link #BIND_RUN} Binds is one Binds ({@link #binds}); any other run is its operators lowered one by one, as {@link
   * #operator} lowers each.
   */
  private Node run(List<Ast.Binary> operators, Node left) {
    BinOp op = operators.getFirst().op();
    if (op == BinOp.ALTERNATIVE) {
      return definedOr(operators.getFirst(), left);
    }
    List<Node> terms = new ArrayList<>(operators.size() + 1);
    terms.add(left);
    for (int i = operators.size() - 1; i >= 0; i--) {
      terms.add(lower(operators.get(i).right()));
    }
    boolean truth = op == BinOp.AND || op == BinOp.OR;
    if (truth && terms.size() > PIPE_CHAIN) {
      return tree(op, terms, 0, terms.size());
    }
    if (!truth && nestedBinds(terms) > BIND_RUN) {
      return binds(operators, terms);
    }
    Node node = left;
    for (int j = 1; j < terms.size(); j++) {
      node = operator(operators.get(operators.size() - j), node, terms.get(j));
    }
    return node;
  }

  /**
   * The Binds a run of an arithmetic or comparison operator on {@code terms}, its leftmost first, has in its nested
   * form ({@link #applied}): one for each operator's result but the outermost one's, and one for each term that is not
   * passed as it is ({@link #passed}).
   */
  private static int nestedBinds(List<Node> terms) {
    int binds = terms.size() - 2;
    for (Node term : terms) {
      if (!passed(term)) {
        binds++;
      }
    }
    return binds;
  }

  /**
   * The run of an arithmetic or comparison operator on {@code terms}, its leftmost first, as one Binds (ADR-0043), in
   * the order its nested form runs: the right operands from the outermost operator in, then the leftmost term, then
   * each operator's result from the innermost out; the outermost operator is the body. A term that is passed as it is
   * takes no value, as in the nested form.
   */
  private Node binds(List<Ast.Binary> operators, List<Node> terms) {
    int n = operators.size();
    List<Node> values = new ArrayList<>();
    List<Integer> bound = new ArrayList<>();
    Node[] rights = new Node[n];
    for (int i = 0; i < n; i++) {
      rights[i] = value(terms.get(n - i), values, bound);
    }
    Node result = value(terms.getFirst(), values, bound);
    for (int i = n - 1; i > 0; i--) {
      result = value(call(operators.get(i), result, rights[i]), values, bound);
    }
    return new Binds(here(operators.getFirst().source()), values, bound, call(operators.getFirst(), result, rights[0]));
  }

  /**
   * The argument that reads {@code operand}: {@code operand} itself when it is {@link #passed}; else a Var of a new
   * slot, {@code operand} added to {@code values} and the slot to {@code bound}.
   */
  private Node value(Node operand, List<Node> values, List<Integer> bound) {
    int slot = operandSlot(operand);
    if (slot != NO_SLOT) {
      values.add(operand);
      bound.add(slot);
    }
    return argument(operand, slot);
  }

  /** The call of {@code b}'s operation on the atomic arguments {@code left} and {@code right}. */
  private Intrinsic call(Ast.Binary b, Node left, Node right) {
    return new Intrinsic(here(b.source()), operation(b.op()), List.of(left, right));
  }

  /**
   * {@code top}, a {@code //}, on {@code first}, its lowered left operand, and the {@code //} down its right operands,
   * by a loop: the parser nests a run of them to the right in a loop, as long as the filter. Each operator spends a
   * node, as its own {@link #lower} would; {@link #built} has spent {@code top}'s. The terms are lowered in their order,
   * then a run of at most {@link #PIPE_CHAIN} of them nests to the right as the parser built it, and a longer one is
   * joined balanced ({@link #tree}): Alt is associative, as it emits each truthy left result and runs its right side
   * only if there was none, catching nothing (ADR-0037).
   */
  private Node definedOr(Ast.Binary top, Node first) {
    List<Ast.Binary> run = new ArrayList<>();
    run.add(top);
    List<Node> terms = new ArrayList<>();
    terms.add(first);
    Ast right = top.right();
    while (right instanceof Ast.Binary next && next.op() == BinOp.ALTERNATIVE) {
      spend();
      run.add(next);
      terms.add(lower(next.left()));
      right = next.right();
    }
    terms.add(lower(right));
    if (terms.size() > PIPE_CHAIN) {
      return tree(BinOp.ALTERNATIVE, terms, 0, terms.size());
    }
    Node node = terms.getLast();
    for (int i = run.size() - 1; i >= 0; i--) {
      node = operator(run.get(i), terms.get(i), node);
    }
    return node;
  }

  /**
   * The terms {@code [from, to)}, at least one, joined by {@code op}, {@code and}, {@code or} or {@code //}, as a
   * balanced tree, each half one operand (ADR-0043); the left half takes the middle term.
   */
  private Node tree(BinOp op, List<Node> terms, int from, int to) {
    if (to - from == 1) {
      return terms.get(from);
    }
    int mid = from + (to - from + 1) / 2;
    Node left = tree(op, terms, from, mid);
    Node right = tree(op, terms, mid, to);
    return joined(op, here(new Source(left.source().start(), right.source().end())), left, right);
  }

  /** The Const of {@code b} when it is an arithmetic operator whose operands fold (#65), or null. */
  private Const folded(Ast.Binary b) {
    return ConstantKey.arithmetic(b.op()) != null && folds.constant(b) instanceof JqValue value ? new Const(here(b.source()), value) : null;
  }

  /** {@code b} on its lowered operands, {@code l op r}: the call of its operation, an If or an Alt. */
  private Node operator(Ast.Binary b, Node left, Node right) {
    return switch (b.op()) {
      case AND, OR, ALTERNATIVE -> joined(b.op(), here(b.source()), left, right);
      default -> applied(here(b.source()), operation(b.op()), left, right);
    };
  }

  /** {@code l op r} at {@code at} for {@code op} {@code and}, {@code or} or {@code //}: an If or an Alt. */
  private Node joined(BinOp op, Source at, Node left, Node right) {
    return switch (op) {
      // {@code l and r} (ADR-0037): {@code if l then (if r then true else false) else false}, the left side outer.
      case AND -> when(at, left, when(here(right.source()), right, bool(at, true), bool(at, false)), bool(at, false));
      // {@code l or r} (ADR-0037): {@code if l then true else (if r then true else false)}, the left side outer.
      case OR -> when(at, left, bool(at, true), when(here(right.source()), right, bool(at, true), bool(at, false)));
      default -> new Alt(at, left, right, JqTruth.TRUTHY);
    };
  }

  /** The operation of an arithmetic or comparison operator. */
  private static Operation operation(BinOp op) {
    return ConstantKey.arithmetic(op) != null ? ConstantKey.arithmetic(op) : ConstantKey.comparison(op);
  }

  /**
   * {@code l op r} at {@code at} as the call of {@code operation} on its operands: {@code r} bound outside {@code l},
   * so the right operand is the outer loop, as jq's {@code gen_binop} runs it, an Identity, Const or Var operand passed
   * as an argument without a Bind (ADR-0034 amendment for #65, ADR-0037, ADR-0038). The left operand was lowered
   * first, as the Binder walks it. A comparison is lowered so even when both sides are constants (design approval O6
   * of #66).
   */
  private Node applied(Source at, Operation operation, Node left, Node right) {
    int rightSlot = operandSlot(right);
    int leftSlot = operandSlot(left);
    Intrinsic op = new Intrinsic(at, operation, List.of(argument(left, leftSlot), argument(right, rightSlot)));
    return bind(right, rightSlot, bind(left, leftSlot, op));
  }

  /** A new slot for an operand, or {@link #NO_SLOT} for an Identity, a Const or a Var, each passed as it is. */
  private int operandSlot(Node operand) {
    return passed(operand) ? NO_SLOT : slots++;
  }

  /** Whether an operand is passed as it is, an Identity, a Const or a Var, taking no slot and no Bind. */
  private static boolean passed(Node operand) {
    return operand instanceof Identity || operand instanceof Const || operand instanceof Var;
  }

  /** {@code not} (ADR-0037, design approval C2): {@code if . then false else true}. */
  private static Node not(Source at) {
    return when(at, new Identity(at), bool(at, false), bool(at, true));
  }

  /**
   * {@code if c then a elif c2 then b else d end} (ADR-0039): nested Ifs on jq's truth, an elif the If of its else and a
   * missing else an Identity, lowered in source order.
   */
  private Node conditional(Ast.If i) {
    Node cond = lower(i.cond());
    Node then = lower(i.then());
    Node otherwise = i.otherwise() == null ? new Identity(here(i.source())) : lower(i.otherwise());
    return when(here(i.source()), cond, then, otherwise);
  }

  /**
   * {@code try body catch handler} at {@code at}, and {@code try body} with a null handler, which takes {@code empty}
   * (ADR-0039). The body is lowered first. A navigation step's {@code ?} is its quiet variant (ADR-0034), which the
   * parser marks on the step, and a {@code ?} on any other term is a step of its {@link #chain}.
   */
  private Node attempt(Source at, Ast body, Ast handler) {
    Node lowered = lower(body);
    Node caught = handler == null ? new Intrinsic(at, Empty.OP, List.of()) : lower(handler);
    return new Try(at, lowered, caught);
  }

  /**
   * {@code label $name | body} (ADR-0039): a slot of its own for the activation's token, which a {@code break} the
   * Binder bound to this label reads.
   */
  private Node label(Ast.Label l) {
    int slot = slots++;
    Env outer = env;
    env = new Env(l, slot, null, outer);
    Node body = lower(l.body());
    env = outer;
    return new Label(here(l.source()), slot, body);
  }

  /**
   * {@code reduce SRC as P (I; U)} (ADR-0041): {@code Reduce(I, s, bindP(SRC, Advance(s, U)))}, with a state slot s of
   * its own and {@link #bound}'s Bind or AltBind as bindP; U runs on {@link #state} of the slot's value, and so does
   * what the Reduce emits, so a state an empty update left is jq's null. SRC and I see the scope outside, U the
   * pattern's names too. They are lowered in jq's order, I first, then SRC, P's keys and U.
   */
  private Node reduce(Ast.Reduce r) {
    Node init = lower(r.init());
    int state = slots++;
    Node source = lower(r.input());
    Node each = bound(r.source(), source, r.patterns(), () -> advance(state, r.update()));
    Source at = here(r.source());
    return pipe(new Reduce(at, init, state, each), state(at));
  }

  /**
   * {@code foreach SRC as P (I; U; E)} (ADR-0041): {@code Foreach(I, s, bindP(SRC, Advance(s, U) | E))}, and without
   * an extract {@code Foreach(I, s, bindP(SRC, Advance(s, U)))}; U runs on {@link #state} of the slot's value, and
   * scopes and order are {@link #reduce}'s, E last. Each result is an update's, so none is an empty state.
   */
  private Node foreach(Ast.Foreach f) {
    Node init = lower(f.init());
    int state = slots++;
    Node source = lower(f.input());
    Node each =
        bound(
            f.source(),
            source,
            f.patterns(),
            () -> {
              Node advance = advance(state, f.update());
              return f.extract() == null ? advance : pipe(advance, lower(f.extract()));
            });
    return new Foreach(here(f.source()), init, state, each);
  }

  /** The Advance of the state slot {@code state} on {@code update}, which runs on the slot's value as jq sees it. */
  private Node advance(int state, Ast update) {
    Source at = here(update.source());
    return new Advance(at, state, pipe(state(at), lower(update)));
  }

  /** {@link LoopState}: jq's null for the empty state an Advance leaves (founding §14), any other value as it is. */
  private static Intrinsic state(Source at) {
    return new Intrinsic(at, LoopState.OP, List.of());
  }

  /**
   * {@code range(A; B)} (ADR-0041): the call of {@link Range} on its bounds, B bound inside A, so the start is the outer
   * loop, an Identity, Const or Var bound passed as an argument without a Bind, as an operator's operand is (ADR-0034
   * amendment for #65).
   */
  private Node range(Ast.Call c) {
    Node start = lower(c.args().get(0));
    Node end = lower(c.args().get(1));
    int startSlot = operandSlot(start);
    int endSlot = operandSlot(end);
    Intrinsic call = new Intrinsic(here(c.source()), Range.OP, List.of(argument(start, startSlot), argument(end, endSlot)));
    return bind(start, startSlot, bind(end, endSlot, call));
  }

  /** {@code if cond then then else otherwise} on jq's truth, spanning {@code at}. */
  private static If when(Source at, Node cond, Node then, Node otherwise) {
    return new If(at, cond, then, otherwise, JqTruth.TRUTHY);
  }

  /** A new constant {@code true} or {@code false}: the IR is a tree, so no node is shared. */
  private static Const bool(Source at, boolean value) {
    return new Const(at, value ? JqBool.TRUE : JqBool.FALSE);
  }

  /** A new slot for a computed key or bound, or {@link #NO_SLOT} for a Const or a Var, which is read as it is. */
  private int slotFor(Node value) {
    return value instanceof Const || value instanceof Var ? NO_SLOT : slots++;
  }

  /** The argument that reads {@code value}: the atomic node itself, or a Var of its slot. */
  private Node argument(Node value, int slot) {
    return slot == NO_SLOT ? value : new Var(here(value.source()), slot);
  }

  /** {@code body} with {@code value} bound to {@code slot} around it, or {@code body} alone for an atomic one. */
  private Node bind(Node value, int slot, Node body) {
    return slot == NO_SLOT ? body : new Bind(here(body.source()), value, slot, body);
  }

  /**
   * One Binds at {@code source} around {@code body} (ADR-0043): each of {@code values} whose slot in {@code slots} is no
   * {@link #NO_SLOT}, bound to it, the first outermost, as {@link #bind} nests them from the last in.
   */
  private static Binds oneBinds(Source source, Node[] values, int[] slots, Node body) {
    List<Node> bound = new ArrayList<>();
    List<Integer> boundSlots = new ArrayList<>();
    for (int i = 0; i < values.length; i++) {
      if (slots[i] != NO_SLOT) {
        bound.add(values[i]);
        boundSlots.add(slots[i]);
      }
    }
    return new Binds(source, bound, boundSlots, body);
  }

  /**
   * The variable {@code reference} at {@code at}, as the Binder bound it: a Var of its declaration's slot, or a Const
   * null for a group name its alternative has not stored yet; a variable jq defines itself is a Stub.
   */
  private Node variable(Object reference, Source at) {
    Object declaration = binder.binding(reference);
    if (declaration == Binder.UNSTORED) {
      return new Const(here(at), JqNull.NULL);
    }
    if (declaration instanceof Binder.Predefined p) {
      throw stub("$", p.name());
    }
    return new Var(here(at), find(declaration).slot());
  }

  /** {@code $__loc__} at {@code at}: its location, a constant (ADR-0038, jq's {@code gen_loc_object}). */
  private Const location(Source at) {
    Source loc = here(at);
    return new Const(loc, ConstantKey.location(text.line(loc.start())));
  }

  /**
   * {@code E as p | body} or {@code E as p1 ?// p2 ... | body} (ADR-0038): {@code E} is lowered in the scope outside,
   * and each of its results is bound to a slot of its own, on which the patterns match.
   */
  private Node binding(Ast.Bind b) {
    Node value = lower(b.value());
    return bound(b.source(), value, b.patterns(), () -> lower(b.body()));
  }

  /**
   * {@code value}, lowered already, bound to a slot of its own and taken apart by {@code patterns} around the node
   * {@code body} lowers in the patterns' scope (ADR-0038): a Bind for one pattern, an AltBind for a {@code ?//} chain.
   * {@code E as p | body} and the source and pattern of a {@code reduce} or {@code foreach} (ADR-0041) lower so.
   */
  private Node bound(Source source, Node value, List<Ast.Pattern> patterns, Supplier<Node> body) {
    int slot = slots++;
    Source at = here(source);
    return patterns.size() == 1 ? destructure(at, value, slot, patterns.getFirst(), body) : alternatives(at, value, slot, patterns, body);
  }

  /**
   * One pattern: the value's Bind, then the match's Binds, then the body, which reads each name at the slot of its
   * first store, as the Binder bound it. {@code E as $x | body} is so one Bind; past {@link #BIND_RUN} Binds the value
   * and the steps are one Binds ({@link #nest}).
   */
  private Node destructure(Source source, Node value, int slot, Ast.Pattern pattern, Supplier<Node> lowerBody) {
    Env outer = env;
    Match match = new Match();
    match(pattern, slot, match);
    Node body = lowerBody.get();
    env = outer;
    List<Step> steps = new ArrayList<>(match.steps.size() + 1);
    steps.add(new Step(value, slot, source));
    steps.addAll(match.steps);
    return nest(source, steps, body);
  }

  /**
   * The {@code ?//} chain, an AltBind: one group slot per name of all its patterns, in the body's scope. Each
   * alternative is its match's Binds around an Identity, the leaf, which emits once per match. At each match a group
   * slot takes the slot of the name's latest store, or, for a name the alternative never stores, the slot of one Const
   * null bound around the chain: jq-core never passes -1, since query-core's null is no jq value (§14).
   */
  private Node alternatives(Source source, Node value, int slot, List<Ast.Pattern> patterns, Supplier<Node> lowerBody) {
    Map<String, Object> groups = binder.groups(patterns);
    Env outer = env;
    List<Alternative> alternatives = new ArrayList<>();
    int nil = NO_SLOT;
    for (Ast.Pattern pattern : patterns) {
      Match match = new Match();
      match(pattern, slot, match);
      env = outer;
      List<Integer> from = new ArrayList<>();
      for (String name : groups.keySet()) {
        Integer own = match.latest.get(name);
        if (own == null) {
          if (nil == NO_SLOT) {
            nil = slots++;
          }
          own = nil;
        }
        from.add(own);
      }
      Source leaf = here(pattern.source());
      alternatives.add(new Alternative(nest(leaf, match.steps, new Identity(leaf)), from));
    }
    List<Integer> groupSlots = new ArrayList<>();
    for (Object group : groups.values()) {
      groupSlots.add(slots);
      env = new Env(group, slots++, null, env);
    }
    Node body = lowerBody.get();
    env = outer;
    Node chain = new AltBind(source, value, slot, alternatives, groupSlots, body);
    return nil == NO_SLOT ? chain : new Bind(source, new Const(source, JqNull.NULL), nil, chain);
  }

  /**
   * The match of {@code pattern} on the value in {@code level}, its steps appended in jq's matcher order (ADR-0038):
   * a name stores the level's slot; an array's elements, last first, each the level's {@code .[i]}; an object's
   * entries in source order, each its key, run on the level's value, then the level's {@code .[key]}, which {@code
   * $name} stores before its own pattern matches it. Each step is jq's non-quiet index, so an absent key or index and a
   * null give null and any other value raises. Each element spends a node of the inline cap.
   */
  private void match(Ast.Pattern pattern, int level, Match match) {
    spend();
    switch (pattern) {
      case Ast.VarPattern v -> store(v.name(), v, level, match);
      case Ast.ArrayPattern a -> {
        for (int i = a.elements().size() - 1; i >= 0; i--) {
          Ast.Pattern element = a.elements().get(i);
          Source place = here(element.source());
          Const at = new Const(place, LiteralNumber.ofLexeme(Integer.toString(i)));
          match(element, step(index(level, at, place), place, match), match);
        }
      }
      case Ast.ObjectPattern o -> {
        for (Ast.ObjPat entry : o.entries()) {
          Node key = key(entry.key());
          Source place = here(entry.source());
          Source keyAt = here(key.source());
          Node read = key instanceof Const || key instanceof Var ? key : new Var(keyAt, step(pipe(new Var(keyAt, level), key), place, match));
          int value = step(index(level, read, place), place, match);
          if (entry.key() instanceof Ast.VarKey k) {
            store(k.name(), k, value, match);
          }
          if (entry.value() != null) {
            match(entry.value(), value, match);
          }
        }
      }
    }
  }

  /** The store of {@code name}, declared by {@code declaration}, in {@code slot}: in scope at once, as its latest. */
  private void store(String name, Object declaration, int slot, Match match) {
    match.latest.put(name, slot);
    env = new Env(declaration, slot, null, env);
  }

  /** A new step of {@code match} that binds {@code value}; its slot. */
  private int step(Node value, Source source, Match match) {
    int slot = slots++;
    match.steps.add(new Step(value, slot, source));
    return slot;
  }

  /** The level's value indexed by the atomic {@code key}, jq's non-quiet {@code INDEX}. */
  private Node index(int level, Node key, Source source) {
    return pipe(new Var(source, level), new Intrinsic(source, Index.PLAIN, List.of(key)));
  }

  /**
   * {@code body} inside the Binds of {@code steps}, the first outermost: a Bind per step, or one Binds at {@code source}
   * past {@link #BIND_RUN} steps (ADR-0043), its values in the same order.
   */
  private static Node nest(Source source, List<Step> steps, Node body) {
    if (steps.size() > BIND_RUN) {
      List<Node> values = new ArrayList<>(steps.size());
      List<Integer> stepSlots = new ArrayList<>(steps.size());
      for (Step step : steps) {
        values.add(step.value());
        stepSlots.add(step.slot());
      }
      return new Binds(source, values, stepSlots, body);
    }
    for (int i = steps.size() - 1; i >= 0; i--) {
      Step step = steps.get(i);
      body = new Bind(step.source(), step.value(), step.slot(), body);
    }
    return body;
  }

  /**
   * A string literal: without interpolation a Const of its text, whatever its format (jq-ref 1.8.2, observed
   * 2026-11-13: {@code @base64 "k"} is {@code "k"}); with one, an Intrinsic of Interpolate on its parts, each text part
   * a Const and each interpolation bound around it unless atomic, the last part outermost, as jq loops (ADR-0038,
   * jq-ref: {@code "\(1,2)\(3,4)"} gives 13, 23, 14, 24); past {@link #BIND_RUN} bound parts, one Binds, the last part
   * first (ADR-0043). Only {@code @text} or no format is built with interpolations.
   */
  private Node string(Ast.Str s) {
    if (plain(s)) {
      return new Const(here(s.source()), BuiltString.of(bytes(s)));
    }
    if (s.format() != null && !s.format().equals("text")) {
      throw stub("the @", s.format(), " string at byte ", here(s.source()).start());
    }
    List<Ast.Part> parts = s.parts();
    Node[] nodes = new Node[parts.size()];
    int[] partSlots = new int[parts.size()];
    List<Node> args = new ArrayList<>(parts.size());
    int binds = 0;
    for (int i = 0; i < parts.size(); i++) {
      nodes[i] =
          switch (parts.get(i)) {
            case Ast.Text t -> new Const(here(t.source()), BuiltString.of(t.bytes()));
            case Ast.Interp p -> lower(p.query());
          };
      partSlots[i] = operandSlot(nodes[i]);
      args.add(argument(nodes[i], partSlots[i]));
      if (partSlots[i] != NO_SLOT) {
        binds++;
      }
    }
    Node body = new Intrinsic(here(s.source()), Interpolate.OP, args);
    if (binds > BIND_RUN) {
      // One Binds (ADR-0043), the last part outermost, as the nested Binds below are.
      Node[] lastFirst = new Node[parts.size()];
      int[] lastFirstSlots = new int[parts.size()];
      for (int i = 0; i < parts.size(); i++) {
        lastFirst[i] = nodes[parts.size() - 1 - i];
        lastFirstSlots[i] = partSlots[parts.size() - 1 - i];
      }
      return oneBinds(here(body.source()), lastFirst, lastFirstSlots, body);
    }
    for (int i = 0; i < parts.size(); i++) {
      body = bind(nodes[i], partSlots[i], body);
    }
    return body;
  }

  /** {@code [body]}: its body's results collected into a built array (ADR-0034 amendment); {@code []} is a constant. */
  private Node array(Ast.ArrayCons a) {
    if (a.body() == null) {
      return new Const(here(a.source()), new BuiltArray(new Object[0], 0));
    }
    return new Collect(here(a.source()), lower(a.body()), BuiltArray::new);
  }

  /**
   * {@code {k: v, ...}} (ADR-0034 amendment): each key bound outside its own value, each pair outside the next, a
   * constant string key and an Identity, Const or Var value passed as arguments without a Bind. Any other key, a
   * variable's or a Const null standing for an unstored group name among them, is bound and checked once its pair has
   * its value, before the next pair runs. A site whose keys are all constant strings builds on its shape, the distinct
   * keys in their first place, with one argument per key, the value of its last pair; an earlier pair of a repeated key
   * still runs, for its results. Any other site passes a key and a value per pair. The key of {@code {$x: v}} is the
   * variable's value (jq-ref, recorded: bind/object-var-key); a shorthand is {@link #shorthand}. Each pair spends a node
   * of the inline cap. Past {@link #BIND_RUN} Binds the pairs are one Binds in the same order ({@link #pairBinds}).
   */
  private Node object(Ast.ObjectCons o) {
    List<Ast.Pair> pairs = o.pairs();
    int n = pairs.size();
    Node[] keys = new Node[n];
    int[] keySlots = new int[n];
    Node[] values = new Node[n];
    int[] valueSlots = new int[n];
    boolean shaped = true;
    int binds = 0;
    for (int i = 0; i < n; i++) {
      spend();
      Ast.Pair pair = pairs.get(i);
      keys[i] = pair.key() instanceof Ast.VarKey k && pair.value() != null ? variable(k, k.source()) : key(pair.key());
      keySlots[i] = keys[i] instanceof Const c && c.value() instanceof JqString ? NO_SLOT : slots++;
      values[i] = pair.value() == null ? shorthand(pair.key(), keys[i], keySlots[i]) : lower(pair.value());
      valueSlots[i] = operandSlot(values[i]);
      shaped &= keySlots[i] == NO_SLOT;
      binds += (keySlots[i] == NO_SLOT ? 0 : 1) + (valueSlots[i] == NO_SLOT ? 0 : 1);
    }
    Source at = here(o.source());
    Node body = shaped ? shaped(at, keys, values, valueSlots) : new Intrinsic(at, BuildObject.PAIRS, pairArguments(keys, keySlots, values, valueSlots));
    if (binds > BIND_RUN) {
      return pairBinds(keys, keySlots, values, valueSlots, body);
    }
    for (int i = n - 1; i >= 0; i--) {
      if (keySlots[i] != NO_SLOT) {
        Source check = here(keys[i].source());
        body = pipe(new Intrinsic(check, ObjectKey.CHECK, List.of(new Var(check, keySlots[i]))), body);
      }
      body = bind(keys[i], keySlots[i], bind(values[i], valueSlots[i], body));
    }
    return body;
  }

  /**
   * The pairs of an object whose nested form has more than {@link #BIND_RUN} Binds, as one Binds around {@code body},
   * its construction (ADR-0043), in the order the nested form runs them: per pair its computed key, its value unless
   * passed as an argument, and the check of its computed key, a value of the Binds bound to a new slot no node reads,
   * since the check gives its input back; then the next pair.
   */
  private Binds pairBinds(Node[] keys, int[] keySlots, Node[] values, int[] valueSlots, Node body) {
    List<Node> bound = new ArrayList<>();
    List<Integer> boundSlots = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      if (keySlots[i] != NO_SLOT) {
        bound.add(keys[i]);
        boundSlots.add(keySlots[i]);
      }
      if (valueSlots[i] != NO_SLOT) {
        bound.add(values[i]);
        boundSlots.add(valueSlots[i]);
      }
      if (keySlots[i] != NO_SLOT) {
        Source check = here(keys[i].source());
        bound.add(new Intrinsic(check, ObjectKey.CHECK, List.of(new Var(check, keySlots[i]))));
        boundSlots.add(slots++);
      }
    }
    return new Binds(here(body.source()), bound, boundSlots, body);
  }

  /**
   * A key that is no {@code $x: v}: a name, the {@code $name} of a shorthand or a pattern, and {@code $__loc__} are
   * constant strings; a string or {@code (query)} is lowered, so a plain string is a Const, which the parser found a
   * string (ADR-0036).
   */
  private Node key(Ast.Key key) {
    return switch (key) {
      case Ast.NameKey k -> new Const(here(k.source()), BuiltString.of(k.name()));
      case Ast.VarKey k -> new Const(here(k.source()), BuiltString.of(k.name()));
      case Ast.LocKey k -> new Const(here(k.source()), BuiltString.of("__loc__"));
      case Ast.StringKey k -> lower(k.string());
      case Ast.QueryKey k -> lower(k.query());
    };
  }

  /**
   * The value of a shorthand pair, whose key {@link #key} lowered to {@code name}, bound to {@code slot} when computed:
   * a name or a constant string is the field of its key; {@code $x} the variable; {@code $__loc__} its location; a
   * string with an interpolation the input's {@code .[k]} of the key's own result, so the key runs once (jq-ref 1.8.2,
   * observed 2026-11-13: {@code {"\("a","b")"}} on {@code {"a":1,"b":2}} gives {@code {"a":1}} and {@code {"b":2}}).
   */
  private Node shorthand(Ast.Key key, Node name, int slot) {
    if (key instanceof Ast.NameKey k) {
      return lower(new Ast.Field(k.source(), null, k.name(), false));
    }
    if (key instanceof Ast.VarKey k) {
      return variable(k, k.source());
    }
    if (key instanceof Ast.LocKey k) {
      return location(k.source());
    }
    Ast.StringKey k = (Ast.StringKey) key;
    if (name instanceof Const) {
      return lower(new Ast.StringField(k.source(), null, k.string(), false));
    }
    Source at = here(k.source());
    return new Intrinsic(at, Index.PLAIN, List.of(new Var(at, slot)));
  }

  /**
   * The construction of a site whose keys are all Consts of strings: its shape, the distinct keys in their first place,
   * and per key the value of its last pair. Keys compare by their UTF-8 bytes, each found among the distinct keys
   * before it through a map by those bytes (ADR-0043), not a scan of them.
   */
  private Intrinsic shaped(Source source, Node[] keys, Node[] values, int[] valueSlots) {
    List<JqString> shape = new ArrayList<>();
    List<Node> args = new ArrayList<>();
    Map<ByteBuffer, Integer> places = new HashMap<>();
    for (int i = 0; i < keys.length; i++) {
      JqString key = (JqString) ((Const) keys[i]).value();
      Node value = argument(values[i], valueSlots[i]);
      Integer at = places.putIfAbsent(ByteBuffer.wrap(key.utf8().toArray(ValueLayout.JAVA_BYTE)), shape.size());
      if (at == null) {
        shape.add(key);
        args.add(value);
      } else {
        args.set(at, value);
      }
    }
    return new Intrinsic(source, BuildObject.shaped(shape.toArray(JqString[]::new)), args);
  }

  /** The arguments of a site with a computed key: a key and a value per pair. */
  private List<Node> pairArguments(Node[] keys, int[] keySlots, Node[] values, int[] valueSlots) {
    List<Node> args = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      args.add(argument(keys[i], keySlots[i]));
      args.add(argument(values[i], valueSlots[i]));
    }
    return args;
  }

  /** A comma chain: its terms flattened by a loop, left to right, then built as a balanced tree. */
  private Node comma(Ast.Comma chain) {
    List<Ast> terms = new ArrayList<>();
    Ast left = chain;
    while (left instanceof Ast.Comma c) {
      terms.add(c.right());
      left = c.left();
    }
    terms.add(left);
    List<Node> nodes = new ArrayList<>(terms.size());
    for (int i = terms.size() - 1; i >= 0; i--) {
      nodes.add(lower(terms.get(i)));
    }
    return balanced(nodes, 0, nodes.size());
  }

  /** The terms {@code [from, to)}, at least one, as a balanced Comma tree; the left half takes the middle term. */
  private Node balanced(List<Node> nodes, int from, int to) {
    if (to - from == 1) {
      return nodes.get(from);
    }
    int mid = from + (to - from + 1) / 2;
    Node left = balanced(nodes, from, mid);
    Node right = balanced(nodes, mid, to);
    return new Comma(here(new Source(left.source().start(), right.source().end())), left, right);
  }

  /** {@code left | right}, spanning both in the filter's text. */
  private Pipe pipe(Node left, Node right) {
    return new Pipe(here(new Source(left.source().start(), right.source().end())), left, right);
  }

  /** A string with no interpolation, which is a constant whatever its format. */
  private static boolean plain(Ast.Str s) {
    return s.parts().stream().allMatch(Ast.Text.class::isInstance);
  }

  /** The value of a plain string: its text parts' bytes, joined. */
  private static byte[] bytes(Ast.Str s) {
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    for (Ast.Part part : s.parts()) {
      value.writeBytes(((Ast.Text) part).bytes());
    }
    return value.toByteArray();
  }
}
