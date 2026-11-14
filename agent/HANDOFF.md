# Handoff
state: RUNNING (owner RESUME 11-14, autonomous to Mon 11-16 08:00 Prague). Max 2 subagent slots is the parallelism ceiling, not the 4 CPUs
last commit: FIX-138 (this one); 59bbad3 F-30 3rd correction + packets 141/142; 47c9b85 FIX-140; 7dc0555 FIX-139; 8105b0f F-30
NOT PUSHED since 47c9b85: push needs one integrated `clean verify -Pit`. main is still 634983a so WORKFLOW §4 CI gates do not run here - owner asked
WIP FIX-136: implemented and module-green in lane2 (96 targeted, core 532, both 0/0/0) but NOT reviewed and NO mutants run. Next exact action: reviewer
  on lane2, then verifier for the mutants it names PLUS the packet's designated one - stop the widened catch deciding the answer, let the :286 save
  timeout answer instead, and the assertFalse on "durable save timed out" is then its only killer. Its commit MUST note D-056 on that decision's row:
  sites :176 and :218 go Exception -> Throwable and landAfter's parameter :227 with them (the implementer reported that; it is beyond the packet).
then FIX-137 in lane3 (reset lane3 first - FIX-138 is applied to main now): doc-only, packet written, needs D-112 in docs/DECISIONS.md at commit time.
  Then FIX-141 (Producers:49), FIX-142 (Parking:86 AND :59), FIX-143 (no packet yet; its row now carries the Deployments.java groundwork).
  Ledgers are never edited in a lane, so a reviewer reading a lane worktree sees a STALE row: say so in every review packet.
F-30: D-049 (d) means every throwable, D-049 needs NO amendment, proof inside the method at :296/:312. CORRECTED THREE TIMES: the permit leak is
  at-least-once only; Parking is two sites and REPLACES the step's failure; the permit is held for saveTimeout then COMES BACK, not leaked for good, so
  the sharp half is the false record - "durable save timed out" for an Error recorded nowhere. Addendum: 2 new candidates in 165 catches, no iceberg.
F-29: the catch half is CLOSED by FIX-133; still open and still CONSIDER-12's - the timeline is stitched, first-writer-wins, seam only in ATTEMPT.
CONSIDER-13 (per-message log) is BLOCKED on the owner, who takes it himself the evening of 11-14: agent/packets/CONSIDER-13-design-draft.md
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 answered 2026-10-30 (option 2)
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..58,61,63,64,68,72..75,77..104,109,112,113,122,124,126,127,129,135,137,141..143; CONSIDER-1..11
pending at hand-over: D-022, D-025-61 exc 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096..111
owner at hand-over: F-18 (5 ms); D-064/65B/67A/72/73; P1-08 (D-049); /DirArchiveStore.bak + /S3ArchiveStore.bak (root deletes); `commit -F -` takes the INDEX
