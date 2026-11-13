# CORE-5b — replication + ACK (split), packet

Inputs: agent/packets/CORE-5.md (Shape S2–S6, S10; 5b designer P1–P13; 5b advisor B1–B8, Q1–Q5, all accepted; A1, A8), D-141, D-142.

## Designer draft 2026-11-13 (split + 5b1) — draft, review at hand-over
Split (one commit each, build order):
- 5b1 holder + repl wire (below).
- 5b2 owner: hook at Journal.write:842-843 sends the round's buffers as DATA non-blocking within an 8 MiB unreceipted window; ACK in position
  order on forcedEnd ≥ end ∧ every holder's WRITTEN ≥ end; PENDING_BYTES counts a record until both. Verdict FLOTTILA_JOURNAL_COPY_TIMEOUT
  (PT2S, PT0.5S..PT10S) on the injected Timer vs the oldest unreceipted frame of the active segment. Loss: seal early, B2 update (row count
  must match), conditional per B3 (zero rows → fail-stop), then one-copy completion; failed/unknown update → retryable 503
  (JournalPeerLostException). B1 insert [O], CAS add after RECEIPT OPENED. W2; rendezvous placement, sticky, re-paired before the first record;
  reconnect 100 ms doubling to 5 s; W5 self-removal before REFUSE FAILED; `journal.copies` health check never a /ready input (A1); S10 notices;
  B7 start refusal naming both keys. If too large, cut before placement/health/notices.
- 5b3 bulk: channel 3 accepted; spill copy at persist before the 202 (persist = local force ∧ SEALED), cap on concurrent spill copies, progress
  rule not age verdict (B4, B6); HAVE (type 7) reconcile per W1/W3 (prefix: header + running crc32c); P11 (replica follows its row) here.

5b1 types:
- `PeerHandshake.dialer(self, peer, nonces, byte channel)`; READ 1, REPL 2, BULK 3. The listener sends its HELLO only after the dialer's,
  naming the dialer's channel; serves read and repl; bulk refused naming "bulk". `PeerLink.channel()` once established; a frame type not of
  the link's channel or direction closes the link.
- `PeerServer.listen(vertx, config, Supplier<PeerIdentity>, journal, timer, ToLongFunction<String> epochOf)`. A repl link gets a package-private
  `Holder implements PeerLink.Receiver`: one writer virtual thread per link draining frames in order; pauses the socket (`hold`) above HOLD_AT
  8 MiB queued, resumes (`unhold`) at 4 MiB; `waiting()` false (keepalive FIX-186).
- `public final class Replicas` (journal package, `Journal.replicas()`): `Replica open(owner, UUID journal, long n, long length, byte[] header)`
  throws JournalFullException, ReplicaMismatch, IllegalArgumentException (bad header), IOException; `Replica.write(long pos, ByteBuffer)`,
  `end()`, `force()`, `seal(long end)`, `fail()`, `detach()`; `long unforced()` (force-lag figure). Package-private Journal byte hooks (reserve
  against the intake edge; unchecked grow/shrink). Codec `ReplFrames` with `Address(owner, journal, n)` and Open/Data/Seal/Receipt/Refuse records.

Wire (big-endian, inside framing v1):
- A = `u16 L · owner UTF-8 (1..63) · journal id (u64 msb, u64 lsb) · n u64 (≥1)`; on a repl link owner and journal equal the HELLO's node and
  journal id, else close.
- OPEN 3 O→H: `A · length u64 · header`; header exactly `SegmentFormat(journal, owner).headerLength()` = 43+L bytes, its CRC and `ours(header, n)` hold.
- DATA 4 O→H: `A · pos u64 · bytes (1..4 MiB)`; pos = file offset (header at 0, as JournalRef.position); footer bytes travel as DATA.
- SEAL 5 O→H: `A · end u64`, the file's whole length with the footer, after the footer DATA.
- RECEIPT 6 H→O: `A · end u64 · state u8`: 1 OPENED (header + force + dir force), 2 WRITTEN (pwrite returned), 3 SEALED (force, rename, dir
  force); (end, state) never decreases; one WRITTEN per segment per drain, sent before that drain's force.
- REFUSE 8 H→O: `A · reason u8 (1 FULL, 2 FAILED, 3 MISMATCH) · u16 len · detail UTF-8 ≤512`. HAVE 7 is 5b3's; type 9 reserved.

