# Spec 1 "Walking skeleton": grilling record

`/grill-with-docs`, 2026-11-11. Published as spec issue #22. The designer drafted round 1 and the architect drafted round 2. The
grilling-adviser evaluated both, and its recommendations went to the owner. This record is the input
of `/to-spec`; once the spec issue is published, that issue is the source of truth.

## Round 1: owner yes to all nine

1. **API slice:** only the members spec 1's callers (bench, CLI, runners) use, under the ADR-0016/0017
   names.
   - In: engine and builder, `compile`, workspace and `read`, `JsonDoc.root`/`close`,
     `JqFilter.run`, `JqSink`, both `JsonWriter.sink` overloads (compact or indent 2), empty
     `Bindings`, `Limits.embedded()`/`cli()`, `Host.sandboxed()`, sealed `JqException` with all four
     subclasses, and the six value kinds with read accessors and `toJson()`.
   - Not yet: `detach`, `of(…)`, owned `read`, `stream`, `RunOptions`, `explain`.
   - Views raise STALE_VIEW from day one.
   - The exported-signature test starts now and grows with each API ticket.
   - `Limits` is carried but not enforced until spec 2; the reader and writer get their tick call
     sites now.
   - Unsupported syntax is a Stub with a fixed jqj-only prefix, scored FAIL by the runners.
2. **The API slice is one class-C ticket** blocked only by the build ticket; every other class-C
   ticket stays blocked by all tooling tickets (F-19).
3. **Spec 1's last ticket** (class A) measures gate steps 1–2 alone and three at a time, and PIT per
   1000 passing `values-v1` seeds, or on those that pass if fewer. Tooling tickets measure only what
   needs no passing case (F-19).
4. **Language:** `.`, `.name`, postfix chains, `|`, parentheses. `."string"`, literals, `.[…]`, `?`
   and `,` are spec 2.
5. **Strings and numbers in spec 1**, as two class-C tickets.
   - In: jq's escape and U+FFFD decoding, jq's re-escape, canonical number printing with the exponent
     limits and -0, and leading-BOM stripping.
   - Out: `-a`, `-S`.
6. **No analysis:** `compile.txt`, the compile-mode runner and the switch start in spec 2. The
   IrVerifier runs.
7. **Malformed input:** a Stub.
   - It prints one fixed jqj-only text in jq's frame (`jq: parse error: … at line L, column C`) with
     rc 5; the API throws `JqSyntaxException`.
   - No `DEVIATIONS.md` row.
   - Tests pin only what stays true after spec 5: the type, rc 5, and stdout equal to jq-ref's.
     Line and column are pinned only where they are jq-ref's.
   - Lenient tokens and a second top-level value are malformed until spec 5.
8. **Runner limits:**
   - The jqtest runner, and later the compile-mode runner, use `Limits.embedded()` without the
     deadline.
   - Cases run through the command line use `Limits.cli()`.
9. **`owner` field:** every built class carries it from the ticket that creates the class. It stays
   null until spec 3.

## Round 2: owner yes

- **Command line:**
  - The filter, stdin or exactly one file, pretty (indent 2) and `-c`.
  - Options anywhere, `--`, combined short flags, and `-c` with piped stdin meaning `.`.
  - jq's text and rc 2 for a missing file and an unknown option.
  - Every other jq option is a Stub.
- **Architect's settlements, accepted with the round:**
  - **Required cases**, named by `/to-tickets`:
    - jqtest: jq.test:48, 58, 148, 152, 156, 160; man.test:1, 5, 29, 33.
    - json: the 123 files in reach (95 `y_`, 25 accepted `i_`, and `n_single_space`,
      `n_structure_no_data`, `n_structure_UTF8_BOM_no_data`). The 173 rejected files stay red until
      spec 5.
    - Hand-written differential cases:
      - each kind, pretty and `-c`;
      - escapes, U+FFFD and DEL;
      - non-canonical numbers, the exponent limit and -0;
      - duplicate keys, and a missing key → null;
      - `.a` type errors with rc 5, and the over-24-byte name truncation;
      - keyword field names;
      - chains, `|` and parentheses;
      - BOM, empty and whitespace-only input;
      - CRLF, and nested empty containers;
      - the file name in an error;
      - missing file and unknown option;
      - the `(at <stdin>:N)` boundaries;
      - `bench.G1`, `G6` and `G7` `.1k`.
    - No malformed-input case is required.
  - **Tickets are vertical slices.** Each class-C ticket ends at an exported seam and turns named
    cases green: API slice → read to values → `.` end to end (compact, CLI entry) → `.name`/chains/
    `|`/parentheses/duplicate keys/the error line (G1) → strings → numbers → pretty output (G6).
    - The CLI is class B.
    - PIT and JaCoCo wiring are in the build ticket; `nightly.yml` is separate.
    - `int run(Invocation)`'s stub shell belongs to the differential-runner ticket.
    - The breakdown also needs the `target/jqj` launcher (G7), a module-path case, jq's MIT text in
      the jar, the G1/G6/G7 scenarios, and the `ratchet/README.md` rewrite.
    - The owner approves the breakdown at `/to-tickets`.
  - **`values-v1`:** the designer drafts its frozen parameters before the first recording (one
    form per seed, a size cap, a lenient share, one document per stdin). The seed count is the
    architect's call; 1000 is a floor.
  - **`(at <stdin>:N)`** follows jq-ref's rule in spec 1: N is the number of read blocks ending in LF
    up to the block holding the completing byte. A block is cut after an LF or after 4091 bytes.
  - **Shapes** (`Limits` "none", `OutputOptions`, `bench.tsv` ids): the designer, in the API-slice
    design.
- **Owner action later:** make `ci.yml`'s three jobs required when it lands (ADR-0014).
