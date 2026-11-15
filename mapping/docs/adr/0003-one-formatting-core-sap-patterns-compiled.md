# One F&O formatting core; SAP date and number patterns compiled statically

Status: accepted (2026-10-30, grilling #3)

The XSLT/XPath front end needs `format-dateTime` and `format-number` with XPath pictures anyway,
so the engine has exactly one formatting core, the F&O one, plus one internal parsing function
that mirrors `format-dateTime`'s picture syntax (F&O 3.1 has no general date parsing). The
graphical date functions and `FormatNum` keep the Java/SAP pattern (`SimpleDateFormat`,
`DecimalFormat`) as their parameter, because PO/CPI users and migrated mappings use it; since that
parameter is always a constant of the function box, the compiler translates it statically to an
XPath picture. Parsing is strict. A pattern that does not translate is a design-time error in
milestone 1. These functions are therefore portable and the differential oracle checks them,
which is where date and number errors are expected.

## Considered options

- **Java patterns at run time** (a second formatting engine) — rejected: two engines with two
  sets of bugs in one runtime, and the oracle would compare our code with itself.
- **XPath pictures in the mapping model** — rejected: foreign to PO/CPI users; migrated patterns
  would need rewriting.
- **Flottila Java functions in the export** — deferred to the later `flottila` export profile
  (ADR-0002), which may support patterns that do not translate.
