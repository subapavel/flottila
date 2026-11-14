# designer memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from r8-mapping (2026-11-10; observed there, re-verify on first use here)

(nothing carried over: the designer's lessons there were about that project's libraries)
- (#62) The designer context has no Bash: list jq-ref probes in the draft for the architect to run. raw.githubusercontent.com works for jq sources; api.github.com gives 403; issue pages via WebFetch come back summarised.
- (#62) jq-1.8.2 parser.y: `as` takes an Expr source at Query level; `try`/`catch` take postfix Terms; `'-' Term`; comparison and assignment levels are nonassoc.