Holder disk: `$FLOTTILA_JOURNAL_DIR/replicas/<owner>/<journal uuid>/<n>.part`, directories created durably; CREATE_NEW, header, force, dir
force, RECEIPT OPENED. SEAL: force, ATOMIC_MOVE to `<n>.seg`, dir force, RECEIPT SEALED; byte-identical to the owner's `segments/<n>.seg`.
Replicas count in Journal.bytes() (Journal:433 `sizes`, JournalStart:67); OPEN reserves `length` against max − max/16; bytes past it counted
unchecked; seal or link close trues the count to the file size. Start: each `.part` checked against its header — bad → unlinked + WARNING;
else CRC-scanned (Recovery.scan), cut after its last whole record, never sealed; `.seg` counted as found.

Link closes (WARNING naming the cause): wrong layout/length; an address not the link's; bad OPEN header or `length` < header length; a 5th
unsealed segment on the link (OPEN_MAX 4); DATA/SEAL for an n not OPENed on this link; empty DATA or pos ≠ end; SEAL end ≠ the replica's end.
REFUSE, link up: FULL (nothing created); MISMATCH (n held as `.seg`, or a `.part` not exactly this header; a header-only `.part` re-OPENs:
force, dir force, OPENED); FAILED (create/write/force/rename/dir force failed: `.part` unlinked on the writer thread, bytes given back, later
DATA/SEAL of n on the link dropped). Epoch (A8): after the proof, a repl link is closed when its epoch < max(highest accepted from that owner,
epochOf(owner)); else it supersedes the owner's existing repl link, its frames taken only once the old writer stopped. No new keys; constants
HOLD_AT/RESUME_AT 8/4 MiB, OPEN_MAX 4, DATA_MAX 4 MiB.

