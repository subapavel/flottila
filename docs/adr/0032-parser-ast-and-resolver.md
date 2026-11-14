# The parser builds an AST; the resolver lowers it to IR

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #62; designer draft); amends ADR-0031 (when Stubs are raised); clarifies ADR-0005 (its rejected "jq-level node family"); amended by ADR-0036 (parser-action errors, #64)

## Context

Founding §4 and §7 put an AST and a resolver between the parser and the IR. #62 must accept jq
1.8.2's whole grammar, `builtin.jq` included, while S2a.13 to S2a.22 lower it ticket by ticket, and
the IR has no kinds yet for most constructs.

## Decision

- `Parser.parse` returns a `Program`: a sealed `Ast` family in `jq-core` `internal.parse`, one
  record per jq-1.8.2 `parser.y` construct, sugar kept, `Source` in ADR-0031 bytes (a postfix node
  spans its own bytes). Recursive descent for the query and term levels, precedence climbing for
  expressions over `BinOp`, which holds `parser.y`'s precedence and associativity in one place.
  Facts observed on jq-ref 1.8.2 (architect, #62): `2 * 3 as $x | $x + 1` gives 7 (`as` takes an
  expression); `- 1 + 2` gives 1; `try 1 catch 2 + 3` gives 4 and `[try error("x") + 1 catch .]`
  is a syntax error (`try` and `catch` take a postfix term); `1 < 2 < 3` is a syntax error at the
  second `<` (non-associative); `{a: 1 as $x | $x}` and `1 + def f: 2; f` are syntax errors;
  `[5,6] [1]` gives 6.
- The `Resolver` is the only reader of the AST and lowers it to IR. A kind it does not lower yet
  is a Stub. The AST has no verifier, never reaches `query-core`, and is dropped after resolution;
  it is not the second IR that ADR-0005 rejected.
- Lexical, syntax and recovery errors are all collected by the parse and thrown before
  resolution, so no Stub hides one. This replaces ADR-0031's "a Stub met earlier wins".
- Syntax-error text is jqj's own (ADR-0020, DEVIATIONS "Syntax-error text"): `syntax error,
  unexpected <jq token name>` at the token, no "expecting" list, stopping at the first syntax error
  except at jq's recovery productions. A recovery production is built only where jq-ref shows its
  message on a recorded input, and its block matches jq (`May need parentheses around object key
  expression`, jq.test `{1+2:3}`). A message that is not a Bison syntax error matches jq exactly:
  an empty program or one with only definitions gives `Top-level program not given (try ".")`.

## Consequences

- Later S2a tickets add lowering, not syntax. String parts are lexed and parsed in #62 (jq's
  `QQSTRING` split); lowering an interpolation stays a Stub until S2a.17 (F-35).
- The diff case ids `precedence/*` and `syntax/*` are immutable once recorded.

## Considered options

- IR with Stub nodes: most constructs have no IR kind, and a Stub node would enter ADR-0005's
  closed family and both backends.
- Stubs at parse time: a syntax error after an unbuilt construct would score as a Stub.
- Bison emulation (expecting lists, silent recovery): ADR-0020 rejects it.
