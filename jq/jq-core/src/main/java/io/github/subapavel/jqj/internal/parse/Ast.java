package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.query.ir.Source;
import java.util.List;

/**
 * The parser's output (GLOSSARY "AST", ADR-0032): one record per construct of jq 1.8.2's grammar ({@code parser.y}),
 * sugar kept, each with its Source in UTF-8 bytes of the filter (ADR-0031). A postfix node spans its own bytes, from
 * the start of its suffix ({@code .name}, {@code .[}, {@code [}, {@code ?}) to its end, a {@code ?} after it included;
 * a node with operands spans them all. Only the {@link Resolver} reads it. A name is kept without its {@code $} or
 * {@code @}.
 */
public sealed interface Ast {
  /** The node's bytes of the filter. */
  Source source();

  /** {@code left | right}. */
  record Pipe(Source source, Ast left, Ast right) implements Ast {}

  /** {@code left, right}. */
  record Comma(Source source, Ast left, Ast right) implements Ast {}

  /** {@code def ...; body}: a definition and the query it is visible in. */
  record Def(Source source, FuncDef def, Ast body) implements Ast {}

  /** {@code value as p1 ?// p2 ... | body}: one pattern, or the alternatives of a destructuring. */
  record Bind(Source source, Ast value, List<Pattern> patterns, Ast body) implements Ast {}

  /** {@code label $name | body}. */
  record Label(Source source, String name, Ast body) implements Ast {}

  /** {@code left op right} for an operator of {@code Expr}. */
  record Binary(Source source, BinOp op, Ast left, Ast right) implements Ast {}

  /** {@code -operand}. */
  record Neg(Source source, Ast operand) implements Ast {}

  /** {@code try body} and {@code try body catch handler}; the handler is null without a catch. */
  record Try(Source source, Ast body, Ast handler) implements Ast {}

  /** {@code reduce input as patterns (init; update)}. */
  record Reduce(Source source, Ast input, List<Pattern> patterns, Ast init, Ast update) implements Ast {}

  /** {@code foreach input as patterns (init; update)} and {@code (init; update; extract)}; extract may be null. */
  record Foreach(Source source, Ast input, List<Pattern> patterns, Ast init, Ast update, Ast extract) implements Ast {}

  /** {@code if cond then then ... end}; an {@code elif} is an If as the otherwise, which is null without an else. */
  record If(Source source, Ast cond, Ast then, Ast otherwise) implements Ast {}

  /** {@code .} alone. */
  record Dot(Source source) implements Ast {}

  /** {@code ..}. */
  record Recurse(Source source) implements Ast {}

  /** {@code .name} or {@code target.name}; the target is null for a term that is the FIELD alone. */
  record Field(Source source, Ast target, String name, boolean optional) implements Ast {}

  /** {@code ."s"} or {@code target."s"}; the target is null for a term that is the dot and string alone. */
  record StringField(Source source, Ast target, Str name, boolean optional) implements Ast {}

  /** {@code target[index]} or {@code target.[index]}. */
  record Index(Source source, Ast target, Ast index, boolean optional) implements Ast {}

  /** {@code target[]} or {@code target.[]}. */
  record Each(Source source, Ast target, boolean optional) implements Ast {}

  /** {@code target[from:to]} with one bound null at most, or {@code target.[from:to]}. */
  record Slice(Source source, Ast target, Ast from, Ast to, boolean optional) implements Ast {}

  /** {@code target?} after a term that is not an index. */
  record Optional(Source source, Ast target) implements Ast {}

  /** A number literal, its lexeme as written. */
  record Num(Source source, String lexeme) implements Ast {}

  /** A string literal, its format (null without one) and its parts: text and interpolations. */
  record Str(Source source, String format, List<Part> parts) implements Ast {}

  /** A bare {@code @name}. */
  record Format(Source source, String name) implements Ast {}

  /** {@code (body)}. */
  record Paren(Source source, Ast body) implements Ast {}

  /** {@code [body]}, or {@code []} with a null body. */
  record ArrayCons(Source source, Ast body) implements Ast {}

  /** {@code {pairs}}. */
  record ObjectCons(Source source, List<Pair> pairs) implements Ast {}

  /** {@code $name}. */
  record Var(Source source, String name) implements Ast {}

  /** {@code $$$$name}: the variable's last read (GLOSSARY "Last read"), as builtin.jq's {@code _modify} writes it. */
  record LastRead(Source source, String name) implements Ast {}

  /** {@code $__loc__}. */
  record Loc(Source source) implements Ast {}

  /** {@code name} or {@code name(arg; ...)}. */
  record Call(Source source, String name, List<Ast> args) implements Ast {}

  /** {@code break $name}. */
  record Break(Source source, String name) implements Ast {}

  /** A part of a string literal. */
  sealed interface Part {
    Source source();
  }

  /** A run of a string literal's text: its plain bytes and its escape runs' values, as UTF-8. */
  record Text(Source source, byte[] bytes) implements Part {}

  /** {@code \(query)}. */
  record Interp(Source source, Ast query) implements Part {}

  /** {@code def name(params): body;}. */
  record FuncDef(Source source, String name, List<Param> params, Ast body) {}

  /** A parameter: a filter {@code name}, or a value {@code $name}. */
  record Param(Source source, String name, boolean value) {}

  /** A destructuring pattern. */
  sealed interface Pattern {
    Source source();
  }

  /** {@code $name}. */
  record VarPattern(Source source, String name) implements Pattern {}

  /** {@code [p, ...]}. */
  record ArrayPattern(Source source, List<Pattern> elements) implements Pattern {}

  /** {@code {entry, ...}}. */
  record ObjectPattern(Source source, List<ObjPat> entries) implements Pattern {}

  /** An entry of an object pattern: a key, then its pattern, which is null for {@code $name} alone. */
  record ObjPat(Source source, Key key, Pattern value) {}

  /** An object key of a construction or a pattern. */
  sealed interface Key {
    Source source();
  }

  /** A name or keyword. */
  record NameKey(Source source, String name) implements Key {}

  /** {@code $name}: the key is the name, the value the variable. */
  record VarKey(Source source, String name) implements Key {}

  /** {@code $__loc__}, the shorthand of {@code "__loc__": $__loc__}. */
  record LocKey(Source source) implements Key {}

  /** A string literal. */
  record StringKey(Source source, Str string) implements Key {}

  /** {@code (query)}. */
  record QueryKey(Source source, Ast query) implements Key {}

  /** A pair of an object construction: its key, and its value, null for the shorthand without a colon. */
  record Pair(Source source, Key key, Ast value) {}

  /** {@code import path as name;}, {@code import path as $name;} (data) or {@code include path;}, metadata optional. */
  record Import(Source source, Str path, String name, boolean data, Ast metadata) {}
}
