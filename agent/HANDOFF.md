# Handoff
state: RUNNING (owner RESUME 11-14, autonomous to Mon 11-16 08:00 Prague). Max 2 subagent slots is the parallelism ceiling, not the 4 CPUs
commits: FIX-147 is the newest (`git log -1`), before it eb15a31 FIX-142, 94b6d02 FIX-141, 0adcbed FIX-136, c42887a FIX-137
PUSHED and observed 2026-11-14: 47c9b85..eb15a31. FIX-147 is committed but NOT yet pushed and NOT yet in an integrated run; it is
  comment-only (ParkingTest 22/22 is the whole evidence owed - there is no javadoc plugin in this build, NOTES/Build and VM).
WIP FIX-145 in /home/user/flottila-lane3, 2 files staged, NOT committed: 3-line fix + 22 cases. r1 FAILed on two blockers, both
  answered - case 3 `aCloseWhoseFutureFailsWithAnInterruptedExceptionLeavesTheFlagClear` pins the PLACEMENT (the mutant moving the
  guard into `recover` fails it, agent/work/FIX-145-M3.log); comment now splits D-056 (catchable) from D-098 (1) (the rule).
next: the scoped PIT on `io.flottila.core.flow.Producers` is running, the last piece owed; then re-review FIX-145, PROGRESS +
  row + commit, then ONE integrated `clean verify -Pit` in the main tree covering FIX-147 and FIX-145 together, then push.
then FIX-143 triage (count SETTLED at 10; label each TRANSLATION or POLICY, that decides the verdict; Retry.java:44 likely RIGHT).
then FIX-144 + FIX-148 (same test, judge together; flake is REAL and load-dependent; do NOT shorten patience()), FIX-146, FIX-149..152.
Ledgers are never edited in a lane, so a reviewer reading a lane worktree sees a STALE row: say so in every review packet.
Lanes: before dispatching anything that runs Maven, ask what the other lanes are MEASURING, and NEVER two Maven jobs in one worktree.
OWNER ASKED 2026-11-14 evening: brainstorm prompt for CONSIDER-13 (the message log) in a separate Fable session - written to
  agent/packets/CONSIDER-13-fable-brainstorm-prompt.md. The design draft it summarises is CONSIDER-13-design-draft.md, NOT DECIDED.
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 answered 2026-10-30 (option 2)
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..58,61,63,64,68,72..75,77..104,109,112,113,122,124,126,127,129,135,143..146,148..152; CONSIDER-1..11
pending at hand-over: D-022, D-025-61 exc 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096..113
owner at hand-over: F-18 (5 ms); D-064/65B/67A/72/73; P1-08 (D-049); /DirArchiveStore.bak + /S3ArchiveStore.bak (root deletes); `commit -F -` takes INDEX
