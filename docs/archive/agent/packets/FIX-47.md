# FIX-47 — reserved prop names refused at deploy (lane 2)

Row: agent/BACKLOG.md FIX-47 [B], before hand-over (D-142, D-140). Worktree: /home/user/flottila-lane2 (branch lane2); every agent works
and runs Maven only there, with -Dmaven.repo.local=/tmp/claude-0/-home-user-flottila/04e96bc4-5ebf-54d6-9d23-886dc7daf9fa/scratchpad/m2-lane2
(seed it once with cp -a ~/.m2/repository). Logs go to /home/user/flottila/agent/work/FIX-47-*.

Acceptance (red first):
A1 Platform prop names are one set in one place (the names the runtime itself writes on an Envelope: `attempt`, `replayOf`, the split
   props `split*` — the implementer confirms the full list by grep of where the runtime sets props, and the test names each).
A2 Deploying a package whose flow config names a reserved prop where a step would read or write it — a `route` with `prop:` / `byProp:`, a
   step that sets or removes a prop (every step kind in the schema that takes a prop name), a `split` naming a prop — is refused with 422
   and a message naming the prop, the flow and the step; nothing is deployed. Reading a reserved prop for routing is refused too (it would
   route on a platform value whose type the flow does not own).
A3 `bin/flottila` validate/test refuses the same package with the same message (the CLI uses the same check).
A4 A flow that names a prop that merely starts like a reserved one but is not in the set (e.g. `attempts`, `splitter` if `split*` is a
   prefix rule — decide and pin whichever the set says) deploys.
A5 Today's ClassCastException path is gone: the route case from the row deploys nowhere, so no message reaches Envelope.nextKey with it.
Docs: ARCHITECTURE section naming props / flow config (scout or grep) gains one sentence; DECISIONS D-140 bracket "reserved at deploy (FIX-47)".

## Red approved (architect, 2026-11-14)
DeploymentsReservedPropTest (18: 13 red, 5 lookalikes green), ReservedPropCommandTest (3: 2 red); log agent/work/FIX-47-red.log.
Set (exact names, case-sensitive): attempt (Envelope:46), replayOf (:39), splitParentId, splitIndex, splitTotal (Split:29-33). Steps that
name a prop: route `prop`, to `byProp`. Check in Deployments.validate (~:1139); CLI via Validate.check. Restore of a recorded package
naming a reserved prop: follow what restore already does for a package refused by validate (find it); the node must not fail to start
for it — pin with one case if restore runs validate.
