#!/usr/bin/env python3
"""FIX-263, acceptance 6: per-class JaCoCo comparison, before (agent/work/FIX-263-before/*.csv) against after
(each module's target/site/jacoco/jacoco.csv of the run just made). Prints, per module: the totals before and after,
every class that left or joined, and every surviving class whose missed lines or missed branches grew.
Run from the repository root after the whole-reactor run: python3 agent/work/FIX-263-cmp.py
"""
import csv
import os
import sys

MODULES = {
    "api": "platform/api", "testkit": "platform/testkit", "core": "platform/core", "schemas": "platform/schemas",
    "components-sftp": "platform/components/sftp", "components-http": "platform/components/http",
    "components-mock": "platform/components/mock", "steps-xslt": "platform/steps/xslt",
    "steps-validate": "platform/steps/validate", "runtime": "platform/runtime", "cli": "platform/cli",
    "sim": "platform/sim",
}


def load(path):
    rows = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[r["PACKAGE"] + "." + r["CLASS"]] = {k: int(r[k]) for k in (
                "LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED")}
    return rows


def pct(covered, missed):
    total = covered + missed
    return "n/a" if total == 0 else "%.2f%%" % (100.0 * covered / total)


def totals(rows):
    lm = sum(r["LINE_MISSED"] for r in rows.values())
    lc = sum(r["LINE_COVERED"] for r in rows.values())
    bm = sum(r["BRANCH_MISSED"] for r in rows.values())
    bc = sum(r["BRANCH_COVERED"] for r in rows.values())
    return "line %s (%d missed of %d), branch %s (%d missed of %d)" % (
        pct(lc, lm), lm, lm + lc, pct(bc, bm), bm, bm + bc)


def main():
    grew = 0
    for name, path in MODULES.items():
        before_path = os.path.join("agent/work/FIX-263-before", name + ".csv")
        after_path = os.path.join(path, "target/site/jacoco/jacoco.csv")
        if not os.path.exists(after_path):
            print("%s: NO AFTER FILE at %s" % (name, after_path))
            grew += 1
            continue
        before, after = load(before_path), load(after_path)
        print("== %s" % name)
        print("   before: %s" % totals(before))
        print("   after:  %s" % totals(after))
        for c in sorted(set(before) - set(after)):
            print("   left:   %s (lines %d, missed %d)" % (
                c, before[c]["LINE_MISSED"] + before[c]["LINE_COVERED"], before[c]["LINE_MISSED"]))
        for c in sorted(set(after) - set(before)):
            print("   joined: %s (lines %d, missed %d)" % (
                c, after[c]["LINE_MISSED"] + after[c]["LINE_COVERED"], after[c]["LINE_MISSED"]))
        for c in sorted(set(before) & set(after)):
            b, a = before[c], after[c]
            if a["LINE_MISSED"] > b["LINE_MISSED"] or a["BRANCH_MISSED"] > b["BRANCH_MISSED"]:
                grew += 1
                print("   GREW:   %s lines missed %d -> %d (of %d -> %d), branches missed %d -> %d (of %d -> %d)" % (
                    c, b["LINE_MISSED"], a["LINE_MISSED"], b["LINE_MISSED"] + b["LINE_COVERED"],
                    a["LINE_MISSED"] + a["LINE_COVERED"], b["BRANCH_MISSED"], a["BRANCH_MISSED"],
                    b["BRANCH_MISSED"] + b["BRANCH_COVERED"], a["BRANCH_MISSED"] + a["BRANCH_COVERED"]))
    print("classes whose missed lines or branches grew (or modules without an after file): %d" % grew)
    return 0


if __name__ == "__main__":
    sys.exit(main())
