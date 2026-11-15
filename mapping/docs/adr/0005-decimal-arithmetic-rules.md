# Decimal arithmetic: Saxon-HE's division scale, double only inside sqrt and fractional power

Status: accepted (2026-10-30, grilling #4); amended by F-20 (2026-11-04, #72)

ADR-0002 makes every number an `xs:decimal`. The engine holds a decimal as a `BigDecimal`. It
reads only the `xs:decimal` lexical form, so `1e3` raises `FORG0001`. It writes the XPath
canonical form: `4.2 − 0.2` → `4`, `1234567 + 0` → `1234567`, `1.50 × 1` → `1.5`.

F&O leaves the precision of a non-terminating division implementation-defined. Our exported
XSLT is checked against Saxon-HE, so we adopt the rule observed on Saxon-HE 12.5:

- Every quotient, including one that terminates (F-20), has the scale `max(18, scale(dividend) − scale(divisor) + 18)` fractional digits,
  with trailing zeros of the operands not counted, and the last digit is rounded half-down. For
  example, `1 div 3` → `0.333333333333333333` and `2.0 div 3` → `0.666666666666666667`.
- Division by zero raises `FOAR0001`.

The rule is written down from Saxon-HE's observed outputs, never from its source (FOUNDING §8).
Oracle cases pin it. One consequence is that `1 div 30000000000000000000001` → `0`. Another is that `1 div 2^30` → `0.000000000931322575`, not its exact value (F-20).

`power` with an integer exponent is computed exactly in decimal: the portable export uses a
helper that multiplies repeatedly. `sqrt`, and `power` with a non-integer exponent, compute in
`xs:double` (`math:sqrt`, `math:pow`) and cast the result back to decimal. Both functions stay
portable and carry a catalogue deviation.

## Considered options

- **Our own fixed precision (e.g. 34 significant digits)**: rejected. Every division in the
  export would need a helper, and the oracle would compare the helper with itself.
- **Precision as a property of the mapping**: rejected. Nobody asked for it, and it adds a knob
  to every oracle case.
- **Exact `BigDecimal.sqrt` with sqrt/power non-portable**: rejected. Milestone 1 has only the
  portable export profile, so these functions could not be exported at all.
- **double for every `power`**: rejected. Integer powers are the common case and can be exact.

## Consequences

- The division rule ties our results to Saxon-HE 12.5. A Saxon upgrade that changes the rule
  shows up as a failing oracle case, and this ADR is revisited.
- Catalogue deviations against PO (float or BigDecimal mode, SAP Note 958486): division scale,
  `sqrt`, fractional `power`. Observed PO cases (`1/3`) are recorded in #14.
