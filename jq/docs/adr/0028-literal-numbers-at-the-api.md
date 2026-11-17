# Literal numbers at the API: values after jq's exponent limits

Status: accepted (2026-11-12, architect, from the designer's draft for #36 "S1.14 Numbers");
confirmed by the owner at PR #48 (F-28); amends ADR-0016 (`JqNumber`).

- `decimalValue()` of a literal is its canonical form's value: jq's value after the exponent limits
  (`5e-1147483647` → `1E-1147483646`), with its scale kept (`1.10` has scale 2). `-0` gives a zero of
  the same scale, because `BigDecimal` has no −0. An Infinity literal throws `ArithmeticException`.
  This replaces ADR-0016's "exact for a literal" only past the limits; within them it is exact.
- `doubleValue()` of an Infinity literal is ±Infinity, though it prints ±1.7976931348623157e+308
  (jq's `jv_dump_term` clamps to DBL_MAX only when printing).
- `doubleValue()` of any other literal follows ADR-0012: the 17-digit HALF_EVEN reduction, then a
  correctly rounded parse.

## Considered options

- The token's exact value: it differs from what jq prints and compares, and `1e-10000000000` has no
  `int` scale.
- ±`Double.MAX_VALUE` as the double of an Infinity literal: it matches the text, but jq's own double
  is infinite.
