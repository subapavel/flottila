# Rail8 Mapping — the engine's technical rules

The process is the repository's: who does what, tasks, commits, findings and the hand-off are in the
root `CLAUDE.md`, `AGENTS.md` and `docs/WORKFLOW.md` (D-151). A new finding about the mapping is
numbered in the root's series from F-54; `F-1`..`F-53` are `docs/findings/` here. The rules below
are the engine's own and hold inside `mapping/`. The file as it was: `docs/archive/CLAUDE-until-2026-11-15.md`.

Open-source mapping engine and editor: graphical message mappings (SAP PO style queues and
contexts) and, later, XSLT 3.0 / XPath 3.1, all compiled to one IR and run on Java 25. UI in
React + PatternFly. This file holds only pointers and the rules nobody may break.

## Read before work

- `docs/FOUNDING-ARCHITECTURE.md` — the owner's agreed direction.
- `GLOSSARY.md` — the project's language. Use its terms in code, tests, tasks and hand-backs.
- `docs/adr/` — decisions that bind the area you touch. Contradicting one is a finding, not a fix.
- `docs/CODING_STANDARDS.md` — read by reviewers; writers read it when a hand-back is rejected.
- Domain docs: single context, `GLOSSARY.md` and `docs/adr/` in `mapping/`. See `docs/agents/domain.md`.
- Checks, ratchet and CI: `mapping/docs/WORKFLOW.md` §6.

## Non-negotiable

- Red before green. A task starts from a failing check: a test, an oracle case or a ratchet entry.
- Never weaken, skip or delete a test, an oracle case or a ratchet entry to go green.
- Never edit ratchet files (`ratchet/`) by hand in a task; only the verifier regenerates them.
- Library APIs: Context7 first, then `javap` / the package's `.d.ts`. Never an API from memory.
- Dependencies: Apache 2.0, MIT, MIT-0, BSD, EDL 1.0, ISC, 0BSD, MPL 2.0, EPL only (ISC, 0BSD: ADR-0012;
  MIT-0, EDL 1.0: ADR-0014); a dual licence counts under its allowed alternative. The list covers every
  compile, runtime and test dependency and all of `ui/package.json`. Exceptions are per artifact, listed in
  the mapping's POM and ADR-0017: Saxon-HE at test scope (oracle), JMH in `bench/jmh` only, and the Quarkus
  test framework's own dependencies, and three dev-only npm build tools (#39). Maven plugins are tools, not
  dependencies (any OSI licence). The list holds inside `mapping/`; the root list is unchanged (D-153).
  The list and the exceptions are frozen as imported: an addition is an issue for the owner, and until the
  embedding is designed work here adds no dependency, licence exception, listener or endpoint (D-153, ISSUE-9).
- No secret, customer payload or real partner data in the repo; corpora are synthetic.
- When practice contradicts an ADR or the architecture: write the finding (the root `docs/WORKFLOW.md` §7,
  from F-54).
