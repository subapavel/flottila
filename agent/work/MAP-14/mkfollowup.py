#!/usr/bin/env python3
"""MAP-14: write mapping/corpus/observations/follow-up-2026-11.md from the logs (latest entry of each run)."""
import sys, json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import obslist as o
C = '/home/user/flottila/mapping/corpus/cases/'
OUT = '/home/user/flottila/mapping/corpus/observations/follow-up-2026-11.md'
def f(r, field):
    v, _ = o.latest(r["runs"], r["idx"], field); return v
def prov(case):
    p = C + case + "/case.json"
    return json.load(open(p, encoding="utf-8")).get("provenance") if os.path.exists(p) else "?"
marked = [r for r in o.rows if "unverified" in str(r["cpi"]) or "partial" in str(r["cpi"])]
assert len(marked) == 32, len(marked)
a = [r for r in marked if r["set"] == "obs-arithmetic"]
d = [r for r in marked if r["set"] == "obs-date" and "provenance" in str(r["cpi"])]
z = [r for r in marked if "partial" in str(r["cpi"])]
t = [r for r in marked if "normalization" in str(r["cpi"])]
assert (len(a), len(d), len(z), len(t)) == (15, 13, 3, 1), (len(a), len(d), len(z), len(t))
for r in marked:
    assert f(r, "cpiOutput") and os.path.exists(o.ROOT + r["set"] + "/" + f(r, "cpiOutput")), f(r, "case")
for r in a + d + z:
    assert f(r, "parameterVerification") == "VERIFIED_PASS", f(r, "case")
assert f(t[0], "parameterVerification") is None
both = [r for r in o.rows if str(r["po"]) == "NOT_RUN" and str(r["cpi"]) == "NOT_RUN"]
assert len(both) == 10 and len(o.rows) == 195
blocked = [r for r in o.rows if "BLOCKED" in str(r["cpi"])]
assert len(blocked) == 1 and f(blocked[0], "mapping") == "mc-opt" and blocked[0]["po"] == "observed"
done = [r for r in o.rows if r not in marked and r not in both and r not in blocked]
assert len(done) == 152, len(done)
RD = {"output-confirmed-parameter-unverified": 0, "output-confirmed-parameter-and-monitor-time-unverified": 0,
      "output-confirmed-default-and-monitor-time-unverified": 0}
for r in a + d: RD[f(r, "reading")] += 1
assert list(RD.values()) == [24, 3, 1], RD
nat = sorted(f(r, "case") for r in marked if f(r, "nativeDefaultEvidence"))
assert nat == ["fn/currentDate/001", "fn/dateTrans/006"], nat
Q = "Exact JVM zone ID and message-monitor start times remain unverified"
def check_text(r): return open(o.ROOT + r["set"] + "/" + f(r, "cpiEvidence"), encoding="utf-8").read()
nq = sum(1 for r in d if Q in check_text(r))
assert nq == 11
Q1 = "Exact JVM zone ID and live editor default remain unverified"
Q6 = "Environment provenance and prohibited time-zone variants are not established by this audit"
for r in d:
    if Q not in check_text(r):
        assert (f(r, "case"), (Q1 if f(r, "case") == "fn/currentDate/001" else Q6) in check_text(r)) in (
            ("fn/currentDate/001", True), ("fn/dateTrans/006", True)), f(r, "case")
assert all(Q6 in check_text(r) for r in a + d)
assert all("JVM locale and arithmetic implementation remain unverified" in check_text(r) for r in a)
def later(r):
    return "`parameterVerification`" + (", `nativeDefaultEvidence`" if f(r, "nativeDefaultEvidence") else "")
def row(r, last):
    return "| `%s` | `%s` | `%s` | `%s` | %s | %s | %s |" % (f(r, "case"), f(r, "mapping"), f(r, "input"),
                                                        f(r, "cpiOutput"), prov(f(r, "case")), later(r), last)
