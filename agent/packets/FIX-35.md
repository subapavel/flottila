# FIX-35 — persist compares bytes on conflict (amends D-061)

Class C. Designer draft approved by the architect. Decision text: docs/DECISIONS.md D-061 "Amended by FIX-35".

## Semantics
- A key's durable bytes never change. `persist(key)` of the same bytes again: idempotent, quiet, releases the local copy.
  Of other bytes: fails with `IllegalStateException`; row unchanged; the index entry, budget and spool file stay (body stays local).
- ISE message: `"the durable body under " + key + " holds other bytes than these " + size + ": a key's durable bytes never change (D-061, FIX-35); nothing was persisted and the body stays local"`.
- PgBodies: `Future<Boolean> insert(BlobKey, Buffer)` — `INSERT … ON CONFLICT DO NOTHING`; rowCount 1 → true (hot path unchanged);
  rowCount 0 → `select 1 from bodies where message_id=$1 and step=$2 and part=$3 and bytes=$4`, true iff a row. Two autocommit statements, no FOR UPDATE.
- TieredBlobStore.persist: `local.compose(b -> bodies.insert(key, b)).compose(same -> same ? release + success : failedFuture(ISE))`.
  Index-miss, S3, above-bound and no-tier branches unchanged.
- testkit InMemoryBlobStore: `Map<BlobKey,Buffer> durable`; `putIfAbsent(k, copy)`; prior non-null and not equal → ISE (same wording); `persisted(k)` = containsKey.
- No schema change; V6 untouched. Parking / Delivery / FlowWorker unchanged (paths already end the item in the ingress park).
- Javadoc reworded: api `BlobStore.persist`, PgBodies class + insert, TieredBlobStore.persist, DurableBlobStoreContract class doc.

## Tests
- DurableBlobStoreContract (InMemory + PgTierContractIT): `persistingOtherBytesUnderADurableKeyFailsIllegalStateAndKeepsTheFirst` (same size last byte changed; other size),
  `persistingTheSameBytesWrittenAgainIsQuiet`, `otherBytesUnderAnotherStepOrPartAreNoConflict`.
- PgBodiesIT: `aKeyWrittenAgain…TheFirstBytesWin` → `aKeyWrittenAgainWithOtherBytesAfterPersistFailsIllegalStateAndTheRowKeepsTheFirst` (wrong expectation, reason: D-061 amended by FIX-35);
  new `aKeyWrittenAgainWithTheSameBytesAfterPersistPersistsQuietlyAndReleasesTheCopy`, `aConflictingRowCommittedWhileThePersistWaitsIsCompared`.
- PgBodiesReleaseIT `aKeyWrittenAgainWhileItsPersistWaitsKeepsTheNewCopyLocal` :168-171 wrong expectation → ISE, released empty, copy local.
- core FlowWorkerTest `aRerunWhoseParkedBodyDiffersFromItsDurableBytesEndsInTheIngressPark`.
- runtime DlqBodiesIT `aStepBodyWhoseKeyHoldsOtherDurableBytesEndsInTheIngressPark` (done-when witness on Postgres).

## Mutants to require
compare skipped on rowCount 0; compare negated; step or part dropped from the compare WHERE; length-only compare; ISE mapped to success;
release on mismatch; InMemory putIfAbsent → put; InMemory compare dropped. Equivalent (accepted): compare also after a successful insert.

## Allowed paths
flottila-api BlobStore.java (javadoc only), flottila-runtime blob/PgBodies.java, blob/TieredBlobStore.java, flottila-testkit InMemoryBlobStore.java,
DurableBlobStoreContract.java; tests: PgBodiesIT, PgBodiesReleaseIT, PgTierContractIT, FlowWorkerTest, DlqBodiesIT, testkit contract self-tests.

## Round 2 (review FAIL r1)
- Per-write spool names and index() unlink of a replaced spool entry's own file: accepted (architect + reviewer). TieredBlobStoreTest:76-78 changed as a wrong expectation (per-write names).
- Blocking: `spooled()` falls back to the durable row whenever a spool file is missing; with the index() unlink a write landing between persist's index read and the lazy open makes persist compare the row with itself and succeed for bytes never made durable.
  Rule: a missing spool file reads the durable row only for an entry proven durable (e.g. a volatile flag on Stored set in persist once `same` is true, before release); for any other entry the read fails, and persist fails (IllegalStateException naming the key: the local copy is gone) leaving the entry indexed and the row unchanged.
- Tests named so far: PgBodiesReleaseIT aKeyWrittenAgainRightAfterItsSpoolPersistKeepsAFileOfItsOwn, aSpoolCopyReplacedByAWriteOfItsKeyIsUnlinkedAndNothingElse; TieredBlobStoreTest:76-78.
  New red: PgBodiesReleaseIT (a) a stream opened over a non-durable spool entry whose file is unlinked by a rewrite fails and never yields the row's bytes; (b) persist over a spool entry whose file is gone fails, the entry stays, the row keeps the first bytes.
- Mutants: F1 fallback condition dropped; F2 flag set unconditionally / on mismatch; F3 flag never set; re-run P1–P6, T1–T10.
