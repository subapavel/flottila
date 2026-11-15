# obs-date

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`). The one request of the date family (spec #70,
#80). Every mapping maps N1 -> `Out/All`, except the currentDate mappings that name another
wire; S1 is `Orders/Order/Line/Sku` and S2 `Orders/Order/Line/Qty`, both with context `Orders`,
so All has one value per Line. One run of an input answers every case that names it. Each case id
is a directory under `corpus/cases/`.

Say for each system the JVM's default time zone (it is the zone a date without an offset is read
in, and the zone every date is written in), and whether the date functions parse leniently: our
reading (ADR-0003) is a strict parse in the run's time zone, which a SimpleDateFormat parses only
with lenient off.

## Mapping date-trans-nnn (dateTrans)

One mapping per row below; only the two formats change.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| N1 | `DateTrans{sourceFormat, targetFormat}`, the formats of the row | in1 = S1 |

Target wire: N1 -> `Out/All`.

| Case | Input | Formats | Question it decides and the assumed reading |
| --- | --- | --- | --- |
| `fn/dateTrans/001` | `dateTrans-001.xml` | `yyyyMMdd` to `dd.MM.yyyy` | 20261104, 20240229, 19991231, 00010101: the date read in the source format is written in the target format. Ours: 04.11.2026, 29.02.2024, 31.12.1999, 01.01.0001. |
| `fn/dateTrans/002` | `dateTrans-002.xml` | `d.M.yyyy H:mm` to `yyyy-MM-dd'T'HH:mm:ss.SSS` | 4.1.2026 7:05, 04.01.2026 07:05, 31.12.1999 23:59: a field of one letter reads one digit or two; fields the source lacks are written as zeros. Ours: 2026-01-04T07:05:00.000 twice, 1999-12-31T23:59:00.000. |
| `fn/dateTrans/003` | `dateTrans-003.xml` | `yyyy-MM-dd HH:mmXXX` to `yyyy-MM-dd HH:mm Z` | 2026-11-04 10:00+02:00, 2026-11-04 10:00Z, 2026-01-01 00:30+01:00, on a JVM in UTC: a date with an offset keeps its instant and is written in the JVM's zone. Ours: 2026-11-04 08:00 +0000, 2026-11-04 10:00 +0000, 2025-12-31 23:30 +0000; keeping the offset would give 10:00 +0200. |
| `fn/dateTrans/004` | `dateTrans-004.xml` | `yyyy-MM-dd HH:mmXXX` to `yyyy-MM-dd HH:mm Z` | The input of 003 on a JVM whose zone is +01:00 (Europe/Prague in winter): the same instants written in that zone. Ours: 2026-11-04 09:00 +0100, 2026-11-04 11:00 +0100, 2026-01-01 00:30 +0100. |
| `fn/dateTrans/005` | `dateTrans-005.xml` | `yyyy-MM-dd HH:mm` to `yyyy-MM-dd'T'HH:mm:ssXXX` | 2026-11-04 10:00, 2026-01-01 00:00 on a JVM whose zone is -04:00 (America/New_York in summer, or a fixed -04:00): a date without an offset is read in the JVM's zone. Ours: 2026-11-04T10:00:00-04:00, 2026-01-01T00:00:00-04:00; a zone with daylight saving may give -05:00 in winter, so name the zone. |
| `fn/dateTrans/012` | `dateTrans-012.xml` | `yyyyMMddHHmmZ` to `dd.MM.yyyy HH:mm XXX` | 202611041000+0200, 202611040030+0100, 202611041000-0530 on a JVM in UTC: Z reads a signed offset of four digits, the instant is kept and written in UTC. Ours: 04.11.2026 08:00 Z, 03.11.2026 23:30 Z, 04.11.2026 15:30 Z. |
| `fn/dateTrans/006` | `dateTrans-006.xml` | none: the defaults | 2026/11/04, 1999/12/31 with both formats left empty: the default pattern is yyyy/MM/dd. Ours: 2026/11/04, 1999/12/31. Say what the editor shows as the default. |

## Mapping date-before, date-after, compare-dates-nnn

One mapping per function and row; only the two formats change.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| N1 | `DateBefore{format1, format2}`, `DateAfter{format1, format2}` or `CompareDates{format1, format2}`, the formats of the row | in1 = S1, in2 = S2 |

Target wire: N1 -> `Out/All`.

| Case | Input | Formats | Question it decides and the assumed reading |
| --- | --- | --- | --- |
| `fn/dateBefore/001` | `dateBefore-001.xml` | `yyyyMMdd`, `dd.MM.yyyy` | The pairs 20261103 / 04.11.2026, 20261104 / 04.11.2026, 20261105 / 04.11.2026: true when date1 is before date2. Ours: true, false, false. |
| `fn/dateAfter/001` | `dateAfter-001.xml` | `yyyyMMdd`, `dd.MM.yyyy` | The same pairs: true when date1 is after date2. Ours: false, false, true. |
| `fn/compareDates/001` | `compareDates-001.xml` | `yyyyMMdd`, `yyyyMMdd` | The pairs 20261103 / 20261104, 20261104 / 20261104, 20261231 / 20260101: the result is -1, 0 or 1 (Date.compareTo). Ours: -1, 0, 1. Say if PO writes another number or text. |
| `fn/compareDates/002` | `compareDates-002.xml` | `yyyy-MM-dd HH:mmXXX` both | The pairs 2026-11-04 10:00+02:00 / 2026-11-04 08:00Z, 2026-11-04 10:00+02:00 / 2026-11-04 08:01Z: dates are compared as instants. Ours: 0, -1. |
| `fn/compareDates/004` | `compareDates-004.xml` | `yyyy-MM-dd HH:mm`, `yyyy-MM-dd HH:mmXXX` | The pairs 2026-11-04 09:30 / 2026-11-04 09:00Z, 2026-11-04 10:00 / 2026-11-04 09:00Z on a JVM whose zone is +01:00: date1 has no offset and is read in the JVM's zone. Ours: -1, 0; in UTC it would be 1, 1. |

## Mappings current-date-nnn (currentDate)

The run time cannot be fixed on PO or CPI: for each run, note the time the message was processed
(the message monitor's start time, with its zone) and the JVM's default time zone, and compare.

| Mapping | Boxes | Target wires |
| --- | --- | --- |
| `current-date` | N1 `CurrentDate` (format left as the editor proposes it) | N1 -> `Out/All` |
| `current-date-time` | N1 `CurrentDate{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}` | N1 -> `Out/All` |
| `current-date-item` | N1 `CurrentDate{yyyyMMdd}` | `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V` |
| `current-date-concat` | N1 `CurrentDate{'@'HH:mm:ss.SSS}`; S2 `Orders/Order/Line/Sku` with context `Order`; F1 `concat`, in1 = S2, in2 = N1 | `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V` |

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/currentDate/001` | `currentDate-001.xml` | The default format: assumed yyyy/MM/dd, so one All with the run's date, 2026/01/01 for a run on that day. Say what the editor shows as the default. |
| `fn/currentDate/002` | `currentDate-001.xml` | The run time to the millisecond, in the JVM's zone: ours, for a run at 2026-11-04T23:30:15.250-05:00, is 2026-11-04T23:30:15.250-05:00 (UTC would give 2026-11-05T04:30:15.250Z). Say whether the milliseconds are the run's start or 000. |
| `fn/currentDate/003` | `currentDate-001.xml` | A generating function under a repeated parent: assumed one V per Item, all three Items, o2 without lines too, each the run's date in the JVM's zone (20261105 for a run at 2026-11-05T00:30:00+01:00). |
| `fn/currentDate/004` | `currentDate-001.xml` | The time is fixed once per run: assumed a@t, b@t \| (empty) \| c@t with one t for the whole message, never two different times; an empty context stays empty. |
