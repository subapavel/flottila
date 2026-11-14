# Handoff
state: RUNNING (owner RESUME 11-14, autonomous to Mon 11-16 08:00 Prague). Max 2 subagent slots is the parallelism ceiling, not the 4 CPUs
commits: FIX-143 is the newest (`git log -1`), before it 07bbac6 FIX-146, 232ed9d FIX-145, e885ed8 CONSIDER-13 prompt, 7ccbaad FIX-147
PUSHED and observed 2026-11-14: eb15a31..232ed9d, cleared by an integrated `clean verify -Pit` in the main tree - 17/17 modules SUCCESS,
  core 566, 0 skipped, `coverage: no decrease`, FIX-144 flake did NOT fire. FIX-146 is committed on top, NOT pushed; it changes no code.
FIX-143 and FIX-146 are committed and NOT pushed. Neither changes behaviour (comments, journal, ledgers), so no new reactor run is
  owed before the next push; the last integrated run covered 232ed9d and is recorded above.
next: FIX-144 + FIX-148 (same test, judge TOGETHER; flake REAL and load-dependent, idle 0/20 vs loaded 1/20; that class
  burns 39.6 s per run in expired 2 s patiences; do NOT shorten patience() - at 2 s load decides the interleaving, the flake's mechanism.
STANDING RULE earned twice today (NOTES): the reviewer has Read/Grep/Glob and NO shell. Anything proved only by a command must be ON DISK
  before the review is dispatched - the diff via `git add -A && git diff --cached`, the build log, the PIT report copied out of target/.
Ledgers are never edited in a lane, so a reviewer reading a lane worktree sees a STALE row: say so in every review packet.
Lanes: never two Maven jobs in one worktree; ask what the others are MEASURING first. DIRECTORY NAME IS NOT BRANCH NAME -
  flottila-lane2 is on branch `lane5` (lane2/lane4 are checked out nowhere, both carry nothing unique). Name the PATH, reset by path.
OWNER, CONSIDER-13 (the message log): prompt at agent/packets/CONSIDER-13-fable-brainstorm-prompt.md plus CONSIDER-13-fable-addendum.md
  (the designer's rejections verbatim - two SENTENCES, not two paragraphs as my prompt said). NOT DECIDED. main is still 634983a.
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 answered 2026-10-30 (option 2)
open fixes: 16,18..20,22..29,31,33,34,36..43,45..52,54..58,61,63,64,68,72..75,77..104,109,112,113,122,124,126,127,129,135,143,144,148..156; CONSIDER-1..11
pending at hand-over: D-022, D-025-61 exc 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096..113
owner at hand-over: F-18 (5 ms); D-064/65B/67A/72/73; P1-08 (D-049); /DirArchiveStore.bak + /S3ArchiveStore.bak (root deletes); `commit -F -` takes INDEX