L = []
A = L.append
A("# Follow-up list for the next observation session (2026-11-16)")
A("")
A("For the owner. Nothing here is a new case and nothing here is waiting: this is the list of what the")
A("observation logs still mark on runs that were made, with the one thing that would settle each mark.")
A("Asked by D-164 (7) (`docs/DECISIONS.md`, MAP-14). A list obliges no one: only the owner runs SAP, and")
A("every item says what stays true if it is never run. No case, expected output, ratchet row or log")
A("entry changes with this file. Notation and what to return: `README.md`.")
A("")
A("How it was read: each set's `meta.json` only grows, and a later entry of a run replaces an earlier")
A("one. A run is a mapping on an input; the entries of one run were linked - four runs carry one")
A("mapping name on PO and another on Cloud Integration - and the latest value of each field taken. A")
A("case alone does not name a run: several mappings test one case. That gives 195 runs in 14 sets: 152")
A("observed on both systems (an output, or the error); 32 whose latest SAP Cloud Integration entry")
A("carries a mark (parts 1 to 3); 1 observed on PO and blocked on Cloud Integration (part 4, with the")
A("one corpus case that is still `assumed`); 10 made on neither system (part 5).")
A("")
A("## Part 1 - the environment of the runs (28 marks, no mapping to run again)")
A("")
A("The 28 runs below have their Cloud Integration output recorded, and the latest entry says")
A("`observed-output-runtime-provenance-unverified`. Their `reading` names what was open when the output")
A("was recorded: `output-confirmed-parameter-unverified` (24 runs),")
A("`output-confirmed-parameter-and-monitor-time-unverified` (`fn/currentDate/002` to `004`) and")
A("`output-confirmed-default-and-monitor-time-unverified` (`fn/currentDate/001`). Later fields of the")
A("same entries answer part of it. The parameter half: `parameterVerification` is `VERIFIED_PASS` on all")
A("28 (2026-11-13; the evidence is `cpi/parameter-audit/<artifact>.json` of the set). The default half:")
A("`nativeDefaultEvidence` on `fn/currentDate/001` and `fn/dateTrans/006` - the editor's default format")
A("was read (`cpi/native-default-currentDate-001.txt`: \"Date Format yyyy/MM/dd\"). What is still open is")
A("the environment, in the check files' own words: \"JVM locale and arithmetic implementation remain")
A("unverified\" (all 15 arithmetic check files) and \"Exact JVM zone ID and message-monitor start times")
A("remain unverified\" (11 of the 13 date check files; `fn/currentDate/001`'s says \"Exact JVM zone ID")
A("and live editor default remain unverified\"). All 28 close with \"Environment provenance and")
A("prohibited time-zone variants are not established by this audit\" - for `fn/dateTrans/006` that is")
A("the only open sentence. These are the questions the requests already asked")
A("(`obs-arithmetic/request.md` :32-34, `obs-date/request.md` :10-13 and :58-59); one answer serves")
A("every run of its group.")
A("")
A("| | What to read | Settles |")
A("| --- | --- | --- |")
A("| E1 | Cloud Integration: the default locale of the JVM that ran the mappings (it gives `DecimalFormat` its decimal "
  "and grouping separators); the arithmetic mode, if it can be seen at all | the 15 `formatNum` runs |")
A("| E2 | Cloud Integration: the default time zone of that JVM, by its zone ID. The recorded outputs are written at "
  "`+0000` / `Z`, which fits UTC; the ID itself is not recorded | the 13 date runs, with E4 for four of them |")
A("| E3 | PO 7.5: the default time zone of the server's JVM, by its zone ID. `obs-date/meta.json` has "
  "`po75.jvmDefaultTimezone` null; its outputs of 2026-11-12 are written at `+0100` (\"Named JVM zone unknown\") | "
  "nothing marked on Cloud Integration; it completes the PO half of part 2 |")
A("| E4 | The four `currentDate` runs were bracketed by an outside clock (`currentDate-001-current-date-time-check.txt`: "
  "\"This is not a message-monitor start timestamp, so equality to processing start is unverified\"). One run of each whose processing start is read from "
  "the message monitor, with its zone, beside the output | `fn/currentDate/001` to `004` |")
A("")
A("The recorded outputs came from the mapping editor's simulation (\"uploaded through simulator\"). The")
A("fact that counts is the setting of the JVM that produced them. If E1 and E2 are read somewhere else -")
A("for example in a deployed integration flow, by a script step that writes")
A("`java.util.Locale.getDefault()` and `java.util.TimeZone.getDefault().getID()` (a suggestion of this")
A("list, not tried) - say where, and say whether one mapping run in the same place gives the recorded")
A("output: `fmt-008` on `formatNum-008.xml` shows both separators, `date-trans-003` on")
A("`dateTrans-003.xml` writes in the JVM's zone.")
A("")
A("If none of it is read: the outputs stay recorded and marked, 21 of the 32 marked runs stay the")
A("evidence of an `observed` case and 11 of a `decided` one, as today.")
A("")
A("| Case | Mapping | Input | Recorded output (under the set's directory) | Case today | Later fields that answer "
  "part of the mark | Settled by |")
