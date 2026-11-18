# Follow-up list for the next observation session (2026-11-16)

For the owner. Nothing here is a new case and nothing here is waiting: this is the list of what the
observation logs still mark on runs that were made, with the one thing that would settle each mark.
Asked by D-164 (7) (`docs/DECISIONS.md`, MAP-14). A list obliges no one: only the owner runs SAP, and
every item says what stays true if it is never run. No case, expected output, ratchet row or log
entry changes with this file. Notation and what to return: `README.md`.

How it was read: each set's `meta.json` only grows, and a later entry of a run replaces an earlier
one. A run is a mapping on an input; the entries of one run were linked - four runs carry one
mapping name on PO and another on Cloud Integration - and the latest value of each field taken. A
case alone does not name a run: several mappings test one case. That gives 195 runs in 14 sets: 152
observed on both systems (an output, or the error); 32 whose latest SAP Cloud Integration entry
carries a mark (parts 1 to 3); 1 observed on PO and blocked on Cloud Integration (part 4, with the
one corpus case that is still `assumed`); 10 made on neither system (part 5).

## Part 1 - the environment of the runs (28 marks, no mapping to run again)

The 28 runs below have their Cloud Integration output recorded, and the latest entry says
`observed-output-runtime-provenance-unverified`. Their `reading` names what was open when the output
was recorded: `output-confirmed-parameter-unverified` (24 runs),
`output-confirmed-parameter-and-monitor-time-unverified` (`fn/currentDate/002` to `004`) and
`output-confirmed-default-and-monitor-time-unverified` (`fn/currentDate/001`). Later fields of the
same entries answer part of it. The parameter half: `parameterVerification` is `VERIFIED_PASS` on all
28 (2026-11-13; the evidence is `cpi/parameter-audit/<artifact>.json` of the set). The default half:
`nativeDefaultEvidence` on `fn/currentDate/001` and `fn/dateTrans/006` - the editor's default format
was read (`cpi/native-default-currentDate-001.txt`: "Date Format yyyy/MM/dd"). What is still open is
the environment, in the check files' own words: "JVM locale and arithmetic implementation remain
unverified" (all 15 arithmetic check files) and "Exact JVM zone ID and message-monitor start times
remain unverified" (11 of the 13 date check files; `fn/currentDate/001`'s says "Exact JVM zone ID
and live editor default remain unverified"). All 28 close with "Environment provenance and
prohibited time-zone variants are not established by this audit" - for `fn/dateTrans/006` that is
the only open sentence. These are the questions the requests already asked
(`obs-arithmetic/request.md` :32-34, `obs-date/request.md` :10-13 and :58-59); one answer serves
every run of its group.

| | What to read | Settles |
| --- | --- | --- |
| E1 | Cloud Integration: the default locale of the JVM that ran the mappings (it gives `DecimalFormat` its decimal and grouping separators); the arithmetic mode, if it can be seen at all | the 15 `formatNum` runs |
| E2 | Cloud Integration: the default time zone of that JVM, by its zone ID. The recorded outputs are written at `+0000` / `Z`, which fits UTC; the ID itself is not recorded | the 13 date runs, with E4 for four of them |
| E3 | PO 7.5: the default time zone of the server's JVM, by its zone ID. `obs-date/meta.json` has `po75.jvmDefaultTimezone` null; its outputs of 2026-11-12 are written at `+0100` ("Named JVM zone unknown") | nothing marked on Cloud Integration; it completes the PO half of part 2 |
| E4 | The four `currentDate` runs were bracketed by an outside clock (`currentDate-001-current-date-time-check.txt`: "This is not a message-monitor start timestamp, so equality to processing start is unverified"). One run of each whose processing start is read from the message monitor, with its zone, beside the output | `fn/currentDate/001` to `004` |

