# Parser-action errors are collected during the parse

Status: accepted (2026-11-12, architect under `/run-spec` #56, ticket #64; designer draft); amends ADR-0032 (errors raised before resolution)

jq 1.8.2's `parser.y` raises some errors from a grammar action, not from the resolver: a constant non-string object key (`{(1): 2}`, `{([1]): 1}`) fails at the action's `FAIL(@n, …)`, and the parse goes on, so later syntax errors are still reported. ADR-0032 lets errors come only from the parser's syntax recovery or from resolution.

## Decision

- **An action error** is recorded when its grammar action runs, at jq's position and with jq's message, and the parse continues. Action errors and syntax errors are reported in the order the parse met them; the error block and its count follow ADR-0031.
- **Constant keys** (a computed key that jq folds to a constant, containers included; a bare `{1: 2}` is a syntax error with its recovery message, not a key error, jq-ref 1.8.2) are checked by the action that builds the object, as jq does. A key jq folds is folded the same way here, before the check.
- Resolution still runs only on a parse with no error (ADR-0032).

## Considered options

- **Check keys in the resolver**: the error count and order differ from jq when the same filter also has a syntax error.
- **Raise and stop at the first action error**: jq reports later errors too.
