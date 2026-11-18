#!/usr/bin/env python3
"""JaCoCo no-decrease check: no module's line or branch coverage falls against a baseline.

Reads every module's target/site/jacoco/jacoco.xml (none under mapping/, which has its own gates, or under examples/, the
samples for plugin authors, which have none), computes line and branch coverage in percent
rounded to one decimal, and compares with a baseline file: the nightly CI job gives the previous
night's measurement, which it keeps in the Actions cache. A module whose coverage dropped by more
than TOLERANCE percentage points (a noise band; the JaCoCo floors in the poms stay exact) fails the
check; a module that has compiled classes (other than package-info) but no report fails too,
because jacoco:check silently skips a module in which no test ran. New modules without a baseline
pass, and so does every module when the baseline file is not there (the first night), which the
output says. `--write` writes every value as measured (no high-water mark): a baseline describes
the tree it was measured on; TOLERANCE is the tolerance of the comparison, never of the file.

Usage:
  python3 ci/coverage_check.py --baseline-file <file>          # compare against that file (CI: the previous night's)
  python3 ci/coverage_check.py --baseline-file <file> --write  # write this measurement to that file (CI: for the next night)
  python3 ci/coverage_check.py --baseline-ref <sha>            # compare against the baseline file as committed at that commit
  python3 ci/coverage_check.py --check-baseline                # the baseline file describes this tree (±TOLERANCE)
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BASELINE = Path("ci") / "coverage-baseline.json"
REPORT = Path("target") / "site" / "jacoco" / "jacoco.xml"
CLASSES = Path("target") / "classes"
COUNTERS = {"line": "LINE", "branch": "BRANCH"}
# modules allowed to have classes without a JaCoCo report; keep it empty
IGNORED: frozenset[str] = frozenset()
# timing-dependent branches (e.g. the testkit's thread-state sampler) move by about one branch
# between runs; a change inside this band, in percentage points, is noise, not a decrease. The JaCoCo
# floors in the poms stay hard.
TOLERANCE = 2.0
# the mapping engine has its own gates and the examples are samples for plugin authors, with no coverage gate: this guard
# reads platform/ and bench/, never a module under mapping/ or examples/
FOREIGN = frozenset({"mapping", "examples"})


def _ours(path: Path, root: Path) -> bool:
    return path.resolve().relative_to(root.resolve()).parts[0] not in FOREIGN


def _percent(missed: int, covered: int) -> float:
    total = missed + covered
    return round(100.0 * covered / total, 1) if total else 100.0


def _has_real_classes(classes_dir: Path) -> bool:
    return any(p.name != "package-info.class" for p in classes_dir.rglob("*.class"))


def _module_name(module_dir: Path, root: Path) -> str:
    return module_dir.resolve().relative_to(root.resolve()).as_posix()


def modules_with_classes(root: Path) -> list[Path]:
    return sorted(m for m in root.rglob(str(CLASSES)) if _ours(m, root) and _has_real_classes(m))


def measure(root: Path) -> dict[str, dict[str, float]]:
    """Coverage per module name for every module that has a JaCoCo report."""
    result: dict[str, dict[str, float]] = {}
    for report in sorted(r for r in root.rglob(str(REPORT)) if _ours(r, root)):
        counters = {c.get("type"): c for c in ET.parse(report).getroot().findall("counter")}
        line = counters.get("LINE")
        if line is None or int(line.get("missed")) + int(line.get("covered")) == 0:
            continue  # nothing measurable (package-info only): no number, so no baseline either
        values = {}
        for key, jacoco_type in COUNTERS.items():
            c = counters.get(jacoco_type)
            values[key] = _percent(int(c.get("missed")), int(c.get("covered"))) if c is not None else 100.0
        result[_module_name(report.parents[3], root)] = values
    return result


def compare(current: dict[str, dict[str, float]], baseline: dict[str, dict[str, float]], root: Path,
            ignore: frozenset[str] = IGNORED) -> list[str]:
    problems: list[str] = []
    for classes_dir in modules_with_classes(root):
        name = _module_name(classes_dir.parents[1], root)
        if name not in current and name not in ignore:
            problems.append(f"{name}: has classes but no JaCoCo report (no test ran)")
    for name, values in sorted(current.items()):
        base = baseline.get(name)
        if base is None:
            continue
        for key in COUNTERS:
            if values[key] < base.get(key, 0.0) - TOLERANCE:
                problems.append(f"{name}: {key} {values[key]} % < baseline {base[key]} % - {TOLERANCE}")
    return problems


def check_baseline(current: dict[str, dict[str, float]], committed: dict[str, dict[str, float]]) -> list[str]:
    """The committed baseline must describe this very tree: every measured module present, none extra,
    and no value more than TOLERANCE below the measurement (a forgotten `--write` after coverage rose)."""
    problems: list[str] = []
    for name, values in sorted(current.items()):
        base = committed.get(name)
        if base is None:
            problems.append(f"{name}: not in the committed baseline — run --write")
            continue
        for key in COUNTERS:
            if values[key] > base.get(key, 0.0) + TOLERANCE:
                problems.append(f"{name}: {key} {values[key]} % is above the committed baseline {base[key]} % by more than {TOLERANCE} — run --write")
            elif values[key] < base.get(key, 0.0) - TOLERANCE:
                problems.append(f"{name}: {key} {values[key]} % is below the committed baseline {base[key]} % by more than {TOLERANCE} — coverage dropped; run --write and say why")
    for name in sorted(committed):
        if name not in current:
            problems.append(f"{name}: in the committed baseline but not measured — run --write")
    return problems


def write_baseline(current: dict[str, dict[str, float]], path: Path) -> None:
    """Writes the measurement as measured, always: the file describes the tree it was measured on,
    nothing remembered from earlier trees. TOLERANCE is the check's band, never the
    file's. Modules no longer measured are dropped."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(current, indent=2, sort_keys=True) + "\n")