A("| --- | --- | --- | --- | --- | --- | --- |")
for r in a: A(row(r, "E1"))
for r in d:
    A(row(r, "E2 and E4" if str(f(r, "case")).startswith("fn/currentDate/") else "E2"))
A("")
A("The 15 `formatNum` runs are in `obs-arithmetic/`, the 13 date runs in `obs-date/`.")
A("")
A("## Part 2 - runs that asked for a time zone the system was not in (3 marks, nothing to run)")
A("")
A("Five date cases ask for a JVM in a named zone. PO 7.5 ran at `+01:00` and Cloud Integration at")
A("`+0000`, so each system met some of them and its other runs are `partial`. The owner decided these")
A("cases on 2026-11-13 (the mapping's F-43, #212): \"The time zone of a run is the service's (UTC by")
A("default). The parts observed in UTC are cited as partial evidence.\" Every one of them is `decided`")
A("in the corpus. A run in the asked zone would add evidence and change no decision. The logs say of")
A("these runs \"No environment settings changed\"; whether a system's zone can be set for a run is not")
A("recorded.")
A("")
A("| Case | Asks for | PO 7.5 (2026-11-12) | Cloud Integration (2026-11-13) | Left open |")
A("| --- | --- | --- | --- | --- |")
A("| `fn/dateTrans/003` | UTC | `partial`: written at `+0100` | recorded at `+0000` (part 1, E2) | E2 |")
A("| `fn/dateTrans/012` | UTC | `partial`: written at `+01:00` | recorded (part 1, E2) | E2 |")
A("| `fn/dateTrans/004` | `+01:00` | observed | `partial-environment-timezone`: written at `+0000` | E3 names the "
  "zone PO met it in |")
A("| `fn/compareDates/004` | `+01:00` | observed | `partial-environment-timezone`: \"consistent with UTC "
  "interpretation, not the requested -1, 0\" | E3 |")
A("| `fn/dateTrans/005` | `-04:00` | `partial`: written at `+01:00` | `partial-environment-timezone`: written at `Z` "
  "| met on neither system |")
