# Regex on joni 2.2.9: `PerlNG` plus the Perl_NT operators joni reads as jq does, with `CAPTURE_GROUP`, jq's match loop translated, the time limit per search call, a pattern cache per Workspace

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 4 round 1); amends ADR-0017 (the regex limit per search call) and ADR-0016 (the Workspace's pattern cache); its ticks are in ADR-0034's spec-4 amendment; Dialect amended 2026-11-18 (owner-proxy for the owner, issue #216: PerlNG plus six Perl_NT operators)

jq-ref facts: probes P1–P8 observed by the architect and those marked A by the adviser, on jq 1.8.2
(spec 4 grilling round 1); joni facts read at the joni-2.2.9 tag.

jq 1.8.2's regex builtins are prelude definitions over `_match_impl`, the C function `f_match`
over Oniguruma. ADR-0017 put jqj's regexes on joni 2.2.9, with a time limit and
`searchInterruptible`. To build them, spec 4 needs four things fixed: the dialect, the loop, where
the limit applies, and when a pattern compiles.

## Decision

- **Dialect**: joni 2.2.9 unpatched, with the `CAPTURE_GROUP` option and a `Syntax` made of
  `Syntax.PerlNG` plus the six op2 operators of Oniguruma's Perl_NT, jq's syntax, that joni reads as
  jq 1.8.2 does: `PLUS_POSSESSIVE_REPEAT`, `PLUS_POSSESSIVE_INTERVAL`, `QMARK_LPAREN_CONDITION`,
  `ESC_CAPITAL_R_LINEBREAK`, `ESC_CAPITAL_X_EXTENDED_GRAPHEME_CLUSTER`, `ESC_CAPITAL_K_KEEP`. The
  absent group (`QMARK_TILDE_ABSENT`) stays off: joni follows Onigmo's older rules and would match
  wrongly without an error, so the pattern fails to compile and is a `DEVIATIONS.md` row. An
  operator is switched on only when the matrix shows it identical to jq 1.8.2. `PerlNG` has
  named groups, `\k`, `\g` and `CAPTURE_ONLY_NAMED_GROUP`. The texts of P6's compile errors are
  joni's own constants ("end pattern with unmatched parenthesis", "unmatched close parenthesis",
  "upper is smaller than lower in repeat range").
- **The matrix**: the regex prelude ticket's red has one recorded case per construct of
  Oniguruma's syntax list and one per error text. A difference becomes a row of `DEVIATIONS.md`
  "regex dialect" or a text mapping. `builtins-regex-v1` draws only constructs the matrix shows
  identical.
- **The loop**: `f_match` is translated under jq's notice, because results depend on its loop. A:
  `"é"|[match("";"g")|.offset]` → `[0,1,1]`; `"éé"|gsub("";"-")` → `"-é--é--"` (one byte forward
  after an empty match). Codepoint offsets come from a running count, not from `f_match`'s rescan
  from the start for each match.
- **The time limit is per joni search call.** jq's `g` loop calls `onig_search` once per match
  (`f_match`), and joni's timeout starts in `searchCommon`. Each call gets the smaller of
  `regexMatchTime` (1 s) and the remaining deadline (ADR-0017). Cancellation stays as ADR-0017 has
  it.
- **Compiled patterns**: each Workspace keeps a bounded cache, keyed by the pattern's bytes and the
  compile options its modifiers give.
  - It starts at 16 entries, least recently used out first, and keeps patterns up to 4 KiB; a
    longer one compiles at each call.
  - The latest entry is tried first, by the identity of the pattern value; that identity is held
    for the current run only. An entry keeps a copy of the bytes, never a value, so it pins no
    document.
  - An entry keeps a compile error too, raised in `f_match`'s order: input type, pattern type,
    modifiers, compile. Nothing compiles at lowering. A:
    `[try ("abc"|test("(")) catch ., (empty|test("("))]` → one "Regex failure: end pattern with
    unmatched parenthesis".
  - Spec 4's diagnostics measure the cache's gain (ADR-0019); changing a bound is a finding.
- **Ticks** (ADR-0034's spec-4 amendment): one per search call, one per started KiB from the search
  start to the match end, one per started KiB of the pattern at each lookup, compiled or cached (so
  a run's steps never depend on the cache).
- **Facts**: `_match_impl` declares path NONE and is pure (spec 3's Facts).
- **Tickets**: the loop, the offsets, the limit, cancellation and the cache are one class-C ticket.
  The regex prelude, the matrix and `builtins-regex-v1` come after it and after spec 3a (`sub` uses
  `+=`).

## Considered options

- **Plain `Syntax.PerlNG`**: 8 more of the 220 recorded cases differ from jq 1.8.2, four of them
  silent wrong matches (possessive `a*+a`, `\K`, `\R`, `\X`).
- **A limit per `_match_impl` call**: it would fail long global scans that jq completes, on a
  command line with no deadline.
- **A one-entry memo per call site**: it misses `test($a, $b)` and needs a slot per site.
- **Compiling a constant pattern at lowering**: its error would come before the input-type check,
  and a filter that never calls it would fail (A above).
- **An unbounded cache, or one on the engine**: a filter could grow it without limit; the engine
  keeps no cache (ADR-0016).
- **Mapping every joni text to Oniguruma's in advance**: the matrix shows which differ, and P6's
  three already agree.
- **A patched joni**: a fork outside the mutation floors (ADR-0017).