The recorded outputs came from the mapping editor's simulation ("uploaded through simulator"). The
fact that counts is the setting of the JVM that produced them. If E1 and E2 are read somewhere else -
for example in a deployed integration flow, by a script step that writes
`java.util.Locale.getDefault()` and `java.util.TimeZone.getDefault().getID()` (a suggestion of this
list, not tried) - say where, and say whether one mapping run in the same place gives the recorded
output: `fmt-008` on `formatNum-008.xml` shows both separators, `date-trans-003` on
`dateTrans-003.xml` writes in the JVM's zone.

If none of it is read: the outputs stay recorded and marked, 21 of the 32 marked runs stay the
evidence of an `observed` case and 11 of a `decided` one, as today.

| Case | Mapping | Input | Recorded output (under the set's directory) | Case today | Later fields that answer part of the mark | Settled by |
| --- | --- | --- | --- | --- | --- | --- |
| `fn/formatNum/001` | `fmt-001` | `formatNum-001.xml` | `cpi/formatNum-001-fmt-001.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/002` | `fmt-002` | `formatNum-002.xml` | `cpi/formatNum-002-fmt-002.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/003` | `fmt-003` | `formatNum-003.xml` | `cpi/formatNum-003-fmt-003.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/004` | `fmt-004` | `formatNum-004.xml` | `cpi/formatNum-004-fmt-004.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/005` | `fmt-005` | `formatNum-005.xml` | `cpi/formatNum-005-fmt-005.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/006` | `fmt-006` | `formatNum-006.xml` | `cpi/formatNum-006-fmt-006.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/007` | `fmt-007` | `formatNum-007.xml` | `cpi/formatNum-007-fmt-007.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/008` | `fmt-008` | `formatNum-008.xml` | `cpi/formatNum-008-fmt-008.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/009` | `fmt-009` | `formatNum-009.xml` | `cpi/formatNum-009-fmt-009.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/010` | `fmt-010` | `formatNum-010.xml` | `cpi/formatNum-010-fmt-010.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/011` | `fmt-011` | `formatNum-011.xml` | `cpi/formatNum-011-fmt-011.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/012` | `fmt-012` | `formatNum-012.xml` | `cpi/formatNum-012-fmt-012.xml` | decided | `parameterVerification` | E1 |
| `fn/formatNum/013` | `fmt-013` | `formatNum-013.xml` | `cpi/formatNum-013-fmt-013.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/014` | `fmt-014` | `formatNum-014.xml` | `cpi/formatNum-014-fmt-014.xml` | observed | `parameterVerification` | E1 |
| `fn/formatNum/015` | `fmt-015` | `formatNum-015.xml` | `cpi/formatNum-015-fmt-015.xml` | observed | `parameterVerification` | E1 |
| `fn/dateTrans/001` | `date-trans-001` | `dateTrans-001.xml` | `cpi/dateTrans-001-date-trans-001.xml` | observed | `parameterVerification` | E2 |
| `fn/dateTrans/002` | `date-trans-002` | `dateTrans-002.xml` | `cpi/dateTrans-002-date-trans-002.xml` | observed | `parameterVerification` | E2 |
| `fn/dateTrans/003` | `date-trans-003` | `dateTrans-003.xml` | `cpi/dateTrans-003-date-trans-003.xml` | decided | `parameterVerification` | E2 |
| `fn/dateTrans/006` | `date-trans-006` | `dateTrans-006.xml` | `cpi/dateTrans-006-date-trans-006.xml` | observed | `parameterVerification`, `nativeDefaultEvidence` | E2 |
| `fn/dateTrans/012` | `date-trans-012` | `dateTrans-012.xml` | `cpi/dateTrans-012-date-trans-012.xml` | decided | `parameterVerification` | E2 |
| `fn/dateBefore/001` | `date-before` | `dateBefore-001.xml` | `cpi/dateBefore-001-date-before.xml` | observed | `parameterVerification` | E2 |
| `fn/dateAfter/001` | `date-after` | `dateAfter-001.xml` | `cpi/dateAfter-001-date-after.xml` | observed | `parameterVerification` | E2 |
| `fn/compareDates/001` | `compare-dates-001` | `compareDates-001.xml` | `cpi/compareDates-001-compare-dates-001.xml` | observed | `parameterVerification` | E2 |
| `fn/compareDates/002` | `compare-dates-002` | `compareDates-002.xml` | `cpi/compareDates-002-compare-dates-002.xml` | observed | `parameterVerification` | E2 |
| `fn/currentDate/001` | `current-date` | `currentDate-001.xml` | `cpi/currentDate-001-current-date.xml` | decided | `parameterVerification`, `nativeDefaultEvidence` | E2 and E4 |
| `fn/currentDate/002` | `current-date-time` | `currentDate-001.xml` | `cpi/currentDate-001-current-date-time.xml` | decided | `parameterVerification` | E2 and E4 |
| `fn/currentDate/003` | `current-date-item` | `currentDate-001.xml` | `cpi/currentDate-001-current-date-item.xml` | decided | `parameterVerification` | E2 and E4 |
| `fn/currentDate/004` | `current-date-concat` | `currentDate-001.xml` | `cpi/currentDate-001-current-date-concat.xml` | decided | `parameterVerification` | E2 and E4 |

The 15 `formatNum` runs are in `obs-arithmetic/`, the 13 date runs in `obs-date/`.

## Part 2 - runs that asked for a time zone the system was not in (3 marks, nothing to run)

Five date cases ask for a JVM in a named zone. PO 7.5 ran at `+01:00` and Cloud Integration at
`+0000`, so each system met some of them and its other runs are `partial`. The owner decided these
cases on 2026-11-13 (the mapping's F-43, #212): "The time zone of a run is the service's (UTC by
default). The parts observed in UTC are cited as partial evidence." Every one of them is `decided`
in the corpus. A run in the asked zone would add evidence and change no decision. The logs say of
these runs "No environment settings changed"; whether a system's zone can be set for a run is not
recorded.

| Case | Asks for | PO 7.5 (2026-11-12) | Cloud Integration (2026-11-13) | Left open |
| --- | --- | --- | --- | --- |
| `fn/dateTrans/003` | UTC | `partial`: written at `+0100` | recorded at `+0000` (part 1, E2) | E2 |
| `fn/dateTrans/012` | UTC | `partial`: written at `+01:00` | recorded (part 1, E2) | E2 |
| `fn/dateTrans/004` | `+01:00` | observed | `partial-environment-timezone`: written at `+0000` | E3 names the zone PO met it in |
| `fn/compareDates/004` | `+01:00` | observed | `partial-environment-timezone`: "consistent with UTC interpretation, not the requested -1, 0" | E3 |
| `fn/dateTrans/005` | `-04:00` | `partial`: written at `+01:00` | `partial-environment-timezone`: written at `Z` | met on neither system |

The three marks of this part are the Cloud Integration entries of `fn/dateTrans/004`,
`fn/dateTrans/005` and `fn/compareDates/004`; their outputs are under `obs-date/cpi/`.

## Part 3 - one input the simulation may have changed (1 mark, one run)

`fn/trim/001`, mapping `text-trim`, input `trim-001.xml`, in `obs-text/`; the output is
`cpi/trim-001-text-trim.xml` and the latest entry says `observed-output-input-normalization-unverified`.
The second source value is `&#9;&#10;x&#13;`; Cloud Integration's second `All` is empty, where PO gives
the expected `x`. The check file: "normalization during simulator upload versus mapping execution was
not isolated. Do not attribute that difference to trim alone without further evidence." The case is
`decided` (2026-11-13, the mapping's F-43 addendum, #212, #221): PO equals the expected output and
the empty value is read as an artefact of the simulation's input.

The one run: `text-trim` on `trim-001.xml` in a way that separates the upload from the mapping. Two
ways, both suggestions of this list and not tried: a deployed flow that receives the file's bytes,
so that the input reaches the mapping without the simulation's upload; or, in the simulation, a look
at the second `Sku` value after the upload and before the mapping. Return the six `All` values. If
it is never run, the case stays decided on PO's output and the mark stays.

## Part 4 - the assumed case `target/minOccurs/004`: one blocked run, and a request drawn up but not run

The one corpus case that is still `assumed`, and both systems contradict it: the case expects an
error, PO 7.50 and Cloud Integration give `Grp` 2 one `Row` with `b` and the run succeeds
(`obs-target-creation`, the mapping's F-38). Its follow-up (`obs-target-creation-2`, part 1) showed
padding up to `minOccurs` with one mapping and the error with another, and F-38 leaves four readings
(a) to (d) that fit every run. The owner deferred the case past milestone 1 (the mapping's F-53, #220,
#222).

What would settle it is F-38's next step: an observation that separates (a) to (d) - for example
mc-memo's mapping with two `Text` under o2, and `removeContexts` over a queue with one value in its
only context under `Row` 1..2 with three `Grp`. Those two mappings are drawn up in
`obs-target-creation-3/request.md` (`mc-memo-two` and `mc-remove-one`, with their inputs and the
structure `src-memos.xsd`); they are not run. Until they are, the case stays listed, assumed and deferred.

One run of that follow-up was made on PO only: `mc-opt` on `minOccurs-004.xml`
(`obs-target-creation-2/meta.json`; `Row` 0..2). PO: "Grp 1 has Row a,b; Grp 2 has no Row when Row is
optional." Cloud Integration: `ENVIRONMENT_BLOCKED` - "The required in-place mapping is locked by a
previous session. [...] No unlock or edit was performed." No later entry replaces it. It is the one
requested run in the logs that was not made and is neither undrawable nor excluded: to redo on Cloud
Integration once the lock is gone, or to leave - F-38 reads the padding rule from PO's run and the
other mappings.

## Part 5 - runs made on neither system (10, none requestable)

| Set | Runs | Recorded reason |
| --- | --- | --- |
| `obs-statistic` | `stat-counter-two-fields` on `counter-001.xml`, `stat-counter` on `counter-002.xml`, `stat-counter-suppress` on `counter-003.xml` | "Native PO counter has no input connector; the requested incoming wire cannot be drawn." "Exact requested graph is not drawable; no substitute simulation or runtime conclusion." |
| `obs-ifwithoutelse` | `iwe-1` on `i1.xml` and on `i2.xml` | "PO counter has no input connector; requested Sku -> N1/N2 wires cannot be drawn." "CPI counter has no input port" |
| `obs-shared-box` | `shared-1` and `shared-2` on `s1.xml` | "PO counter has no input connector, so the requested Id -> counter wires cannot be drawn." "CPI counter has no input connector" |
| `obs-structures` | `str-nil` on `nil.json` | "PO message mapping reads XML only; the case tests the adapter's / converter's JSON to XML conversion, not the mapping" - "Explicitly excluded by the owner's observation prompt on both systems." |
| `obs-lift-reuse` | `spec2-create-if` on `createIf-002.xml`, `spec2-create-if-twice` on `createIf-003.xml` | "Explicitly superseded and marked Skip this run in request.md; covered by obs-target-creation-2" |

Seven cannot be drawn in either editor (the counter has no input), one the owner excluded, two were
withdrawn by their own request and are covered by a later set. That leaves 193 requested runs: eight
made on neither system, one on PO only (part 4), 184 on both.

## Beside the list

Three logs carry a top-level note older than their latest entries: `obs-arithmetic/meta.json` and
`obs-text/meta.json` say "CPI observations pending", `obs-date/meta.json` says "CPI API preparation
is not simulation evidence". The runs' latest entries govern (the check files say so); the notes are
left as recorded.
