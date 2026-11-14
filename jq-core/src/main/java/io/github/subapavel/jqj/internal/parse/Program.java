package io.github.subapavel.jqj.internal.parse;

import java.util.List;

/**
 * A parsed filter (ADR-0032): jq's {@code TopLevel}, its module directive's metadata (null without one), its imports,
 * and either its top-level filter, definitions before it folded into it as {@link Ast.Def}s, or only definitions, with
 * a null main. {@code folds} is the compile's fold budget, which the parser's key checks began to spend and the
 * resolver's folds go on spending (ADR-0035); {@code text} is the filter's text, which places the resolver's error
 * blocks and gives a {@code $__loc__} its line.
 */
public record Program(Ast module, List<Ast.Import> imports, List<Ast.FuncDef> defs, Ast main, ConstantKey folds, FilterText text) {}
