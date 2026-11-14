package io.github.subapavel.jqj.oracle.gen;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.subapavel.jqj.oracle.diff.CaseFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code core-v1} Generator (ADR-0020, #58): a function from a Seed to one differential case, {@code
 * gen/core-v1/<seed>}, whose filter is drawn from a typed grammar of the core language to depth 4 and whose stdin is
 * one small strict JSON document of its own. Like {@link ValuesV1} it uses only the JDK and {@link SplitMix64}, with the
 * seed as the state, integer draws only and no hash iteration. It is frozen at the first merge of {@code
 * diff/gen/core-v1.rec}: a change to any output byte makes it {@code core-v2}.
 *
 * <p>The parameters, approved on #58 (architect, 2026-11-12, from the designer's draft), after the dry run's first
 * tuning step (W_ANY 25 to 15 %, try, try-catch and {@code ?} 2 to 3, empty 2 to 1, select 3 to 2, and the
 * productions above 60 % rc 5 halved: array and object pattern, reduce, {@code N - N}, {@code N / N}, {@code or}),
 * and the owner's delegated decision on #58 (2026-11-12): label 2 to 6 with the {@code K, R} body, the break scope
 * opened in the try handler, the {@code //} left side and the arguments of select, first, limit and map, and no seed
 * with {@code ?//} and also {@code first}, {@code limit} or {@code break}:
 *
 * <table>
 *   <caption>core-v1</caption>
 *   <tr><th>parameter</th><th>value</th></tr>
 *   <tr><td>Form</td><td>{@code seed % 7}: 0–3 {@code -c E}; 4 {@code E} pretty; 5 {@code -c def g(f; $p): D; E}, a
 *       non-recursive def that E calls at least once (E is redrawn until it does); 6 {@code -c def r(f; $k): if $k < 1
 *       then B else f | r(f; $k - 1) end; r(F; K)} with a literal K in 0..4, the one recursive template</td></tr>
 *   <tr><td>seeds</td><td>[0, 2100), 300 per form</td></tr>
 *   <tr><td>types</td><td>N number, S string, B boolean, A array, O object, Z null, V any. A typed slot is drawn as V
 *       with {@value #W_ANY} %. {@code gen(type, depth, scope)}: the root of each of E, D, B and F is depth 1; a node at
 *       depth 1, 2, 3, 4 is a leaf with 15, 35, 60, 100 %</td></tr>
 *   <tr><td>V inner</td><td>pipe 12, comma 6, {@code as $v} 6, array pattern 1, object pattern 1, {@code ?//} 1, if 3,
 *       if-elif 1, if without else 1, try 3, try-catch 3, {@code (E)?} 3, {@code //} 3, reduce 1, foreach 1, foreach
 *       with extract 1, label 6 (its body {@code K, R} while a place that gives several results is left and K, R fit
 *       in depth 4; that comma takes the place), select 2, first 2, {@code limit(n; E)} with n in 0..3 2, local {@code def f: ..;},
 *       {@code def f(g): ..;} and {@code def f($x): ..;} 1 each, {@code error(E)} 1, a call of a def or parameter of
 *       arity 1 or 2 in scope 6; a typed production of N 8, S 6, B 6, A 6, O 6</td></tr>
 *   <tr><td>typed inner</td><td>N: {@code + - * / %} 3 1 2 1 1, {@code X | length} (X one of S A O) 3, {@code
 *       range(a; b)} 2. S: {@code S + S} 4, {@code V | tostring} 3, {@code S * n} (n in 0..3) 2, {@code V | type} 1.
 *       B: {@code V == V} 2, {@code V != Z} 1, {@code N < N}, {@code <=}, {@code >}, {@code >=} 1 each, {@code and} 2,
 *       {@code or} 1, {@code B | not} 2. A: {@code [V]} 6, {@code A + A} 2, {@code A - A} 1, {@code A | map(V)} 2. O:
 *       {@code {k: V}} with 1–2 keys 5, {@code O + O} 2, {@code O * O} 1, {@code {(S): V}} 1. Z: {@code null}</td></tr>
 *   <tr><td>leaves</td><td>V: {@code .} 8, path 12, {@code $v} 6, literal 4, {@code empty} 1, {@code length} 2,
 *       {@code type}, {@code tostring}, {@code not}, {@code error} 1 each, {@code break $l} 12 when a label is
 *       breakable (the draft's 4 gave breaks in 14 seeds), a call of an arity-0 def or parameter in scope 4. N: number
 *       6, {@code length} 2, path 2, {@code $v} 2. S: string 6, {@code tostring} 2, {@code type} 1, path 1, {@code $v} 1. B: {@code true}/{@code false} 6, {@code
 *       not} 2, path 1, {@code $v} 1. A: {@code []} or 1–2 scalars 6, path 1, {@code $v} 1. O: {@code {}} or one key
 *       and a scalar 6, {@code .} 1, path 1, {@code $v} 1; each typed leaf also {@code break $l} 4 when a label is
 *       breakable (a break has every type). A V literal is N 4, S 3, B 2, Z 1, A 1, O 1</td></tr>
 *   <tr><td>paths</td><td>{@code .k} 5, {@code .k.k} 2, {@code .[n]} (n in −1..2) 2, {@code .k?} 1, {@code .[]} 2,
 *       {@code .[]?} 1</td></tr>
 *   <tr><td>pools</td><td>numbers {@code 0 1 2 3 -1 0.5 1.10 1e2 -0 100000000000000000001} weights 5 5 4 3 3 2 2 2 1
 *       1; strings {@code "" "a" "b" "x y" "é" "a,b"} and the escapes <code>"&#92;n"</code>,
 *       <code>"&#92;u00e9"</code>; keys {@code a b c "x y"}; variables {@code $x $y $z}; labels {@code $l $m}; defs
 *       and their closure parameters {@code f g h}, never a name already in scope</td></tr>
 *   <tr><td>bounds</td><td>{@code range} bounds −2..6; at most {@value #SITES} places per filter that can give several
 *       results (comma, {@code range}, {@code .[]}, {@code .[]?}), none in the template's F</td></tr>
 *   <tr><td>documents</td><td>top object 50, array 30, scalar 20; depth ≤ 3; 0–4 members (0 with 15 %), distinct keys
 *       per object: {@code a} 30, {@code b} 25, {@code c} 15, {@code "x y"} 10, {@code ""} 5, 1–3 printable ASCII
 *       characters without {@code "} and the backslash 15; values number 30, string 25, true, false, null 5 each,
 *       array 15, object 15 (a container only inside one at depth 1 or 2), numbers and strings from the filter's
 *       pools; strict JSON, compact, a trailing LF on 70 %, at most {@value #DOCUMENT_CAP} bytes (redrawn above)</td></tr>
 *   <tr><td>excluded</td><td>by construction: a {@code break} is drawn only from the breakable labels, a set a label
 *       adds to and a try body, a {@code ?} operand, a whole {@code ?//} binding, a def body, the arguments of a user
 *       def or a closure parameter and the argument of {@code error} empty (a try handler, the left side of {@code //}
 *       and the arguments of select, first, limit and map keep it); a seed that holds a {@code ?//} holds no
 *       {@code first}, {@code limit} or {@code break}, and the other way round (on jq-ref,
 *       {@code [1] | [limit(1; . as [$a] ?// $a | $a)]} gives {@code [1,[1]]}); a closed name list (no {@code ..}, recurse,
 *       {@code range/3}, while, until, repeat, walk, flatten, tostream, sort, unique, min, max, {@code $__loc__},
 *       input, inputs, debug, env, {@code $ENV}, now, halt, {@code @format}, assignment); a def's name only after its
 *       body; only bound names, exact arities, computed keys only as {@code (S)}, and a key jq folds to a constant
 *       (a literal, or {@code + - * / % == != < <= > >=} or {@code S * n} over constants) redrawn as a string literal,
 *       since jq refuses a constant non-string key at compile time; no literal {@code /} or {@code %} by a literal
 *       zero (the divisor becomes {@code 1})</td></tr>
 *   <tr><td>printing</td><td>one printer; every compound operand in parentheses, a negative literal too</td></tr>
 * </table>
 */
public final class CoreV1 {

  /** The Generator's name: the family is {@code gen/core-v1}. */
  public static final String NAME = "core-v1";

  public static final String FAMILY = "gen/" + NAME;

  /** The recorded seed range, [{@value #FROM}, {@value #TO}). */
  public static final long FROM = 0;

  public static final long TO = 2100;

  /** The percentage of typed slots drawn as V. */
  static final int W_ANY = 15;

  /** The places per filter that can give several results. */
  static final int SITES = 3;

  /** The largest document in bytes, the trailing LF included. */
  static final int DOCUMENT_CAP = 512;

  /** The leaf chance in percent by depth 1..4. */
  private static final int[] LEAF = {15, 35, 60, 100};

  private static final List<String> NUMBERS = List.of("0", "1", "2", "3", "-1", "0.5", "1.10", "1e2", "-0", "100000000000000000001");

  private static final int[] NUMBER_WEIGHTS = {5, 5, 4, 3, 3, 2, 2, 2, 1, 1};

  /** The strings as spelled in the filter and in a document; the last two are escapes. */
  private static final List<String> STRINGS = List.of("\"\"", "\"a\"", "\"b\"", "\"x y\"", "\"é\"", "\"a,b\"", "\"\\n\"", "\"\\u00e9\"");

  /** The keys as spelled in a path, an object and a pattern. */
  private static final List<String> KEYS = List.of("a", "b", "c", "\"x y\"");

  private static final List<String> VARS = List.of("$x", "$y", "$z");

  private static final List<String> LABELS = List.of("$l", "$m");

  private static final List<String> DEFS = List.of("f", "g", "h");

  /** The operators jq evaluates at compile time when every operand is a constant. */
  private static final Set<Op> FOLDED = EnumSet.of(Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE, Op.REPEAT);

  /** The types of the grammar. */
  enum Type {
    N,
    S,
    B,
    A,
    O,
    Z,
    V
  }

  /**
   * The productions. {@code text} is a leaf's word or a binary operator with its spaces; {@code term} is true for a
   * production jq parses as a term, which the printer leaves without parentheses as an operand.
   */
  public enum Op {
    DOT(".", true),
    PATH("", true),
    VAR("", true),
    LITERAL("", true),
    EMPTY("empty", true),
    LENGTH("length", true),
    TYPE("type", true),
    TOSTRING("tostring", true),
    NOT("not", true),
    ERROR("error", true),
    BREAK("break ", false),
    CALL("", true),
    RANGE("range", true),
    PIPE(" | ", false),
    COMMA(", ", false),
    ALTERNATIVE(" // ", false),
    ADD(" + ", false),
    SUB(" - ", false),
    MUL(" * ", false),
    DIV(" / ", false),
    MOD(" % ", false),
    EQ(" == ", false),
    NE(" != ", false),
    LT(" < ", false),
    LE(" <= ", false),
    GT(" > ", false),
    GE(" >= ", false),
    AND(" and ", false),
    OR(" or ", false),
    LENGTH_OF(" | length", false),
    TOSTRING_OF(" | tostring", false),
    TYPE_OF(" | type", false),
    NOT_OF(" | not", false),
    REPEAT(" * ", false),
    AS("", false),
    AS_ARRAY("", false),
    AS_OBJECT("", false),
    ALT_DESTRUCT("", false),
    IF("", false),
    IF_ELIF("", false),
    IF_NO_ELSE("", false),
    TRY("", false),
    TRY_CATCH("", false),
    OPTIONAL("", true),
    REDUCE("", false),
    FOREACH("", false),
    FOREACH3("", false),
    LABEL("", false),
    SELECT("", true),
    FIRST("", true),
    LIMIT("", true),
    ERROR_OF("", true),
    COLLECT("", true),
    MAP("", false),
    OBJECT("", true),
    OBJECT_COMPUTED("", true),
    DEF0("", false),
    DEF1("", false),
    DEF1V("", false),
    DEF2("", false),
    RECURSE("", false);

    private final String text;
    private final boolean term;

    Op(String text, boolean term) {
      this.text = text;
      this.term = term;
    }
  }

  /**
   * One node of a filter's tree.
   *
   * @param names the node's spelled parts: a leaf's text, a bound variable or label, a def's name and parameters, the
   *     keys and variables of a pattern ({@code key, $var} pairs), the keys of an object, a literal count
   * @param kids the sub-filters, in the order the printer writes them
   */
  public record Node(Op op, List<String> names, List<Node> kids) {
    public Node {
      names = List.copyOf(names);
      kids = List.copyOf(kids);
    }
  }

  /**
   * One generated case.
   *
   * @param args the command line of the seed's Form, the filter last
   * @param tree the filter's tree: E for forms 0–4, a {@link Op#DEF2} of D and E for form 5, a {@link Op#RECURSE} of B
   *     and F for form 6
   * @param stdin the document
   */
  public record Generated(long seed, List<String> args, Node tree, byte[] stdin) {

    /** The case block, LF-terminated: {@code case}, an {@code arg} per option, {@code filter}, {@code stdin}, {@code end}. */
    public String block() {
      StringBuilder b = new StringBuilder("case ").append(FAMILY).append('/').append(seed).append('\n');
      for (String a : args.subList(0, args.size() - 1)) b.append("arg ").append(a).append('\n');
      b.append("filter ").append(CaseFormat.escape(args.getLast().getBytes(UTF_8))).append('\n');
      b.append("stdin ").append(CaseFormat.escape(stdin)).append('\n');
      return b.append("end\n").toString();
    }
  }

  private CoreV1() {}

  /**
   * The text of {@code diff/gen/core-v1.cases} for the seeds [{@code from}, {@code to}): a header naming the
   * Generator, one {@code # dropped <seed> <reason>} line per Dropped seed, then the block of every other seed, each
   * after a blank line.
   *
   * @param dropped the reason ({@code rc3} or {@code timeout}) by Dropped seed, in seed order
   */
  public static String file(long from, long to, Map<Long, String> dropped) {
    StringBuilder b = new StringBuilder();
    b.append("# Generated cases of core-v1 (#58), seeds [").append(from).append(", ").append(to).append("): ");
    b.append("oracle/src/main/java/io/github/subapavel/jqj/oracle/gen/CoreV1.java.\n");
    b.append("# Written with core-v1.rec by scripts/oracle-record.sh --generate core-v1 ").append(from).append(' ').append(to);
    b.append(", never by hand; a seed that hits a deviation keeps its block and gains deviation and jqj-* lines.\n");
    for (Map.Entry<Long, String> d : dropped.entrySet()) b.append("# dropped ").append(d.getKey()).append(' ').append(d.getValue()).append('\n');
    for (long seed = from; seed < to; seed++) {
      if (!dropped.containsKey(seed)) b.append('\n').append(generate(seed).block());
    }
    return b.toString();
  }

  /** The case of one seed. */
  public static Generated generate(long seed) {
    Draw d = new Draw(new SplitMix64(seed));
    int form = (int) Math.floorMod(seed, 7L);
    Node tree =
        switch (form) {
          case 5 -> d.withDef();
          case 6 -> d.recursive();
          default -> d.gen(Type.V, 1, Scope.TOP);
        };
    byte[] stdin = d.document();
    String filter = print(tree);
    return new Generated(seed, form == 4 ? List.of(filter) : List.of("-c", filter), tree, stdin);
  }

  /** The filter text of a tree. */
  public static String print(Node n) {
    StringBuilder b = new StringBuilder();
    print(b, n);
    return b.toString();
  }

  private static void print(StringBuilder b, Node n) {
    List<String> m = n.names();
    List<Node> k = n.kids();
    switch (n.op()) {
      case DOT, EMPTY, LENGTH, TYPE, TOSTRING, NOT, ERROR -> b.append(n.op().text);
      case PATH, VAR, LITERAL -> b.append(m.getFirst());
      case BREAK -> b.append("break ").append(m.getFirst());
      case CALL -> {
        b.append(m.getFirst());
        if (!k.isEmpty()) args(b, k);
      }
      case RANGE -> b.append("range(").append(m.get(0)).append("; ").append(m.get(1)).append(')');
      case PIPE, COMMA, ALTERNATIVE, ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, LE, GT, GE, AND, OR -> {
        operand(b, k.get(0));
        b.append(n.op().text);
        operand(b, k.get(1));
      }
      case LENGTH_OF, TOSTRING_OF, TYPE_OF, NOT_OF -> {
        operand(b, k.getFirst());
        b.append(n.op().text);
      }
      case REPEAT -> {
        operand(b, k.getFirst());
        b.append(" * ").append(m.getFirst());
      }
      case AS, AS_ARRAY, AS_OBJECT, ALT_DESTRUCT -> {
        operand(b, k.get(0));
        b.append(" as ");
        pattern(b, n);
        b.append(" | ");
        operand(b, k.get(1));
      }
      case IF -> {
        b.append("if ");
        print(b, k.get(0));
        b.append(" then ");
        print(b, k.get(1));
        b.append(" else ");
        print(b, k.get(2));
        b.append(" end");
      }
      case IF_ELIF -> {
        b.append("if ");
        print(b, k.get(0));
        b.append(" then ");
        print(b, k.get(1));
        b.append(" elif ");
        print(b, k.get(2));
        b.append(" then ");
        print(b, k.get(3));
        b.append(" else ");
        print(b, k.get(4));
        b.append(" end");
      }
      case IF_NO_ELSE -> {
        b.append("if ");
        print(b, k.get(0));
        b.append(" then ");
        print(b, k.get(1));
        b.append(" end");
      }
      case TRY -> {
        b.append("try ");
        operand(b, k.getFirst());
      }
      case TRY_CATCH -> {
        b.append("try ");
        operand(b, k.get(0));
        b.append(" catch ");
        operand(b, k.get(1));
      }
      case OPTIONAL -> {
        b.append('(');
        print(b, k.getFirst());
        b.append(")?");
      }
      case REDUCE, FOREACH, FOREACH3 -> {
        b.append(n.op() == Op.REDUCE ? "reduce " : "foreach ");
        operand(b, k.getFirst());
        b.append(" as ").append(m.getFirst()).append(' ');
        args(b, k.subList(1, k.size()));
      }
      case LABEL -> {
        b.append("label ").append(m.getFirst()).append(" | ");
        operand(b, k.getFirst());
      }
      case SELECT, FIRST, ERROR_OF -> {
        b.append(n.op() == Op.SELECT ? "select" : n.op() == Op.FIRST ? "first" : "error");
        args(b, k);
      }
      case LIMIT -> {
        b.append("limit(").append(m.getFirst()).append("; ");
        print(b, k.getFirst());
        b.append(')');
      }
      case COLLECT -> {
        b.append('[');
        print(b, k.getFirst());
        b.append(']');
      }
      case MAP -> {
        operand(b, k.get(0));
        b.append(" | map(");
        print(b, k.get(1));
        b.append(')');
      }
      case OBJECT -> {
        b.append('{');
        for (int i = 0; i < k.size(); i++) {
          if (i > 0) b.append(", ");
          b.append(m.get(i)).append(": ");
          operand(b, k.get(i));
        }
        b.append('}');
      }
      case OBJECT_COMPUTED -> {
        b.append("{(");
        print(b, k.get(0));
        b.append("): ");
        operand(b, k.get(1));
        b.append('}');
      }
      case DEF0, DEF1, DEF1V -> {
        def(b, n);
        operand(b, k.get(1));
      }
      case DEF2 -> {
        def(b, n);
        print(b, k.get(1));
      }
      case RECURSE -> {
        b.append("def r(f; $k): if $k < 1 then ");
        print(b, k.get(0));
        b.append(" else f | r(f; $k - 1) end; r(");
        print(b, k.get(1));
        b.append("; ").append(m.getFirst()).append(')');
      }
    }
  }

  /** {@code def name(params): body; }, the rest left to the caller. */
  private static void def(StringBuilder b, Node n) {
    List<String> m = n.names();
    b.append("def ").append(m.getFirst());
    if (m.size() > 1) b.append('(').append(String.join("; ", m.subList(1, m.size()))).append(')');
    b.append(": ");
    print(b, n.kids().getFirst());
    b.append("; ");
  }

  /** {@code (a; b; …)}. */
  private static void args(StringBuilder b, List<Node> args) {
    b.append('(');
    for (int i = 0; i < args.size(); i++) {
      if (i > 0) b.append("; ");
      print(b, args.get(i));
    }
    b.append(')');
  }

  /** {@code $v}, {@code [$a, $b]}, {@code {k: $a, …}} or {@code [$a, …] ?// {k: $a, …}}. */
  private static void pattern(StringBuilder b, Node n) {
    List<String> m = n.names();
    switch (n.op()) {
      case AS -> b.append(m.getFirst());
      case AS_ARRAY -> b.append('[').append(String.join(", ", m)).append(']');
      case AS_OBJECT -> objectPattern(b, m);
      default -> {
        List<String> vars = new ArrayList<>();
        for (int i = 1; i < m.size(); i += 2) vars.add(m.get(i));
        b.append('[').append(String.join(", ", vars)).append("] ?// ");
        objectPattern(b, m);
      }
    }
  }

  private static void objectPattern(StringBuilder b, List<String> pairs) {
    b.append('{');
    for (int i = 0; i < pairs.size(); i += 2) {
      if (i > 0) b.append(", ");
      b.append(pairs.get(i)).append(": ").append(pairs.get(i + 1));
    }
    b.append('}');
  }

  /** A sub-filter in an operand place: in parentheses unless jq reads it as one term. */
  private static void operand(StringBuilder b, Node n) {
    boolean term = n.op().term && !(n.op() == Op.LITERAL && n.names().getFirst().startsWith("-"));
    if (term) {
      print(b, n);
      return;
    }
    b.append('(');
    print(b, n);
    b.append(')');
  }

  /** True if a call of {@code name} occurs anywhere in {@code n}. */
  private static boolean calls(Node n, String name) {
    if (n.op() == Op.CALL && n.names().getFirst().equals(name)) return true;
    for (Node k : n.kids()) {
      if (calls(k, name)) return true;
    }
    return false;
  }

  /** A def or closure parameter in scope. */
  private record Fn(String name, int arity) {}

  /**
   * The sub-filter places #58's decision names. A closed place drops the labels from outside it, because a catching
   * construct, a def or {@code error} would lie between such a label and a break. An opened place keeps them, as
   * every place not named here does. A seed with a {@code ?//} draws no break at all ({@code alt}), so the
   * binding's closed scope is a second guard.
   */
  enum Place {
    TRY_BODY(false),
    OPTIONAL_OPERAND(false),
    ALT_DESTRUCT_BINDING(false),
    DEF_BODY(false),
    CALL_ARGUMENT(false),
    ERROR_ARGUMENT(false),
    TRY_HANDLER(true),
    ALTERNATIVE_LEFT(true),
    SELECT_ARGUMENT(true),
    FIRST_ARGUMENT(true),
    LIMIT_ARGUMENT(true),
    MAP_ARGUMENT(true);

    private final boolean opened;

    Place(boolean opened) {
      this.opened = opened;
    }
  }

  /**
   * What a node may use: the bound variables, the labels a {@code break} may name, and the defs and parameters with
   * their arities.
   */
  record Scope(List<String> vars, List<String> breakable, List<Fn> fns) {

    static final Scope TOP = new Scope(List.of(), List.of(), List.of());

    Scope var(String v) {
      return new Scope(with(vars, v), breakable, fns);
    }

    Scope label(String l) {
      return new Scope(vars, with(breakable, l), fns);
    }

    /** The scope of place {@code p}: this one where {@code p} is opened, no breakable label where it is closed. */
    Scope in(Place p) {
      return p.opened ? this : new Scope(vars, List.of(), fns);
    }

    Scope fn(String name, int arity) {
      List<Fn> f = new ArrayList<>(fns);
      f.add(new Fn(name, arity));
      return new Scope(vars, breakable, f);
    }

    List<Fn> arity0() {
      return fns.stream().filter(f -> f.arity() == 0).toList();
    }

    List<Fn> callable() {
      return fns.stream().filter(f -> f.arity() > 0).toList();
    }

    /** The def names not yet taken in this scope, in pool order. */
    List<String> fresh() {
      return DEFS.stream().filter(d -> fns.stream().noneMatch(f -> f.name().equals(d))).toList();
    }

    private static List<String> with(List<String> list, String x) {
      if (list.contains(x)) return list;
      List<String> out = new ArrayList<>(list);
      out.add(x);
      return out;
    }
  }

  /**
   * One seed's draws: the PRNG, the places left that can give several results, and whether the seed holds a {@code ?//}
   * ({@code alt}) or a {@code first}, {@code limit} or {@code break} ({@code cut}); a seed never holds both.
   */
  private static final class Draw {

    private final SplitMix64 r;
    private int sites = SITES;
    private boolean alt;
    private boolean cut;

    Draw(SplitMix64 r) {
      this.r = r;
    }

    /** Form 5: {@code def g(f; $p): D; E}, E redrawn until it calls g. */
    Node withDef() {
      Node body = gen(Type.V, 1, Scope.TOP.fn("f", 0).var("$p"));
      int left = sites;
      boolean altInBody = alt;
      boolean cutInBody = cut;
      Scope after = Scope.TOP.fn("g", 2);
      Node rest;
      do {
        sites = left;
        alt = altInBody;
        cut = cutInBody;
        rest = gen(Type.V, 1, after);
      } while (!calls(rest, "g"));
      return new Node(Op.DEF2, List.of("g", "f", "$p"), List.of(body, rest));
    }

    /** Form 6: the recursive template, B with f and $k, F without a place that gives several results. */
    Node recursive() {
      Node base = gen(Type.V, 1, Scope.TOP.fn("f", 0).var("$k"));
      sites = 0;
      Node step = gen(Type.V, 1, Scope.TOP);
      return new Node(Op.RECURSE, List.of(Integer.toString(r.below(5))), List.of(base, step));
    }

    /** A filter of type {@code t} at {@code depth} (1..4). */
    Node gen(Type t, int depth, Scope s) {
      Type u = t != Type.V && r.below(100) < W_ANY ? Type.V : t;
      return r.below(100) < LEAF[depth - 1] ? leaf(u, s) : inner(u, depth + 1, s);
    }

    private Node leaf(Type t, Scope s) {
      int v = s.vars().isEmpty() ? 0 : 1;
      int b = s.breakable().isEmpty() || alt ? 0 : 1;
      return switch (t) {
        case V -> {
          int i = pick(8, 12, 6 * v, 4, 1, 2, 1, 1, 1, 1, 12 * b, s.arity0().isEmpty() ? 0 : 4);
          yield switch (i) {
            case 0 -> leaf(Op.DOT);
            case 1 -> path();
            case 2 -> var(s);
            case 3 -> literal(Type.V);
            case 4 -> leaf(Op.EMPTY);
            case 5 -> leaf(Op.LENGTH);
            case 6 -> leaf(Op.TYPE);
            case 7 -> leaf(Op.TOSTRING);
            case 8 -> leaf(Op.NOT);
            case 9 -> leaf(Op.ERROR);
            case 10 -> brk(s);
            default -> new Node(Op.CALL, List.of(one(s.arity0()).name()), List.of());
          };
        }
        case N -> switch (pick(6, 2, 2, 2 * v, 4 * b)) {
          case 0 -> literal(Type.N);
          case 1 -> leaf(Op.LENGTH);
          case 2 -> path();
          case 3 -> var(s);
          default -> brk(s);
        };
        case S -> switch (pick(6, 2, 1, 1, v, 4 * b)) {
          case 0 -> literal(Type.S);
          case 1 -> leaf(Op.TOSTRING);
          case 2 -> leaf(Op.TYPE);
          case 3 -> path();
          case 4 -> var(s);
          default -> brk(s);
        };
        case B -> switch (pick(6, 2, 1, v, 4 * b)) {
          case 0 -> literal(Type.B);
          case 1 -> leaf(Op.NOT);
          case 2 -> path();
          case 3 -> var(s);
          default -> brk(s);
        };
        case A -> switch (pick(6, 1, v, 4 * b)) {
          case 0 -> literal(Type.A);
          case 1 -> path();
          case 2 -> var(s);
          default -> brk(s);
        };
        case O -> switch (pick(6, 1, 1, v, 4 * b)) {
          case 0 -> literal(Type.O);
          case 1 -> leaf(Op.DOT);
          case 2 -> path();
          case 3 -> var(s);
          default -> brk(s);
        };
        case Z -> literal(Type.Z);
      };
    }

    /** An inner production of type {@code t}; its sub-filters are at depth {@code d}. */
    private Node inner(Type t, int d, Scope s) {
      int site = sites > 0 ? 1 : 0;
      return switch (t) {
        case V -> {
          List<String> fresh = s.fresh();
          int def = fresh.isEmpty() ? 0 : 1;
          int def1 = fresh.size() < 2 ? 0 : 1;
          int call = s.callable().isEmpty() ? 0 : 6;
          int altw = cut ? 0 : 1;
          int cutw = alt ? 0 : 2;
          int i = pick(12, 6 * site, 6, 1, 1, altw, 3, 1, 1, 3, 3, 3, 3, 1, 1, 1, 6, 2, cutw, cutw, def, def1, def, 1, call, 8, 6, 6, 6, 6);
          yield switch (i) {
            case 0 -> node(Op.PIPE, gen(Type.V, d, s), gen(Type.V, d, s));
            case 1 -> {
              sites--;
              yield node(Op.COMMA, gen(Type.V, d, s), gen(Type.V, d, s));
            }
            case 2 -> {
              String v = one(VARS);
              yield new Node(Op.AS, List.of(v), List.of(gen(Type.V, d, s), gen(Type.V, d, s.var(v))));
            }
            case 3 -> {
              List<String> vars = distinct(VARS, r.between(1, 2));
              Node source = gen(Type.V, d, s);
              yield new Node(Op.AS_ARRAY, vars, List.of(source, gen(Type.V, d, bind(s, vars))));
            }
            case 4 -> destructure(Op.AS_OBJECT, d, s);
            case 5 -> {
              alt = true;
              yield destructure(Op.ALT_DESTRUCT, d, s.in(Place.ALT_DESTRUCT_BINDING));
            }
            case 6 -> node(Op.IF, gen(Type.B, d, s), gen(Type.V, d, s), gen(Type.V, d, s));
            case 7 -> node(Op.IF_ELIF, gen(Type.B, d, s), gen(Type.V, d, s), gen(Type.B, d, s), gen(Type.V, d, s), gen(Type.V, d, s));
            case 8 -> node(Op.IF_NO_ELSE, gen(Type.B, d, s), gen(Type.V, d, s));
            case 9 -> node(Op.TRY, gen(Type.V, d, s.in(Place.TRY_BODY)));
            case 10 -> node(Op.TRY_CATCH, gen(Type.V, d, s.in(Place.TRY_BODY)), gen(Type.V, d, s.in(Place.TRY_HANDLER)));
            case 11 -> node(Op.OPTIONAL, gen(Type.V, d, s.in(Place.OPTIONAL_OPERAND)));
            case 12 -> node(Op.ALTERNATIVE, gen(Type.V, d, s.in(Place.ALTERNATIVE_LEFT)), gen(Type.V, d, s));
            case 13, 14, 15 -> {
              Op op = i == 13 ? Op.REDUCE : i == 14 ? Op.FOREACH : Op.FOREACH3;
              String v = one(VARS);
              List<Node> kids = new ArrayList<>(List.of(gen(Type.V, d, s), gen(Type.V, d, s), gen(Type.V, d, s.var(v))));
              if (op == Op.FOREACH3) kids.add(gen(Type.V, d, s.var(v)));
              yield new Node(op, List.of(v), kids);
            }
            case 16 -> {
              String l = one(LABELS);
              Scope in = s.label(l);
              // With a multi-result place left, the body is K, R: a break in K cuts R off, so it shows in the output.
              if (site == 0 || d == 4) yield new Node(Op.LABEL, List.of(l), List.of(gen(Type.V, d, in)));
              sites--;
              Node body = node(Op.COMMA, gen(Type.V, d + 1, in), gen(Type.V, d + 1, in));
              yield new Node(Op.LABEL, List.of(l), List.of(body));
            }
            case 17 -> node(Op.SELECT, gen(Type.B, d, s.in(Place.SELECT_ARGUMENT)));
            case 18 -> {
              cut = true;
              yield node(Op.FIRST, gen(Type.V, d, s.in(Place.FIRST_ARGUMENT)));
            }
            case 19 -> {
              cut = true;
              yield new Node(Op.LIMIT, List.of(Integer.toString(r.below(4))), List.of(gen(Type.V, d, s.in(Place.LIMIT_ARGUMENT))));
            }
            case 20 -> {
              String name = one(fresh);
              yield new Node(Op.DEF0, List.of(name), List.of(gen(Type.V, d, s.in(Place.DEF_BODY)), gen(Type.V, d, s.fn(name, 0))));
            }
            case 21 -> {
              List<String> two = distinct(fresh, 2);
              Node body = gen(Type.V, d, s.in(Place.DEF_BODY).fn(two.get(1), 0));
              yield new Node(Op.DEF1, two, List.of(body, gen(Type.V, d, s.fn(two.get(0), 1))));
            }
            case 22 -> {
              String name = one(fresh);
              String v = one(VARS);
              Node body = gen(Type.V, d, s.in(Place.DEF_BODY).var(v));
              yield new Node(Op.DEF1V, List.of(name, v), List.of(body, gen(Type.V, d, s.fn(name, 1))));
            }
            case 23 -> node(Op.ERROR_OF, gen(Type.V, d, s.in(Place.ERROR_ARGUMENT)));
            case 24 -> {
              Fn f = one(s.callable());
              List<Node> args = new ArrayList<>();
              for (int k = 0; k < f.arity(); k++) args.add(gen(Type.V, d, s.in(Place.CALL_ARGUMENT)));
              yield new Node(Op.CALL, List.of(f.name()), args);
            }
            case 25 -> inner(Type.N, d, s);
            case 26 -> inner(Type.S, d, s);
            case 27 -> inner(Type.B, d, s);
            case 28 -> inner(Type.A, d, s);
            default -> inner(Type.O, d, s);
          };
        }
        case N -> switch (pick(3, 1, 2, 1, 1, 3, 2 * site)) {
          case 0 -> node(Op.ADD, gen(Type.N, d, s), gen(Type.N, d, s));
          case 1 -> node(Op.SUB, gen(Type.N, d, s), gen(Type.N, d, s));
          case 2 -> node(Op.MUL, gen(Type.N, d, s), gen(Type.N, d, s));
          case 3 -> divide(Op.DIV, d, s);
          case 4 -> divide(Op.MOD, d, s);
          case 5 -> node(Op.LENGTH_OF, gen(List.of(Type.S, Type.A, Type.O).get(r.below(3)), d, s));
          default -> {
            sites--;
            yield new Node(Op.RANGE, List.of(Integer.toString(r.between(-2, 6)), Integer.toString(r.between(-2, 6))), List.of());
          }
        };
        case S -> switch (pick(4, 3, 2, 1)) {
          case 0 -> node(Op.ADD, gen(Type.S, d, s), gen(Type.S, d, s));
          case 1 -> node(Op.TOSTRING_OF, gen(Type.V, d, s));
          case 2 -> new Node(Op.REPEAT, List.of(Integer.toString(r.below(4))), List.of(gen(Type.S, d, s)));
          default -> node(Op.TYPE_OF, gen(Type.V, d, s));
        };
        case B -> switch (pick(2, 1, 1, 1, 1, 1, 2, 1, 2)) {
          case 0 -> node(Op.EQ, gen(Type.V, d, s), gen(Type.V, d, s));
          case 1 -> node(Op.NE, gen(Type.V, d, s), gen(Type.Z, d, s));
          case 2 -> node(Op.LT, gen(Type.N, d, s), gen(Type.N, d, s));
          case 3 -> node(Op.LE, gen(Type.N, d, s), gen(Type.N, d, s));
          case 4 -> node(Op.GT, gen(Type.N, d, s), gen(Type.N, d, s));
          case 5 -> node(Op.GE, gen(Type.N, d, s), gen(Type.N, d, s));
          case 6 -> node(Op.AND, gen(Type.B, d, s), gen(Type.B, d, s));
          case 7 -> node(Op.OR, gen(Type.B, d, s), gen(Type.B, d, s));
          default -> node(Op.NOT_OF, gen(Type.B, d, s));
        };
        case A -> switch (pick(6, 2, 1, 2)) {
          case 0 -> node(Op.COLLECT, gen(Type.V, d, s));
          case 1 -> node(Op.ADD, gen(Type.A, d, s), gen(Type.A, d, s));
          case 2 -> node(Op.SUB, gen(Type.A, d, s), gen(Type.A, d, s));
          default -> node(Op.MAP, gen(Type.A, d, s), gen(Type.V, d, s.in(Place.MAP_ARGUMENT)));
        };
        case O -> switch (pick(5, 2, 1, 1)) {
          case 0 -> {
            List<String> keys = distinct(KEYS, r.between(1, 2));
            List<Node> values = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) values.add(gen(Type.V, d, s));
            yield new Node(Op.OBJECT, keys, values);
          }
          case 1 -> node(Op.ADD, gen(Type.O, d, s), gen(Type.O, d, s));
          case 2 -> node(Op.MUL, gen(Type.O, d, s), gen(Type.O, d, s));
          default -> {
            Node key = gen(Type.S, d, s);
            // jq folds a constant key at compile time and refuses one that is no string (rc 3).
            if (constant(key)) key = literal(Type.S);
            yield node(Op.OBJECT_COMPUTED, key, gen(Type.V, d, s));
          }
        };
        case Z -> leaf(Type.Z, s);
      };
    }

    /** True for a literal, or an arithmetic or comparison operator (or {@code S * n}) over constants: what jq folds. */
    private static boolean constant(Node n) {
      if (n.op() == Op.LITERAL) return true;
      if (!FOLDED.contains(n.op())) return false;
      for (Node k : n.kids()) {
        if (!constant(k)) return false;
      }
      return true;
    }

    /** {@code a / b} or {@code a % b}; a literal divided by a literal zero gets the divisor {@code 1}. */
    private Node divide(Op op, int d, Scope s) {
      Node a = gen(Type.N, d, s);
      Node b = gen(Type.N, d, s);
      if (a.op() == Op.LITERAL && b.op() == Op.LITERAL && zero(op, b.names().getFirst())) b = new Node(Op.LITERAL, List.of("1"), List.of());
      return node(op, a, b);
    }

    /** True for a divisor that is zero, or truncates to zero under {@code %}. */
    private static boolean zero(Op op, String literal) {
      return literal.equals("0") || literal.equals("-0") || (op == Op.MOD && literal.equals("0.5"));
    }

    /** An object pattern or {@code [..] ?// {..}}: 1–2 distinct keys, each with its own variable, then the body. */
    private Node destructure(Op op, int d, Scope s) {
      int n = r.between(1, 2);
      List<String> keys = distinct(KEYS, n);
      List<String> vars = distinct(VARS, n);
      List<String> pairs = new ArrayList<>();
      for (int k = 0; k < n; k++) {
        pairs.add(keys.get(k));
        pairs.add(vars.get(k));
      }
      Node source = gen(Type.V, d, s);
      return new Node(op, pairs, List.of(source, gen(Type.V, d, bind(s, vars))));
    }

    private static Scope bind(Scope s, List<String> vars) {
      Scope out = s;
      for (String v : vars) out = out.var(v);
      return out;
    }

    private Node path() {
      int many = sites > 0 ? 1 : 0;
      int i = pick(5, 2, 2, 1, 2 * many, many);
      String text =
          switch (i) {
            case 0 -> "." + one(KEYS);
            case 1 -> "." + one(KEYS) + "." + one(KEYS);
            case 2 -> ".[" + r.between(-1, 2) + "]";
            case 3 -> "." + one(KEYS) + "?";
            case 4 -> ".[]";
            default -> ".[]?";
          };
      if (i >= 4) sites--;
      return new Node(Op.PATH, List.of(text), List.of());
    }

    /** {@code break $l} for a breakable label. */
    private Node brk(Scope s) {
      cut = true;
      return new Node(Op.BREAK, List.of(one(s.breakable())), List.of());
    }

    private Node var(Scope s) {
      return new Node(Op.VAR, List.of(one(s.vars())), List.of());
    }

    private Node literal(Type t) {
      return new Node(Op.LITERAL, List.of(literalText(t)), List.of());
    }

    private String literalText(Type t) {
      return switch (t) {
        case N -> number();
        case S -> one(STRINGS);
        case B -> r.below(2) == 0 ? "true" : "false";
        case A -> {
          if (r.below(2) == 0) yield "[]";
          List<String> items = new ArrayList<>();
          for (int k = r.between(1, 2); k > 0; k--) items.add(scalar());
          yield "[" + String.join(", ", items) + "]";
        }
        case O -> {
          if (r.below(2) == 0) yield "{}";
          String key = one(KEYS);
          String value = scalar();
          yield "{" + key + ": " + (value.startsWith("-") ? "(" + value + ")" : value) + "}";
        }
        case Z -> "null";
        case V -> literalText(List.of(Type.N, Type.S, Type.B, Type.Z, Type.A, Type.O).get(pick(4, 3, 2, 1, 1, 1)));
      };
    }

    private String scalar() {
      return r.below(2) == 0 ? number() : one(STRINGS);
    }

    private String number() {
      return NUMBERS.get(pick(NUMBER_WEIGHTS));
    }

    /** The document: one strict JSON value, compact, redrawn while it is over {@value #DOCUMENT_CAP} bytes. */
    byte[] document() {
      byte[] out;
      do {
        StringBuilder b = new StringBuilder();
        switch (pick(50, 30, 20)) {
          case 0 -> object(b, 1);
          case 1 -> array(b, 1);
          default -> scalar(b, pick(30, 25, 5, 5, 5));
        }
        if (r.below(10) < 7) b.append('\n');
        out = b.toString().getBytes(UTF_8);
      } while (out.length > DOCUMENT_CAP);
      return out;
    }

    /** A member value inside a container at {@code depth}: containers only while {@code depth < 3}. */
    private void value(StringBuilder b, int depth) {
      int nest = depth < 3 ? 15 : 0;
      int i = pick(30, 25, 5, 5, 5, nest, nest);
      if (i == 5) array(b, depth + 1);
      else if (i == 6) object(b, depth + 1);
      else scalar(b, i);
    }

    private void scalar(StringBuilder b, int kind) {
      b.append(
          switch (kind) {
            case 0 -> number();
            case 1 -> one(STRINGS);
            case 2 -> "true";
            case 3 -> "false";
            default -> "null";
          });
    }

    private int members() {
      return r.below(100) < 15 ? 0 : r.between(1, 4);
    }

    private void array(StringBuilder b, int depth) {
      b.append('[');
      for (int k = members(); k > 0; k--) {
        value(b, depth);
        if (k > 1) b.append(',');
      }
      b.append(']');
    }

    private void object(StringBuilder b, int depth) {
      b.append('{');
      List<String> keys = new ArrayList<>();
      for (int k = members(); k > 0; k--) {
        String key;
        do {
          key = documentKey();
        } while (keys.contains(key));
        keys.add(key);
        b.append('"').append(key).append("\":");
        value(b, depth);
        if (k > 1) b.append(',');
      }
      b.append('}');
    }

    private String documentKey() {
      return switch (pick(30, 25, 15, 10, 5, 15)) {
        case 0 -> "a";
        case 1 -> "b";
        case 2 -> "c";
        case 3 -> "x y";
        case 4 -> "";
        default -> {
          StringBuilder k = new StringBuilder();
          for (int n = r.between(1, 3); n > 0; n--) {
            int c = 0x20 + r.below(93);
            if (c >= '"') c++;
            if (c >= '\\') c++;
            k.append((char) c);
          }
          yield k.toString();
        }
      };
    }

    /** {@code n} distinct names drawn from {@code pool}, in draw order. */
    private List<String> distinct(List<String> pool, int n) {
      List<String> out = new ArrayList<>();
      while (out.size() < n) {
        String x = one(pool);
        if (!out.contains(x)) out.add(x);
      }
      return out;
    }

    private <T> T one(List<T> pool) {
      return pool.get(r.below(pool.size()));
    }

    /** An index drawn by integer weights. */
    private int pick(int... weights) {
      int total = 0;
      for (int w : weights) total += w;
      int x = r.below(total);
      int i = 0;
      while (x >= weights[i]) x -= weights[i++];
      return i;
    }

    private static Node leaf(Op op) {
      return new Node(op, List.of(), List.of());
    }

    private static Node node(Op op, Node... kids) {
      return new Node(op, List.of(), List.of(kids));
    }
  }
}
