/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): check_object_key and constant_fold in src/parser.y, and the
 * folds its block_is_const sees, gen_const_array and gen_const_object in src/compile.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.internal.op.Arithmetic;
import io.github.subapavel.jqj.internal.op.Comparison;
import io.github.subapavel.jqj.internal.op.ObjectKey;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.ir.Source;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.EvaluationException;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parser-action check of a computed object key (ADR-0036, GLOSSARY "Constant key"): a key jq folds to a constant
 * that is no string is jq's error. jq folds what its grammar builds as one constant (jq-ref 1.8.2, observed
 * 2026-11-13): a number, a string without interpolation (its format applies to none), {@code true}, {@code false} and
 * {@code null}; parentheses; a pipe one side of which is {@code .} alone; an array of constants joined by commas that
 * nest to the left only ({@code [(1,2),3]}, not {@code [1,(2,3)]}); and an object whose keys are names, strings or
 * constant strings and whose values are constants, folded as a construction is (a repeated key keeps its first place
 * and its last value). {@code $__loc__} folds too, to its {@link #location} (#67, ADR-0038), and so does the pair
 * {@code $__loc__} of an object, to the key {@code "__loc__"} and that value (jq-ref: {@code {({$__loc__}): 1}} is rc
 * 3). Nothing else folds: {@code 1|2}, {@code -1}, {@code 1?}, {@code [.]} and an object with any other shorthand pair
 * or a variable as a key or a value are known only at run time. An arithmetic operator on two constants folds to its result (#65, {@link #binary}), which the
 * resolver lowers to a Const too; a comparison of two constants folds to its boolean (#66), which the resolver leaves
 * to the run (design approval O6). One instance holds one compile's folds (ADR-0035 amendments, #65 third and fourth
 * reviews): each node is folded at most once, its result kept, and every fold charges one run state with the
 * compile's budget; a {@link Program} carries it from the parser to the resolver.
 */
final class ConstantKey {
  /** What {@code .} alone folds to: a pipe drops it. */
  private static final Object DOT = new Object();

  /** The calls that are constants, by name and arity (jq's parser). */
  private static final Map<String, JqValue> CONSTANTS = Map.of("true/0", JqBool.TRUE, "false/0", JqBool.FALSE, "null/0", JqNull.NULL);

  /** The arithmetic operators, which jq folds, and their operations. */
  private static final Map<BinOp, Operation> ARITHMETIC =
      Map.of(
          BinOp.ADD, Arithmetic.PLUS,
          BinOp.SUBTRACT, Arithmetic.MINUS,
          BinOp.MULTIPLY, Arithmetic.MULTIPLY,
          BinOp.DIVIDE, Arithmetic.DIVIDE,
          BinOp.MODULO, Arithmetic.MODULO);

  /** The comparison operators, which jq folds too, and their operations (#66). */
  private static final Map<BinOp, Operation> COMPARISON =
      Map.of(
          BinOp.EQUAL, Comparison.EQUAL,
          BinOp.NOT_EQUAL, Comparison.NOT_EQUAL,
          BinOp.LESS, Comparison.LESS,
          BinOp.GREATER, Comparison.GREATER,
          BinOp.LESS_EQUAL, Comparison.LESS_EQUAL,
          BinOp.GREATER_EQUAL, Comparison.GREATER_EQUAL);

  /**
   * The bytes the operators of a Const's whole fold may build together (ADR-0035 amendment: no fold result larger than
   * 4 KiB): a string of 4096 bytes with its fixed charge, so 4096 bytes fold and 4097 do not; any other value is held
   * to the same charges. The literals of the filter text are no fold results, so they count against the compile's
   * budget only.
   */
  static final long FOLD_BYTES = Charges.STRING + 4096;

  /** The keys and the file of every {@link #location}, built once: no fold builds them. */
  private static final JqString FILE = BuiltString.of("file");

  private static final JqString LINE = BuiltString.of("line");

  private static final JqString TOP_LEVEL = BuiltString.of("<top-level>");

  /** The key of the pair {@code $__loc__}, which a fold builds and charges as any name key. */
  private static final byte[] LOC_KEY = "__loc__".getBytes(StandardCharsets.UTF_8);

  /** The bytes all the folds of one compile may build together, the literals and the key-kind checks included: 1 MiB. */
  static final long COMPILE_BYTES = 1 << 20;

  /** The steps all the folds of one compile may take together, the key-kind checks included: 2^20. */
  static final long COMPILE_STEPS = 1 << 20;

  /**
   * The one run state every fold of this compile charges, begun once with the compile's budget and never ended: once a
   * budget is spent, every later fold that would charge it is none, and the run computes it.
   */
  private final RunState state = new RunState();

  /** Each node folded so far, by identity: what it folded to, and the bytes its operators built. */
  private final Map<Ast, Kept> kept = new IdentityHashMap<>();

  /** The bytes the operators of this compile's folds have built so far. */
  private long built;

  /** The bytes of the kept folds a fold has used so far, which its own operators did not build again. */
  private long reused;

  /** The filter's text, which gives a {@code $__loc__} its line. */
  private final FilterText text;

  /** A node's fold: its constant, {@link #DOT} or null, and the bytes the operators of its whole fold built. */
  private record Kept(Object value, long bytes) {}

  /**
   * The folds of one compile of {@code text}, with the whole compile budget left: the parser makes one, the resolver
   * uses it too.
   */
  ConstantKey(FilterText text) {
    this.text = text;
    state.begin(COMPILE_STEPS, Long.MAX_VALUE, COMPILE_BYTES, Long.MAX_VALUE);
  }

  /**
   * jq's {@code $__loc__} on {@code line} ({@code gen_loc_object}): {@code {"file":"<top-level>","line":line}}, the line
   * counting LF only (ADR-0038).
   */
  static JqValue location(int line) {
    return new BuiltObject(new JqString[] {FILE, LINE}, new Object[] {TOP_LEVEL, LiteralNumber.ofLexeme(Integer.toString(line))});
  }

  /**
   * jq's message for the key {@code query} when it folds to a constant that is no string, or null. jq folds without a
   * cap, so the check takes the fold whatever its size, as long as the compile's budget holds it: jq-ref gives rc 3 for
   * {@code {(("x"*5000)/"x"):1}}. Past the compile's budget the key is left to the run, which raises the same text with
   * rc 5 (DEVIATIONS "Huge constant keys").
   */
  String error(Ast query) {
    return fold(query) instanceof JqValue value && !(value instanceof JqString) ? ObjectKey.message(state, value) : null;
  }

  /**
   * The constant {@code ast} folds to when its operators built at most {@link #FOLD_BYTES}, or null: what the resolver
   * lowers to a Const.
   */
  JqValue constant(Ast ast) {
    return fold(ast) instanceof JqValue value && kept.get(ast).bytes() <= FOLD_BYTES ? value : null;
  }

  /**
   * The constant {@code ast} folds to, {@link #DOT} for {@code .} alone, or null when it is none, computed once and
   * kept: a fold that raises, or that would build or step past the compile's budget, is none, and so is each fold
   * around it.
   *
   * <p>An operator jq folds is folded by a loop down its left operands that are such operators (#86): the parser
   * builds a left-associative chain in a loop, as long as the filter. The term the chain starts from is folded first,
   * then each operator from the innermost out, each kept as its own fold would keep it: every one of them starts its
   * fold where the outermost does. None of those operators is kept yet, since an operator's fold is asked for before
   * its operands' (a computed key's by the parser, an operator's by the resolver, outermost first), and a fold keeps
   * the whole chain below it.
   */
  private Object fold(Ast ast) {
    Kept known = kept.get(ast);
    if (known != null) {
      reused += known.bytes();
      return known.value();
    }
    long builtBefore = built;
    long reusedBefore = reused;
    List<Ast.Binary> chain = new ArrayList<>();
    Ast left = ast;
    while (left instanceof Ast.Binary b && operation(b.op()) != null) {
      chain.add(b);
      left = b.left();
    }
    if (chain.isEmpty()) {
      Object value;
      try {
        value = compute(ast);
      } catch (EvaluationException e) {
        value = null;
      }
      kept.put(ast, new Kept(value, built - builtBefore + reused - reusedBefore));
      return value;
    }
    Object value = fold(left);
    for (int i = chain.size() - 1; i >= 0; i--) {
      Ast.Binary b = chain.get(i);
      value = value instanceof JqValue l ? binary(b, l) : null;
      kept.put(b, new Kept(value, built - builtBefore + reused - reusedBefore));
    }
    return value;
  }

  private Object compute(Ast ast) {
    return switch (ast) {
      case Ast.Dot _ -> DOT;
      case Ast.Paren p -> fold(p.body());
      case Ast.Pipe p -> pipe(fold(p.left()), fold(p.right()));
      case Ast.Num n -> LiteralNumber.ofLexeme(n.lexeme());
      case Ast.Str s -> string(s);
      // A call by its name and arity, as jq names a function.
      case Ast.Call c -> CONSTANTS.get(c.name() + "/" + c.args().size());
      case Ast.ArrayCons a -> array(a.body());
      case Ast.ObjectCons o -> object(o.pairs());
      // An operator jq folds is folded by fold's loop; any other Binary is none, as the default.
      case Ast.Loc l -> location(l.source());
      default -> null;
    };
  }

  /**
   * The {@code $__loc__} at {@code at}: its {@link #location}, charged as an object of two entries, whose strings are
   * shared.
   */
  private JqValue location(Source at) {
    state.chargeBuilt(Charges.CONTAINER + 2 * Charges.ENTRY);
    return location(text.line(at.start()));
  }

  /** The operation of an arithmetic operator, or null for any other. */
  static Operation arithmetic(BinOp op) {
    return ARITHMETIC.get(op);
  }

  /** The operation of a comparison operator, or null for any other. */
  static Operation comparison(BinOp op) {
    return COMPARISON.get(op);
  }

  /**
   * jq's {@code constant_fold} of {@code + - * / %} and (#66) {@code == != < > <= >=} (GLOSSARY "Fold"): the operator
   * on two constants, computed now as the run would, charging the compile's budget, the bytes it builds counted for
   * {@link #constant}'s cap; a comparison builds none. A fold that raises, or that would build or step past the budget,
   * is none ({@link #fold}): the run computes it, raising or building and charging there. {@code and}, {@code or} and
   * {@code //} are not folded (ADR-0037). {@code b}'s left operand has folded to {@code left}; its right one is folded
   * now.
   */
  private Object binary(Ast.Binary b, JqValue left) {
    if (!(fold(b.right()) instanceof JqValue right)) {
      return null;
    }
    long before = state.built();
    Object value;
    try {
      value = ((Fn2) operation(b.op()).form()).apply(state, JqNull.NULL, left, right);
    } catch (EvaluationException e) {
      return null;
    }
    built += state.built() - before;
    return value;
  }

  /** The operation of an operator jq folds, arithmetic or comparison, or null for any other. */
  private static Operation operation(BinOp op) {
    return ARITHMETIC.getOrDefault(op, COMPARISON.get(op));
  }

  /** A pipe of two folds: the other side when one is {@code .} alone, otherwise none. */
  private static Object pipe(Object left, Object right) {
    if (left == DOT) {
      return right;
    }
    return right == DOT ? left : null;
  }

  /**
   * A string without interpolation: its text parts' bytes, joined, charged as a string; with an interpolation, none.
   */
  private Object string(Ast.Str s) {
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    for (Ast.Part part : s.parts()) {
      if (!(part instanceof Ast.Text text)) {
        return null;
      }
      value.writeBytes(text.bytes());
    }
    return string(value.toByteArray());
  }

  /** The string of {@code bytes}, charged before it is built (ADR-0035 amendment: a fold's literals are charged). */
  private JqString string(byte[] bytes) {
    state.chargeBuilt(Charges.STRING + bytes.length);
    return BuiltString.of(bytes);
  }

  /**
   * {@code [body]}: empty without a body; otherwise the constants of a comma chain that nests to the left only, a
   * pipe with {@code .} or parentheses around any part of it, each right-hand term one constant. It is charged as a
   * container and its elements.
   */
  private Object array(Ast body) {
    // The terms are found right to left, as the chain nests, then folded left to right, each added once at the end.
    List<Ast> terms = new ArrayList<>();
    Ast chain = body == null ? null : spine(body);
    while (chain instanceof Ast.Comma c) {
      terms.add(c.right());
      chain = spine(c.left());
    }
    if (chain != null) {
      terms.add(chain);
    }
    List<Object> elements = new ArrayList<>(terms.size());
    for (int i = terms.size() - 1; i >= 0; i--) {
      elements.add(fold(terms.get(i)));
    }
    for (Object e : elements) {
      if (!(e instanceof JqValue)) {
        return null;
      }
    }
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * elements.size());
    return new BuiltArray(elements.toArray(), elements.size());
  }

  /** {@code ast} without the parentheses and the pipes with {@code .} around it, as jq's blocks join them away. */
  private static Ast spine(Ast ast) {
    for (;;) {
      if (ast instanceof Ast.Paren p) {
        ast = p.body();
      } else if (ast instanceof Ast.Pipe p && dot(p.left())) {
        ast = p.right();
      } else if (ast instanceof Ast.Pipe p && dot(p.right())) {
        ast = p.left();
      } else {
        return ast;
      }
    }
  }

  /**
   * Whether {@code ast} folds to {@link #DOT}: {@code .} alone, in parentheses or piped to itself. It is told by the
   * shape, without a fold, so no fold is computed, and charged, twice.
   */
  private static boolean dot(Ast ast) {
    return switch (ast) {
      case Ast.Dot _ -> true;
      case Ast.Paren p -> dot(p.body());
      case Ast.Pipe p -> dot(p.left()) && dot(p.right());
      default -> false;
    };
  }

  /**
   * {@code {pairs}}: each key a constant string and each value a constant, or none. It is charged as a construction
   * is, a container and an entry per pair, and a name key, like the pair {@code $__loc__}'s, as a string.
   */
  private Object object(List<Ast.Pair> pairs) {
    Object[] entries = new Object[2 * pairs.size()];
    for (int i = 0; i < pairs.size(); i++) {
      Ast.Pair pair = pairs.get(i);
      Object key =
          switch (pair.key()) {
            case Ast.NameKey k -> string(k.name().getBytes(StandardCharsets.UTF_8));
            case Ast.LocKey _ -> string(LOC_KEY);
            case Ast.StringKey k -> string(k.string());
            case Ast.QueryKey k -> fold(k.query());
            default -> null;
          };
      Object value = pair.key() instanceof Ast.LocKey k ? location(k.source()) : pair.value() == null ? null : fold(pair.value());
      if (!(key instanceof JqString) || !(value instanceof JqValue)) {
        return null;
      }
      entries[2 * i] = key;
      entries[2 * i + 1] = value;
    }
    state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * pairs.size());
    return BuiltObject.ofPairs(entries);
  }
}
