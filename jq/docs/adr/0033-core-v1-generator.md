# core-v1: seven forms, a closed name list and its own documents

Status: accepted (2026-11-12; decided by the grilling-adviser on the owner's delegation, "give 53 and 58 to fable to answer", ticket #58; designer draft); amends ADR-0020 (`core-v1`)

ADR-0020 names `core-v1` as spec 2's generator ("a typed core-language grammar to depth 4") with a floor of 1000 seeds. This ADR fixes what the generator draws, so its recordings can be frozen. A changed generator gets a new name (ADR-0020).

## Decision

- **Forms** by `seed % 7`, 2100 seeds `[0, 2100)`, 300 per form: 0–3 `-c E`; 4 `E` pretty-printed; 5 a non-recursive `def` that E calls; 6 the one bounded recursive template `r(f; $k)` with a literal K from 0 to 4. Only form 6 needs spec 2b, so at most 1800 seeds can pass in 2a.
- **Grammar**: typed (N S B A O Z, and V for any type), depth at most 4, weighted productions, at most three places per filter that can give several results. The name list is closed as #58 fixes it; the round-2 natives (`sort unique min max has keys tonumber tojson`) stay out (#72, #73 cover them). No `limit(-1; …)`.
- **Documents** are the generator's own (strict JSON, at most 512 B, keys from the filter's pool), not `values-v1`'s.
- **Computed keys** that jq folds to a non-string constant (`{(1+1): 2}` is rc 3 in jq 1.8.2) are redrawn as string literals.
- **`break` placement**: a `break` is drawn only for a label in scope, and never in a try body, a `?` operand, a `?//` binding, a def body, or an argument of a user def or a closure parameter (a callee can run its argument under `try`). It may appear in a try handler, on the left of `//`, and in the arguments of `select`, `first`, `limit` and `map`: jq-ref 1.8.2 breaks straight through each (probed with a trailing `, 5`).
- **`?//`**: no seed holds `?//` together with `first`, `limit` or `break`. On jq-ref, `[1] | [limit(1; . as [$a] ?// $a | $a)]` gives `[1,[1]]`, a shape of the listed `?//` deviation, which belongs in a hand-written case.
- **Weights**: `label` 6; `break` 12 in V leaves and 4 in typed leaves; a label body is drawn as `K, R` when a multi-result place is left, so that a break changes the output.
- **Freeze numbers** (dry run on jq-ref 1.8.2, 52e54b1): rc 3, timeouts and two-run rejections 0; rc 5 at 39 % (bound 42 %); 953 seeds rc 0 with non-empty stdout (bound 900); `label` in 343 seeds (250), `break` in 132 (90), every production in at least 35 (30); a break fires with rc 0 in 82 seeds (60); rewriting every `break` to `empty` changes rc, stdout or stderr in 23 seeds (20). The generator-only bounds are freeze tests.

## Considered options

- **rc 5 within 10–30 %**: a draft target in no ADR; GATE check 2 counts cases whatever their rc, and an rc 5 case still compares jq's stderr byte for byte. A further tuning round cost 3–6 productions their 30-seed floor.
- **The ticket's narrower exclusion (try, `?`, `?//` only)**: lets a `break` reach a callee's `try`. **A wider one (also the handler, `//`, every closure argument)**: hides the ParamCall path ADR-0008 singles out.
- **Raising the `break` weight alone**: 34 of 35 breaks were indistinguishable from `empty`.
- **Adding the round-2 natives**: more construction rules before a freeze, for natives their census cases already cover.
- **`values-v1`'s documents**: about 20 % carry lenient tokens, and their random keys never meet `.a`.
