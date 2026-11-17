# Numbers: literals keep jq's decimal meaning, arithmetic is double

Status: accepted (2026-11-10, owner, issue #9 "How do numbers behave and print?"); gives the meaning of ADR-0002's number flags; supersedes the number sentences of founding §5 and §10; closes F-1; NONFINITE never written, F-73 (spec 5)

A number is in one of three states (ADR-0003):
- a `NumberView` into the tape;
- a `LiteralNumber`, which holds jq's canonical text, its double and its flags, all computed
  eagerly;
- a `DoubleNumber`.

A filter constant, `tonumber`, `fromjson` or `detach` makes a literal, and so does `-x` or `length`
of a literal (jq's `jv_number_negate` and `jv_number_abs`). Arithmetic and the math builtins make a
`DoubleNumber`. Everything that is not arithmetic keeps a literal a literal, including `null + x`
and `x + null`. Negating a literal zero gives a positive zero with the same exponent, as
`decNumberMinus` does: filter `-0` → `0`, `-0.0 | -.` → `0.0`, `-0E-7 | -.` → `0E-7`, while the
computed `-(0*1)` → `-0`. A sign flip on the text would be wrong. `abs` is jq's prelude definition, so it keeps `-0` (`0*-1 | abs` → `-0`).

The rules below were observed on jq-ref 1.8.2 by the grilling adviser: commands C1–C8 of issue #9
round 1, and differential runs on 200 000 random tokens and 477 500 doubles.

## Flags (ADR-0002)

- **FITS_LONG**: the token is an integer (`-?(0|[1-9][0-9]*)`), is not `-0`, and fits a `long`. It
  covers the whole `long` range. Such a token compares exactly as a long, can be used as an index,
  and decodes without BigDecimal.
- **CANONICAL**: the token's bytes equal what jq prints for it, so the writer copies them. The
  reader sets the flag on every strict token except these:
  - a token with an exponent;
  - a lenient token (`+1`, `.5`, `1.`, `007`);
  - `[-]0.` followed by 6 or more leading zeros in the fraction, or 7 or more when the fraction is
    all zeros.
- **NONFINITE**: the literal is NaN or ±Infinity. That covers every spelling jq accepts (`NaN`,
  `nan`, `-NaN`, `sNaN`, `Infinity`, `inf`, …) and overflow past decNumber's exponent limit
  (`10E999999999`). Which spellings the reader accepts belongs to "How do the JSON reader and
  writer behave?".
- **The spare bit stays spare.** A short literal (CANONICAL, span ≤ 15) can be derived: its
  double order equals its decimal order, and its one-division parse is exact.

## Values and comparison

- **A literal's double** follows jq's `jvp_literal_number_to_double`:
  - up to 17 significant digits, the correctly rounded parse;
  - more than 17, a HALF_EVEN round to 17 digits first, then the parse.

  Correct rounding would differ from jq on 9 871 of 200 000 generated literals of 18 or more digits. A `LiteralNumber`
  computes the double once; a view computes it on each read.
- **Exponent limits** follow decNumber:
  - an adjusted exponent above 999 999 999 gives an Infinity literal;
  - below −1 147 483 646 the value is rounded HALF_UP to that quantum (`5E-1147483647` →
    `1E-1147483646`).
- **Comparison is jq's pairwise rule.** Two literals compare as exact decimals: by long when both
  fit, by double when both are short, otherwise with `BigDecimal.compareTo`. Any other pair
  compares as doubles, with primitive `<` and `==`. NaN sorts below every number, another NaN included, and is never equal; against other types it
  is a number (`nan < -infinite` and `nan > null` are true).
- **Ties between equal values with different text**: `sort` is stable, `min` keeps the first and
  `max` the last. `min`, `max` and their `_by` forms are jq's left-to-right scan (`minmax_by`), so
  they match jq even inside a cycle (351 arrays of length 3–5 over the three cycle values, adviser).
- **jq's rule is not transitive.** Two literals with the same double, plus a computed number equal
  to both, form a cycle. jqj sorts with its own stable merge sort; `Arrays.sort` threw on 1486 of
  2000 such arrays. In a cycle, the result of `sort`, `sort_by`, `unique`, `unique_by` and
  `group_by` is jqj's own (`DEVIATIONS.md`).

## Printing

- **A literal number prints as jq prints it.** A CANONICAL token is copied byte for byte.
  Any other literal is rewritten to jq's canonical form: `1e2` → `1E+2`, `0.0000001` → `1E-7`,
  `0.0000000` → `0E-7`, `1e-2` → `0.01`, while `1.10` and `-0` stay. This is `BigDecimal.toString`
  except for the sign of zero, the non-finite values and the exponent limits.
- **NaN prints `null`. ±Infinity prints `±1.7976931348623157e+308`.** An Infinity literal still
  compares above every finite literal.
- **A `DoubleNumber` prints in jq's `jvp_dtoa_fmt` layout:**
  - an integral value with |d| ≤ 2^53 prints as long digits, and −0.0 as `-0`;
  - otherwise the shortest digits from `Double.toString` are laid out again: exponential form iff
    decpt ≤ −4 or decpt > ndigits+15, lowercase `e`, the exponent sign always shown, at least two
    exponent digits (`1e-05`, `1e+17`, `0.30000000000000004`);
  - a one-digit fix applies where Java picks a two-digit form: `4.9E-324` → `5e-324`,
    `9.9E-324` → `1e-323`;
  - this was byte-identical to jq-ref on 477 500 doubles.
- **One printer** serves `tostring`, `tojson`, `@text`, `@json`, `@csv`/`@tsv` (where NaN is an
  empty field), `@sh` and the values in error messages.
- **`tonumber`** returns a number unchanged. A string goes through decNumber's grammar into a
  literal: `+5` and `.5` are accepted; `" 4"`, `"4 "`, `"0x10"` and `""` are rejected.

## Arithmetic and folding

- **Arithmetic is always double.** A long fast path is allowed only for `+` and `−`, when both
  operands are integral, not −0 and at most 2^53 in magnitude, and |result| ≤ 2^53 (jq-ref:
  `9007199254740993 - 1` → `9007199254740991`, `100000000000000041 - 100000000000000000` → `32`).
  `0 * -1` is `-0`.
- **Folding keeps the state** (ADR-0007). jq's own `constant_fold` calls the same binops. A
  `DoubleNumber` never becomes literal text, neither in the IR nor in class data, so a folded number
  prints exactly as the unfolded one would.
- **This ADR binds behaviour and the flag bits,** not the public fields of `LiteralNumber`. What a
  host can read from a number belongs to "What are the public API, the host SPI and the sandbox?".

## Considered options

- **Always print the original bytes** (founding §5 and §10 as written): every exponent-form token
  would differ from jq, which means a whole class of deviations.
- **Correct rounding beyond 17 digits:** a deviation for no gain.
- **Exact-value comparison everywhere:** a total order, but
  `(100000000000000000001+0) == 100000000000000000001` would turn from true to false.
- **Reproducing jq-ref's libc qsort in a cycle:** brittle, and tied to one binary.
- **Porting `jv_dtoa.c`:** David Gay's notice is not on the licence list. A Ryu port (Apache 2.0) is
  the fallback if printing allocation shows in a benchmark.
- **A BigDecimal per literal:** an allocation per value, and it has no −0 or Infinity.
- **FITS_LONG only up to 2^53:** 17–19-digit IDs would fall to BigDecimal.

## Amendment (F-73, spec 5)

- **NONFINITE is never written**; the bit is reserved beside the spare one. A NaN or Infinity
  literal is found when its token is decoded.
  - Every NaN and Infinity spelling is a lenient token. An overflow needs an exponent, since a
    token holds at most 2^24−1 bytes (ADR-0002). So none of them is CANONICAL, and each is decoded
    at access anyway.
  - Writing the flag would make the fast path weigh every exponent: `1e1000000000` is a strict
    token, and jq-ref prints `1.7976931348623157e+308`.
  - This replaces the NONFINITE flag above; the values it names keep their meaning.
- A lenient token is never CANONICAL or FITS_LONG (ADR-0015's spec-5 amendment): `+1` → `1`,
  `2.e3` → `2E+3`, `nan` → `null`.
