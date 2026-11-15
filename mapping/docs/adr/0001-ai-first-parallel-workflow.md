# AI-first workflow: Flottila's rigour, Matt Pocock's skill chain, parallel tickets

Status: accepted (2026-10-30)

Flottila ran one writer at a time with a review after every task; over its first 81 tasks 52 had
at least one review FAIL and the backlog grew 85 fix rows, while its single-file ledger made
parallel branches impossible. This project keeps Flottila's non-negotiables (an architect that
never codes, red before green, evidence words, findings, model and effort chosen by ticket class,
a stronger independent gate reviewer) and replaces the loop with Matt Pocock's skills: wayfinder
maps for big unknowns, grilling with a glossary and ADRs, specs and tracer-bullet tickets with
native blocking edges in GitHub Issues, up to three implementers in git worktrees merged one by
one into a spec's integration branch, and one two-axis review per spec instead of an LLM review
per ticket (class-C tickets keep a per-ticket review). Oracles (differential runs against
Saxon-HE on exported XSLT, a golden corpus, later the W3C suites) and a ratchet replace most
hand-written acceptance tests, which is what makes the cheaper review safe.

## Considered options

- **Flottila's loop unchanged** — proven, but sequential and review-bound.
- **Matt's `/implement-spec` unchanged** — parallel, but no evidence discipline, no class-based
  model choice, no per-ticket automatic gate, review only at the end even for hard paths.
- **Agent teams / several cloud sessions pulling tickets** — more parallelism than a 4 vCPU VM
  can build, and no single place where merges are serialised.

## Consequences

- Upstream skills stay unchanged in `.claude/skills/`; the project's own orchestration is
  `run-spec` and `gate`. Updating upstream is a copy plus a diff review.
- Ratchet files are written only by the verifier on the integration branch.
- Central registries are banned (`docs/CODING_STANDARDS.md`) so parallel tickets do not collide.
- Re-measure after the first two specs: review FAILs per ticket, fix tickets, wall time per
  ticket, tokens per ticket. If per-spec review lets defects through, move `class:B` to
  per-ticket review.
