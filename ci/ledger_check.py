#!/usr/bin/env python3
"""Ledger table-shape guard: every row of every markdown table in docs/archive/agent/BACKLOG.md and
docs/DECISIONS.md must end with an unescaped pipe and split, on pipes not preceded by a backslash, into
the header's cell count (BACKLOG: exactly; DECISIONS: at most, legacy rows without a Reason cell are
tolerated). Counting pipes is not the check: a stray separator paid for by a missing closing pipe has the
header's pipe count and the wrong cell count, and GitHub drops the cell it cannot place. An unterminated
trailing fragment counts as a cell. Reports every bad row (file, line, expected, actual), exits non-zero.

It also catches one content collision the shape rule cannot see: within one ledger file no id may
be the id cell of more than one row. Two sessions in parallel each read the same highest id and each take
the next one; the merge is the first moment one file holds both rows. A row allocates an id only when it
sits inside a table and is neither that table's header nor a separator, and the id it allocates is its first
cell -- never an id named in a Depends-on cell, a title or prose. Ids are compared whole, as text: D-020 and
D-020a are different ids. Every occurrence line of a duplicated id is reported.

Usage:
  python3 ci/ledger_check.py              # from the repository root
  python3 ci/ledger_check.py --root <dir> # a tree holding docs/archive/agent/BACKLOG.md and docs/DECISIONS.md
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# (path under the root, whether a row may have fewer cells than its header)
LEDGERS = ((Path("docs") / "archive" / "agent" / "BACKLOG.md", False), (Path("docs") / "DECISIONS.md", True))
SEPARATOR = re.compile(r":?-+:?")
UNESCAPED_PIPE = re.compile(r"(?<!\\)\|")


def is_row(line: str) -> bool:
    return line.strip().startswith("|")


def is_separator(line: str) -> bool:
    if not is_row(line):
        return False
    cells = split_row(line)[0]
    return bool(cells) and all(SEPARATOR.fullmatch(c.strip()) for c in cells)


def split_row(line: str) -> tuple[list[str], bool]:
    """The row's cells and whether it ends with an unescaped pipe. The text before the first pipe is not a
    cell; the empty part after a closing pipe is not one either, but an unterminated trailing fragment is."""
    parts = UNESCAPED_PIPE.split(line.strip())[1:]
    closed = len(parts) > 0 and parts[-1] == ""
    return (parts[:-1] if closed else parts), closed


def check_text(name: str, text: str, allow_fewer: bool) -> list[str]:
    problems: list[str] = []
    lines = text.split("\n")
    header_cells = 0
    in_table = False
    for i, line in enumerate(lines):
        if not is_row(line):
            in_table = False
            continue
        if not in_table:
            if i + 1 < len(lines) and is_separator(lines[i + 1]):
                in_table = True
                header_cells = len(split_row(line)[0])
            else:
                continue
        elif is_separator(line):
            continue
        cells, closed = split_row(line)
        n = len(cells)
        ok_count = n <= header_cells if allow_fewer else n == header_cells
        if closed and ok_count:
            continue
        want = f"at most {header_cells}" if allow_fewer else f"{header_cells}"
        why = [] if closed else ["no closing pipe"]
        if not ok_count:
            why.append("wrong cell count")
        problems.append(f"{name}:{i + 1}: expected {want} cells, found {n} ({', '.join(why)})")
    return problems


def id_cells(text: str) -> dict[str, list[int]]:
    """Id cell -> the 1-based lines of every row that allocates it. A row allocates an id when it sits inside
    a table (a header over a separator opened it) and is neither that header nor a separator; its id is the
    first cell, stripped. The whole cell text is the id, so no prefix or substring ever matches."""
    occurrences: dict[str, list[int]] = {}
    lines = text.split("\n")
    in_table = False
    for i, line in enumerate(lines):
        if not is_row(line):
            in_table = False
            continue
        if not in_table:
            # A header only opens a table when a separator follows; the header itself allocates nothing.
            in_table = i + 1 < len(lines) and is_separator(lines[i + 1])
            continue
        if is_separator(line):
            continue
        cells = split_row(line)[0]
        ident = cells[0].strip() if cells else ""
        if ident:
            occurrences.setdefault(ident, []).append(i + 1)
    return occurrences


def check_duplicate_ids(name: str, text: str) -> list[str]:
    return [f"{name}: id {ident} on {len(lns)} rows: lines {', '.join(str(n) for n in lns)}"
            for ident, lns in id_cells(text).items() if len(lns) > 1]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="repository root (default: .)")
    args = ap.parse_args(argv)
    root = Path(args.root)
    problems: list[str] = []
    duplicates: list[str] = []
    for rel, allow_fewer in LEDGERS:
        path = root / rel
        if not path.is_file():
            problems.append(f"{rel.as_posix()}: ledger file not found at {path}")
            continue
        text = path.read_text(encoding="utf-8")
        problems += check_text(rel.as_posix(), text, allow_fewer)
        duplicates += check_duplicate_ids(rel.as_posix(), text)
    for p in problems:
        print(f"LEDGER SHAPE: {p}", file=sys.stderr)
    for d in duplicates:
        print(f"LEDGER DUPLICATE ID: {d}", file=sys.stderr)
    total = len(problems) + len(duplicates)
    print("ledgers: well-formed" if not total else f"ledgers: {total} problem(s)")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
