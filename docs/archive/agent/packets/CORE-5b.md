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

## CORE-5b2a designer draft (2026-11-14) — draft, review at hand-over; awaiting the advisor
P1 journal: `public interface Replication { NONE; preopened(n); wrote(n, pos, List<ByteBuffer>); sealed(n, pos, ByteBuffer footer, end) }` (NONE
   + peer.Replicator; journal cannot depend on peer). `Journal.Copies Journal.replicate(Replication)` once; Copies { List<Pairing> pair(holder);
   copied(n, end); single(Set<Long>); sealEarly(n); failed(Set<Long>, JournalPeerLostException); fenced(Throwable) }, `record Pairing(n, header,
   length)`. `JournalPeerLostException extends RuntimeException` → retryable Nack 503 + Retry-After (PgIntake:378).
P1b peer: `Replicator implements Replication` (start(Vertx, PeerConfig, PeerIdentity, Journal, SegmentRows, Supplier<Map<String,String>> live,
   Duration copyTimeout, Clock, Timer); close()); one pump virtual thread `journal-repl-<peer>`; inner Link Receiver, waiting() false (COPY_TIMEOUT
   is the only verdict). PeerLink gains full() (writeQueueFull) and a drainHandler → Receiver.drained(). PgMembership gains peers().
P2 hook never blocks the writer: Journal.write calls wrote(...) per paired segment between the write loop and the force loop (:886/:887); the
   sealer calls sealed(...) after its force (:1032); pre-open calls preopened(n) after extents.put (:928). Each enqueues under the Replicator's
   lock; memory bounded by PENDING_BYTES (a record's buffer lives until its ACK).
P3 pump: queues `opens` and `stream`; OPEN only while < OPEN_MAX unsealed; DATA(n) only after OPENED(n); contiguous chunks merged to DATA_MAX;
   sends while unreceipted < WINDOW 8 MiB (one frame always) and !full(); repl socket setWriteQueueMaxSize(WINDOW); full → wait for drained();
   running crc32c per segment over header + DATA gives SEAL's crc; receipts checked on the event loop (own address, OPENED end = header length,
   WRITTEN increasing ≤ sentEnd, SEALED end = SEAL's end; else close), queued for the pump; the Journal lock never taken on the event loop.
P4 ACK: a round's completion only marks records forced; the head of the position-ordered unacked queue completes while forced ∧ (segment single
   ∨ copied(n) ≥ end); `pending -= length` at that pop. copied(n, end) only after n's CAS committed. A segment's mode frozen at its first
   reservation (paired if pair() handed it out before, else single). NONE: every segment single = today. Journal.fail fails the unacked queue.
P5 pairing: pair(holder) returns and marks segments whose file exists with no record reserved (current at 0 records, the pre-opened one); OPEN
   length = max(segmentBytes, headerLength + longestRecord); called at link-up and each preopened(); peer sticky else the lowest node id in
   peers() (5b2b: rendezvous). COPIES=1: no Replicator, Journal NONE, nothing dials; the node still holds peers' copies (Q6).
P6 B1+B3: insert unchanged [O]; after OPENED(n) one statement `addCopy(O,E,n,H)`: with live (nodes where node=O and epoch=E and lease_until is
   not null), update copies=array[O,H] where node=O and segment=n and copies in (array[O], array[O,H]) and exists(live) → (live count, updated
   count). Idempotent retry. live 0 → fenced; u 0 → fail-stop. Failed/unknown → n's waiting records 503, retried 100 ms doubling to 5 s.
P7 verdict: per link a send-ordered deque of frames awaiting receipts (OPEN→OPENED, DATA→WRITTEN ≥ end, SEAL→SEALED) stamped at send; one Timer
   task at head.sentAt + COPY_TIMEOUT; lost if head age ≥ COPY_TIMEOUT; unsent frames do not age. Also lost on close with anything in flight, a
   receipt protocol error, REFUSE per P8.
P8 REFUSE: before OPENED: FULL → n single, no statement, WARNING, link up; MISMATCH → same, ERROR; FAILED → lost. After OPENED: FAILED or
   MISMATCH → lost (H already left the row, P10).
P9 loss, one episode per link: close + sealEarly the active segment (rolls only with records); paired segments with no CAS → single at once;
   wait for in-flight CAS; B2 removeHolder(O,E,S,H) = update copies = array_remove(copies, H) where node=O and segment=any(S) and exists(live)
   returning segment (+ live count), S = CAS-committed segments H has not SEALED (spills one copy until 5b3); rows = |S| → single(S) + one
   ERROR naming H and S; live 0 → fenced; 0 < rows < |S| → fenced (sealedBy leaves S out, sweeper never deletes); B2 failed/unknown →
   failed(S) 503, retried 100 ms → 5 s; no new link until B2 committed; reconnect 100 ms → 5 s, reset at the next OPENED.
