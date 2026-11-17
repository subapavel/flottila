# jqj

An implementation of the [jq](https://jqlang.org/) language on Java 25. A filter is compiled once
and run directly over JSON bytes, without building an object tree of the input. Built for
integration runtimes that run the same filter on many messages, where speed and bounded memory
matter; usable from the command line and embedded as a library.

Status: **Phase 0 — decisions before code.** See `docs/FOUNDING-ARCHITECTURE.md`,
`docs/wayfinder/phase-0-map.md` and `docs/GATE.md` (milestone 1, draft).

Built AI-first: agents write the code under the rules in `CLAUDE.md`, `AGENTS.md` and
`docs/WORKFLOW.md`; work is tracked in GitHub Issues.

jqj is an independent implementation and is not affiliated with the jq project.

Licence: Apache 2.0 (`LICENSE`). Third-party notices: `NOTICE`.
