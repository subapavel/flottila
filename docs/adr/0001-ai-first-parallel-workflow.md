# AI-first workflow taken from r8-mapping

Status: accepted (2026-11-10)

jqj is built the way `subapavel/r8-mapping` is built, because the owner asked for the same
style of work and that workflow has carried several specs to `main` there: an architect that never
codes, red before green, evidence words, findings, model and effort chosen by ticket class, a
stronger independent gate reviewer, and Matt Pocock's skill chain — wayfinder maps for big
unknowns, grilling with a glossary and ADRs, specs and tracer-bullet tickets with native blocking
edges in GitHub Issues, up to three implementers in git worktrees merged one by one into a spec's
integration branch, and one two-axis review per spec (class-C tickets keep a per-ticket review).
Oracles and a ratchet replace most hand-written acceptance tests, which is what makes the cheaper
review safe; jqj is unusually well served here, because the original jq ships its own test files
and its binary answers any case we can generate.

Taken unchanged: `AGENTS.md` roles, models and efforts, concurrency and command rules; the agent
definitions; every skill (`.claude/skills/UPSTREAM.md`); the hooks and settings; the generic
scripts. Adapted: the ticket-class examples, the `oracle:*` labels (`jqtest`, `differential`,
`backend`, `json`), the checks of `docs/WORKFLOW.md` §6 (no UI, no service), the coding standards.
The lessons r8-mapping's agents recorded that are not about its domain are seeded into
`.claude/agent-memory/` and `agent/NOTES.md`, marked as carried over.

## Considered options

- **Rail8's first loop** (one writer, a review after every task) — sequential and review-bound;
  r8-mapping's ADR-0001 records why it was replaced.
- **A lighter setup for a smaller project** — jqj has no UI and no service, so fewer checks; but
  the hard paths (tape, IR, code generator, ownership) are exactly what class C exists for.

## Consequences

- Upstream skills stay unchanged in `.claude/skills/`; the project's own orchestration is
  `run-spec` and `gate`. Updating upstream is a copy plus a diff review.
- Ratchet files are written only by the verifier on the integration branch.
- Central registries are banned (`docs/CODING_STANDARDS.md`) so parallel tickets do not collide.
- An improvement to the workflow found here is worth offering back to r8-mapping, and the
  reverse; the two repos do not share files, so each change is copied by hand.
