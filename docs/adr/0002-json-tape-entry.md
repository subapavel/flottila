# JSON tape entry: one long per value

Status: accepted (2026-11-10, owner, issue #5 "What are the value model and the tape layout?"); amended by ADR-0015 (issue #11); amended by F-21 (exact size limits); F-40 amendment (#82) withdrawn 2026-11-13: over its cost bar, owner to decide; duplicate keys amended by ADR-0044 (#85); NONFINITE reserved by F-73 (spec 5)

A document's tape is one `long[]` with **one entry per JSON value, object keys included**. Each
entry is a 4-bit tag, a 28-bit span and a 32-bit offset. We chose it because it is the smallest
layout that still lets a field lookup reject most keys by length alone, and lets every container
be stepped over in one jump. Measured on the Spike B document (`bench/RESULTS.md`), it is 16.4 MB
of tape for 11.2 MB of JSON, 1.46× the input. A reused tape reads a 1 KB message in ~2.1 µs and
10 MB in ~34 ms, including UTF-8 classification and the depth limit.

- **Span**: a scalar's byte length (a string's includes its quotes), or the number of entries
  inside a container. A tag ≥ 8 has the sign bit set, so the next sibling is
  `e < 0 ? i + 1 + span : i + 1` without decoding the tag. `query-core` relies on this rule, the
  `long[]` and an `int` cursor, never on a tag's meaning (founding architecture §14).
- **Offset**: a scalar's first byte. For a container it is the **closing bracket**: the reader
  writes the entry there anyway, so the end is known at once. The start is found by stepping back
  from the first child.
- **Tags**:

  | Tag | Meaning |
  | --- | --- |
  | 0 | invalid |
  | 1 | null |
  | 2 | false (falsy ⇔ tag ≤ 2) |
  | 3 | true |
  | 4 | number |
  | 5 | string whose bytes are its value and its compact output |
  | 6 | string that must be re-encoded (escapes, DEL, invalid UTF-8, which jq turns into U+FFFD) |
  | 7 | reserved scalar: skipped value (ADR-0015) |
  | 8 | array |
  | 9 | object |
  | 10 | reserved (the F-40 distinct-key amendment was withdrawn, #82) |
  | 11–14 | reserved for other formats |
  | 15 | reserved: wide (two-long) entry |

- **Numbers**: one tag. The span holds 4 flags (FITS_LONG, CANONICAL, NONFINITE, one spare) and a
  24-bit length. Their meaning is given by ADR-0012.
- **Limits**: 2^31−3 bytes per document (F-12), 2^28−1 bytes per string, quotes included (about
  256 MiB; the owner confirmed this is enough for Rail8 (R8)), 2^24−1 bytes per number literal,
  2^28−1 entries per container (F-21). Beyond a limit the reader
  raises a jq error. The wide tag lifts these limits later if ever needed.
- **Duplicate keys are found on first touch, and the tape is read-only after the read.** `.a`
  scans to the last match. `keys`, `length`, `.[]` and the writer check again on every pass.
  jq keeps the first position with the last value: `{"x":0,"a":1,"b":2,"a":3,"x":9}` →
  `{"x":9,"a":3,"b":2}` (observed on jq-ref 1.8.2).
- The layout gives nothing extra for copying a whole container byte for byte. The writer decides
  that with one walk over the container's entries ("How do the JSON reader and writer behave?").

## Considered options

- **Two longs per value**: 32.8 MB on the same document and twice the reader's tape writes, for
  room nobody needs yet.
- **Keys fused into their value's entry**: 38 % smaller, but every key compare reads document bytes,
  key views need a second form, and a fifth tag bit halves the limits.
- **Entries for containers only**: ~85 % smaller, but every access re-tokenises bytes, against
  founding §5.
- **Container offset at the opening bracket** (the Spike B prototype): the end then needs bracket
  matching. **An extra END entry per container**: +15 % tape.
- **Duplicate detection in the reader**: +32 % at 1 KB and 100 KB, +43 % at 10 MB, measured
  (`bench/RESULTS.md`, "Tape reader for milestone 1"). That is over the owner's ~10 % line, and a
  cheaper detector still cost +56 % on a 78 841-key object.
- **Writing a duplicate-check result into the tape**: it would make the tape mutable during an
  evaluation, which matters once a document is shared.

## Amendment (ADR-0015, issue #11)

- The document limit is 2^31−3 bytes, the largest `byte[]` (F-12), not 4 GiB.
- Tag 7 is reserved for a skipped value: span = byte length, offset = first byte, a scalar for the
  sibling rule. No reader writes it in milestone 1.

## Amendment (F-40, ticket #82) — withdrawn

Withdrawn 2026-11-13 under its own cost bar: #82 measured the reader at +12 % on Spike B 100 KB, +26 % on 10 MB and 2.59x on the flat 78 841-key object (`bench/RESULTS.md` "#82" on branch `t/82-distinct-keys`, a01fda2). Tag 9 stays the only object tag; #63's per-key-compared ticks stand. #82 is `ready-for-human`: withdraw for good, accept the cost, or redesign (for example, hashing keys while they are hot during the parse). The text below is the withdrawn design, kept for that decision.

- **Tag 10 is a distinct-key object**: the reader proved its keys all different. Tag 9 is an object not proved so, which may or may not repeat a key. This replaces the reserved "known to hold a duplicate key": with an unproved tag every walker stays correct, only slower.
- **The proof runs at the object's close**, where the reader already rewrites the entry, so the tape stays read-only once the document exists. It runs only for objects of more than 8 keys whose keys are all tag 5: each key's bytes are hashed into a reused table, a slot match is settled by a byte compare, and a key that needs more than 8 probes ends the proof (tag 9), so the reader stays linear on hostile keys.
- **On tag 10**, `.[]`, length, the writer and `detach()` take each member once, and a field lookup stops at its match: `.[]` charges one tick per member, `.a` one per key scanned. Tag 9 keeps #63's scans, ticked per key compared; the writer's scan on tag 9 is ticked the same way.
- **Cost bar**: this narrower detector reverses the rejected option above only while it stays within the owner's ~10 % line on the Spike B documents (1 KB, 100 KB, 10 MB), measured with JMH against the tip before #82 and recorded in `bench/RESULTS.md` "#82", together with the 78 841-key object. Over the line, #82 stops `ready-for-human` and this amendment is withdrawn.

## Amendment (ADR-0044, ticket #85)

- **Duplicate keys are proved on the walk side**: every walk of a read object (`.[]`, `keys`, `length`, `to_entries`, the writer, Detach and the others ADR-0044 lists) begins with its key proof, about linear in the keys and never n², and takes each key once at its first place with its last value; `.a` still scans to the last match and proves nothing, the reader proves nothing, and tag 10 stays reserved (the reader-side proof of #82, +12 % at 100 KB, +26 % at 10 MB and 2.59x flat, is a considered option of ADR-0044).

## Amendment (F-73, spec 5)

NONFINITE is a reserved bit: no reader writes it, and a NaN or Infinity literal is found when its
token is decoded (ADR-0012's F-73 amendment). The span keeps its four flag bits and 24-bit length.
