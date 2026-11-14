# Try marks downstream errors by a wrap depth; Label and Break are slot tokens

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #68; designer draft); amends ADR-0005 (Try, Label and Break fields), ADR-0008 (catch set, run state), ADR-0037 (Alt and Collect after a break) and ADR-0038 (AltBind rethrow); the internal Detach versus the public `detach()` corrected at the spec 2a review (#100); amended by F-85 (#194: a false a node's own Holder gave ends at that node)

jq 1.8.2 implements `try` as `TRY_BEGIN`, body, `TRY_END`. An error that leaves the body's output (a downstream error) is wrapped on its way back, and `TRY_BEGIN` unwraps and re-raises a wrapped error instead of running the handler. jq 1.8.2 has neither FORK_OPT nor gen_try_handler. The jq facts here were read from jq-1.8.2 source; #68's red records them on jq-ref, and jq-ref wins.

## Decision

- **`if c then a (elif …) else d end`** lowers to nested `If(…, JqTruth)` (ADR-0037). A missing `else` is Identity.
- **`Try(source, body, handler)`** always has a handler. `try E` and `E?` take `Empty`. A navigation `?` stays the quiet variant (ADR-0034).
- **Wrap depth**:
  - An error thrown from a Try's sink, that is by what runs downstream of the body's result, leaves with its wrap depth + 1.
  - At that Try's run it is unwrapped (− 1) and re-raised.
  - A Try runs its handler only on an error of depth 0, on that error's value.
  - AltBind catches an error of any depth, and its last alternative rethrows the instance it caught.
  - `JqFilter.run` sees depth 0.
- **`error`** is an Fn0 raising its input as the value, null included. `error(f)` is `f | error`, as builtin.jq defines it. Raising builds nothing and charges nothing.
- **The error payload** leaves `JqFilter.run` detached (ADR-0016). #68 builds the internal Detach, which copies a view into built values on an explicit stack at no charge, under ADR-0016's host rule (#85 replaced it with the shared walk). The public `JqValue.detach()` is not built in 2a (#100, spec 7).
- **The CLI message**:
  - a string prints `jq: error (at <name>:N): <text>`, cut at its first NUL as jq's `%s` does (jq-ref, `control/error-nul-string`);
  - any other value prints `jq: error (at <name>:N) (not a string): <compact dump>`;
  - rc 5.
- **`Label(source, slot, body)` and `Break(source, slot)`**:
  - Labels have their own scope, apart from variables.
  - `RunState.label()` numbers activations from 1 in each run.
  - A Break marks its token and returns false. Every node passes on at once a false from its `out` or
    from a Break. A false that only a node's own Holder gave (ADR-0043, F-85) ends at that node.
  - `Collect` emits nothing after a false, and `Alt` does not run its right side after one.
  - An unknown label is jq's compile error `$*label-l is not defined`, collected with ADR-0038's unbound-variable errors.
- **Cost**: If, Try (the handler entry included), Label, Break and `error` neither tick nor charge. Each runs at most once per activation (F-32's criterion).

## Considered options

- **An "emitting" flag per Try**: it goes stale once an AltBind in the body catches a downstream error.
- **A separate Downstream exception class**: it needs a second catch in AltBind and in `JqFilter.run`.
- **A nullable handler**: every backend would test for null.
- **Formatting the message inside the document's scope instead of detaching**: library callers would hold a stale view, against ADR-0016.
- **A tick on handler entry**: it does not multiply results.
