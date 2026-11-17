# Code under the SunPro (fdlibm) notice may be translated for 11 math builtins

Status: accepted (2026-11-15, owner); amends ADR-0051 (closes "the licence for 11 builtins is
open") and CLAUDE.md's licence list; answers issue #105

ADR-0051 leaves 11 math builtins without a Java counterpart: `erfc`, `gamma`, `lgamma`, `lgamma_r`,
`tgamma`, `j0`, `j1`, `jn`, `y0`, `y1` and `yn`. The mature implementations (FreeBSD msun, musl's
libm) carry the SunPro (fdlibm) notice, which is not on CLAUDE.md's list. ADR-0012 refused David
Gay's notice for the same reason. The fallback was formulas from DLMF chapters 5, 7 and 10, written
by jqj with each function's measured ulp bound in DEVIATIONS "libm last digits".

## Decision

- **Allowed.** Source under the SunPro notice may be translated into Java for these 11 builtins
  only. The exception covers translated source, not a dependency.
- **Notice.** Each translated file keeps the notice of the file it was translated from, copied
  from that file, never written from memory. The file is listed in `NOTICE` with its source path
  and revision, as CLAUDE.md asks of every copied or translated file.
- **Jar.** The `jq-core` jar carries each notice that a translated file keeps (GATE check 8 as
  amended on 2026-11-15).
- **Source.** The ticket that translates names one upstream (FreeBSD msun or musl) and its
  revision, read through the network or Context7, not from memory.
- **Bounds.** Each function still gets its measured ulp bound and its row in DEVIATIONS "libm last
  digits" (ADR-0051).
- **Not covered.** CORE-MATH's licence is still unread (its host is blocked here). Using it needs
  its own decision.

## Considered options

- **DLMF formulas only** (the fallback). It keeps the list unchanged. But jqj would write 11
  special functions itself, with more work and more risk near poles and zeros, and its last digits
  would sit further from jq's.
- **A general allowance of the SunPro notice.** That is wider than the need; other uses would come
  back to the owner.