P10 W5: before REFUSE FAILED/MISMATCH for an OPENED n the holder awaits leave(O,n,H) (unconditional array_remove of its own id); a failed leave →
   ERROR, REFUSE still sent. PeerServer.listen gains SegmentRows.
P11 FLOTTILA_JOURNAL_COPY_TIMEOUT PT2S (PT0.5S..PT10S); COPIES=2 and value ≥ FLOTTILA_JOURNAL_APPEND_WAIT refuses start naming both; COPIES=1 unchecked.
P12 SegmentRows addCopy, removeHolder, leave (NO_ROWS defaults succeed as live); intake pool; no migration.
P13 Node starts the Replicator after the join with the joined epoch; closes after the journal (last SEAL out) and before the pools: drains ≤
   COPY_TIMEOUT then fails the rest 503. ONE_COPY notice stays (takeover is 5c). A lost peer leaves journal health and /ready UP (A1).
Tests: 1 JournalCopiesTest (noneIsToday, ackNeedsBoth, positionOrder, pendingUntilBoth, frozenAtFirstRecord, hookBesideTheForce, singleAndFailed,
sealEarly, fenced; scripted Replication, Q1); 2 JournalAckOrderProperties (jqwik); 3 ReplicatorStreamTest; 4 ReplicatorIdentityIT; 5
ReplicatorWindowTest; 6 ReplicatorVerdictTest; 7 ReplicatorLossTest; 8 ReplicatorRefuseTest; 9 ReplicatorReconnectTest; 10 HolderLeaveTest; 11
PgSegmentRowsCopiesIT; 12 NodeConfigCopyTimeoutTest; 13 NodeReplicationIT (two nodes). Mutants listed by the designer: ACK on one condition;
non-head pop; pending freed at force; complete before CAS; one-copy before B2; B2 without live or count check; > for ≥; newest frame judged;
window/OPEN_MAX ignored; DATA before OPENED; re-pairing a frozen segment; dial before B2; B7 at COPIES=1; NONE calling the hook.
Cut: 5b2a1 = P1–P8, P11, P12 addCopy; tests 1–6, 8, 9, 11 (addCopy), 12; not wired into Node (a loss fails waiting records 503, rows untouched).
5b2a2 = P9, P10, removeHolder/leave, P13; tests 7, 10, rest of 11, 13.
Rejected: send from the writer; block the writer on the window; pre-open waiting for OPENED; insert [O,H]; a thread per receipt; ACK before CAS;
PEER_TIMEOUT as verdict; editing V12 (Flyway checksum; say it in §4 and PgSegmentRows javadoc).
Expensive: copies-row semantics (owner [0], CAS after OPENED, fence predicate); position-order ACK incl. 503s; the key and B7; public Replication
and Journal.Copies; JournalPeerLostException; owner wire obligations (OPEN length, crc32c, window).
Conflicts: B4 age verdict for active-segment frames only vs P7 (OPEN, SEAL too); Journal javadoc :57 "forced" → "acknowledged"; CLAUDE.md mocks
vs scripted Replication (Q1).
Open: Q1 scripted Replication in tests 1–2 [yes]; Q2 short B2 row count → fail-stop [yes]; Q3 a CAS stuck in the network committing after B2
(full fix: V20 generation column) [accept, ask advisor]; Q4 JournalPeerLostException for a failed CAS too [yes]; Q5 setWriteQueueMaxSize units and
writeQueueFull/drainHandler from a virtual thread [Context7].

