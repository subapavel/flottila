# CORE-4c3 — the pinned-cut start refusal, ACCEPT_LOSS, the open-channel cache and the start carries (class C; D-105)

Architect 2026-11-04. Base: f340ded (CORE-4c2). Design: agent/packets/CORE-3.md item 9 (start), item 8 (channel cache), "Review r2
additions" (ACCEPT_LOSS tied to its loss, lost_at, free space) and the "Added in CORE-4a/4b/4c1" carries not done in 4c2. Existing
start code: Journal.java:300-410 (unlinkUnregistered, dropUnfiled, the identity guard at :383). V12 has `body_refs.lost_at`
only; `work.lost_at` and `journal_losses` do not exist yet.

## Scope
1. **V13** (`deploy/postgres/V13__journal_losses.sql`): `work.lost_at timestamptz` (null = not lost); `journal_losses(node text,
   journal_id uuid, digest text, accepted_at timestamptz, pk (node, journal_id, digest))`. Migration counts 12 → 13 (a changed
   expectation with its reason).
2. **Pins at start** (item 9): rows without a file are dropped only if unpinned. A segment without a valid trailer is scanned and
   cut at its first bad record (exists). A committed pin — work row (`body_segment`, lost_at null), body_refs not archived and
   lost_at null, DLQ ⋈ body_refs — into a missing segment or at/past a segment's cut refuses the start, naming the items (message
   ids, at most a bounded list plus the count) and printing `<journal id>:<digest>`, where the digest is a stable hash over the
   sorted given-up items. Nothing changes on disk or in Postgres on a refusal.
3. **ACCEPT_LOSS**: `FLOTTILA_JOURNAL_ACCEPT_LOSS` (NodeConfig, the only env reader). Exactly the printed value accepts that loss:
   the lost work rows get `lost_at` (held: never claimed, never run, never DLQ), their body_refs get `lost_at` (pin nothing), each
   item logged at ERROR, the digest recorded in `journal_losses` — all in one transaction before the journal opens for appends.
   Any other value refuses. A value already recorded accepts nothing new (WARNING); a later loss needs its own value. The identity
   cases (a row without NODE, NODE with another id, the NODE-lost guard below) use the same printed value: accepted → a new journal
   id replaces the old row and every pin into the old journal is given up as above.
4. **Lost reads**: a read of a lost ref fails "body lost by ACCEPT_LOSS" (never BlobNotFound, never retryable); a DLQ retry or
   replay of a lost body is refused with that reason (D-096 409); admin GET of a lost body answers 410 or 409 with that reason
   (test-author picks one, the architect approves it with the red).
5. **Free space**: start refuses when the journal filesystem's usable space + the journal's bytes < the journal's MAX_BYTES (no
   ENOSPC crash loop); the message names both numbers. The space source is a seam (FileStore) so the test needs no full disk.
6. **Open-channel cache** (item 8): reads pread over a bounded set of open channels (a NodeConfig key, finite, range-checked),
   least recently used closed first; a channel of a segment removed from disk is dropped on its failure; close() closes all.
7. **Store read checks its key**: the record header's message id, step and part must match the BlobKey; a misaddressed ref fails
   as corrupt (ERROR), never with another message's bytes.
8. **Carries**: the NODE-lost guard (`registered == null && filed > 0` refuses whether NODE is present or not); a close during a
   Postgres outage logs no fail-stop ERROR; a crashed generation's `MASTER.*.tmp` is removed at the next start under LOCK; the
   admin health shows a notice while the master key is generated (D-106); after a fail-stop caused by a dir force, `failure` is
   re-checked after any later force of that dir; `durably()`'s append wait ends when the answer completes (CORE-3 item 6).

Not here: the sweeper, the disk bound with 503, `pg_xact_status`, KEEP's conflict branch key share, an index hit on a swept
segment → 404 (all CORE-4d); FIX-120.

## Red tests (test-author)
NodeJournalIT.aPinnedMissingSegmentRefusesStart / .aPinAtOrPastTheCutRefusesStart / .anUnpinnedRowWithoutAFileIsDropped /
.acceptLossWithThisJournalIdHoldsTheLostItems / .aWrongOrStaleAcceptLossAcceptsNothing / .aSecondLossNeedsItsOwnAcceptance /
.aLostNodeFileWithSegmentFilesRefuses / .anIdentityLossIsAcceptedByItsPrintedValue / .tooLittleFreeSpaceRefusesStart /
.aCrashedMasterTmpIsRemoved / .theAdminHealthNamesAGeneratedKey; lost reads (store, DLQ retry, replay, admin GET);
JournalReadTest (or similar): .readsShareABoundedSetOfChannels / .aMisaddressedRefFailsAsCorrupt; a quiet close during an outage;
the dir-force re-check; durably()'s wait. MigrationsIT V13. Red = compile error or failing assertion, agent/work/CORE-4c3-red.log.

## Constraints
CLAUDE.md design rules; only runtime touches Postgres; NodeConfig the only env reader; no key value in a log; sizes long, limits
finite; fake clock/timer, held seams, no sleeps; javap for FileStore/FileChannel APIs. Floors no decrease.

## Checks
`mvn -B -ntp -q -pl flottila-runtime -am verify`, reactor `verify -Pit`, coverage check; logs agent/work/CORE-4c3-*.log.
