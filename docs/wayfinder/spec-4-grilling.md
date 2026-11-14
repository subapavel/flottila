# Spec 4 "Builtins" — grilling record

Input for `/to-spec`. Under the owner's delegation of 2026-11-14 (until Monday 2026-11-16 morning,
`agent/HANDOFF.md`, comment on #56), the `grilling-adviser` (Fable) answers for the owner. Drafts:
designer (round 1). jq behaviour: jq-ref 1.8.2 probes, observed by the architect (P1–P8) and the
adviser (marked A); jq 1.8.2 `src/builtin.c` and `src/jv.c`. Java facts: observed on JDK 25.0.5.

To show the owner on Monday (adviser's list):
- **Licence (owner-only):** may code under the SunPro (fdlibm) notice, or CORE-MATH once its licence
  is read, be translated for 11 math builtins (question 3)? Until then the fallback applies.
- Arm's `erf.c` is ported under MIT, a listed licence, so no exception; a second copyright holder
  enters NOTICE and the jar.
- Visible difference: the last digits of 36 math builtins are jqj's own (question 3).
- Owner-accepted text changes: ADR-0017 (date locale, zone readers, "per match"), GATE rows 2 and 4
  (G3, F-33), GATE check 2's host-facing list (`debug`, `stderr` left it in spec 3).

## Round 1 (grilling-adviser for the owner, 2026-11-14)

1. **One spec, no split (option B).** The families need nothing from each other, so a split protects
   no order (unlike 3a/3b) and adds a spec gate, a review and a PR. 15 tickets is the size of 2b and
   3. The only owner-held item has a fallback and sits in the last math ticket.
2. **`fromjson` in spec 4 on today's reader (A).** Valid strict text, literal numbers, BOM, "Unexpected
   extra JSON values" and "Expected JSON value". P8: `[1E+1000,"1E+1000",true,false]`,
   `[100000000000000000001,"100000000000000000001",false,false]`, `"Unexpected extra JSON values
   (while parsing '[1,2] 3')"`. Malformed text and lenient tokens wait for spec 5's replay (M3).
3. **Math: 25 exact builtins bit for bit, 36 computed ones jqj's own (A reshaped).**
   - Exact (the draft's 25): jq-ref's bits and its x86-64 conversions. An exponent or order outside
     `int`/`long`, or NaN, becomes the minimum value on every platform. P2: `ldexp(1;1e10)` → `0`,
     `ldexp(1;2.7)` → `4`, `scalbln(1;1e19)` → `0`, `jn(1e10;1)` → `null`, `fmin(nan;1)` → `1`.
     A: `scalb(1;2.7)` → `null`, `-0.5|nearbyint` → `-0`.
   - Computed: jq-ref gives no single target. A: its strings name glibc and GCC 13.3.0; under
     `GLIBC_TUNABLES=glibc.cpu.hwcaps=-FMA,-FMA4,-AVX2_Usable,-AVX2` it changed 11 of 9 000 `sin`,
     8 `sinh`, 6 `atan2`, 5 `exp` and 3 `pow` results on this machine. jq.test rounds every
     transcendental it checks (`cos * 1000000|floor / 1000000`, lines 847–857).
   - A (12 000 inputs): `StrictMath` differs from jq-ref on all 17 functions tried: `log1p` 3,
     `tanh` 5, `sin` 424, `exp` 855, `atan2` 2 164, `cbrt` 5 527, all within 2 ulp but one `cbrt`.
     The draft's "where P3 shows identity" holds nowhere. `27|cbrt` → `3.0000000000000004` on jq-ref.
   - Rule: `StrictMath` for the 18 Java has, never `Math` (its javadoc fixes no bits across
     platforms), so jqj prints the same digits everywhere. `acosh`, `asinh`, `atanh`, `exp2`, `exp10`
     and `log2` are written from their definitions over `StrictMath`, exact where jq-ref is
     (A: `8|log2` → `3`, `10|exp2` → `1024`, `3|exp10` → `1000`). One DEVIATIONS section "libm last
     digits" covers the 36; a JUnit test holds each to its measured ulp bound on a recorded sample.
   - `erf`, `erfc`, `lgamma`, `lgamma_r`, `gamma`, `tgamma`, `j0`, `j1`, `jn`, `y0`, `y1`, `yn`: Java
     has none (`javap StrictMath`). Verified: ARM optimized-routines is "MIT OR Apache-2.0 WITH
     LLVM-exception" (its LICENSE) and has a double `erf.c`, no gamma, no Bessel; it counts as MIT,
     so `erf` is ported under Arm's notice. CORE-MATH's licence could not be read (gitlab.inria.fr is
     blocked here): not relied on. FreeBSD msun and musl carry the SunPro notice, not on the list
     (as David Gay's, ADR-0012). **Owner on Monday.** Fallback, no new licence: the 11 are written
     from published formulas (DLMF 5, 7, 10), each with its measured bound in the row.
4. **Dates: jq's wrappers translated, glibc's behaviour from probes, jqj's own calendar (A reshaped).**
   - glibc is LGPL and musl is not what jq-ref runs. Translated under jq's notice: `tm2jv`, `jv2tm`,
     `f_mktime`, `f_gmtime`, `f_strptime`, `f_strftime`, `f_strflocaltime`, `set_tm_wday`,
     `set_tm_yday`. They hold the quirks. A: `-1.5|gmtime` → `[1969,11,31,23,59,59.5,3,364]`;
     `[1969,11,31,23,59,59,0,0]|mktime` → "invalid gmtime representation", with `58` → "mktime not
     supported on this platform"; `"2015-03-05T23:51:47Z   "|strptime(…)` appends `"   "` as a ninth
     element. P5: `[1900,0,0,22,30,0,8,367]` (the 8 and 367 sentinels).
   - `java.time` stops at year 999 999 999 (`Year.MAX_VALUE`); jq-ref does not. A: `[1e300,0,1]|mktime`
     → `67768036160140800`; `67767976233532800|gmtime` → year `-2147483648`. The calendar is `long`
     arithmetic in O(1) with `int` wrap as observed; the JDK supplies zone offsets only.
   - The strftime table is total, so no "unprobed directive" rows. A: glibc converts
     `A B C D F G H I M P R S T U V W X Y Z a b c d e g h j k l m n p r s t u w x y z %`, copies every
     other letter (`%E` → `%E`, `%f` → `%f`) and takes flags and widths (`%_5d|%-d|%^a|%#Z|%10Y` →
     `    5|5|THU|gmt|0000002015`). A result longer than the format plus 100 bytes, or an empty
     result of a non-empty format, raises "strftime/1: unknown system failure" (P4, `f_strftime`).
     Names are fixed English tables (ADR-0020: no locale effect). strptime gets the same total
     table. One recorded case per conversion, flag and modifier, for both.
5. **Zones: offsets from the JDK's tzdb; `%Z` is the JDK's English short name, or tzdata's numeric
   form where the JDK has none (A reshaped).**
   - A (tzdb 2026e): `Locale.ROOT` gives `GMT+09:00` for Asia/Tokyo, so the draft's assumption fails.
     `Locale.ENGLISH` gives `JST`, `EST`/`EDT`, `IST`, `CET`, but `GMT+04:00` for Asia/Dubai (P4:
     `+04`) and never `LMT` (P4: `LMT +0918`). A `GMT±hh:mm` name prints as `±hh[mm]`.
   - `strftime` prints `GMT` in every zone (P4; A: `localtime|strftime("%H %Z %z")` → `17 GMT +0000`
     under America/New_York).
   - Gap and overlap follow jq-ref, not `ZoneRules`' default. A, America/New_York:
     `[2021,2,14,2,30,0,0,0]|strflocaltime(…)` → `2021-03-14T03:30:00 EDT -0400`;
     `[2021,10,7,1,30,0,0,0]` → `2021-11-07T01:30:00 EST -0500`.
   - One DEVIATIONS section "zone abbreviations": LMT eras, zones tzdata names differently, tzdata
     version. An unknown `TZ` is UTC (ADR-0020) with an empty name (A: `strflocaltime("%Z")` →
     "unknown system failure"); the ticket mirrors it or adds a row. A TZif name hook (option B)
     can be added later without rework.
6. **Coverage is a hard rule at spec 4's gate (A, list corrected).** The uncovered list must lie
   within the 13 host-facing signatures still unbuilt: `input`, `inputs`, `env`, `halt`,
   `halt_error/0,1`, `input_filename`, `input_line_number`, `get_search_list`, `get_prog_origin`,
   `get_jq_origin`, `modulemeta`, `now`. `debug/0,1` and `stderr` are built in 3a (spec 3, A3) and
   must be covered. A builtin counts when jqj's Binder resolves a reference written in a passing
   case's own filter text to it at that arity. A shadowing definition, a call from inside the
   prelude, `..` and `@csv` (for `format/1`) do not count. The 226 names are jq-ref's stored
   `builtins` output (P7b: `226`); the tool's first run corrects the draft's per-family lists.
7. **Generators (A, with conditions).** Each `builtins-<family>-v1` calls its family's spec-4 builtins
   and the family's earlier ones, none of spec 3's path builtins.
   - Every builtin is also called on the other kinds under `try … catch .` (error text is compared).
   - dates-v1 runs under `TZ=UTC` and draws only recorded conversions.
   - regex-v1 draws only constructs that question 8's matrix shows identical.
   - math-v1 prints exact builtins raw and computed ones, when normal, as
     `[logb, (significand * 4294967296 | rint)]` (exact functions only). It never calls the 12 of
     the last math ticket.
8. **Regex dialect: joni 2.2.9 as it is, `Syntax.PerlNG` with `CAPTURE_GROUP` (A).** Read at the
   joni-2.2.9 tag: `PerlNG` has named groups, `\k`, `\g` and `CAPTURE_ONLY_NAMED_GROUP`; the texts of
   P6's compile errors are joni's own constants ("end pattern with unmatched parenthesis",
   "unmatched close parenthesis", "upper is smaller than lower in repeat range"). The prelude
   ticket's red is a matrix: one recorded case per construct of Oniguruma's syntax list and per
   error text. A difference becomes a DEVIATIONS row ("regex dialect") or a text mapping.
9. **The 1 s limit is per joni search call (A).** jq's `g` loop calls `onig_search` once per match
   (`f_match`), and joni's timeout starts in `searchCommon`. Each call gets the smaller of 1 s and
   the remaining deadline. A limit per `_match_impl` call would fail long global scans that jq
   completes, on a command line with no deadline.
10. **Compiled patterns: a small bounded cache per Workspace, keyed by pattern bytes and flags
    (A reshaped).** The latest entry is tried first by identity. It keeps a compile error too, raised
    in `f_match`'s order (input type, pattern type, modifiers, compile). A one-entry memo per call
    site misses `test($a, $b)` and needs a slot per site. Nothing compiles at lowering. A:
    `[try ("abc"|test("(")) catch ., (empty|test("("))]` → one "Regex failure: end pattern with
    unmatched parenthesis". The designer sets the bounds (start: 16 entries, patterns up to 4 KiB).
11. **Translate jq's C under jq's notice (A):** `f_format`, the base64 tables, the trim and implode
    checks, `f_match`, `f_bsearch`, `jv_contains`.
    - Results depend on jq's loops. A: `[3,1,2]|bsearch(2)` → `2`; `"é"|[match("";"g")|.offset]` →
      `[0,1,1]`; `"éé"|gsub("";"-")` → `"-é--é--"` (one byte forward after an empty match).
    - `contains` runs on an explicit stack with `MAX_CONTAINS_DEPTH` 10 000 (ADR-0029 amended). A:
      10 000 arrays around `0` → `true`, 10 001 → "Containment check too deep"; `{a:…}` 10 000 times
      around `{}` → `true`, 10 001 → the error.
    - Codepoint offsets come from a running count, not `f_match`'s rescan from the start per match.
    - P7: 1.8.2 has no `@base32` or `@base32d` ("base32 is not a valid format").
12. **One tick table as an ADR-0034 amendment (A, rows changed).** A ticket corrects a row only by a
    finding; each rate is pinned by two adjacent counts.
    - Byte loops (trim, `startswith`, `endswith`, formats, `fromjson`, `strftime`, `strptime`): one
      per started KiB of max(input, output).
    - `explode`, `implode`, `builtins`: one per element, as every other array copy, not per KiB.
    - `_strindices` and string `contains`: `split`'s rate plus one per index.
    - Array and object `contains`: one per pair plus Equality's. `bsearch`: one per probe plus the
      compare's.
    - Regex: one per search call, one per started KiB from the search start to the match end, one
      per started KiB of a pattern compiled.
    - Scalar math: none, except M1.

### Decided beyond the draft

- **M1. `jn` and `yn` tick once per 1 024 steps of any loop that grows with the order**, so they see
  the deadline and a cancel (ADR-0008). A: `jn(2000000000; 1e9)` ran past a 20 s timeout on jq-ref;
  `yn(200000000; 1e9)` took 0.42 s.
- **M2. `strptime` reads the host zone for `%s`.** A: `"1425599507"|strptime("%s")` →
  `[2015,2,5,23,51,47,4,63]` under `TZ=UTC`, `[2015,2,6,8,51,47,5,64]` under Asia/Tokyo. ADR-0017's
  list of zone readers gains it; it is built in the host-zone ticket.
- **M3. `fromjson`'s Stub and charge.** Malformed text and lenient tokens end the run with the
  reader's Stub, which `try` must not catch, so no case passes on it. jq.test 2315, 2324, 2498 and
  2563 wait for spec 5. A result charges bytes built for the document it keeps (the designer derives
  the rule from ADR-0017's schedule), shown by a BYTES_BUILT witness.
- **M4. No new IR kind, no Java recursion over value depth.** Every spec-4 builtin is an Operation or
  a prelude definition, so 2b's heap forms and frames are untouched; a ticket that wants a node
  writes a finding. `contains`, the formats and `fromjson` each get a 10 000-deep witness on a
  512 KiB stack.
- **M5. A diagnostics ticket.** No gate cell calls a spec-4 builtin (ADR-0019). It records, with no
  factor, beside same-session jq, on 100 KB inputs: `test` per element (cache on and off), `gsub`,
  `ascii_downcase`, `@csv`, `tojson|fromjson`, `strftime`. The rows name spec 6's candidates for a
  native (ADR-0006: on a measured win only).
- **M6. Facts.** Each native declares its `Facts` on Operation (spec 3, question 3) in its own ticket:
  path NONE; the zone readers (`localtime`, `strflocaltime`, `strptime`) are not pure; `keepsArgs`
  true where a native can return its input (the trim family), under `keepsArgsIsSound` from day one.
- **M7. Spec 3's P6 list is corrected.** `ltrimstr`, `rtrimstr` and `trimstr` are prelude definitions
  whose body is `.` or a slice, so jqj gives jq's paths with no row. A:
  `{"k":"xy"}|[path(.k|ltrimstr("z")), path(.k|rtrimstr("y")), path(.k|trim)]` →
  `[["k"],["k",{"start":null,"end":1}],["k"]]`. The row is for the C natives `trim`, `ltrim`, `rtrim`.
- **M8. GATE rows 2 and 4 take F-33's wording** (G3 in spec 2); F-33's "Change made" gains GATE; no
  new finding. The hand-over's `toarray` is dropped (P7: "toarray/0 is not defined").

### Ticket shape (approved by the adviser for the owner): 15 (3 class A, 10 class B, 2 class C)

1. (A) Coverage tool, the stored 226 list, the census entry (`SPEC_ISSUES`). First.
2. (B) Strings, with strings-v1.
3. (B) `fromjson` (#99, M3).
4. (B) Collections: `contains`, `inside`, `bsearch`, `_strindices`, `indices`, `builtins`; collections-v1.
5. (B) Formats, with formats-v1.
6. (C) `_match_impl` on joni: translated loop, running offsets, per-search limit, cancellation, cache.
7. (B) Regex prelude, the matrix and its rows, regex-v1. After 6 and spec 3a (`sub` uses `+=`).
8. (B) Exact math (25) with the conversions.
9. (B) Computed math: 18 on `StrictMath`, 6 from definitions; the DEVIATIONS section, the bound test,
   math-v1.
10. (B) The 12: `erf` (Arm port), then the 11 by the owner's answer or the fallback; M1. Last of math.
11. (B) `gmtime`, `mktime`, `strftime`: wrappers, calendar, the strftime table.
12. (B) `strptime`, the four iso8601 definitions, dates-v1. After 11.
13. (C) `localtime`, `strflocaltime`, `%s`, zone names; the passing jq.test and man.test cases also
    pass under Asia/Tokyo and America/New_York. After 12.
14. (A) Hand cases for the 2a–3 builtins that ticket 1 lists uncovered. Late.
15. (A) Diagnostics (M5). After 2, 5, 7, 11.

Each generator ticket records enough seeds to keep 200 after drops. Spec 4 needs spec 3a only
(Facts, `+=`, debug), not 3b: input to spec 3's round 2.

### Records to write

- New ADRs: math (question 3, M1); dates and zones (questions 4 and 5, M2); regex on joni
  (questions 8 to 10).
- Amend: ADR-0017 (limit per search call, fixed English tables in place of "root locale", the zone
  readers), ADR-0016 (the Workspace cache), ADR-0029 (contains), ADR-0034 (tick table), ADR-0015
  (`fromjson` in spec 4, M3), ADR-0020 (question 7's rules), ADR-0019 (M5), ADR-0023 with GATE (one
  spec, question 6's rule and list, rows 2 and 4).
- Findings: StrictMath is not jq-ref's libm, and jq-ref's digits vary with the CPU; the JDK's root
  locale has no zone abbreviations; `strptime %s` reads the zone; `java.time`'s year range; `jn`/`yn`
  are unbounded loops; spec 3's P6 list (M7).
- DEVIATIONS: "libm last digits", "zone abbreviations", "regex dialect". "Regex limit by time" stays.
- NOTICE: each translated jq function; Arm's `erf.c` with Arm's MIT text in the jar.

### Open

- Round 2: not needed before `/to-spec`. Reopen if 3a's Facts differ from spec 3's record.
- Owner on Monday: the licence question of question 3. gitlab.inria.fr has to be allowed in the
  session's network settings before CORE-MATH's licence can be read.
- Missing facts:
  - joni against Oniguruma 6.9 per construct, and whether joni searches from a mid-character start
    as jq's one-byte advance needs (ticket 7's matrix and ticket 6's red);
  - the JDK's English names against tzdata over all zones (ticket 13 runs jq-ref under each `TZ`);
  - glibc's strptime table (ticket 12's probes);
  - the ulp bound for all 36 computed builtins (ticket 9's sample; 17 measured here);
  - the cache's gain (ticket 15);
  - the draft's 76/9/15/110 counts and per-family lists (ticket 1's first run; not checked here).

## Records round (architect, 2026-11-14)

The records designer drafted ADR-0051 (math), ADR-0052 (dates and zones), ADR-0053 (regex on
joni), F-65 to F-70 and the amendments from round 1. The architect settled the drafting conflicts:

1. **Regex ticks and the cache.** A run's steps must not depend on what the Workspace ran before
   (ADR-0017: equal step counts; ADR-0020's backend oracle compares `stepsUsed()`). So a pattern
   charges one tick per started KiB at each lookup, compiled or cached (ADR-0053, ADR-0034's spec-4
   table).
2. **ADR-0019** "G3 in spec 4 (ADR-0023)" now reads "G3 in spec 2a (F-33)", as F-33 rules.
3. **DEVIATIONS "Regex limit by time"** says "one joni search call" (ADR-0053's wording).
4. **NOTICE** is not edited yet. Each spec-4 ticket that translates jq's code, or ports Arm's
   `erf`, adds its own line with its file's path. The designer's draft lines sit in the ticket
   bodies.
5. **The translation list** follows jq 1.8.2's source as read: `f_contains` holds the depth error
   and the kinds check, and the trim natives are `f_string_trim`, `f_string_ltrim` and
   `f_string_rtrim`. Each ticket lists what it actually translates.

Open for the tickets: how a computed result that is not normal prints in `builtins-math-v1`;
`fromjson`'s charge for tag-6 strings; the regex tick span for a search that finds nothing; offsets
past the JDK's zone rules; the diagnostics' cache switch.

For the owner on 2026-11-16, in addition to round 1's list:
- GATE check 2's host-facing list changes as the owner's text change inside ADR-0023's amendment,
  with no finding of its own;
- GATE check 8 names only jq's MIT text in the jar; Arm's MIT text must ship too once `erf` lands;
- the designer's choices: least-recently-used eviction, the run-scoped pattern identity, the
  `fromjson` charge rule, the 25/18/6/12 lists, the order conversion for `jn` and `yn`, and F-70's
  row going to spec 4's strings ticket.

## Ticket approval (grilling-adviser for the owner, 2026-11-14)

The adviser approved the 15 tickets with changes (2 class A, 11 class B, 2 class C):
1. `builtins-dates-v1` stays in the strptime ticket and calls no zone reader (ADR-0020 sentence).
2. The diagnostics ticket is also blocked by `fromjson`'s ticket and by main with 3b merged.
3. `fromjson` charges an escaped string's decoded UTF-8 length; a search that finds nothing ticks
   to the input's end (ADR-0034); a computed math result that is not normal prints as
   `[class, copysign(1; .)]`, NaN as `"nan"` (ADR-0020); the cache is turned off by the Workspace's
   internal capacity 0, reached as the compile-mode switch is.
4. No 400-year cycles for far instants. jq-ref applies the daylight rule through year 5 881 580
   and none after (July of 5 881 581, 10⁷ and 2·10⁹ print `EST -0500` under America/New_York,
   `CET +0100` under Europe/Berlin), while the JDK gives `-04:00` for July of 10⁷. After jq-ref's
   last daylight instant jqj uses the zone's standard offset and name; the host-zone ticket
   bisects the instant in a northern and a southern zone.
5. `%s` is built with each table under the run's zone (UTC until the host-zone ticket); `strftime`
   and `strptime` are not pure. `"12 rest"|strptime("%s")` → `[1970,0,1,0,0,12,4,0," rest"]`.
6. The coverage ticket is class B (a Resolver hook in jq-core); its first run assigns every
   unbuilt signature outside the 13 to a ticket (`toboolean/0` is in no list). The formats ticket
   names `@urid` (`"%41"|@urid` → `"A"`). The hand-cases ticket is also blocked by the `fromjson`,
   12-math and host-zone tickets.
