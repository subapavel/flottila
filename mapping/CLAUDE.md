# Flottila Mapping — rules for every agent

Open-source mapping engine and editor: graphical message mappings (SAP PO style queues and
contexts) and, later, XSLT 3.0 / XPath 3.1, all compiled to one IR and run on Java 25. UI in
React + PatternFly. It will become the mapping engine and mapping UI of Flottila; until then it is
a separate repo with no dependency on Flottila. Everything in the repo is in English.

This file is loaded into every context, so it holds only pointers and the rules nobody may break.

## Read before work

- `docs/FOUNDING-ARCHITECTURE.md` — the owner's agreed direction; grilling confirms it, never re-asks it.
- `GLOSSARY.md` — the project's language. Use its terms in code, tests, tickets and hand-backs.
- `AGENTS.md` — roles, ticket classes (A/B/C/G → model and effort), concurrency, evidence words.
- `docs/WORKFLOW.md` — how a spec goes from idea to `main`; read the section you are in.
- `agent/HANDOFF.md` — the current state and the next exact action (architect only).
- `docs/adr/` — decisions that bind the area you touch. Contradicting one is a finding, not a fix.
- `docs/CODING_STANDARDS.md` — read by reviewers; writers read it when a hand-back is rejected.

Work items live in GitHub Issues (`docs/agents/issue-tracker.md`): wayfinder maps, specs and
tickets with native blocking edges. There is no backlog file.

## Non-negotiable

- Red before green. A ticket starts from a failing check: a test, an oracle case or a ratchet entry.
- Never weaken, skip or delete a test, an oracle case or a ratchet entry to go green.
- Never claim a build, test, measurement or push you did not observe. Evidence words only
  (`VERIFIED_PASS`, `EXECUTED_FAIL`, `NOT_RUN`, `ENVIRONMENT_BLOCKED`, see `AGENTS.md`).
- Never edit ratchet files (`ratchet/`) by hand in a ticket; only the verifier regenerates them
  on the integration branch.
- Writers work only inside their worktree and the paths their ticket names.
- Library APIs: Context7 first, then `javap` / the package's `.d.ts`. Never an API from memory.
- Dependencies: Apache 2.0, MIT, MIT-0, BSD, EDL 1.0, ISC, 0BSD, MPL 2.0, EPL only (ISC, 0BSD: ADR-0012;
  MIT-0, EDL 1.0: ADR-0014); a dual licence counts under its allowed alternative. The list covers every
  compile, runtime and test dependency and all of `ui/package.json`. Exceptions are per artifact, listed in
  the root POM and ADR-0017: Saxon-HE at test scope (oracle), JMH in `bench/jmh` only, and the Quarkus
  test framework's own dependencies, and three dev-only npm build tools (#39). Maven plugins are tools, not dependencies (any OSI licence).
- No secret, customer payload or real partner data in the repo; corpora are synthetic.
- When practice contradicts an ADR or the architecture: write the finding (`docs/WORKFLOW.md` §7).

## Context hygiene

- Build and test output goes to `.logs/<ticket>-<check>.log` (backend) or
  `ui/.logs/<ticket>-<check>.log` (frontend); read the tail or the failing section only.
- Large reads go to the scout. A hand-back is at most 40 lines.
- After a compaction: re-read `agent/HANDOFF.md`, run `git status` and `git log -3`, and treat
  everything older than the last hand-back as unknown.

## Agent skills

### Issue tracker

GitHub Issues with sub-issues and native blocking. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles plus `class:*`, `oracle:*`, `spec`, `wayfinder:*`. See `docs/agents/triage-labels.md`.

### Domain docs

Single context: `GLOSSARY.md` and `docs/adr/` at the root. See `docs/agents/domain.md`.
