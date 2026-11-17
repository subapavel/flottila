# Filter positions and compile-error blocks

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #61; designer draft); amends ADR-0016 (`JqCompileException`); amended by ADR-0032 (errors before Stubs); Source of inlined prelude nodes amended by ADR-0042 (#71)

## Context

jq reports every lexical error of a filter, each as a block with the line, the column, the source
line and carets (jq-1.8.2 `locfile_locate`), then `jq: N compile error(s)`. ADR-0016 gave
`JqCompileException` one message and did not say what unit `line()` and `column()` count.

## Decision

- `Source` holds UTF-8 byte offsets into the filter text. Line = the number of LFs before the start,
  plus 1. Column = the number of bytes since the line start, plus 1 (jq's `locfile`). `line()` and
  `column()` on `JqCompileException` use these units.
- An **error block** is the text `locfile_locate` prints for one error: message, position, the
  source line and carets that stop at the line end.
- `public JqCompileException(List<String> blocks, int line, int column)`: `getMessage()` is the
  blocks joined by LF, then `\njq: N compile error(s)` (jq's wording for 1 and for N). `line` and
  `column` are the first block's. The `String` constructor stays for Stubs.
- Lexical errors are collected and thrown together at the end of the parse; a Stub met earlier
  wins.
- A lexeme's flags (ADR-0012 `FITS_LONG` and the rest) are computed on the normalised lexeme, so
  `007` fits a long.
- A message that quotes an escape run quotes it as jq does: jq-ref prints U+FFFD for an invalid
  byte inside the `while parsing '…'` part and the raw bytes in the source line (observed on
  `"\é"`, rc 3). jqj prints the same bytes, so no deviation.

## Consequences

- ADR-0005's "no `Const` holds a view" is not checked by the IR verifier: `query-core` cannot see
  the format's view type (founding §14). It is held by construction: only the parser makes `Const`.
- `ConstOp` is not a step site (ADR-0008) and charges no tick.

## Considered options

- Char offsets: the columns and carets need byte arithmetic anyway.
- Report the first error only: jq reports every bad run.
