# Flottila — rules for Claude

Design spec: `docs/CONTEXT.md`. Read it before changing core or runtime.

## Design rules (non-negotiable)

- `Envelope` is an immutable record; every step returns a new one. Body and parsed views are
  shared by reference across `with*` calls. Headers / props / keys / attachments stay separate.
- `Body` is a sealed interface — bytes, never a consumable stream; `open()` always returns a
  fresh stream.
- `Stage` contract is exactly `Multi<Envelope> apply(Multi<Envelope> in)`. Pipeline = `plug()`
  composition. Patterns are factory methods in `Stages`, not new abstractions.
- Stages hold only what was injected at build time. `Runtime` is used only inside `FlowBuilder`;
  no stage lambda may capture `rt`. Before each commit run:
  `grep -n "rt\." src/main/java/io/flottila/runtime/FlowBuilder.java` and check every hit is in
  build-time code, not inside a returned lambda.
- State whose loss loses messages goes to the DB; state whose loss only changes tempo may be
  in-memory.

## Working rules

- For library API questions prefer `javap -cp <jar>` against the resolved jars in `~/.m2` over
  docs or memory. Context7 is unreliable here.
- One commit per plan step. Stop and report after each step before continuing.
- No new Maven modules without an explicit decision.