class BaselineError(RuntimeError):
    """The baseline could not be read from the given git ref (anything but "file absent there")."""


def _load_baseline(ref: str | None, path: Path, root: Path) -> dict:
    if not ref:
        return json.loads(path.read_text()) if path.exists() else {}
    rel = path.resolve().relative_to(root.resolve()).as_posix()
    env = dict(os.environ, LC_ALL="C", LANGUAGE="C")  # the "absent" test below reads git's English messages
    out = subprocess.run(["git", "show", f"{ref}:{rel}"], cwd=root, capture_output=True, text=True, env=env)
    if out.returncode == 0:
        return json.loads(out.stdout)
    absent = ("does not exist in" in out.stderr) or ("exists on disk, but not in" in out.stderr)
    if absent:
        return {}  # first commit that introduces the baseline: nothing to compare against
    raise BaselineError(f"cannot read {rel} at {ref}: {out.stderr.strip()}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="repository root (default: .)")
    ap.add_argument("--baseline-file", default=None, help=f"baseline JSON (default: <root>/{BASELINE.as_posix()})")
    ap.add_argument("--baseline-ref", default=None, help="git ref whose committed baseline file to compare against")
    ap.add_argument("--write", action="store_true", help="rewrite the baseline file from the current reports")
    ap.add_argument("--check-baseline", action="store_true",
                    help="verify the committed baseline file describes this tree (no forgotten --write)")
    ap.add_argument("--ignore", default=",".join(sorted(IGNORED)),
                    help="comma-separated modules allowed to have classes without a report (default: %(default)s)")
    args = ap.parse_args(argv)
    root = Path(args.root)
    path = Path(args.baseline_file) if args.baseline_file else root / BASELINE
    current = measure(root)
    for name, v in sorted(current.items()):
        print(f"{name}: line {v['line']} %, branch {v['branch']} %")
    if args.write:
        write_baseline(current, path)
        print(f"baseline written to {path}")
        return 0
    if args.check_baseline:
        committed = json.loads(path.read_text()) if path.exists() else {}
        problems = check_baseline(current, committed)
        for p in problems:
            print(f"STALE BASELINE: {p}", file=sys.stderr)
        print("baseline: current" if not problems else f"baseline: {len(problems)} problem(s)")
        return 1 if problems else 0
    ignore = frozenset(x for x in args.ignore.split(",") if x)
    try:
        baseline = _load_baseline(args.baseline_ref, path, root)
    except BaselineError as e:
        print(f"COVERAGE BASELINE ERROR: {e}", file=sys.stderr)
        return 2
    if not args.baseline_ref and not path.exists():
        print(f"no baseline at {path}: nothing compared")
    problems = compare(current, baseline, root, ignore)
    for p in problems:
        print(f"COVERAGE DECREASE: {p}", file=sys.stderr)
    print("coverage: no decrease" if not problems else f"coverage: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
