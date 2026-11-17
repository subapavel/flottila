#!/usr/bin/env python3
"""BRAND-1 commit C (+ FIX-226): the golden constants of PeerKnownAnswerTest and ReplFramesTest, recomputed outside the code under test.

Standard library only: HMAC-SHA256 from `hmac`/`hashlib`, HKDF written out per RFC 5869 (extract, then expand T(1)..T(n)),
CRC32C (Castagnoli, reflected polynomial 0x82F63B78) written out bit by bit. Inputs are the ones the tests document
(PeerKnownAnswerTest class comment and the python3 block at bothProofFramesOfAFixedExchangeAreTheKnownBytes; ReplFramesTest's
constants OWNER, JOURNAL, N, CREATED_MS, LENGTH, POS, END, DATA_BYTES, DETAIL and the segment header layout of SegmentFormat's
comment). FIX-226: DATA, RECEIPT and REFUSE are written out the same way, from the layout ReplFramesTest documents
(DATA = A . pos u64 . bytes, RECEIPT = A . end u64 . state u8, REFUSE = A . reason u8 . u16 len . detail UTF-8) and its
assertion inputs (state 2 = WRITTEN, reason 1 = FULL). None of the three frames carries the name or the magic: the old and
the new run print them identical.

Usage: python3 -I agent/work/BRAND-1-C-golden.py <name> <magic>   e.g. `flottila FJS1` (old: must reproduce the tests' constants)
       or `rail8 RJS1` (new).
"""
import hashlib
import hmac
import struct
import sys
import uuid


def H(k: bytes, m: bytes) -> bytes:
    return hmac.new(k, m, hashlib.sha256).digest()


def hkdf(salt: bytes, ikm: bytes, info: bytes, n: int) -> bytes:
    """RFC 5869 §2.2 extract (an empty salt is HashLen zeros: hmac pads the key with zeros to the block) and §2.3 expand."""
    prk = H(salt if salt else bytes(32), ikm)
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = H(prk, t + info + bytes([i]))
        out += t
        i += 1
    return out[:n]


def crc32c(data: bytes) -> int:
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
    return crc ^ 0xFFFFFFFF


def peer(name: str) -> dict:
    info = f"{name}-peer-v1".encode("ascii")
    k1, k2 = bytes(range(32)), bytes(range(0x80, 0xA0))
    dn, ln = bytes(range(0x40, 0x60)), bytes(range(0x60, 0x80))
    sub = lambda k: hkdf(b"", k, info + b" subkey", 32)                    # noqa: E731
    fp = lambda k: H(sub(k), info + b" fingerprint")[:8]                   # noqa: E731
    master_fp = lambda k: H(k, info + b" fingerprint")[:8]                 # noqa: E731

    def hello(role, node, epoch, j, nonce, fingerprint):
        return (bytes([1, role, 1]) + struct.pack(">H", len(node)) + node + struct.pack(">q", epoch) + uuid.UUID(j).bytes
                + nonce + fingerprint)

    def proofs(fingerprint, ikm):
        dh = hello(1, b"node-b", 4, "0b0b0b0b-0000-4000-8000-00000000000b", dn, fingerprint(k1))
        lh = hello(2, b"node-a", 3, "0a0a0a0a-0000-4000-8000-00000000000a", ln, fingerprint(k2))
        keys = hkdf(dn + ln, ikm, info, 128)

        def proof(pk, fk):
            f = struct.pack(">I", 57) + bytes([2]) + struct.pack(">q", 1) + H(pk, info + b" proof" + dh + lh)
            return (f + H(fk, f)[:16]).hex()
        return proof(keys[0:32], keys[64:96]), proof(keys[32:64], keys[96:128])

    dialer, listener = proofs(fp, sub(k1))
    master_dialer, master_listener = proofs(master_fp, k1)
    tc3 = hkdf(b"", bytes([0x0B] * 22), b"", 42).hex()
    tc1 = hkdf(bytes(range(0x0D)), bytes([0x0B] * 22), bytes(range(0xF0, 0xFA)), 42).hex()
    return {
        "SUB_K1": sub(k1).hex(), "SUB_K2": sub(k2).hex(),
        "FP_K1": fp(k1).hex(), "FP_K2": fp(k2).hex(),
        "MASTER_FP_K1": master_fp(k1).hex(), "MASTER_FP_K2": master_fp(k2).hex(),
        "DIALER_PROOF": dialer, "LISTENER_PROOF": listener,
        "MASTER_DIALER_PROOF": master_dialer, "MASTER_LISTENER_PROOF": master_listener,
        "RFC5869_TC3 (no label)": tc3, "RFC5869_TC1 (no label)": tc1,
    }


def repl(magic: str) -> dict:
    owner = b"node-b"
    journal = uuid.UUID("0b0b0b0b-0000-4000-8000-00000000000b").bytes
    n, created, length, end = 7, 1767225600850, 1048576, 57
    pos = 49                      # ReplFramesTest.POS (the first byte after the 49-byte header)
    data = b"ABCDEFGH"            # ReplFramesTest.DATA_BYTES
    state = 2                     # RECEIPT's state, WRITTEN (ReplFramesTest.receiptIsItsGoldenBytes...)
    reason = 1                    # REFUSE's reason, FULL (ReplFramesTest.refuseIsItsGoldenBytes...)
    detail = "journal full".encode("utf-8")  # ReplFramesTest.DETAIL
    # header: magic · version u8 1 · journal id · u16 owner length · owner · n u64 · created ms u64 · crc32c u32 of the rest
    body = magic.encode("ascii") + bytes([1]) + journal + struct.pack(">H", len(owner)) + owner + struct.pack(">qq", n, created)
    header = body + struct.pack(">I", crc32c(body))
    address = struct.pack(">H", len(owner)) + owner + journal + struct.pack(">q", n)
    file_crc = crc32c(header + data)
    return {
        "HEADER": header.hex(),
        "OPEN": (address + struct.pack(">q", length) + header).hex(),
        "FILE_CRC": f"0x{file_crc:08x}",
        "SEAL": (address + struct.pack(">q", end) + struct.pack(">I", file_crc)).hex(),
        "DATA": (address + struct.pack(">q", pos) + data).hex(),
        "RECEIPT": (address + struct.pack(">q", end) + bytes([state])).hex(),
        "REFUSE": (address + bytes([reason]) + struct.pack(">H", len(detail)) + detail).hex(),
    }


if __name__ == "__main__":
    name, magic = sys.argv[1], sys.argv[2]
    for key, value in {**peer(name), **repl(magic)}.items():
        print(f"{key} {value}")
