# Spec 5 "JSON in full" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` (Fable) answers for the owner. Drafts:
designer (round 1). jq behaviour: jq-ref 1.8.2 probes by the architect (P1–P7) and the adviser, and
jq 1.8.2's `jv_parse.c`, `main.c`, `util.c` and `builtin.jq` at the tag; master is read, not run.

To show the owner on Monday (adviser's list): these change owner-accepted text — ADR-0016 (question
1's read method, `-n` out of `InputOptions`, question 9's withers); ADR-0018 and the glossary (the
conservative compile detaches at every read, question 7); ADR-0002/0012 (NONFINITE never written,
question 5); ADR-0015 (A1 copies a jq quirk that loses a byte, a DEVIATIONS row is the other way;
A2); ADR-0023 (the split, A3's census rows); host streaming saves memory only on an array target
(question 2). No owner-only matter arose: each newly translated jq file is MIT, under `NOTICE`.

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **The main loop pulls with `JsonDoc Workspace.read(JsonInputs)`** (a), null at the end of the
   input stream. The same `JsonInputs` in `RunOptions` feeds `input` (`[., input]` on `[1] [2] [3]`
   → `[[1],[2]]`, then "jq: error (at <stdin>:0): break", rc 5). A `JqSyntaxException` leaves it
   resumable; the command line decides as `main.c` does: "jq: parse error: …" and stop, rc 5, or
   under `--seq` "jq: ignoring parse error: …", read on, rc unchanged. `-n` leaves `InputOptions`:
   the command line runs once on null (P5: `-ns .` → `null`, `-ns [inputs]` → `[[1,2]]`).
   `JsonInputs` exports the current file name and line for `(at <file>:N)`.
2. **Host streaming hands out element documents on one reused tape** (a); outside the target the
   reader only validates (Spike B: 22–24 ms against 33–35 ms per 10 MB). Conditions: a target that
   is no array, or a missing path, falls back to a whole read and `run` (`{"a":{"x":1}} | .a[]` →
   `1`; `{} | .items[]` → "Cannot iterate over null (null)"); depth, line and column continue from
   the enclosing document; when the sink stops, the rest is not read (a DEVIATIONS line);
   eligibility is a syntactic test over spec 3's Operation facts, unproven → `needsWholeDocument`.
3. **One translated file, `JvParser`, with a replay mode and a stream mode** (a). `stream_token` is
   a second state machine with its own texts: of 20 malformed inputs probed, 8 give another text
   under `--stream` (`{"a":}` → "Unmatched '}'" against "Missing value in key:value pair").
   `--stream` never runs the fast reader and has no depth limit (20 000 open brackets end in
   "Unfinished JSON term at EOF"); events before an error are emitted (P5: `[1,[2` → `[[0],1]`).
4. **The error replay starts at the first byte after the last emitted value, in a fresh parser
   state** with jq's line, column, BOM and seq state. A replay that accepts what the fast reader
   rejected throws `IllegalStateException` (a): a reader bug must not look like a malformed
   document. Added: the fast reader emits a top-level number or literal only if the next byte is
   not `]`, `}`, `,`, `:` or NUL (`1]` prints nothing, `1 ]` prints `1`), and
   `fastVerdictEqualsReplay` also compares the count of values emitted before the error.
5. **Lenient tokens are checked at read and decoded at access** (a). A token ends at jq's class
   boundary (P3: `1true` → "Invalid numeric literal at EOF at line 1, column 5"); the fast path
   stays strict; a lenient token clears CANONICAL and FITS_LONG (P3: `+1` → `1`, `2.e3` → `2E+3`,
   `nan` → `null`). NONFINITE becomes a reserved bit: writing it would make the fast path weigh
   every exponent (`1e1000000000` is strict; P3: `1.7976931348623157e+308`).
6. **Read blocks are virtual** (a): the reader takes what the channel gives, up to 64 KiB a read,
   never waits for a full buffer before it parses (jq-ref prints the first of two lines sent a
   second apart before the second arrives), and computes block ends only for `(at <file>:N)`,
   `input_line_number`, `-R` (A1) and the drop after a caught `input` error. Required beside the
   draft's case: `1 x] 2 3\n4\n` under `[limit(5; repeat(try input catch .))]` →
   `[1,"Invalid numeric literal at line 1, column 4",4,"break","break"]`.
7. **Holds: 5a detaches every `input` document at its read; 5b adds the hold analysis to the
   optimised compile** (a, reshaped).
   - A hold is every place a value stays live across a node that may read input, that node's own
     `.` included. The draft's list missed the pipe: `input | [., input]` → `[1,2]`. Also required
     (stdin `1 2 3`): `def f(g): [g, g]; input | f(., input)` → `[1,2,1,3]`.
   - [2b, 3] "May read input" is a per-group fact computed as 2b computes may-break-out, from the
     `reads` fact spec 3 puts on Operation. No IR kind is added (one that proves needed ships its
     heap form and path variant: 2b question 4, spec 3 A2).
   - The conservative compile keeps detach-at-read for good: under "detach at every hold" both
     compiles share one hold list, and the compile-mode oracle cannot see a missed hold.
8. **The NUL line is master's one change to `classify`** (a): NUL is class INVALID, nothing else
   differs; jq 1.8.2's `scan` already holds `case INVALID: return "Invalid character"`, unreached.
   The pending token's value is dropped, as for `1]`: `123\x00` → empty stdout, "jq: parse error:
   Invalid character at line 1, column 4", rc 5 (jq 1.8.2, P1: `123`, rc 0). `fromjson`'s text is
   cut at the NUL as jq's `%.*s` cuts it (`"1\u0000]"` → "… (while parsing '1')").
9. **Writer options are withers on `OutputOptions`** (a, reshaped): `indent(int)` for 0..7,
   `tab()`, `sortKeys()`, `ascii()`, `raw(RawMode {RAW, JOIN, NUL})`, `seq()`, `color()`,
   `color(String jqColors)`; `-e` and `--unbuffered` stay in jq-cli. No `indent(-1)`: the command
   line maps it to `tab()`, and folds `-c`, `--tab` and `--indent` in argument order, the last one
   winning (`--indent 3 -c` is compact, `-c --indent 0` → `[\n1\n]`); `-M` beats `-C` in either
   order. jq's interactions, each a required case:
   - under `ascii()` a string is written as JSON even when raw (`-r -a` on `"é","abc"` →
     `"é"`, `"abc"`), and `--raw-output0 -a` prints a string holding NUL;
   - `seq()` writes RS before every result except a raw string (`--seq -r '"a",1'` → `a\n\x1e1\n`);
   - `--raw-output0` on a string holding NUL ends with rc 5, and no `try` catches it;
   - `-S` sorts only what the writer prints (`keys_unsorted` → `["b","a"]`, `tojson` unsorted);
   - one bad `JQ_COLORS` field resets every colour (`0;31:xyz` prints null in `0;90`).
10. **A frozen `malformed-v1` generator** (a), recorded whole before the replay ticket and frozen on
    a dry run with bounds written beforehand (as #58). Its forms cover GATE check 3's routes and
    modes (`-c .`, `try input catch .`, `-s`, `--seq`, `--stream`, `fromjson`). NUL only inside
    strings (ADR-0023: generators avoid deviation shapes). No `test_transform`.
11. **Diagnostic rows plus the 10 % bar** (a). The draft's rows are measured first, on the pre-spec
    reader; a ticket that changes the fast loop measures against them in one session (as #85) and
    does not merge over the bar. Added row: `inputs | .a` over 10 000 × 1 KB, in 5a and in 5b
    (ADR-0019's "detach of inputs"). No stream cell joins the gate.
12. **Split: 5a "JSON in full", 5b "holds, root use, host streaming"** (a); ADR-0023 allows it.

### Decided beyond the draft

- **A1. `-R` and `-Rs` decode each read block on its own**, as jq does (`util.c` calls
  `jv_string_sized` per block). For `A E2 z z` with E2 at offset 4089 of a line jq-ref prints
  `A�z` (the cut sequence takes the block's last byte, F-26's rule), on a short line
  `A�zz`; 2 018 of 9 304 probed (bytes, offset) pairs differ so; a JSON string does not.
- **A2. `--seq` texts come from the translation; the RS split stays outside the fast loop** (`scan`
  handles RS before every state, strings included). ADR-0015's "a cut document followed by RS
  vanishes without a message" holds only after whitespace: `\x1e[1\x1e2` → "jq: ignoring parse
  error: Truncated value at line 1, column 4". Empty input also prints an ignored error, rc 0.
- **A3. `fromjson`'s lenient and malformed cases belong to 5a.** Spec 4 builds it for strict input;
  jq.test 2315, 2319 (its input is `{"a":nan}`), 2324 and 2498 move to spec 5 in the census.
- **A4. `inputs` runs in constant depth**: it is `try repeat(input) catch …` (`builtin.jq` 189) and
  rests on 2b's tail jump. Required: more documents than a small `recursionDepth`.
- **A5.** `$__prog_args` is not in jq 1.8.2 (P7: "$__prog_args is not defined", rc 3) and leaves
  spec 7's list. A missing input file is 5a's: "jq: error: Could not open file nope: No such file
  or directory", the other files still run, rc 2.

### Probe facts for the tickets (jq-ref 1.8.2, adviser)

- Position: `(at <unknown>)` before any read (`jq -n --raw-output0 '"a\u0000b"'`); `-n` gives
  `[input_filename, input_line_number]` → `[null,0]`.
- Files: a document spans files (`[4,\n` then `5]\n6` → `[[4,5],"d",1]`); `-R` lines join across
  files (`5]\n6` then `1\n2\n` → `"5]"`, `"61"`, `"2"`).
- Slurp: `-s` with a parse error prints nothing, rc 5; `-s --stream` with an error prints no events;
  `-s --seq` skips bad texts and still prints its one array (`[1,2,3,4]` after two ignored errors).
- `-e`: `true false` → rc 1; `select(.)` on `true false` → rc 0.
- `--indent`: 0 after `-c` is pretty with no indent; `--indent -1` prints as `--tab`.
- Colour: `-C` beats `NO_COLOR`; an empty `NO_COLOR` is unset; fewer `JQ_COLORS` fields keep the
  rest and a ninth field is accepted; a raw string is never coloured; `[]` and `{}` are coloured as
  one unit.
- `--raw-output0`: a non-string result gets its NUL too.
- `--seq`: `\x1e"a\x1eb"` gives "Truncated value at line 1, column 4" and then "Invalid numeric
  literal at line 1, column 6 (need RS to resync)"; a BOM before the first RS is accepted.
- Path mode: `path(input)` → "Invalid path expression with result 1".

### Ticket shape (approved by the adviser for the owner): 18 (11 class C, 7 class B)

5a (15): 1. malformed-v1 (B); 2. reader diagnostics, baseline first (B); 3. lenient tokens;
4. `JvParser` replay with the NUL line and the position-less texts, closing #76; 5. the input
stream reader: `read(JsonInputs)`, several values, read blocks, files; 6. `input`, `inputs`,
`input_filename`, `input_line_number`, tape B, resume, detach at read [2b]; 7. command line: files,
`-n`, `-e`, rc over several inputs (B); 8. `-s`, `-R`, `-Rs`; 9. `--stream` [3]; 10. `--seq` in and
out; 11. the withers with `--indent`, `--tab`, `-S`, `-a`, closing #98; 12. `-r`, `-j`,
`--raw-output0` (B); 13. `-C`, `-M`, `NO_COLOR`, `JQ_COLORS` (B); 14. `fromjson` through the replay
[4] (B); 15. shtest groups translated and recorded (B, raised from A: it judges which groups drop
or wait for spec 7). Only 6 [2b], 9's `tostream` property [3], 14 [4] and parts of 15 need
another spec.

5b (3, all C): holds, with its `explain()` column; root use with `needsWholeDocument` [3];
`stream()` with the fallback, DEVIATIONS' `test:` rows and `streamAllocationFlat`.

### Records to write

- New ADR: host streaming and root use (question 2); amends ADR-0004, 0007, 0016; DEVIATIONS
  "Streaming" gains the sink-stop line. Glossary: **Element document** (the document one streamed
  element is read into, on the Workspace's reused element tape, with its root at index 0; it goes
  stale when `rest` returns for that element. _Avoid_: chunk, item).
- Amend ADR-0015 (questions 3, 4, 6, 8, 9, A1, A2), ADR-0016 with ADR-0024 (1, 9), ADR-0002 and
  ADR-0012 (5), ADR-0007 (7), ADR-0018 and the glossary's "Conservative compile" (7), ADR-0019
  (11), ADR-0020 (10), ADR-0023 with GATE's table (12, A3, A5).
- Findings: no exported pull for main documents; `--stream`'s own texts; NONFINITE never written;
  the `--seq` cut text; `-R` per read block; `fromjson`'s census rows; the pipe as a hold.
- DEVIATIONS "NUL": question 8's lines; the reason says 1.8.2 already holds the INVALID branch and
  master's `classify` reaches it (source read, not run).
- NOTICE: `JvParser` (`jv_parse.c`), the colour layout and `jq_set_colors` (`jv_print.c`), shtest.

### Open

- Round 2: not needed for 5a before `/to-spec`. 5b needs one once spec 3's round 2 and 2b's
  analysis tickets are fixed: the hold sites over 2b's frames and closures, and the root-use test.
  Done 2026-11-14: "Round 2" below.
- Missing facts: the fast loop's cost with the token-boundary compare and several values per
  stream (tickets 3, 5); the cost per `input` document of detach-at-read (ticket 6's row);
  `jq_set_colors`' validity rule (`jv_print.c`, ticket 13); other spec-4 census cases needing a
  lenient token or a replay text (census sweep); colour by default on a terminal as `main.c` does
  (the JDK call by `javap`; no differential case reaches it).

## Records round (architect, 2026-11-14)

The records designer drafted ADR-0054 (host streaming), F-71 to F-77 and the amendments of round
1. The architect settled:

1. **Census.** Spec 4's M3 named jq.test 2315, 2324, 2498 and 2563; this round's A3 named 2315,
   2319, 2324 and 2498. Spec 5 takes all five (F-76, ADR-0023).
2. **Shared files.** Specs 4 and 5 amend the same ADRs. Spec 5's status additions sit at the end of
   each status line, and its amendment sections after spec 4's.

For the owner on 2026-11-16, in addition to round 1's list:
- founding §6.7 says "each with its own small tape"; ADR-0054 uses one reused element tape;
- F-77 replaces ADR-0007's owner-accepted hold list, not only ADR-0018's and the glossary's text.

Open for the tickets: what `Workspace.read(JsonInputs)` returns under `-R`, `-Rs` and `--stream`;
where a read after a `JqSyntaxException` starts outside `--seq`; the names of `JsonInputs`'
position accessors; bad `indent` and `JQ_COLORS` values; whether today's reader writes NONFINITE;
whether `malformed-v1` gets a GATE check 2 minimum.

## Ticket approval (grilling-adviser for the owner, 2026-11-14)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14. 5a's 15
tickets are approved for publishing with the changes below; 5b is not published yet (6f). The
architect re-ran the probes on jq-ref 1.8.2; where the two differ, the architect's run stands.
1. Order and edges stand as tabled, with three changes: S5a.9 names the one 3a ticket that makes
   `tostream` pass; T6, T9 and T14 publish without `ready-for-agent` until their spec 3a and spec 4
   blockers have issue numbers; T7 ← T4 and T7 ← T11 are serialising edges (one jq-cli main loop
   and option parser). S5a.5 before S5a.4 and S5a.7 before S5a.6 are approved.
2. ADR-0016 gains "Amendment (spec 5a tickets)": `InputPosition(String fileName, long line)`,
   what `read(JsonInputs)` returns per mode, the read after a `JqSyntaxException`, bad arguments.
   The architect's run corrected its `-s` sentence: `1 x] 2\n3\n` under `-s` prints no array,
   "Invalid numeric literal at line 1, column 4", rc 5.
3. `malformed-v1` (S5a.1): more mutations, 1 800 seeds (300 per form, `seed % 6`) and bound floors
   written beforehand. The diagnostics (S5a.2) take round 1's list (question 11). T5 carries the
   NOTICE line for `jq_util_input_read_more` (`src/util.c`); T8 extends it (a ticket matter).
4. Classes confirmed: S5a.1, S5a.2, S5a.12, S5a.13 and S5a.15 are B. A public name or behaviour
   outside ADR-0016 raises S5a.12 or S5a.13 to C by a ticket comment.
5. `--unbuffered` belongs to S5a.7 (the main loop flushes after each result, `main.c` 207).
   `malformed-v1` has GATE check 2's minimum of 200 per form after drops (F-78, ADR-0023).
6. Changes before publishing:
   - a. **Correction to round 1, question 3** (and F-72): the two texts were swapped. `{"a":}`
     under `--stream` → "Missing value in key:value pair at line 1, column 6"; without it →
     "Unmatched '}' at line 1, column 6", rc 5 both (`jv_parse.c` 396 and 221).
   - b. **Correction to round 1, A5**: a missing file does not let every other file run. With
     `ok1` = `1\n2\n`, `ok2` = `3\n`: `jq -c . nope ok1 ok2` → `1`, rc 2 (the main loop tests the
     open-failure count before each read, `main.c` 679); `jq -c . ok1 nope ok2` → `1`, `2`, `3`,
     rc 2; `jq -nc '[inputs]' ok1 nope ok2` → `[1,2,3]`, rc 2. Rc 2 replaces a parse error's 5 and
     `-e`'s code (`main.c` 704). ADR-0015 is amended.
   - c. `input_filename` on stdin is `"<stdin>"` (`echo 1 | jq -c '[input_filename,
     input_line_number]'` → `["<stdin>",1]`); only `-n` gives `[null,0]`. T5 drops "null on stdin".
   - d. **Correction to round 1, question 9**: `-r -a` on `"é"` prints `"é"` (architect's
     probe), as ADR-0015 already says. T12's red says so.
   - e. T6's F-77 cases and `path(input)` hold only under `-n`. Without it,
     `def f(g): [g, g]; input | f(., input)` on `1 2 3` → "jq: error (at <stdin>:0): break", rc 5,
     no output; with `-n` → `[1,2,1,3]`, rc 0.
   - f. 5b's spec issue and tickets are not published before round 2 (WORKFLOW §3 step 1 wants the
     grilling frontier empty first). Its shape is round 2's input; U2 and U3 gain "Part of".

Missing facts: `jq_set_colors`' validity rule (T13 reads `jv_print.c`); the issue numbers of the
3a Facts ticket, the 3a `tostream` ticket and S4.3; the fast loop's cost with the boundary compare
(T2, then T3 and T5); a BOM in a second file (T5 records it).

Note from spec 3's ticket approval (2026-11-14), proposed by the grilling-adviser for the owner
under the delegation of 2026-11-14, for 5a's tickets: the output-options ticket records `debug`
under `-a`, `-S` and `-C` (3a's S3a.3 records one case per flag the command line has); spec 5b's
hold-run ticket records its licence fact: `licenceFacts` gains
`reduce range(n) as $i ([]; . + [input])` (round 2, question 10, by the grilling-adviser for the
owner under the delegation of 2026-11-14; it was the `input` ticket's).

## Round 2 (spec 5b: holds, root use, host streaming)

Answered 2026-11-14 by the grilling-adviser for the owner under the delegation of 2026-11-14, for
the owner's review on 2026-11-16. Draft: designer. jq behaviour: jq-ref 1.8.2 (`--version` →
jq-1.8.2), the architect's probes P1–P13 and about 45 of the adviser's. The architect re-ran B, C,
S5b.2's 15 guard values, the two wrap cases, P13's `repeat` and `.items[] | del(.a)`, and
confirmed them. Nothing of jqj was built or run, so every jqj statement is design.

### Four gaps in the draft (they change the answers)

- A. **`reads` has two meanings (F-79).** S3a.2 (#154) and S3a.3 (#155) set `reads` true for
  getpath, setpath, delpaths, `debug` and `stderr` ("shares storage with its input"). F-77,
  ADR-0007 and S5a.6 (#144) use it for "reads the input stream". Left alone, every `del`, `pick`,
  `tostream` or `debug` is a reading node and `.items[] | del(.a)` reads whole. Spec 5's meaning
  stands and the five are false. In #152–#168 only #154 and #155 give the fact a value.
- B. **A resumed generator is a hold the two site kinds miss.** `(input | .[]) | [., input]` on
  `[1,2] 10 20` → `[1,10]`, `[2,20]`. `.[]` walks document 1 after its consumer read document 2,
  and no node whose `.` is document 1 has a reading child.
- C. **A Path key is a keeper.** `input as $x | {} | .[$x] |= input` on `"a" 7 8` → `{"a":7}`. The
  left side does not read, so question 4(c) left the key a view across the right side's read.
- D. **Questions 1 and 4(d) disagree (F-80).** Question 1 copies only input-document views; 4(d)
  and ADR-0022 need the state's view flag clear. With `. + [[$m.a, input]]` the inserted value
  keeps a main-document view, so the flag stays set.

### Answers

1. **Hold sites: an entry site and a store site per node, two facts, no IR kind** (accepted, with
   three changes). Entry: a node detaches its `.` on entry when it may read `.` after a node that
   may read. Store: a keeper detaches a value as it keeps it when it lives across a node that may
   read. A hold's detach copies only input-document views.
   - An emit to a consumer that may read counts as a node that may read (B). The consumer fact is
     joined over a function's call sites and a closure's `ParamCall` sites; its safe value is true.
   - Keepers also include pattern and item slots and a DYNAMIC key entering a Path or walk state
     (C).
   - A hold's detach copies input-document views and leaves main-document views. A state that an
     in-place site wrote is not detached again at its state hold (D).
   - Cost: a second, top-down bit per function and closure parameter. A coarser fact (true inside
     every function of a program that reads) is legal; it copies each document once in filters
     like `inputs | [..]`, as 5a does.
2. **A closure argument that reads makes its call site a hold; inside the callee a `ParamCall` may
   read only if some call site, tail jumps included, passes a reading closure** (accepted). A
   Call's value cells are slots bound at the callee's entry. Cost: one bit per (function, closure
   parameter); one reading call site makes the parameter read at every site.
3. **Hold sites are IR facts, so a heap form detaches wherever its stack form does; a suspension
   and a pending jump add no site** (accepted). `holdsAgreeOnTheDriver` runs the hold cases
   optimised with `stackBytes` 0. Cost: the hold table runs once more with `stackBytes` 0.
4. **Holds against spec 3's owner, Update, PathOf and 3b's licence** (accepted, with changes to (c)
   and (d)).
   - (a) Detaching a built container whose view flag is clear returns it, owner kept. (b) An
     Update's rebuild is a store site and its own `.` an entry site. Both as drafted.
   - (c) Add the `key` site. A DYNAMIC key is detached as it enters a Path or walk state when its
     PathOf body or its Update (either side) may read. This is a static flag, so it costs nothing
     where nothing reads. #162 is not edited: U1a adds the TRACKED rule. Cost: `.[input] |= f`
     loses WALK and the licence.
   - (d) With D's rule. #167's bullet "Inserted values detached in a state hold" moves to U1b,
     because 3b has no hold fact.
5. **The root-use test is a spine of constant KEY and INDEX steps ending in an ALL, then a `rest`
   that does not may-read and uses no outer slot but a parameter slot** (accepted, sharpened). The
   test runs over main's flattened top-level Pipe chain, and the spine ends at the first ALL. So
   `.items | .[] | . + 1` (jq-ref: `2`, `3`) streams like `.items[] | …`. Costs are as drafted.
6. **A top-level PathOf, Update, Bind, Reduce, Foreach, Label or Collect reads whole; PathOf and
   Update inside `rest` stream** (accepted). `.items[] as $x | f` reads whole because `f`'s `.` is
   the root: `.items[] as $x | [$x, (.items|length)]` → `[1,2]`, `[2,2]`.
7. **`explain()` gains `reads=` and `detach=`, and a program line for root use** (accepted).
   `detach=` gains `key`; `rootUse` has two values.
8. **What catches a missed hold** (changed). Keep `holdAnalysisNeverMissesAHold`, no recorded
   generator, ADR-0020 unchanged. A stale view on a required recorded case already fails the
   differential oracle, so the gap is cases, not the verdict. The table is a hand list again, and
   B and C show what one misses, so two tests are added:
   - `holdTableCoversEveryKind`: the table has a row for every permitted `Node` record ×
     Node-typed component that can read, so a new kind fails until it has a row.
   - `insertedReadsNeverGoStale`: each jqtest, differential and generated program P runs under
     `-n` as `input | P'`. P' wraps one seeded sub-expression X as `((input|empty), X)` or
     `(input as $r | X)`; stdin is the case's document, then filler documents. Optimised never
     ends in STALE_VIEW and agrees with conservative.
   - Cost: a splice by source span in the oracle module and one more pass over the cases. It needs
     no jq-ref recording and no GATE minimum.
9. **The reds** (changed, see "Ticket shape" below). Most of the draft's "reds" pass before the
   ticket, because 5a detaches at every read. They are guards.
10. **5b depends on 3b through U1b only (S3b.3)** (accepted). After the split, root use and
    `stream` do not wait for 3b. The `. + [input]` licence fact moves from S5a.6 to U1b: #144 has
    no such line and no edge to S3b.2.

### Conflicts

- 1 (`ParamCall` joins one bit per parameter instead of "any ParamCall may read"), 2 (parameter
  slots pass "uses no outer slot"), 4 (path shape moves toward TRACKED) and 5 (F-77's cases under
  `-n`): accepted.
- 3 (Root use): two values, ELEMENTS and WHOLE. Cost: a filter that ignores its input reads whole;
  a third value can be added later.

### Probe facts that pin the answers (jq-ref 1.8.2, all under `-n` unless marked)

- Question 1, entry site (P1): `input | (input, .)` on `1 2` → `2`, `1`.
- Question 1, store sites: `input | [., input]` on `1 2 3` → `[1,2]`, and
  `def f(g): [g, g]; input | f(., input)` → `[1,2,1,3]` (P2); `try error(input) catch (input, .)`
  on `1 2` → `2`, `1` (P12).
- Gap B: `(input | .[]) | [., input]` on `[1,2] 10 20` → `[1,10]`, `[2,20]`. Gap C:
  `input as $x | {} | .[$x] |= input` on `"a" 7 8` → `{"a":7}`.
- Question 2: `def r(f): if . >= 2 then [f] else [f, (.+1 | r(f|.))] end; 0 | r(input)` on
  `1 2 3 4` → `[1,[2,[3]]]` (P3, a real closure); `def f($v): if . == 0 then (input | [$v, .])
  else (. - 1 | f($v)) end; input as $a | 1 | f($a)` on `10 20` → `[10,20]` (P4, a value
  parameter); `def r(f): if . > 0 then f else (.+1 | r(f|.)) end; input as $a | 0 |
  r(input | [$a, .])` on `1 2 3` → `[1,2]` (P5, a captured slot).
- Question 4: `input | .[] |= input` on `[0,0] 1 2` → `[1,2]` (P6); `{"a":1} | .[input] |= input`
  on `"a" 7` → `{"a":7}` (P7); `[path(.[input][input])]` on `"a" "b"` → `[["b","a"]]` (P8);
  `reduce range(n) as $i ([]; . + [input]) | length` over `seq n` takes 0.074 s, 0.144 s and
  0.287 s for n = 100 000, 200 000 and 400 000, linear (P9).
- Question 5: `.items | .[] | . + 1` → `2`, `3`.
- Question 6: `.items[] as $x | [$x, (.items|length)]` → `[1,2]`, `[2,2]`. Without `-n`, on
  `{"items":[{"a":1},{"a":[2]}]}`: `.items[] | [paths]` → `[["a"]]`, `[["a"],["a",0]]`, and
  `.items[] | .a |= 1` → `{"a":1}`, `{"a":1}` (P10). On `{"items":[1,2]}`, `.items[] | error` →
  "jq: error (at <stdin>:0) (not a string): 1", rc 5 (P11).
- Question 8, both wraps keep jq's result: `input | [path((input|empty), .[0])]` → `[[0]]`;
  `input | (.[] | ((input|empty), .)) |= . + 1` on `[5,6] 0 0 0 0` → `[6,7]`;
  `input | .[] |= (input as $r | . + 1)` → `[6,7]`.
- S5b.3: `.items[] | del(.a)` → `{"b":2}`, so `del` does not read (gap A).
- P13, settled by behaviour: `[limit(4; 1 | repeat(. * 2))]` → `[2,2,2,2]`, which fits jq 1.8.2's
  `def _repeat: exp, _repeat;` (`src/builtin.jq`), so `.` in `inputs` is the caller's `.` at every
  level.

### Ticket shape (approved by the adviser for the owner): 4, all class C

U1 splits into facts and run, as S3a.10/S3a.11 and S3b.2/S3b.3 do.

- **S5b.1 (U1a) "May-read and hold facts, shown through explain()".**
  - Builds: F-79's meaning; may-read with the parameter bit; the consumer fact; the site facts;
    the TRACKED rule; `reads=` and `detach=` (`in`, `slot`, `state`, `elem`, `cell`, `payload`,
    `leaf`, `key`, `read`). Conservative shows `reads=true` and only `detach=read`. 5b enters
    `SPEC_ISSUES`. The run does not change: `input` still detaches at its read.
  - Red: `mayReadFacts` (P3–P5: the parameter reads; `def f(g): [g, g]; [f(1), input]`: `g` does
    not); `holdFacts` (S5b.2's table at the explain seam, with B's and C's filters);
    `pathShapeFacts` row `.[input] |= 1` → TRACKED; `onlyInputReads`; `explainShowsHolds`.
  - Blocked by: S3a.1, S3a.10, S5a.6, S2b.9, S2b.10, S2b.12, S2b.14.
- **S5b.2 (U1b) "Holds at run in the optimised compile".**
  - Builds: the optimised `input` stops detaching at its read, outside path contexts; the sites
    detach in the push form, the value form and the heap forms; D's detach; the in-place sites'
    detach; the `inputs | .a` row.
  - Red: `inputsDotADetachesNothing` (BYTES_BUILT below one document's charge; `[inputs]` keeps
    S5a.6's charge) and the bench row.
  - Properties: `holdTableCoversEveryKind`, `holdAnalysisNeverMissesAHold`,
    `insertedReadsNeverGoStale`, `holdsAgreeOnTheDriver`, `reduceInputStaysLinear` (10⁵,
    BYTES_BUILT, also with `[[$m.a, input]]`); `licenceFacts` gains `. + [input]`.
  - Guards, recorded under `-n` in all four runs (stdin → jq-ref): F-77's two, P1–P12, B's and C's
    filters, and:
    - `def f: input | .[]; [f | [., input]]` `[1,2] 10 20` → `[[1,10],[2,20]]`
    - `reduce (input | .[]) as $x (0; . + $x + input)` (same stdin) → `33`
    - `[foreach (input | .[]) as $x (0; . + $x + input)]` (same stdin) → `[11,33]`
    - `input | (.a + input)` `{"a":1,"b":5} 2` → `3`; `input | (input + .b)` → `7`
    - `input | {x: .a, y: input, z: .b}` `{"a":1,"b":5} 2 3` → `{"x":1,"y":2,"z":5}`
    - `input | "\(.a)-\(input)-\(.b)"` `{"a":1,"b":5} "k"` → `"1-k-5"`
    - `input | .[.k] |= input` `{"k":"a","a":0} 7` → `{"k":"a","a":7}`
    - `input | [paths(input | true)]` `[1,[2]] 5 6 7 8` → `[[0],[1],[1,0]]`
    - `input | [.a[] as $x | input | [$x, .]]` `{"a":[1,2]} 5 6` → `[[1,5],[2,6]]`
    - `[input, input] as [$a, $b] | input | [$a, $b, .]` `1 2 3` → `[1,2,3]`
    - `input | label $out | .[] | (input, .)` `[3,4] 10 20` → `10`, `3`, `20`, `4`
    - `input | .a += input` `{"a":[1]} [2] [3]` → `{"a":[1,2]}`
    - `def f(g): [g, input, g]; f(input)` `1 2 3` → `[1,2,3]`
    - `input | [limit(2; .[]) | . + input]` `[3,4] 10 20` → `[13,24]`
  - Blocked by: S5b.1, S3b.3, S3a.9, S3a.11, S2b.4, S2b.11.
- **S5b.3 (U2) "Root use and needsWholeDocument".**
  - Red `rootUseFacts`: the draft's two lists and the conservative line.
    - Streams: `.[]`, `.items[]`, `.a.b[0][] | select(.x)`, `.items[] | select(.id == $id)`,
      `.items[] | .a |= 1`; added `.items | .[] | . + 1`, `.items[]? | .a`, `.items[] | del(.a)`
      (jq-ref `{"b":2}`), `.items[] | debug`.
    - Reads whole: `.items[] | input`, `.items[] | r(input)`, `. as $x | .items[] | $x`,
      `[.items[]]`, `.items[-1][]`, `.[.k][]`, `path(.items[])`; added `.items[] as $x | $x`,
      `first(.items[])`, `.items[], .more[]`.
    - The conservative compile reads whole on every filter above.
  - Also `explainShowsRootUse`. Blocked by: S5b.1.
- **S5b.4 (U3) "Host stream(): element documents, fallback, DEVIATIONS rows,
  streamAllocationFlat".**
  - Red: as the draft's question 9, with `streamErrorPayloadDetached` (P11). `streamedEqualsWhole`
    runs on roots `{"items":[d1,…,dn]}` built from values-v1 documents, plus a non-array root and a
    missing-path root, and includes a `rest` that raises on a later element.
  - Blocked by: S5b.3, S5a.4, S5a.5.

### Publishing

5b may be published after three things; there is no round 3.
- The records below are written.
- #154, #155, #167 and #164 are corrected before a writer takes S3a.2 or S3a.3.
- The designer's redraft of the four tickets passes one ticket approval, as 5a's and spec 3's did.

Every blocker has an issue number (#153–#168 exist), so the tickets carry `ready-for-agent`.

### Records written

- ADR-0007 and ADR-0054: "Amendment (spec 5 round 2)" (may read input with the parameter bit, the
  emit rule, hold sites, a hold's detach, path shape; the root-use test with two values).
- GLOSSARY: "Operation", "May read input", "Root use", "Hold".
- F-79 (major): `reads` means the input stream; ADR-0005, spec 3's ticket approval item 2.
- F-80 (minor): ADR-0022 "Detach and budgets", first bullet.
- ADR-0023: 5b has 4 tickets. F-77 points to this round. The note above on 5a's `input` ticket
  now names spec 5b's hold-run ticket.
- ADR-0018, 0020, 0045, 0046, 0047 and 0048 are unchanged.
- On GitHub (not in this commit): #154 and #155 bodies say `reads` false (F-79); #167's bullet
  moves to S5b.2 by a comment; #164's "whose `input` ticket records its licence fact" becomes
  "spec 5b's hold-run ticket records its licence fact".

### Open

- The time of `insertedReadsNeverGoStale` over the cases. S5b.2 measures it; above the gate
  target it samples by seed.
- Whether a detach walk ticks per element visited. If not, the mixed-view linear red bounds bytes
  only, not a walk. Also whether a container header has a free bit for "holds an input-document
  view". S5b.2 reads the value-model code.
- What the joined consumer fact buys over the coarse one. S5b.2's `inputs | .a` row and one
  `inputs | [..] | length` row show it.
- Whether `$ENV` and named arguments are parameter slots. S5b.3 records one case each.

## Ticket approval, spec 5b

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, for the
owner's review on 2026-11-16. Spec 5b's body and S5b.1–S5b.4 are approved with the changes below.
The architect re-ran every new jq value on jq-ref 1.8.2 and confirmed it.

Changes (exact text):
- C1, S5b.1 Red, `mayReadFacts`, fourth bullet → "`def f(g): if . > 0 then . - 1 | f(g|.) else
  [g, g] end; [1 | f(1), input]`: a `ParamCall` `g` remains and shows `reads=false`, although the
  program reads (jq-ref on stdin `7`: `[[1,1],7]`). The closure is passed on as `g|.`, as P3 and
  P5 do, because specialisation removes a closure passed on unchanged (GLOSSARY "Specialisation",
  #117's red). `def f(g): [g, g]; [f(1), input]` stays as a row: no `ParamCall` is left."
- C2, S5b.1 Red, `holdFacts`, the ParamCall-site proposal → "The consumer bit at a ParamCall site:
  in `def m(f): if . > 0 then . - 1 | m(f|.) else f | [., input] end; 1 | m(input | .[])`, the
  closure's `.[]` gets `in` (jq-ref on `[1,2] 10 20`: `[1,10]`, `[2,20]`)."
- C3, S5b.1 What to build, site facts, last sentence → "`read` marks an `input` that a PathOf body
  or an Update's left side can reach, through keys, calls and closure arguments too; a marked
  `input` detaches at every read, the safe value."
- C4, S5b.1 Red, `onlyInputReads` → "`onlyInputReads` (*guard*): no operation but `input` has
  `reads` set; `input_filename` and `input_line_number` are not pure, with `reads` false (#144's
  comment)."
- C5, S5b.2 Red, third bullet → "The `licenceFacts` row `. + [input]`, licensed, is red if the
  tip's licence treats `input`'s `.` as an escape; this ticket then builds ADR-0022's `reads`
  sentence in "Borrows". Otherwise it is a *guard*. Either way `licenceFacts` shows no licence for
  `(. + [input]), (. + [0])`."
- C6, S5b.2 Guards: the "recorded" values are `3` and `7`; `"1-k-5"`; `[[1,5],[2,6]]`; `[1,2,3]`.
  Add: "`reduce (1,2) as $x ([]; (. + [input]), (. + [0]))`, stdin `7 8` → `[0,0]`" and "without
  `-n`, `. as $m | reduce (1,2) as $i ([]; . + [[$m.a, input]])`, stdin `{"a":5} 7 8` →
  `[[5,7],[5,8]]`".
- C7, S5b.2 `insertedReadsNeverGoStale`, add: "A case with no document takes `null`; the filler is
  the case's document repeated; cases under `-s`, `-R`, `--stream` or `--seq` are skipped. A run
  that ends in the splice's own "break" is repeated with more filler. The ticket posts the counts
  of skipped cases and of wraps jqj refuses to compile. jq-ref keeps paths under both wraps:
  `input | [path((input|empty), .[0])]` and `input | [path(input as $r | .[0])]` on `[5] 0 0` →
  `[[0]]`; `input | .[] |= (input as $r | . + 1)` on `[5,6] 0 0` → `[6,7]`."
- C8, S5b.2 Acceptance, fourth box → "The value-model comment is posted. It also says what bounds
  a hold's detach walk over a built container that holds only main-document views: the walk copies
  nothing, and ADR-0004 ticks per KiB of detach. If nothing bounds it, that is a finding settled
  before merge."
- C9, S5b.3 `rootUseFacts`: ELEMENTS gains "`def r(f): if . > 0 then f else (.+1 | r(f|.)) end;
  .items[] | r(.a)`, a real closure that does not read (jq-ref on `{"items":[{"a":3}]}`: `3`)".
  WHOLE gains "`.items[] as $x | [$x, (.items|length)]` (jq-ref on `{"items":[1,2]}`: `[1,2]`,
  `[2,2]`; the body's `.` is the root)".
- C10, S5b.4: the fallback bullet gains "; a spine step that meets null or a value of the wrong
  kind counts as a missing path". `streamedEqualsWhole`'s fixed rows → "Fixed rows with jq-ref's
  values: `.a[]` on `{"a":{"x":1}}` → `1`; `.items[]` on `{}` → "jq: error (at <stdin>:0): Cannot
  iterate over null (null)", rc 5; `.a.b[0][]` on `{"a":{"b":{"x":[1]}}}` → "Cannot index object
  with number (0)", on `{"a":[1]}` → "Cannot index array with string ("b")", on `{"a":{"b":[]}}` →
  "Cannot iterate over null (null)", rc 5 each; `.items[]? | .a` on `{"items":7}` → nothing, rc 0."
- C11, spec body, "Required at the spec gate", first bullet → "F-77's two cases, P1–P8, P12 and
  S5b.2's guard table, under `-n`, passing in ADR-0020's four runs: optimised, conservative,
  copy-only and trampoline (the conservative compile with `stackBytes` 0). A `left out:` row on one
  of them fails the gate. The optimised compile's heap forms are covered only by
  `holdsAgreeOnTheDriver`;". "Under the delegation" gains "ADR-0022 "Borrows" and "WALK key steps",
  ADR-0054's wrong-kind step, #144's Facts comment (5b ticket approval)".

Record amendments:
- R1, `docs/adr/0022-in-place-updates.md`, "Borrows", append: "An operation whose `reads` fact is
  set, with `keepsArgs` false, takes nothing from its `.` (only `input` sets `reads`, F-79;
  `onlyInputReads` pins it), so the chain arriving as its `.` is no read of the chain. This is the
  rule behind "`input` is no escape" above; a second operation that sets `reads` needs its own
  ruling."
- R2, same file, "WALK key steps", after "is a WALK step.": "A key that may read input is none: its
  Update is TRACKED and has no licence, as `.[input] |= f` (ADR-0007, "Amendment (spec 5 round
  2)")." The Status line gains "; "Borrows" and "WALK key steps" amended by spec 5b's ticket
  approval (grilling-adviser for the owner under the delegation of 2026-11-14)".
- R3, `docs/adr/0007-analysis-contracts.md`, "Amendment (spec 5 round 2)", two bullets: "**WALK
  key steps** (the ADR-0022 amendment above): a key that may read input is no WALK step, because
  its Update is TRACKED." and "**Root use**: "uses no outer slot" in the bullet above is replaced
  by ADR-0054's "Amendment (spec 5 round 2)": `rest` may use a parameter slot, and the fact has two
  values, ELEMENTS and WHOLE."
- R4, `docs/adr/0054-host-streaming-element-documents.md`, "Amendment (spec 5 round 2)", append:
  "A spine step that meets null or a value of the wrong kind counts as a missing path: `stream`
  falls back to the whole read, which gives jq's result or error (`{"a":[1]} | .a.b[0][]` →
  "Cannot index array with string ("b")")."
- R5, `GLOSSARY.md`: "**Guard**: A required case or test that passes before its ticket and is kept
  so that the ticket cannot break it; it is not the ticket's red. Not a WALK's guard (ADR-0007).
  _Avoid_: regression test (alone)."
- R6, comment on #144: "Spec 5b's ticket approval (grilling-adviser for the owner under the
  delegation of 2026-11-14): the Facts bullet is `input`'s: path NONE, not pure, `reads` set.
  `input_filename` and `input_line_number` are path NONE, not pure, `reads` false, because only
  `input` reads the input stream (F-79)."

Round 2's guards "P1–P12" are P1–P8 and P12; P9 is `reduceInputStaysLinear`, and P10 and P11 are
S5b.4's. Seven values the probe file lacked at round 2 were observed after it, on 2026-11-14, and
re-run by the adviser.