A("")
A("The three marks of this part are the Cloud Integration entries of `fn/dateTrans/004`,")
A("`fn/dateTrans/005` and `fn/compareDates/004`; their outputs are under `obs-date/cpi/`.")
assert all(f(r, "cpiOutput").startswith("cpi/") for r in z)
A("")
A("## Part 3 - one input the simulation may have changed (1 mark, one run)")
A("")
r = t[0]
A("`%s`, mapping `%s`, input `%s`, in `obs-text/`; the output is" % (f(r, "case"), f(r, "mapping"), f(r, "input")))
A("`%s` and the latest entry says `%s`." % (f(r, "cpiOutput"), r["cpi"]))
A("The second source value is `&#9;&#10;x&#13;`; Cloud Integration's second `All` is empty, where PO gives")
A("the expected `x`. The check file: \"normalization during simulator upload versus mapping execution was")
A("not isolated. Do not attribute that difference to trim alone without further evidence.\" The case is")
A("`decided` (2026-11-13, the mapping's F-43 addendum, #212, #221): PO equals the expected output and")
A("the empty value is read as an artefact of the simulation's input.")
A("")
A("The one run: `text-trim` on `trim-001.xml` in a way that separates the upload from the mapping. Two")
A("ways, both suggestions of this list and not tried: a deployed flow that receives the file's bytes,")
A("so that the input reaches the mapping without the simulation's upload; or, in the simulation, a look")
A("at the second `Sku` value after the upload and before the mapping. Return the six `All` values. If")
A("it is never run, the case stays decided on PO's output and the mark stays.")
A("")
A("## Part 4 - the assumed case `target/minOccurs/004`: one blocked run, and a request not yet drawn up")
A("")
A("The one corpus case that is still `assumed`, and both systems contradict it: the case expects an")
A("error, PO 7.50 and Cloud Integration give `Grp` 2 one `Row` with `b` and the run succeeds")
A("(`obs-target-creation`, the mapping's F-38). Its follow-up (`obs-target-creation-2`, part 1) showed")
A("padding up to `minOccurs` with one mapping and the error with another, and F-38 leaves four readings")
A("(a) to (d) that fit every run. The owner deferred the case past milestone 1 (the mapping's F-53, #220,")
A("#222).")
A("")
A("What would settle it is F-38's next step: an observation that separates (a) to (d) - for example")
A("mc-memo's mapping with two `Text` under o2, and `removeContexts` over a queue with one value in its")
A("only context under `Row` 1..2 with three `Grp`. Those two mappings are not drawn up: they need a")
A("request of their own (boxes, inputs, structures, in the form of `obs-target-creation-2/request.md`),")
A("which is a corpus task and not this list. Until then the case stays listed, assumed and deferred.")
A("")
A("One run of that follow-up was made on PO only: `mc-opt` on `minOccurs-004.xml`")
A("(`obs-target-creation-2/meta.json`; `Row` 0..2). PO: \"Grp 1 has Row a,b; Grp 2 has no Row when Row is")
A("optional.\" Cloud Integration: `ENVIRONMENT_BLOCKED` - \"The required in-place mapping is locked by a")
A("previous session. [...] No unlock or edit was performed.\" No later entry replaces it. It is the one")
A("requested run in the logs that was not made and is neither undrawable nor excluded: to redo on Cloud")
A("Integration once the lock is gone, or to leave - F-38 reads the padding rule from PO's run and the")
A("other mappings.")
assert "The required in-place mapping is locked by a previous session." in f(blocked[0], "cpiReason")
assert "No unlock or edit was performed." in f(blocked[0], "cpiReason")
assert f(blocked[0], "summary") == "PO: Grp 1 has Row a,b; Grp 2 has no Row when Row is optional."
A("")
A("## Part 5 - runs made on neither system (10, none requestable)")
A("")
A("| Set | Runs | Recorded reason |")
A("| --- | --- | --- |")
A("| `obs-statistic` | `stat-counter-two-fields` on `counter-001.xml`, `stat-counter` on `counter-002.xml`, "
  "`stat-counter-suppress` on `counter-003.xml` | \"Native PO counter has no input connector; the "
  "requested incoming wire cannot be drawn.\" \"Exact requested graph is not drawable; no substitute simulation or "
  "runtime conclusion.\" |")
A("| `obs-ifwithoutelse` | `iwe-1` on `i1.xml` and on `i2.xml` | \"PO counter has no input connector; requested Sku -> "
  "N1/N2 wires cannot be drawn.\" \"CPI counter has no input port\" |")
A("| `obs-shared-box` | `shared-1` and `shared-2` on `s1.xml` | \"PO counter has no input connector, so the requested Id -> counter "
  "wires cannot be drawn.\" \"CPI counter has no input connector\" |")
A("| `obs-structures` | `str-nil` on `nil.json` | \"PO message mapping reads XML only; the case tests the adapter's / "
  "converter's JSON to XML conversion, not the mapping\" - \"Explicitly excluded by the owner's observation prompt on "
  "both systems.\" |")
A("| `obs-lift-reuse` | `spec2-create-if` on `createIf-002.xml`, `spec2-create-if-twice` on `createIf-003.xml` | \"Explicitly superseded and marked Skip this run "
  "in request.md; covered by obs-target-creation-2\" |")
A("")
A("Seven cannot be drawn in either editor (the counter has no input), one the owner excluded, two were")
A("withdrawn by their own request and are covered by a later set. That leaves 193 requested runs: eight")
A("made on neither system, one on PO only (part 4), 184 on both.")
A("")
A("## Beside the list")
A("")
A("Three logs carry a top-level note older than their latest entries: `obs-arithmetic/meta.json` and")
A("`obs-text/meta.json` say \"CPI observations pending\", `obs-date/meta.json` says \"CPI API preparation")
A("is not simulation evidence\". The runs' latest entries govern (the check files say so); the notes are")
A("left as recorded.")
text = "\n".join(L) + "\n"
for i, l in enumerate(text.split("\n"), 1):
    if not l.startswith("|"):
        assert len(l) <= 105, (i, len(l), l[:50])
if sys.argv[-1] == "dry":
    print("dry ok,", len(L), "lines")
else:
    open(OUT, "w", encoding="utf-8").write(text); print("written", OUT, len(L), "lines")