Tests (RawPeer gains `dial(port, self, peer, channel)`; fake JournalFiles that holds or fails writes and forces; fake Timer; no sleeps):
T1 ReplFramesTest golden bytes each frame, ±1 byte refused. T2 PeerReplHandshakeTest (repl → listener HELLO byte 2 = 2, read → 1; bulk refused
naming bulk; READ on repl closes; OPEN on read closes). T3 PeerReplEpochTest (same/higher epoch supersedes: first EOF, new OPEN receipted;
lower or below epochOf closed while the old still receipts). T4 ReplicaHolderTest.open (OPENED after file force then dir force; `.part` = header).
T5 .receiptAfterPwrite (held write → no frame; release → WRITTEN pos+len while force held; unforced > 0 then 0). T6 .protocol (each close case).
T7 .full (REFUSE FULL, no file, bytes() unchanged; next fitting OPEN receipted; own append JournalFull from replica room). T8 .mismatchAndReopen.
T9 .failed (REFUSE FAILED, `.part` gone, bytes() back; later DATA dropped, link up). T10 .seal (order; `.seg`, no `.part`). T11
ReplicaBackpressureTest (held write, 64 MiB sent → queued ≤ 12 MiB; release → in order, increasing ends). T12 ReplicaIdentityIT (real
Journal's sealed segment via OPEN + DATA (footer in 2 frames) + SEAL → byte-identical `.seg`). T13 ReplicaStartTest (counted; torn `.part` cut,
stays `.part`; bad header unlinked + WARNING). T14 MembershipIT only if Q1 yes (renew answers live peers' epochs in its one statement).
Vert.x: 5b1 uses calls PeerLink already uses; before 5b2 javap NetSocket.writeQueueFull, drainHandler, WriteStream.setWriteQueueMaxSize.

Rejected: listener channel bitmask / 0 = any; relaxing the dialer's channel check; footer inside SEAL (index can exceed 4 MiB); SEAL before
the footer; holder trailer read-back. Expensive to reverse: types 3–8 and layouts; RECEIPT states (WRITTEN = pwrite returned); replicas layout;
replicas in MAX_BYTES at the intake edge; listener speaking second; the epoch rule.
Conflicts: D-142 "handshake final" vs listener speaking second (order, not bytes; RawPeer.connect, PeerHandshakeSourceBoundTest and the KAT
comment :35 change, test-author with reason). P1 footer order superseded; P2 role-from-first-frame superseded by the channel (B5).
Open: Q1 epochOf from renew-answered epochs (one more array in the same statement + a holder restart check) [rec]; Q2 sealed replicas never
swept until P11 → P11 in 5b3, no deployed release between 5b2 and 5b3 [rec]; Q3 holder forces once per drain [rec]; Q4 OPEN length =
max(segmentBytes, header + longest record) (5b2) [rec]; Q5 W5 self-removal in 5b2 [rec]; Q6 holder accepts repl whatever its own COPIES [rec].

## Advisor (Fable, 2026-11-13) — SOUND WITH CHANGES; architect's disposition: all accepted
C1 handshake bytes and order unchanged: the listener's HELLO goes at once with channel 1 on every link; the link's channel is the dialer's
   byte 2 (both proofs bind it, PeerHandshake:139-140, :201-206); the listener takes 1|2, refuses 3 naming bulk; dialer branch unchanged.
   "Listener speaks second" is dropped. CORE-5a2 O2 wording becomes: a dialer refuses a listener whose HELLO names a channel other than read.
C2 FAILED keeps the file: the replica is closed, the count trued to its size, no unlink (unlink only once H is out of `copies`, 5b2: CAS,
   then REFUSE, then sweep). C3 start: a `.part` no longer than its header with a bad header is unlinked; a longer one with a bad header is
   kept, ERROR, never served; a `.part` with a valid trailer is renamed to `.seg` (not cut). C4 REFUSE FULL when replica bytes + length exceed
   half the intake edge (constant) or the total exceeds the edge; the owner's own append still fits. C5 force and seal on a second thread per
   link; the writer waits only above HOLD_AT unforced bytes. C6 the epoch bound is re-checked at every OPEN; package-private close-by-owner
   for 5c; an owner unknown to the renew has no bound. C7 FIX-186 moves before CORE-5c; a link close trues the reservation count. C8 a repl
   HELLO's node must be a DNS label (NodeConfig:671-673), else closed. C9 the wire states "no DATA(n) before RECEIPT OPENED(n)"; OPEN_MAX 4 and
   DATA_MAX 4 MiB are wire constants in D-139. C10 test gaps (below). Alternative accepted: SEAL carries crc32c of [0, end) — the holder checks
   it before the rename; a mismatch is REFUSE MISMATCH with the `.part` kept.
Q1 epochOf from the renew (lower bound only; unknown → no bound) + C6; T14 stays. Q2 P11 (replica follows its row, also unlisted `.part`)
in 5b2b. Q3 per C5. Q4 yes. Q5 yes, C2's order. Q6 yes. Frames become final at 5b3's review (HAVE may change OPEN/MISMATCH for a `.part`
with records). Split: 5b1; 5b2a hook, ACK order, verdict, loss B1–B3, W2, W5, B7, pairing with the one live peer; 5b2b rendezvous
placement, `journal.copies` health (one-copy bytes include spills until 5b3), notices, P11; 5b3 bulk + HAVE. Two-node run records ACK p99
and max `unforced()`. D-139 clear list gains the segment header and footer index.

## Acceptance 5b1 (red first) — the draft's tests as amended
SEAL 5 = `A · end u64 · crc32c u32` (crc32c of the file's bytes [0, end)). Other layouts as the draft.
T1 ReplFramesTest: golden bytes for OPEN/DATA/SEAL/RECEIPT/REFUSE computed outside the code (python, script and output under agent/work/),
   decode back; one byte short or long refused.
T2 PeerReplHandshakeTest: a repl dial links, `channel()` = 2 on both sides, the listener's HELLO bytes are the same on read and repl dials
   (channel 1); a bulk dial refused naming bulk; a relay flipping the dialer's channel byte never establishes; READ on a repl link and OPEN on
   a read link close it; a repl HELLO whose node is not a DNS label (e.g. `..`, `a/b`) is closed.
T3 PeerReplEpochTest: same/higher epoch supersedes (first EOF; with the old writer held, the new OPEN is receipted only after release);
   lower, or below epochOf, closed while the old link still receipts; the bound re-checked at OPEN (epochOf raised after the proof → the next
   OPEN closes the link).
T4 ReplicaHolderTest.open: OPENED after file force then dir force (recorded order); `.part` = header.
T5 .receiptAfterPwrite: held write → no frame; release → WRITTEN pos+len while the force is held; unforced() > 0, then 0 after the force.
T5b .forceOffTheReceiptPath: with a force held, a second DATA is still receipted WRITTEN; the writer waits only past HOLD_AT unforced bytes.
T6 .protocol: each link-closing case of the draft closes the link, one case each; DATA before OPENED(n) closes it.
T7 .full: replica bytes + length above half the intake edge → REFUSE FULL, no file, bytes() unchanged; the next fitting OPEN receipted; the
   holder's own append still fits after replicas took their half.
T8 .mismatchAndReopen (sealed n → MISMATCH; header-only `.part` re-OPEN → OPENED).
T9 .failed: a failed write → REFUSE FAILED; the `.part` stays, closed, bytes() trued to its size; later DATA of n dropped, link up.
T10 .seal: holding force, rename and dir force in turn, SEALED arrives only after all three; `.seg`, no `.part`; a SEAL whose crc32c differs
   → REFUSE MISMATCH, `.part` kept.
T10b .countTrued: bytes past OPEN's length counted, then trued at seal; a link close trues the reservation of every unsealed replica.
T10c .ownersIndependent: owner X's held write does not stop owner Y's receipts.
T11 ReplicaBackpressureTest: held write, 64 MiB sent → queued ≤ 12 MiB; release → receipted in order, ends strictly increasing.
T12 ReplicaIdentityIT: a real Journal's sealed segment via OPEN + DATA (footer in 2 frames) + SEAL → byte-identical `.seg`.
T13 ReplicaStartTest: Journal.open counts `.part`/`.seg` in bytes() and in the start room check (JournalStart requireRoom); a torn `.part`
   cut to its last whole record, stays `.part`; a header-only bad `.part` unlinked + WARNING; a longer bad-header `.part` kept + ERROR, not
   served; a `.part` with a valid trailer renamed `.seg`.
T14 MembershipIT: the renew answers live peers' epochs in its one statement; epochOf of an unknown owner gives no bound.

## Red approved 5b1 (architect, 2026-11-13)
Tests: peer/ReplWire (fixture), ReplFramesTest, PeerReplHandshakeTest; journal/ReplicaWorld (fixture), PeerReplEpochTest, ReplicaHolderTest,
ReplicaBackpressureTest, ReplicaIdentityIT, ReplicaStartTest; node/PgMembershipEpochsIT. Golden bytes agent/work/CORE-5b1-frames.{py,log}.
Red: compile (agent/work/CORE-5b1-red.log); against scratch shims T1/T2 20 of 45 fail at assertions, T3–T13 44 of 44.
Edited by the test-author (C1): PeerHandshakeVersionTest and PeerHelloLinkTest refusal cases narrowed to bulk.
Choices approved: ReplFrames package-private in peer, records with encode()/decode(ByteBuffer) throws PeerRefusedException; Replicas.unforced()
total over replicas; OPENED end = header length; OPEN's reservation in bytes() after OPENED, FULL is strictly above half or edge; re-OPEN of
a `.part` with records → MISMATCH (until 5b3); "never served" = OPEN throws ReplicaMismatch; PgMembership.epochOf(String), < 1 = no bound;
DATA before OPENED closes on arrival; type 9 closes. Known weak pins: rename held only between the two forces; T3 order after a fairness
barrier (a late wrong implementation could pass); T11 measured from outside with a 13 MiB bound.

## 5b1 review r1 FAIL → fix round (architect)
R1 Journal:699 overflow on a wire u64 length → subtract form; case: OPEN 2 with length Long.MAX_VALUE - 10 after OPEN 1 → REFUSE FULL, bytes() unchanged.
R2 Replica:107 / Replicas:100 true to the file's size (channel.size()), not `end`; cases: HeldFiles "write k bytes then throw" (DATA and the header).
R3 Holder:463 RECEIPT/REFUSE fire-and-forget → the writer awaits the previous drain's last send before its next take(); case: an owner that
   sends but never reads leaves the holder's outbound bounded (the socket paused).
R4 C6 close-by-owner deferred to CORE-5c (row updated).
R5 case: accept epoch 2, accept 3, dial 2 → closed (Math::max pin). R6 a failed create's bytes() asserted (counted at its size).
R7/R8 docs (architect). Also taken: `queued` counts frame length, not payload (case: 8 MiB of 1-byte DATA pauses the socket within the
   bound); Replicas.open throws IAE for an owner that is not a DNS label (one shared pattern with NodeConfig); PeerProtocolTest:767
   (aLinkPausedByAnAbandonedConsumerIsClosedAfterATimeoutAndItsReadsFail, "expected DIALER but was LISTENER", agent/work/CORE-5b1-reactor.log)
   failed in the whole reactor — find whether 5b1 changed who closes first; fix the cause, not the test. Deferred to 5b3: index-CRC check
   before a start rename of a `.part` with a valid trailer.

## 5b1 review r2 FAIL → fix round (architect)
S1 ReplicaBackpressureTest:139/:158 bound widened to HOLD_AT + 4 MiB of frames (M31 still dies; 20/20 with the KiB logged). S2 :91-93/:183-185
fail fast with the sender's cause. S3/S4 docs (architect): §4 and D-139 state the reset and name NetSocketInternal (javap evidence
agent/work/CORE-5b1-r2-javap.log: no per-socket reset; setSoLinger only server/client-wide); FIX-180 confines netty/internal to journal.peer;
netty-transport declared in flottila-runtime/pom.xml. S5 ReplicaSupersedeTest 10/10 on the final tree. Also: abort split into mark → old.stop()
→ reset, so the order holds across event loops. Known weak pins: M42 (no old.stop()) and M46 (stop before abort) are timing-dependent.
