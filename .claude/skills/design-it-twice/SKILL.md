---
name: design-it-twice
description: Design a contract or another hard piece several times before building it — several agents independently draft radically different interfaces, a second model argues against the recommendation, the owner-proxy picks. Use for every class:C issue — a new contract (adapter, step, API for custom code, plugin loading), public API, storage format, security and the other hard work.
---

<!-- Adapted from mattpocock/skills `codebase-design/DESIGN-IT-TWICE.md`, commit
     5863806dd4e15c63174bee4e7515a4256792ecfa (2026-11-12), MIT licence
     (../LICENSE-mattpocock-skills). After John Ousterhout's "Design It Twice": the first idea is
     rarely the best. -->

Required for every `class:C` issue before its implementation.

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

The drafts come from your own agents.

Each draft returns:

1. the interface: types, methods, parameters, invariants, ordering, error modes;
2. a usage example from the caller's side;
3. what the implementation hides behind it;
4. its dependencies and how a mock implements it;
5. trade-offs: where it is strong, where it is thin.

## 3. Compare and choose

Put the drafts side by side and compare them by depth (how much a caller gets per entry point),
locality (where a future change lands) and how a mock and the contract suite would test them.
Recommend one, or a hybrid with the parts named.

A second model then argues against the recommendation. It names the weak points, the failure
scenarios and what the other drafts do better. Codex does this, and only this: it writes no draft
and reviews no pull request. Sessions cannot run Codex; the owner runs it by hand in his Codex
cloud environment over this repository, read-only.

1. Post one comment on the issue with the complete prompt for Codex in a single code block, ready
   to copy. The prompt stands on its own: it names the files to read, states the recommendation
   and the alternatives turned down, asks for the weak points and what would break first, and
   tells Codex to change no file and open no pull request. Its last line is `Write your review as
   a comment on issue #N in subapavel/rail8.`: a Codex cloud run posts that comment by itself, but
   only when the prompt says so. Label the issue `codex-wanted`. Never write a mention of Codex in
   a comment.
2. Tell the owner at once. The daily overview lists every issue that still waits with `codex-wanted`.
3. The owner pastes the prompt into Codex. Codex posts its answer as a comment on the issue; when
   it does not, the owner pastes the answer instead.
4. Read the answer. Take each objection, or turn it down with a reason in the issue. Remove the
   label and go on.
5. When no answer has come by the next morning's daily overview (08:00 Prague time), an agent on a
   different Claude model than yours argues against the recommendation instead. Say so in the
   issue, remove the label and go on. An answer that arrives later but before the merge is still
   handled as in step 4.

Give the brief, the drafts, the recommendation and the objections to the `owner-proxy` agent; it
chooses. Record the chosen contract and the reason
in the pull request that introduces it, and write the contract before its implementation.
