# Dates and zones: jq's wrappers translated, glibc's conversions from probes, jqj's own calendar, zones from the JDK

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 4 round 1); amends ADR-0017 (fixed English tables in place of the root locale; `strptime` and `strftime` join the zone readers for `%s`); its ticks are in ADR-0034's spec-4 amendment; findings F-66, F-67 and F-68

jq-ref facts: probes P1–P8 observed by the architect and those marked A by the adviser, on jq 1.8.2
(spec 4 grilling round 1); Java facts on JDK 25.0.5 with its tzdb 2026e.

jq 1.8.2's date builtins are wrappers in `src/builtin.c` around the C library's time functions.
jq-ref's C library is glibc, which is LGPL; musl is not what jq-ref runs. `java.time` stops at year
999 999 999, where jq-ref goes on (F-68), and the JDK's root locale has no zone abbreviations
(F-66).

## Decision

- **jq's wrappers, translated** under jq's notice: `tm2jv`, `jv2tm`, `f_mktime`, `f_gmtime`,
  `f_strptime`, `f_strftime`, `f_strflocaltime`, `set_tm_wday` and `set_tm_yday`. They hold the
  quirks. A: `-1.5|gmtime` → `[1969,11,31,23,59,59.5,3,364]`; `[1969,11,31,23,59,59,0,0]|mktime`
  → "invalid gmtime representation", with `58` → "mktime not supported on this platform";
  `"2015-03-05T23:51:47Z   "|strptime(…)` appends `"   "` as a ninth element. P5:
  `[1900,0,0,22,30,0,8,367]` (the 8 and 367 sentinels).
- **glibc's behaviour comes from probes, never from its code.** The strftime table is total, so no
  directive is left unprobed. A: glibc converts `A B C D F G H I M P R S T U V W X Y Z a b c d e g h
  j k l m n p r s t u w x y z %`, copies every other letter (`%E` → `%E`, `%f` → `%f`) and takes
  flags and widths (`%_5d|%-d|%^a|%#Z|%10Y` → `    5|5|THU|gmt|0000002015`). Two results raise
  "strftime/1: unknown system failure" (P4, `f_strftime`): one longer than the format plus 100
  bytes, and an empty result of a non-empty format. `strptime` gets the same total table from its
  own probes. Every conversion, flag and modifier has one recorded case, for both.
- **Names** of days, months and AM/PM come from fixed English tables; no locale has an effect
  (ADR-0020).
- **The calendar** is jqj's own `long` arithmetic in O(1), wrapping to `int` as observed. A:
  `[1e300,0,1]|mktime` → `67768036160140800`; `67767976233532800|gmtime` → year `-2147483648`. The
  JDK supplies zone offsets only.
- **Zones**: offsets come from the JDK's tzdb. `%Z` is the JDK's `Locale.ENGLISH` short name (A:
  `JST`, `EST`/`EDT`, `IST`, `CET`). A `GMT±hh:mm` name prints as `±hh[mm]`, tzdata's numeric form
  (P4: `+04` for Asia/Dubai). `strftime` prints `GMT` in every zone (P4; A:
  `localtime|strftime("%H %Z %z")` → `17 GMT +0000` under America/New_York).
- **Gap and overlap** follow jq-ref, not `ZoneRules`' default. A, America/New_York:
  `[2021,2,14,2,30,0,0,0]|strflocaltime(…)` → `2021-03-14T03:30:00 EDT -0400`;
  `[2021,10,7,1,30,0,0,0]` → `2021-11-07T01:30:00 EST -0500`.
- **The zone readers** are `localtime`, `strflocaltime`, and `strptime` and `strftime` for `%s`
  (`0|strftime("%s")` → `"-32400"` under Asia/Tokyo, F-67); `strptime` reads the host zone for `%s`
  (A: `"1425599507"|strptime("%s")` → `[2015,2,5,23,51,47,4,63]` under `TZ=UTC`,
  `[2015,2,6,8,51,47,5,64]` under Asia/Tokyo; F-67). They are not pure (spec 3's Facts), `strftime`
  and `strptime` included. They come in one host-zone ticket, whose passing jq.test and man.test
  cases also pass under Asia/Tokyo and America/New_York (GATE check 1).
- **An unknown `TZ`** is UTC (ADR-0020) with an empty zone name (A: `strflocaltime("%Z")` →
  "unknown system failure"). The host-zone ticket either mirrors it or adds a row.
- `DEVIATIONS.md` "zone abbreviations" covers LMT eras (P4: `LMT +0918`; the JDK never names one
  `LMT`), zones that tzdata names differently from the JDK, and the tzdata version.
- `builtins-dates-v1` runs under `TZ=UTC` and draws only recorded conversions (ADR-0020's spec-4
  amendment).

## Considered options

- **`java.time` as the calendar**: it stops at year 999 999 999, where jq-ref gives values (F-68).
- **Translating glibc's or musl's code**: glibc is LGPL, not on the list; musl's behaviour is not
  jq-ref's.
- **The root locale** (ADR-0017 as written): it gives `GMT+09:00` for Asia/Tokyo (F-66).
- **A TZif name reader now** (option B): it would give tzdata's own names, LMT included, but it is a
  second zone source beside the JDK's. It can be added later without rework.
- **`ZoneRules`' default for gap and overlap**: it is not jq-ref's choice (A above).
- **Rows for unprobed directives**: the table is total, so there are none.
