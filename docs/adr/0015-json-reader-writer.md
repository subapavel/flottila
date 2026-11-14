# JSON reader and writer: jq's grammar, jq's messages by replay, a byte[] loop

Status: accepted (2026-11-11, owner, issue #11 "How do the JSON reader and writer behave?"); amends ADR-0002 (document limit, tag 7), ADR-0004 (built values from `-R` and `--stream`) and ADR-0008 (`tick(int n)`, fuel reset); closes F-3; findings F-12 and F-13; founding §2's licence sentence is read through this ADR; amended by F-21 (exact size limits); amended by F-22 (depth counting); F-24 open (whole-container copy and whitespace); amended by ADR-0030 (run state, #59); amended by spec 4 grilling round 1 (`fromjson` in spec 4, #99); amended by spec 5 round 1 (F-71 to F-76: `JvParser`'s two modes, the replay start, lenient tokens, virtual read blocks, NUL, `-R` per block, `--seq`, the main loop, writer options) and ADR-0054 (host streaming); amended by the spec 5a ticket approval (missing files)

jq-ref 1.8.2 decides what the reader accepts and what it prints. The adviser ran every rule below
on it (issue #11 round 1, commands K1–K8) and read jq-1.8.2's `jv_parse.c`, `jv_unicode.c`,
`jv_print.c`, `util.c` and `main.c`. The reader and writer live in `jq-core` (ADR-0013), in the
non-exported package `.internal.json`.

## Grammar (the same accept set on the command line and embedded)

- **Whitespace** is SP, TAB, CR and LF only. A form feed is an invalid literal
  (`[1,\f2]` → "Invalid numeric literal at line 1, column 6").
- **Values need no separator.** `[1][2]`, `""""`, `1"a"`, `"a"[1]` and `1[2]` give two values each.
  After a top-level value only `]`, `}`, `,` and `:` are errors. A number or literal directly before
  one is dropped (`1]` → "Unmatched ']' at line 1, column 2" and nothing printed; `true]`,
  `null:` too), while `1 ]`, `"a"]` and `"a",` print the value first.
- **Literals and numbers.** A token is a literal (`true`, `false`, `null`) or goes through
  decNumber's grammar (ADR-0012): `+1`, `.5`, `1.`, `007`, `2.e3` and the NaN and Infinity
  spellings are accepted (`NaN0` and `NaN00` too); `NaN1` → "Invalid numeric literal"; `nul`, `tru`,
  `truex` → "Invalid literal"; `n`, `0x10` → "Invalid numeric literal". `'a'` → "Invalid string
  literal; expected \", but got '". The fast path accepts strict JSON numbers and sets the flags of
  ADR-0012; any other token takes a slow decNumber-grammar check written from the grammar, not
  translated from decNumber's code (its ICU licence is not on the list).
- **NUL.** A raw NUL byte outside a string is always an error, at the NUL. jq 1.8.2 instead ends the
  token there (`1\x002` → `1`); this is a `DEVIATIONS.md` row. Inside a string a raw byte below 0x20
  is an error; DEL is kept.
- **Empty or whitespace-only input** is zero documents.

## Strings and BOM

- A high surrogate escape not followed by a low one → "Invalid \uXXXX\uXXXX surrogate pair
  escape", at the closing quote. A lone low surrogate becomes U+FFFD.
- **UTF-8 is classified, never rejected** (F-3). Tag 6 marks a string with an escape, DEL or a
  sequence that jq's `jvp_utf8_next` calls invalid. Decoding repeats its consumption, one U+FFFD per
  bad sequence: a bad lead byte (`80`–`c1`, `f5`–`ff`) or a stray continuation takes one byte (`c0
  80` → two U+FFFD); an overlong, surrogate or above-U+10FFFF sequence takes the whole sequence
  (`e0 80 80`, `ed a0 80`, `f4 90 80 80` → one each); a missing continuation takes the bytes before
  it.
- **BOM**: stripped only at the start of the input stream and of a `fromjson` string, and not
  counted in the column. A BOM elsewhere, a second file's included, is an invalid literal. A partial
  BOM followed by more input → "Malformed BOM" with no position; a partial BOM cut by EOF gives no
  output and rc 0.

## Limits

- **Depth** is jq's parser stack: one slot per open array or object, plus one while an object key's
  value is open (F-22). A `[` or `{` that would make it exceed 10 000 → "Exceeds depth limit for parsing at line L, column C", an ordinary jq
  error that `try fromjson catch .` catches (jq.test 2563), not a budget termination (F-13).
  `Limits.nestingDepth` sets the number. Built values have no depth limit. So 10 000 arrays
  pass, the 10 001st fails, and objects nest 5 000 deep. `--stream` has no depth limit, as jq.
- **Sizes** (ADR-0002, amended): 2^31−3 bytes per document (F-12), 2^28−1 bytes per string (quotes
  included), 2^24−1 bytes per number literal, 2^28−1 entries per container (F-21), each → "Exceeds {document|string|number|container}
  size limit for parsing at line L, column C". A slurped stream is one document, so the document
  limit covers the whole input.

## Errors: jq's text by replay

- **Text**: "<msg> at line L, column C", or "<msg> at EOF at line L, column C", plus " (need RS to
  resync)" under `--seq`. L counts newlines from the start of the input stream across documents
  and files; C counts bytes since the last newline up to the detecting byte. The command line
  prints a main-loop parse error as "jq: parse error: <text>", with no file position; an error
  raised inside the filter, `input`'s included, as "jq: error (at <file>:<n>): <text>".
- **How**: the fast reader only learns that a document is invalid. A Java translation of
  `jv_parse.c`'s token and state machine then re-reads from the start of that document with the
  line anchor of the discarded bytes, and produces jq's message, line and column. The translation
  keeps jq's MIT notice in its file header, is listed in `NOTICE`, and jq's MIT licence text ships
  in the `jq-core` jar (`META-INF/`), as the MIT licence requires (CLAUDE.md: "copied or
  translated"). Founding §2's "only files copied from jq" reads as "copied or translated". The
  error also carries the byte offset as a field (`JqSyntaxException`, ADR-0016); the text has no
  offset.
- `fromjson` adds " (while parsing '…')"; a second value → "Unexpected extra JSON values"; empty →
  "Expected JSON value".
- **Resuming after a caught `input` error** follows jq's block model: the rest of the current read
  block (through the next LF, at most 4 091 bytes) is dropped and line and column do not advance
  over it (`1 x]\n3 y]\n5` gives a second error "at line 1, column 8"). `input_line_number` counts
  per file in the same blocks.

## Input stream and modes

- **One input stream reader** serves the command-line main loop and `input`/`inputs`. Its parser
  state runs across files, so a document may span files (`printf 1 >c; printf 2 >d; jq . c d` →
  `12`); the error line counts across files too, `input_line_number` per file.
- The main document is read into the Workspace's tape A, each `input` document into tape B
  (ADR-0004). Two buffers rotate, and a region under a live `JsonDoc` is never overwritten. On
  underflow the buffer doubles and only the straddling document is read again.
- When the input is exhausted, `input` raises jq's error "break" (`printf 1 | jq input` →
  "jq: error (at <stdin>:0): break", rc 5), which `inputs` ends on.
- **Embedding** is one-value mode over the host's bytes, without a copy.
- **`-n`**: `input` reads the stream; its parse error is a catchable `FilterException`.
- **`-s`**: one document over the whole stream with a synthetic root array; empty input → `[]`.
- **`-R`**: each line is a built string, LF stripped, CR and NUL kept, invalid UTF-8 replaced;
  `-Rs` is one string, `""` on empty input.
- **`--stream`**: its own tokenizer, no tape; events are built values equal to `tostream`; events
  before an error are emitted, then rc 5. `-s --stream` slurps the events.
- **`--seq`**: RS separators; "jq: ignoring parse error: …" and resync at the next RS; a cut
  document followed by RS vanishes without a message; "Potentially truncated top-level numeric
  value at EOF" for a number cut by EOF; rc 0.
- `--stream-errors` comes later.

## Writer

- **Strings**: tag 5 is copied, under `-a` only if all ASCII. Tag 6 is decoded and re-escaped as
  `jvp_dump_string`: `\" \\ \b \t \n \f \r`, other bytes below 0x20 and 0x7F as lowercase `\u%04x`,
  `/` not escaped; under `-a` non-ASCII as `\u%04x`, astral as a surrogate pair, keys included.
- **Numbers**: as ADR-0012.
- **Whole-container copy** only in compact mode without `-S`, `-a` or `-C`, after one walk proves
  every entry is null, a boolean, tag 5, CANONICAL or a container, no object repeats a key, and the
  depth is within the print limit. Which spec ships it is ticket 17's call.
- **Print depth**: a value inside the 10 001st level prints `<skipped: too deep>`, unquoted, as does
  `tojson`; the writer keeps its stack in the Workspace.
- **Value dumps in error messages** are `jv_dump_string_trunc`: a string is first cut to 30
  codepoints; the dump keeps 25 bytes (26 with no delimiter), stepped back to a UTF-8 boundary, then
  `...` and the closing `"`, `]` or `}` (`[0,1,2,3,4,5,6,7,8,9,10,1...]`).
- **Options**: pretty with indent 2 by default; `-c`; `--indent n` for n in −1..7 (−1 is a tab, 0
  newlines without indent; otherwise "jq: --indent takes a number between -1 and 7", rc 2); `--tab`;
  `-S` sorts keys at every level in UTF-8 byte order, and `tojson` ignores it; `-r`, `-a`, `-j`;
  `--raw-output0` (a string holding NUL → "Cannot dump a string containing NUL with
  --raw-output0 option", rc 5, earlier outputs kept); `--seq`; `-C`/`-M` with `NO_COLOR` and
  `JQ_COLORS` (default `0;90:0;39:0;39:0;39:0;32:1;39:1;39:1;34`; an invalid value prints "Failed to
  set $JQ_COLORS" and keeps the default); `-e` (rc 1 for a false or null last output, 4 for none).

## Reader loop and ticks

- The reader loops over `byte[]` (`m1NoDup` in `bench/RESULTS.md` "Tape reader for milestone 1":
  34.3 ms per 10 MB, 2.1 µs per 1 KB). A writable `byte[]`-backed heap `MemorySegment` is read in place through `heapBase()` (it returns the array, with `address()` as
  the offset on a slice). Any other segment (native, mapped, read-only, or backed by another array
  type) is copied once into a `byte[]`.
  Reading eight bytes at a time comes later, measured.
- **Why `byte[]` is enough for Rail8 (R8)** (owner, 2026-11-11): R8 never hands a whole body as
  a direct buffer. A step that needs the whole document copies it once into a `byte[]` from the
  body's whole-content call, which is allowed only below the blob store's tree limit, always under
  2 GiB. Larger bodies are only ever streamed in chunks. So no `MemorySegment` loop spike is needed.
- The reader and the writer charge one tick per KiB (ADR-0008), which needs `Workspace.tick(int n)`;
  a re-read charges only new bytes. The fuel reset comes before the main document is read.

## Later: a chunked reader

A reader fed a stream of chunks (R8 reads every large body as one) is a separate later
item, not part of milestone 1. The input stream reader's rotating buffers are its starting point.

## Later: skipped subtrees

Touched fields and skipping unread subtrees stay out of milestone 1. Tag 7 is reserved for a
skipped value (span = byte length, offset = first byte, a scalar for the sibling rule). The gain is
at most 34 % of read time at 10 MB, 15 % at 100 KB and 9 % at 1 KB (Spike B, validateOnly against
tapeReused; skipAll's 58/39/31 % does not validate).

## Conformance

- JSONTestSuite at `1ef36fa0` (MIT, © 2016 Nicolas Seriot, listed in `NOTICE`) supplies inputs;
  jq-ref supplies the expected verdict and output (95 of 95 `y_` accepted, 25 of 188 `n_` accepted,
  25 of 35 `i_` accepted). The `y_`/`n_`/`i_` label is a column in `ratchet/oracle/json.txt`. The NUL
  row above makes `n_multidigit_number_then_00` differ from jq-ref; it is pinned as a deviation.
- jq's `tests/shtest` groups for the options above enter the ratchet; whether the script is copied
  under jq's notice or re-expressed as differential cases is the oracle ticket's call (#15).

## Considered options

- **An RFC-strict reader or a strict host switch:** jq.test's NaN cases fail; a switch is a second
  accept set and can come later, since it only rejects more.
- **Rejecting invalid UTF-8:** F-3.
- **Our own messages with byte offsets:** jq.test 2326–2333 and 2500 fail.
- **Replay written from behaviour only:** no notice, but it must mirror jq's check order and
  strings anyway.
- **jq's check order inside the fast loop; a resumable fast reader.**
- **A `MemorySegment` loop now:** keeps 4 GiB and zero copy for native input, but unmeasured, and
  R8 never passes a whole document as a direct buffer (owner).
- **Accepting NUL as jq 1.8.2 does:** bytes would hide behind a NUL in a host's message; jq master
  already rejects it.
- **Slurp as N documents; `-R` lines or `--stream` events on a tape; duplicate detection in the
  reader (ADR-0002); document flags; whole-container copy in pretty mode.**

## Amendment (#35 "S1.13 Strings", F-26)

- Decoding repeats jq's two steps. `found_string`'s pass writes each escape sequence's code point
  with `jvp_utf8_encode` (a lone low surrogate as its three bytes); `jv_string_sized` then replaces
  bad sequences over the whole result, raw bytes and escape output together.
- A sequence cut by the end of the string takes every remaining byte, continuation or not
  (`length = end - in`): `"\xe0\xff"` gives one U+FFFD (`i_string_truncated-utf-8`). This replaces
  "a missing continuation takes the bytes before it" above.
- Tag-6 text is decoded at each access and never stored; the tape stays read-only.

## Amendment (spec 4 grilling round 1, question 2 and M3)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

- `fromjson` is built in spec 4 on today's reader (#99): valid strict text, literal numbers, the
  BOM, "Unexpected extra JSON values" and "Expected JSON value". P8:
  `[1E+1000,"1E+1000",true,false]`,
  `[100000000000000000001,"100000000000000000001",false,false]`, `"Unexpected extra JSON values
  (while parsing '[1,2] 3')"`.
- Malformed text and lenient tokens end the run with the reader's Stub, which `try` does not catch,
  so no case passes on it. jq.test 2315, 2324, 2498 and 2563 wait for spec 5's replay.
- A result charges bytes built once, for the document it keeps. The charge is what `detach()` of
  its root would charge under ADR-0017's schedule: 8 B per array element, 16 B per object entry,
  each string's UTF-8 length, the fixed charge per container and per string, nothing for numbers.
  A parsed value so costs what the same value built costs, on both backends. A BYTES_BUILT witness
  shows it.
- It ticks as ADR-0034's spec-4 table says. A 10 000-deep document passes on a 512 KiB stack, with
  no Java recursion over its depth.

## Amendment (spec 5 round 1; F-71 to F-76)

Proposed 2026-11-14 by the grilling-adviser for the owner under the delegation of 2026-11-14.
Facts were observed on jq-ref 1.8.2 (P1–P7 and the adviser's probes), with jq 1.8.2's `jv_parse.c`,
`main.c`, `util.c` and `builtin.jq` read at the tag.

- **One translation, two modes** (F-72). `JvParser` is the one file translated from `jv_parse.c`.
  It has a replay mode and a stream mode; the stream mode is `stream_token`, a second state machine
  with its own texts. `--stream` never runs the fast reader and has no depth limit: 20 000 open
  brackets end in "Unfinished JSON term at EOF". Events before an error are emitted (`[1,[2` →
  `[[0],1]`).
- **The replay starts at the first byte after the last emitted value**, in a fresh parser state
  with jq's line, column, BOM and seq state. This replaces "from the start of that document" above.
  - A replay that accepts what the fast reader rejected throws `IllegalStateException`: a reader
    bug must not look like a malformed document.
  - The fast reader emits a top-level number or literal only if the next byte is not `]`, `}`,
    `,`, `:` or NUL (`1]` prints nothing, `1 ]` prints `1`).
  - `fastVerdictEqualsReplay` also compares how many values were emitted before the error.
- **Lenient tokens are checked at read and decoded at access.** A token ends at jq's class
  boundary (`1true` → "Invalid numeric literal at EOF at line 1, column 5"). The fast path stays
  strict, and a lenient token clears CANONICAL and FITS_LONG (`+1` → `1`, `2.e3` → `2E+3`,
  `nan` → `null`). NONFINITE is never written (F-73).
- **Read blocks are virtual.** The reader takes what the channel gives, up to 64 KiB a read, and
  never waits for a full buffer before it parses: jq-ref prints the first of two lines sent a
  second apart before the second arrives.
  - It computes block ends only for `(at <file>:N)`, `input_line_number`, `-R` and the drop after
    a caught `input` error.
  - Required: `1 x] 2 3\n4\n` under `[limit(5; repeat(try input catch .))]` →
    `[1,"Invalid numeric literal at line 1, column 4",4,"break","break"]`.
- **NUL is class INVALID**: this is master's one change to `classify`, and nothing else differs.
  1.8.2's `scan` already holds `case INVALID: return "Invalid character"`, unreached.
  - The pending token's value is dropped, as for `1]`: `123\x00` → empty stdout,
    "jq: parse error: Invalid character at line 1, column 4", rc 5 (jq 1.8.2: `123`, rc 0).
  - `fromjson`'s text is cut at the NUL, as jq's `%.*s` cuts it (`"1\u0000]"` → "… (while
    parsing '1')").
- **`-R` and `-Rs` decode each read block on its own** (F-75). `util.c` calls `jv_string_sized` per
  block, so a sequence cut by a block's end takes the block's last bytes (F-26). This replaces
  "invalid UTF-8 replaced" above. `-R` lines join across files (`5]\n6` then `1\n2\n` → `"5]"`,
  `"61"`, `"2"`).
- **`--seq`** (F-74). Its texts come from the translation. The RS split stays outside the fast loop,
  because `scan` handles RS before every state, strings included.
  - "A cut document followed by RS vanishes without a message" above holds only after whitespace.
  - `\x1e[1\x1e2` → "jq: ignoring parse error: Truncated value at line 1, column 4".
  - `\x1e"a\x1eb"` gives "Truncated value at line 1, column 4", then "Invalid numeric literal at
    line 1, column 6 (need RS to resync)".
  - Empty input also prints an ignored error, rc 0. A BOM before the first RS is accepted.
- **The main loop pulls with `Workspace.read(JsonInputs)`** (F-71, ADR-0016).
  - A parse error prints "jq: parse error: …" and stops, rc 5. Under `--seq` it prints
    "jq: ignoring parse error: …" and reads on, rc unchanged.
  - `-n` belongs to the command line: it runs once on null (`-ns .` → `null`, `-ns [inputs]` →
    `[[1,2]]`; `[input_filename, input_line_number]` → `[null,0]`).
  - Before any read the position is `(at <unknown>)`.
  - A missing file prints "jq: error: Could not open file nope: No such file or directory", and
    rc 2 ends the run (corrected at the spec 5a ticket approval, below).
  - `-s` with a parse error prints nothing, rc 5; `-s --stream` with an error prints no events;
    `-s --seq` skips bad texts and still prints its one array.
  - `path(input)` → "Invalid path expression with result 1".
- **`inputs` runs in constant depth**: it is `try repeat(input) catch …` (`builtin.jq` 189) and
  rests on 2b's tail jump (ADR-0047). Required: more documents than a small `recursionDepth`.
- **`fromjson`'s lenient and malformed cases** go through the replay in spec 5 (F-76).
- **Writer options are `OutputOptions` withers** (ADR-0016).
  - The command line folds `-c`, `--tab` and `--indent n` in argument order, and the last one wins
    (`--indent 3 -c` is compact, `-c --indent 0` → `[\n1\n]`). It maps `--indent -1` to `tab()`.
    `-M` beats `-C` in either order.
  - `-e` and `--unbuffered` stay in jq-cli. `-e` reads the last output (`true false` → rc 1;
    `select(.)` on `true false` → rc 0).
  - jq's interactions, each a required case:
    - under `-a` a string is written as JSON even when raw (`-r -a` on `"é","abc"` → `"\u00e9"`,
      `"abc"`), and `--raw-output0 -a` prints a string holding NUL;
    - `--seq` writes RS before every result except a raw string (`--seq -r '"a",1'` →
      `a\n\x1e1\n`);
    - `--raw-output0` on a string holding NUL ends with rc 5, and no `try` catches it; a non-string
      result gets its NUL too;
    - `-S` sorts only what the writer prints (`keys_unsorted` → `["b","a"]`, `tojson` unsorted);
    - one bad `JQ_COLORS` field resets every colour (`0;31:xyz` prints null in `0;90`), while fewer
      fields keep the rest and a ninth is accepted;
    - `-C` beats `NO_COLOR`, and an empty `NO_COLOR` counts as unset;
    - a raw string is never coloured, and `[]` and `{}` are coloured as one unit.
- `NOTICE` lists `JvParser` (`jv_parse.c`), the colour layout and `jq_set_colors` (`jv_print.c`),
  and the shtest translation.

## Amendment (spec 5a ticket approval: missing files)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14. Observed
on jq-ref 1.8.2 by the adviser and the architect, with `ok1` = `1\n2\n` and `ok2` = `3\n`. This
replaces "the other files still run" in the spec 5 round 1 amendment above.

- A missing file prints "jq: error: Could not open file <f>: No such file or directory".
- The run ends with rc 2, which replaces a parse error's 5 and `-e`'s code (`main.c` 704).
- With the missing file first, the main loop stops after one value: `jq -c . nope ok1 ok2` → `1`.
  The loop tests the open-failure count before each read (`main.c` 679).
- With the missing file later, the other files run: `jq -c . ok1 nope ok2` → `1`, `2`, `3`.
- `[inputs]` reads all the files that exist: `jq -nc '[inputs]' ok1 nope ok2` → `[1,2,3]`.
- jq-cli can do this with a `JsonSource` channel that opens at its first read.
