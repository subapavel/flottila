# CORE-5a2 — peer link changes before replication (class B, D-141)

Row: agent/BACKLOG.md CORE-5a2. Owner 2026-11-12 accepted the advisor's D-139 advice (agent/packets/CORE-5.md "Advisor on D-139", D-141).
Code: flottila-runtime/src/main/java/io/flottila/runtime/journal/peer/ (PeerHandshake HELLO layout :56-60, version check :97, length :101,
fingerprint :160-162, hkdf; PeerServer handshake bound :89; PeerConfig READS_MAX 256; NodeConfig peer keys :705-708). Known-answer tests:
flottila-runtime/src/test/java/io/flottila/runtime/journal/peer/PeerKnownAnswerTest.java.

## Decision
P1 HELLO v1 gains a channel byte after the role: 1 read, 2 repl, 3 bulk (only `read` is used in 5a2; repl/bulk are reserved for 5b and a
   listener refuses them until then with a refusal naming the channel). The proofs cover the whole HELLO as today.
P2 Version rule: a HELLO with version >= 1 is accepted; the link speaks min(own, peer) — today both are 1. Trailing bytes after the v1
   fields are tolerated (covered by the proofs) up to the HELLO cap. Version 0 or a HELLO shorter than v1 is refused.
P3 FLOTTILA_PEER_READS range 1..64 (READS_MAX 64), default 16; NodeConfig refuses 65 naming the key and 1..64.
P4 At most 8 links in their handshake per source address (PeerConfig.HANDSHAKES_PER_SOURCE 8) beside the 64 in all; a 9th from one address is
   closed at once; a slot is given back when the link is established, refused or closed.
P5 Peer subkey: K_peer = HKDF-SHA256(salt = empty, ikm = master, info = "flottila-peer-v1 subkey", 32 bytes). The fingerprint is
   HMAC-SHA256(K_peer, "flottila-peer-v1 fingerprint")[0..8]; the session keys are HKDF(salt = both nonces, ikm = K_peer, info "flottila-peer-v1").
   The master key itself is no longer an HMAC or HKDF input on the link. PeerKnownAnswerTest's literals change accordingly (recomputed outside the
   code, the python commands in its comments updated).
P6 Docs (architect): D-139 clear-metadata list and "peer port reachable by nodes only", §4 Body journal sentence, changelog row.

## Acceptance (red first, test-author)
A1 a HELLO with version 2 and trailing bytes from a peer links at version 1 (both directions); version 0 and a short HELLO are refused.
A2 a HELLO naming channel repl or bulk is refused by the 5a2 listener with a refusal naming the channel; channel read links as today.
A3 NodeConfig: FLOTTILA_PEER_READS 64 accepted, 65 refused naming the key and 1..64; the in-process ctor refuses 65 too.
A4 nine handshake connections from one source address: the 9th is closed at once while the first 8 wait; a slot returns after a refusal.
   (Loopback: one source address for every connection — the test may bind distinct 127.0.0.x sources to show the per-address count.)
A5 PeerKnownAnswerTest: the subkey, the fingerprint and both proof frames under the subkey match literals recomputed outside the code; the
   fingerprint is not a prefix of the master key nor of the subkey; a mutant using the master key directly fails.
No sleep-based timing; fake timer for timeouts.

## Red approved (architect, 2026-11-13)
API: channel = HELLO payload byte 2 (after role, before the u16 node length), 1 read / 2 repl / 3 bulk; version unsigned byte;
PeerConfig.HANDSHAKES_PER_SOURCE 8; READS_MAX 64; refusal names the channel. Fixture ScriptedPeer (self-tested vs python); RawPeer.dial(from).
Tests: PeerHandshakeVersionTest :60 :75 :89 :102 :119 :128; PeerHelloLinkTest :99 :112 :130 :143 :156 :166; PeerConfigTest :32 :42;
NodeConfigPeerTest :97 :104; PeerProtocolTest:243 (65th READ); PeerHandshakeSourceBoundTest :127 :141 :152 :165 (compile red);
PeerKnownAnswerTest :90 :101 :123 :133 :143 :153 :201 (literals recomputed; architect re-derived K_peer(K1) 29270d2b… and FP 921381e52ce9d750).
Test-author edits to implementer tests (fixture follows P2/P4): PeerProtocolTest:401 sources .2–.9; PeerHandshakeTest:59 version 3 → 0.
O1 the trailing-byte allowance stays within the existing HELLO cap (HELLO_LEN_MAX); no new constant.
O2 an unknown channel (0, 4..255) is refused naming its number; a dialer refuses a listener HELLO whose channel differs from the one dialed.
   The implementer adds its own cases for O2.
