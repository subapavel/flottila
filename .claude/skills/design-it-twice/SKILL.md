---
name: design-it-twice
description: Design a contract several times before building it — several agents independently draft radically different interfaces, one draft comes from Codex, the owner-proxy picks. Use only for contracts — an adapter, a step, the API for custom code, plugin loading, other public API.
---

<!-- Adapted from mattpocock/skills `codebase-design/DESIGN-IT-TWICE.md`, commit
     5863806dd4e15c63174bee4e7515a4256792ecfa (2026-11-12), MIT licence
     (../LICENSE-mattpocock-skills). After John Ousterhout's "Design It Twice": the first idea is
     rarely the best. -->

## 1. Frame the problem

Write a brief of at most 40 lines that every designer gets unchanged:

- what the contract is for, and who calls it;
- the constraints any design must meet: the `AGENTS.md` technical rules it touches, and the
  sections of `docs/ARCHITECTURE.md` that govern it, quoted;
- what sits behind the contract and what it depends on;
- a rough code sketch that makes the constraints concrete. It illustrates; it proposes nothing.

## 2. Draft independently

Start at least three agents in parallel, each in a fresh context with the brief and one
constraint of its own, so the drafts differ radically:

- "Minimise the interface: one to three entry points, as much behind each as possible."
- "Maximise flexibility: many use cases, room to extend."
- "Make the most common caller's case trivial."

The fourth draft comes from Codex, given the same brief with no extra constraint: run the Codex
CLI non-interactively (`codex exec`; its `--help` gives the flags) in a read-only sandbox. When
Codex cannot be run, say so in the comparison and go on with the other drafts.

Each draft returns:

1. the interface: types, methods, parameters, invariants, ordering, error modes;
2. a usage example from the caller's side;
3. what the implementation hides behind it;
4. its dependencies and how a mock implements it;
5. trade-offs: where it is strong, where it is thin.

## 3. Compare and choose

Put the drafts side by side and compare them by depth (how much a caller gets per entry point),
locality (where a future change lands) and how a mock and the contract suite would test them.
Recommend one, or a hybrid with the parts named. Then give the brief, the drafts and the
recommendation to the `owner-proxy` agent; it chooses. Record the chosen contract and the reason
in the pull request that introduces it, and write the contract before its implementation.
