# SAP PO/CPI-compatible mapping semantics on a modern core

Status: accepted (2026-10-30, grilling #3)

Message mappings from SAP PO and SAP Cloud Integration must migrate without changing their
results, but the engine must not copy PO. So the **observable queue semantics** match PO/CPI
exactly: contexts and context changes, SUPPRESS, last-value reuse, target-field creation,
`minOccurs`/`maxOccurs`. Everything inside follows `docs/FOUNDING-ARCHITECTURE.md` (§2–§7).
Results that are only incidental Java behaviour may differ, as a **documented deviation** in the
function catalogue: numbers are computed as decimals (PO's default is Java `float`,
`4.2 − 0.2 = 3.9999998`), and strings order by code point with `upper-case` then `lower-case` for
case-insensitive order (PO orders by UTF-16 units). Where the SAP documentation leaves behaviour
open (#2 §7), an **assumed reading** applies until an **observed case**, the same synthetic
mapping run on SAP PO 7.5 and on SAP Cloud Integration by the owner, confirms or corrects it.

## Considered options

- **Bit-exact PO everywhere** (float printing, UTF-16 order, lenient dates) — rejected: emulates
  Java accidents, makes the XSLT export unreadable, and PO itself has two numeric modes.
- **Own semantics inspired by PO** — rejected: migrated mappings would silently change output.
- **Float compatibility mode per mapping** — deferred: reopened with the importer only if observed
  cases show real mappings depend on float results.

## Consequences

- Every function carries `portable: yes|no`. The **portable** export profile (pure XSLT 3.0 with
  the helper module, the only profile in milestone 1) covers every portable function; the
  differential oracle covers exactly those. A later **flottila** profile may call Flottila Java
  extension functions: lock-in to Flottila is accepted there. This refines FOUNDING §6 ("no
  vendor lock-in" holds for the portable profile). UDFs are never portable; the golden corpus
  covers them.
- Migration is split by format, not by repo: iFlow-forge extracts artefacts (TPZ, SimpleQuery)
  and proposes fixes; a deterministic 1:1 converter in this repo (later milestone) reads SAP's
  own `<mappingtool>` / `.mmap` format and writes a mapping model with *unresolved function*
  placeholders plus an import report. This replaces FOUNDING §6's "near 1:1 import" with a
  narrower contract. No code dependency on iFlow-forge.
- Mappings taken from real systems are extracted only by the owner on a local machine and serve
  as templates outside the repo; the repo holds synthetic rewrites only. The same holds for
  observed-case outputs.
- Milestone 1's gate may not leave an assumed reading on any function the milestone ships.
