# ISSUE-8 — the operations brief's [inferred] items and the open questions of its rewrite
Task: OPS-1
Date: 2026-11-15

## What is needed
The owner's word on each line below: yes, no, or the rule that holds instead. Every line names the
row that waits for it and the default the documents state until the answer. Sections are those of
`docs/OPERATIONS-BRIEF.md`; the same lines stand in `docs/ARCHITECTURE.md` §14.

A. Marked [inferred], "to verify" or "not thought through" in the brief. The default of every line
in A: it is not stated as design anywhere, and its row builds nothing on it before the answer.
1. §2.2 A successful call has its message and is not repeated in the connection log — OPS-5.
2. §2.4 A few seconds between a step's start and its display is acceptable — OPS-2.
3. §2.4 Every send attempt is its own record, and a message waiting behind another in an ordered
   queue says which one it waits for — OPS-2. (That a send attempt is a log record is decided, §2.1.)
4. §2.10 The hook that attributes standard Java logging to its message and step, with isolated
   extension classloaders ("to verify at design") — OPS-4. Default: the direction stands (no new R8
   logging API, attribution through the bound `MessageContext`); OPS-4 verifies the hook first.
5. §2.10 A small jq module with levelled functions built on `debug`, so that jqj needs no change
   ("to verify") — OPS-4. Default: `debug` and `stderr` map to the log level debug; no module.
6. §2.10 `xsl:message` is info, with `terminate="yes"` error — OPS-4.
7. §2.10 Platform resources that custom code uses record their lookups: the query and row count at
   debug, the returned values at trace — OPS-4.
8. §2.12 Search across messages in the log (every message with the same error, every message where
   author code wrote a given text) — OPS-2.
9. §3 The retention scheme "a default plus a per-interface override" — CORE-13. Default: both
    periods are configurable; how they are set is not stated.
10. §5 The list of rights, with "Rights are assigned per environment" read as part of the list —
    OPS-9. Default: rights, roles and assignments are decided; which rights exist is not stated.
11. §5 Switching the recording of payload views off needs a special permission and is itself
    recorded — OPS-9. Default: every payload view is recorded, configurable.
12. §5 An agent's key "can be narrowed, never widened, and follows that user's rights" — OPS-10.
    Default: the marker is read as covering only these words; "has the rights of the user who
    created it (the default, configurable)" is stated as decided. Say so if the marker covers both.
13. §6 An alert may also travel through an ordinary R8 interface; alerts about the platform's own
    health keep a direct path — OPS-8. Default: channels only notify and link to the inbox.
14. §6 R8 only notes on an alert that the cause has passed — OPS-8. Default: an alert never closes
    by itself.
15. §6 Notes on an alert stay with it; an alert nobody takes over is escalated after a set time —
    OPS-8.
16. §7 The operating modes of R8 ("not thought through") — CONSIDER-21, parked by the owner.

B. Where the two briefs meet. The default of every line in B: there is no general rule between the
briefs; the row's class-C design decides the point, marked `draft — review at hand-over`.
1. The path of a sync run's log records: the mover's batches without the durable journal path
   (CORE-REWORK Q-6), or the node's journal like every other log record (brief §3) — OPS-2.
2. The body that entered a failing step: kept by the switch `errors` (Q-9), or in the fixed core of
   every error record (brief §2.5) — OPS-5, CORE-13.
3. A step's `skip` (Q-9) and a body above the recording threshold (D-110) against a trace's
   "maximum" (brief §2.9) and "nothing is truncated" (brief §3) — OPS-3. Default: D-110 stands,
   the brief's §1 does not replace it; what "maximum" covers is not stated.
4. Retention: sync, async and error periods (Q-9), or one period for a message's log records and
   one for its payload (brief §3) — CORE-13.

C. Open questions of the rewrite. Each has its own default.
1. Which unit "interface" names behind a dispatcher flow that serves many PO interfaces: the log
   threshold, the team and the caller's assignment attach to it — OPS-3, OPS-9, OPS-11. Default:
   an interface is a deployed flow where no dispatcher is involved; the dispatcher case is unbound.
2. Does "separated by team" also mean a key per team — OPS-9. Default: the separation is enforced
   at the one decision point; the key stays as built (per tenant, with the one tenant `default`).
3. Does a merged PR count as the human's approval "in R8" — OPS-10. Default: deploy stays
   git-first (PR + CI only); the proposal rule is for run-time state and settings.
4. Does an agent's key inherit its user's right to build and deploy — OPS-10. Default: "no direct
   production deploy (PR + CI only)" stands; nothing more is stated.
5. The scope of the `ai:` step's provider, which was per tenant in v3.3 — OPS-9. Default: open;
   the step is not built.
6. Do v3.3's runtime consumer entitlements (PO sender agreements, §9 Partner directory) become the
   assignments of a caller, or do both stand — OPS-11. Default: both sentences stand as written;
   neither is stated as replacing the other.
7. Where TLS ends. The brief's §2.7 says TLS "should end in R8 so that certificates and handshake
   failures stay visible" — a "should", with no marker; the CONSIDER-21 row parks where TLS ends
   with the operating modes — OPS-5, CONSIDER-21. Default: no load balancer is required and none
   is refused; where TLS ends is not stated.

D. One action: the owner applies the steering patch (F-38), `git apply agent/patches/OPS-1-steering.patch`.

## Why the agent cannot decide it
The brief's own status note says of the [inferred] items: "confirm each with the owner before
building on it", and the OPS-9 row repeats it as its acceptance. The points of B set one brief of
the owner against the other; neither brief ranks them. The questions of C are expensive to
reverse: C.1 binds the schema keys of OPS-3, the scope of OPS-9 and the assignment target of
OPS-11, C.2 the journal's keys and the later SQL of OPS-9.

## What was tried
Nothing was decided. OPS-1 rewrote `docs/ARCHITECTURE.md` to the brief (changelog 3.25, F-38) and
states none of these lines as design; §14 lists each with its row. F-38 "For the owner's review"
names what the rewrite itself marks as draft.

## Default if the owner does not answer
Each line's default above holds, and work goes on with what the brief states as decided. Nothing
is built on a line of A before its answer. A point of B or C is decided in its row's design, as a
draft for the owner's review (`draft — review at hand-over`).
D: until the owner applies the patch, `CLAUDE.md` and the two agent definitions keep their present wording; no session applies it.

## Owner's answer
