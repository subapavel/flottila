# Spec 2 "Core language" — grilling record

Published as spec 2a: #56.

Input for `/to-spec`. Owner answers, round by round. Drafts: designer (round 1), architect (later
rounds); every round evaluated by the `grilling-adviser` first.

## Round 1 (owner: yes to all eight, 2026-11-12)

1. **Split 2a / 2b at recursion.** 2a: run protocol with the Workspace, the full parser, every
   non-recursive construct, non-recursive `def` and closures inlined with an inline node cap from
   the first ticket (above it a Stub until 2b), the prelude loader and the natives its cases need,
   the steps / deadline / bytes-built / bytes-written budgets, core-v1, the compile-mode runner, G2
   and G4 at 10⁴ in `bench.tsv`. 2b: recursive groups, call kinds, form-B trampoline,
   recursion-depth budget, `stackBackstopIsTermination`; cardinality + may-break-out + value form,
   folding, specialisation, tail calls; U calibration; diagnostics. Census: about 402 jq-test cases
   for 2a, 26 for 2b; the 9 recursive `builtin.jq` definitions (`recurse/1`, `recurse/2`,
   `_flatten/1`, `while/2`, `until/2`, `repeat/1`, `combinations/0`, `tostream/0`, `walk/1`) are
   Stubs until 2b.
2. **Bytes built per slot:** ADR-0017 amendment (8 B / element, 16 B / entry, UTF-8 length, a
   measured fixed charge per built container and string).
3. **Conservative compile first** behind `CompileMode` from 2a's first ticket; each analysis its own
   class-C ticket in 2b (red: facts test + two-sided cardinality property test); the compile-mode
   oracle after every merge (F-29).
4. **Lazy prelude:** jq 1.8.2 `builtin.jq` copied byte for byte (notice, `NOTICE`), parsed once per
   JVM on the first compile naming a non-native builtin, transitive closure only; jqj natives win
   by name and arity. 2a's parser accepts the whole file (update operators lowered to a Stub until
   spec 3; `$$$$v`).
5. **jq-test target = named case list per spec gate** from a case census script over jq-ref's
   disassembly.
6. **core-v1** as drafted (typed, depth 4, spec 2's constructs plus `length type not empty error
   tostring select map range/2 first limit` and one bounded recursive `def`; seeds [0, 2100);
   recorded whole at 2a's start; excludes breaks across `try`/`?`/`?//`, sort cycles, deep
   recursion incl. `..` and `range/3`, syntax errors, `$__loc__`, `input`; ≥ 1000 cases by 2b's
   gate) plus a jq-ref distribution dry run the owner approves before the freeze.
7. **U calibration in 2b** without path-mode `..` (F-30).
8. **Compare and merge depth** as jq (ADR-0029).

Not owner questions (architect): retro order #49 → #50, #47 before class-C tickets, #51–#55 in
parallel; owner approves the setup-script changes of #50 and #54. Settled by ADRs: holds before
spec 5, `explain()` timing, compile-mode `conservative` lines, case per construct, 1 KB bench
cases. Minor finding F-31 (ADR-0006 natives wording).

## Round 2 (owner: yes to all five, 2026-11-12)

Draft by the architect, cut to five by the adviser.

1. **2a's extra natives, a closed list:** `has`, `keys`, `keys_unsorted`, `tonumber`, `tojson`,
   `nan`, `infinite`, `isnan`, `isinfinite`, `isnormal`, `have_decnum`, `have_literal_numbers`
   (about +40 jq-test cases). Formats, regex, `_strindices`, `contains` stay in spec 4.
2. **Sort and its kin in 2a:** natives `sort`, `unique`, `min`, `max`, `_sort_by_impl`,
   `_group_by_impl`, `_unique_by_impl`, `_min_by_impl`, `_max_by_impl`; the `_by` forms stay
   prelude. 2a's census about 470 cases, 2b 26.
3. **G3 in 2a** (F-33).
4. **Tick sites:** ADR-0008's plus `Comma`'s right branch and each further `AltBind` alternative
   (F-32).
5. **Nightly PIT dispatched mid-2a** at about half its class-C tickets (F-34).

Settled, not asked: the run state is a neutral `query-core` context owned by the `Workspace`; no
exported `jq-core` signature names it (ADR-0013, ADR-0016). Budget witnesses, bytes written at the
sink, `JqTermination(BUDGET, kind)` (GATE check 4, ADR-0016). `$__loc__` in 2a
(`{"file":"<top-level>","line":N}`); `compile(text, variables)` and `Bindings` per ADR-0016; `env`
and `$ENV` in spec 7. 12 of the 19 `%%FAIL` cases in 2a; the 7 module ones in spec 7 (ADR-0024).

For `/to-spec` (architect):
- The inline cap: one node budget per compile, spent in source order, value measured by the
  designer (any cap from about 10⁴ leaves jq-test untouched); over the cap a Stub in 2a. What 2b does
  over the cap (a PLAIN call would change ADR-0006's "every call to a non-recursive function becomes
  its body") is decided in 2b's spec by a finding.
- Pin by differential cases: `{(0):1}` rejected at compile time vs `{("a"|length):1}` at run time;
  `"u\vw"`'s JSON-parser error text from 2a's lexer; ADR-0029's boundaries.
- `keepsArgs` of the new natives is declared before spec 3's `keepsArgsIsSound`.
- Owed approvals: the core-v1 dry run, the setup-script changes of #50 and #54, the ticket breakdown.
- For spec 3 / 4 grilling: jq's `MAX_PATH_DEPTH` ("Path too deep") and `MAX_CONTAINS_DEPTH`
  ("Containment check too deep") are not covered by ADR-0029.

