# Math builtins: 25 exact ones with jq-ref's bits, 36 computed ones with jqj's own last digits

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 4 round 1); its ticks are in ADR-0034's spec-4 amendment; findings F-65 and F-69; the licence for 11 builtins is open (issue #105, owner)

jq-ref facts: probes P1–P8 observed by the architect and those marked A by the adviser, on jq 1.8.2
(spec 4 grilling round 1); Java facts on JDK 25.0.5.

jq 1.8.2 has 61 math builtins, each a call into the C library (`src/libm.h`). For 25 of them IEEE
754 or the C standard fixes every bit. For the other 36 nothing does, and jq-ref gives no single
target: its strings name glibc and GCC 13.3.0, and under
`GLIBC_TUNABLES=glibc.cpu.hwcaps=-FMA,-FMA4,-AVX2_Usable,-AVX2` it changed 11 of 9 000 `sin`, 8
`sinh`, 6 `atan2`, 5 `exp` and 3 `pow` results on this machine (A). jq.test rounds every
transcendental it checks (`cos * 1000000|floor / 1000000`, lines 847–857). `StrictMath` differs
from jq-ref on all 17 functions tried (F-65).

## Decision

- **Exact (25)**: `ceil`, `copysign`, `drem`, `fabs`, `fdim`, `floor`, `fma`, `fmax`, `fmin`,
  `fmod`, `frexp`, `ldexp`, `logb`, `modf`, `nearbyint`, `nextafter`, `nexttoward`, `remainder`,
  `rint`, `round`, `scalb`, `scalbln`, `significand`, `sqrt`, `trunc`. jqj gives jq-ref's bits and
  its x86-64 conversions: an exponent or order outside `int` or `long`, or NaN, becomes that type's
  minimum value on every platform, the order of `jn` and `yn` included. P2: `ldexp(1;1e10)` → `0`,
  `ldexp(1;2.7)` → `4`, `scalbln(1;1e19)` → `0`, `jn(1e10;1)` → `null`, `fmin(nan;1)` → `1`. A:
  `scalb(1;2.7)` → `null`, `-0.5|nearbyint` → `-0`.
- **Computed (36)** print jqj's own last digits, the same on every platform:
  - the 18 that `StrictMath` has, never `Math` (its javadoc fixes no bits across platforms):
    `acos`, `asin`, `atan`, `atan2`, `cbrt`, `cos`, `cosh`, `exp`, `expm1`, `hypot`, `log`,
    `log10`, `log1p`, `pow`, `sin`, `sinh`, `tan`, `tanh`;
  - 6 written from their definitions over `StrictMath`, exact where jq-ref is: `acosh`, `asinh`,
    `atanh`, `exp2`, `exp10`, `log2` (A: `8|log2` → `3`, `10|exp2` → `1024`, `3|exp10` → `1000`);
  - 12 that Java lacks (`javap StrictMath`): `erf`, `erfc`, `gamma`, `lgamma`, `lgamma_r`,
    `tgamma`, `j0`, `j1`, `jn`, `y0`, `y1`, `yn`. `erf` is ported from Arm's optimized-routines,
    licensed "MIT OR Apache-2.0 WITH LLVM-exception" (its LICENSE). That counts under MIT, a listed
    licence, so no exception is needed: Arm enters `NOTICE` as a second copyright holder, and Arm's
    MIT text ships in the `jq-core` jar. The other 11 follow the owner's answer below. Until then,
    with no new licence, each is written from published formulas (DLMF chapters 5, 7 and 10).
- **Checked by bounds, not digits**: one `DEVIATIONS.md` section, "libm last digits", covers the
  36, each function's row with its measured ulp bound. A JUnit test holds each function to its bound
  against jq-ref's results on a recorded sample. `builtins-math-v1` sees a computed result only
  through exact functions and never calls the 12 (ADR-0020's spec-4 amendment).
- **`jn` and `yn` tick** once per 1 024 steps of any loop that grows with the order, so the
  deadline and a cancel reach them (ADR-0008, F-69). No other math builtin ticks.
- **Facts**: every math native declares path NONE and is pure, in its own ticket (spec 3's Facts).
- **Order**: first the exact ticket with the conversions. Then the computed ticket: the 18 and the
  6, the section, the bound test, `builtins-math-v1`. Last of math, the 12 with the ticks of `jn`
  and `yn`; it is the only ticket the owner's answer touches.

## Open (owner, issue #105)

May code under the SunPro (fdlibm) notice be translated for the 11? Or CORE-MATH's, once its
licence is read? FreeBSD msun and musl carry the SunPro notice, which is not on the list (as David
Gay's, ADR-0012), so a yes is a licence exception and needs its own ADR. CORE-MATH's licence could
not be read (gitlab.inria.fr is blocked in this session), and nothing relies on it.

## Considered options

- **`Math`**: its javadoc fixes no bits across platforms, so the printed digits would vary with
  the JVM and the CPU.
- **jq-ref's digits for the 36** (glibc's code): glibc is LGPL, not on the list, and jq-ref's own
  digits change with the CPU's features.
- **`StrictMath` only where a probe shows identity with jq-ref** (the draft): that holds for none of
  the 17 tried (F-65).
- **A row per function and input**: digits that move with the machine cannot pin a row; a bound per
  function can.
- **The SunPro or CORE-MATH code for the 11 now**: that is the owner's licence question.
- **A tick for every math call**: a scalar operation ticks nothing (ADR-0034); only a loop that
  grows with the data needs one.
