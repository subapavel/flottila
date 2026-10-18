#!/usr/bin/env python3
"""Bench regression gate (WORKFLOW §4 `bench` job): every bench/out/<name>.json is compared with the
baseline for <name> recorded in bench/RESULTS.md as an HTML comment
`<!-- baseline:<name> {"machine": "<label>", "throughputPerSecond": …, "p99Ms": …} -->`.

A run is compared only with the baseline of its own machine (the run's machine.label, set through
BENCH_MACHINE): numbers from the cloud VM say nothing about a GitHub-hosted runner (D-037).
Throughput more than 20 % below the baseline fails; p99 more than 50 % above fails — the wider p99
band is the run-to-run spread observed in the three Phase 0 baseline runs (12.0–18.1 ms, D-037).
Runs without a baseline for their machine are listed, not compared; a RESULTS.md without any
marker means the gate is not armed yet (exit 0).

Usage: python3 bench/check_regression.py [--results bench/RESULTS.md] [--out bench/out]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

THROUGHPUT_LIMIT = 20.0
P99_LIMIT = 50.0
MARKER = re.compile(r"<!--\s*baseline:(\S+)\s+(\{.*?\})\s*-->")

Key = tuple[str, str]  # (machine label, run name)


def baselines(results: Path) -> dict[Key, dict[str, float]]:
    if not results.exists():
        return {}
    found: dict[Key, dict[str, float]] = {}
    for name, body in MARKER.findall(results.read_text()):
        values = json.loads(body)
        machine = values.pop("machine", None)
        if not machine:
            raise ValueError(f"baseline marker for {name!r} names no machine; a number without its machine does not exist (CLAUDE.md)")
        found[(machine, name)] = values
    return found


def _runs(out: Path) -> dict[Key, dict]:
    runs: dict[Key, dict] = {}
    if out.exists():
        for p in sorted(out.glob("*.json")):
            run = json.loads(p.read_text())
            runs[(run.get("machine", {}).get("label", "unknown"), p.stem)] = run
    return runs


def compare(out: Path, base: dict[Key, dict[str, float]]) -> list[str]:
    problems: list[str] = []
    for (machine, name), run in _runs(out).items():
        b = base.get((machine, name))
        if b is None:
            continue
        label = f"{name} [{machine}]"
        if "throughputPerSecond" in b:
            drop = 100.0 * (b["throughputPerSecond"] - run["throughputPerSecond"]) / b["throughputPerSecond"]
            if drop > THROUGHPUT_LIMIT:
                problems.append(f"{label}: throughputPerSecond {run['throughputPerSecond']} is {drop:.1f} % below the baseline {b['throughputPerSecond']} (limit {THROUGHPUT_LIMIT:.0f} %)")
        if "p99Ms" in b:
            rise = 100.0 * (run["p99Ms"] - b["p99Ms"]) / b["p99Ms"]
            if rise > P99_LIMIT:
                problems.append(f"{label}: p99Ms {run['p99Ms']} is {rise:.1f} % above the baseline {b['p99Ms']} (limit {P99_LIMIT:.0f} %)")
    return problems


def unbaselined(out: Path, base: dict[Key, dict[str, float]]) -> list[str]:
    return [f"{name} [{machine}]" for (machine, name) in _runs(out) if (machine, name) not in base]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", default="bench/RESULTS.md")
    ap.add_argument("--out", default="bench/out")
    args = ap.parse_args(argv)
    base = baselines(Path(args.results))
    if not base:
        print("bench: no baseline recorded yet — regression check not armed")
        return 0
    out = Path(args.out)
    for name in unbaselined(out, base):
        print(f"bench: {name} has no baseline for this machine (not compared)")
    problems = compare(out, base)
    for p in problems:
        print(f"BENCH REGRESSION: {p}", file=sys.stderr)
    print("bench: no regression" if not problems else f"bench: {len(problems)} regression(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