## CORE-5b2a advisor disposition (2026-11-14, architect) — accepted with changes
Accepted W1–W8: every link close is a loss episode (W1); S = every segment an addCopy was ever sent for, unless refused before execution, that H
has not SEALED (W2); a frozen-single segment while a Replication is set is counted (segments, bytes) and its transition logged once; Node holds
intake until the first pairing or COPY_TIMEOUT (W4); B2 compares returned segments by identity, a missing one the journal no longer holds is no
fence (W5); the verdict runs on the monotonic source NodeLease takes, never the wall Clock (W6); loss = abandon + reset, never close() (W7);
FULL/MISMATCH before OPENED is single only if no addCopy was ever sent for n, else loss (W8). A1: outbound bounded by receipts only (WINDOW + one
frame); no full()/drainHandler/setWriteQueueMaxSize, no PeerLink change; Q5 dissolves. A3: Journal.Copies is a final nested class.
Q1 yes (scripted Replication is a fake at the journal's port). Q2 yes after W5. Q3: V20 now with addCopy (`copies_gen bigint not null default 0`;
removeHolder/leave bump and return it; addCopy expects it; u = 0 with gen moved → n single). Q4 yes; message names the statement and Postgres.
B7 becomes 2 × COPY_TIMEOUT < APPEND_WAIT (R4). B4 reworded: age verdict on the repl link, progress rule on bulk. Javadoc :57, :61-64, :122-129,
§4, PgSegmentRows:25 change meaning (names kept). §4 Pools: addCopy/removeHolder on the intake pool, leave on the holder's work pool.
R3 to the gate: a two-node run asserts zero single segments. Cut (A2), three rows: 5b2a1 Journal only (P1 Replication + Copies, P2, P4; tests
1–2); 5b2a2 Replicator whole (P1b, P3, P5–P10, P12 + V20; tests 3–11); 5b2a3 Node (P11, P13; tests 12–13).

## Acceptance 5b2a1 (Journal side of the two-copy ACK)
Public in io.flottila.runtime.journal: `interface Replication` with NONE and preopened(long n), wrote(long n, long pos, List<ByteBuffer>
bytes), sealed(long n, long pos, ByteBuffer footer, long end); `JournalPeerLostException extends RuntimeException`; `Journal.Copies
Journal.replicate(Replication)` callable once (second call ISE); final nested class Copies with pair(String holder) → List<Pairing(n, header
ByteBuffer, length)>, copied(n, end), single(Set<Long>), sealEarly(n), failed(Set<Long>, JournalPeerLostException), fenced(Throwable),
singleSegments(), singleBytes().
T1 noneIsToday: without replicate() (or with NONE) every observable of today holds and no hook is called.
T2 ackNeedsBoth: with a Replication set, a record of a paired segment completes only when forced and copied(n, end ≥ its end); either alone does not.
T3 positionOrder: an append completes only after every earlier append in journal position order has (a later copied segment waits on an earlier one).
T4 pendingUntilBoth: PENDING_BYTES admission frees a record's bytes at its completion, not at the force.
T5 frozenAtFirstRecord: a segment's mode is fixed at its first reservation; pair() hands out only segments with no record reserved (the current at
   0 records, the pre-opened one); a segment reserved before any pair() is single; pair() never returns it later; length = max(segmentBytes,
   header + longest record).
T6 hookBesideTheForce: wrote(n,pos,bytes) carries exactly the bytes written at pos for paired segments only, before the force of that round;
   sealed(...) after the seal's force with the footer and end; preopened(n) after a pre-open; no hook runs while the Journal lock is held (a hook
   that calls a Copies method or appends from another thread does not deadlock).
T7 single: single(S) completes the forced records of S; a single segment while a Replication is set increments singleSegments/singleBytes and logs
   the transition to single once (one line, not per segment).
T8 failed: failed(S, e) completes the waiting records of S exceptionally with e (retryable), frees their pending bytes; later ones not in S still complete.
T9 sealEarly(n): seals the active segment n if it holds records (a no-op at 0 records); the next append goes to a new segment.
T10 fenced(t): fails the journal (Journal.fail): waiting records fail, later appends refuse.
T11 completion never runs on the writer, sealer or a hook caller thread (fresh virtual thread as today).
T12 JournalAckOrderProperties (jqwik): random interleavings of append/force/copied/single/failed: completions are a prefix-ordered by position,
   none completes before forced ∧ (single ∨ copied), pending returns to 0 at quiescence.
Docs (architect): Journal javadoc :57/:61-64/:122-129 meaning, §4 sentence, changelog. Mutants: ACK on one condition; non-head pop; pending freed at
force; NONE calling the hook; re-pairing a frozen segment; single count not incremented; hook under the Journal lock.

## Red approved 5b2a1 (architect, 2026-11-14)
JournalCopiesTest (24, T1–T11), JournalAckOrderPropertiesTest (T12, named *Test for Surefire), fakes ScriptedReplication, GatedFiles; red =
compile (Copies, JournalPeerLostException, replicate, Replication); stubs in a scratch copy: 23/24 + 1/1 error, noneIsToday[WITHOUT] green.
Resolved: failed(S) fails every waiting record of S, forced or not; singleBytes = record bytes reserved in single segments while a
Replication is set; JournalPeerLostException(String) and (String, Throwable); record Journal.Pairing(long n, ByteBuffer header, long length);
sealed() is called for paired segments only; failures are position-ordered like successes.
