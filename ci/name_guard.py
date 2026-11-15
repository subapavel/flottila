#!/usr/bin/env python3
"""Old-name guard (BRAND-4, D-148, D-149, D-154): the product's old name, `flottila`, and its old namespace,
`io.rail8`, do not come back.

Scans every file git tracks (`git ls-files -z` at the top level of the working directory; untracked and
ignored files are never read) for the name in any letter case, in the file's path and in its content. The
old namespace counts as the name: `io.rail8`, `io/rail8` and the regex-escaped `io\\.rail8` and `io\\\\.rail8`,
in that letter case, unless a letter, digit or `_` stands directly before `io` (`studio.rail8` is another
word); `dev.rail8` and `rail8.io` are not the name.
Every occurrence must be allowed by an entry of ci/name-allowlist.txt, read from disk at the same top level
whether it is tracked or not. One entry per line, `#` comments and blank lines ignored:

  <path glob> | <literal or *> | <reason>

The glob is matched against the whole tracked path: `**` matches any run of characters, `/` included, `*`
any run without `/`; every other character stands for itself. `*` as the second cell allows every
occurrence in the matched files, their paths included; a literal allows only the occurrences it covers,
matched exactly as written (letter case included), in a line, in the path, or in the bytes of a file that
is not UTF-8. An occurrence is credited to one entry: a matching `*` entry first (the first in the list),
else the longest covering literal (the first in the list on a tie). An entry credited with nothing in the
whole run is itself a finding, so the list shrinks when an entry's reason goes away.

Findings, one per line: `path:line: text` (one per line of a file however often the name occurs in it, at
most WIDTH characters of the line around the first occurrence not allowed); `path: the path holds the name`;
`path: not UTF-8, the content holds the name` (one per such file); `ci/name-allowlist.txt:<n>: unused
allow-list entry: <entry>`. The last line counts them. Exit 0 clean (one line with the number of files
scanned), 1 findings, 2 a malformed allow-list entry (named by its line) or a usage error.

Usage:
  python3 ci/name_guard.py   # from anywhere inside the repository; CI runs it from the root
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# the old name in any letter case, or the old namespace (D-154) not inside a longer word
_NAMESPACE = r"(?<![A-Za-z0-9_])io(?:\.|/|\\\.|\\\\\.)rail8"
NAME = re.compile("(?i:flottila)|" + _NAMESPACE, re.ASCII)
NAME_BYTES = re.compile(b"(?i:flottila)|" + _NAMESPACE.encode("ascii"))
ALLOWLIST = "ci/name-allowlist.txt"
STAR = "*"
WIDTH = 160
LEAD = 40  # characters kept before the first finding when a long line is cut to WIDTH


class Entry:
    """One allow-list line; `literal` is None for a `*` entry."""

    def __init__(self, line: int, text: str, glob: str, literal: str | None):
        self.line = line
        self.text = text
        self.pattern = glob_pattern(glob)
        self.literal = literal
        self.used = False


def glob_pattern(glob: str) -> re.Pattern[str]:
    parts = re.split(r"(\*\*|\*)", glob)
    return re.compile("".join(".*" if p == "**" else "[^/]*" if p == "*" else re.escape(p) for p in parts),
                      re.DOTALL)


def parse_allowlist(text: str) -> tuple[list[Entry], list[str]]:
    """The entries and one problem per malformed line (`ci/name-allowlist.txt:<n>: ...`)."""
    entries: list[Entry] = []
    problems: list[str] = []
    for n, raw in enumerate(text.split("\n"), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cells = [c.strip() for c in line.split("|", 2)]
        why = ("fewer than three cells" if len(cells) < 3 else "an empty glob" if not cells[0]
               else "an empty literal" if not cells[1] else "no reason" if not cells[2] else None)
        if why:
            problems.append(f"{ALLOWLIST}:{n}: malformed allow-list entry ({why}): {line}")
        else:
            entries.append(Entry(n, line, cells[0], None if cells[1] == STAR else cells[1]))
    return entries, problems


def first_not_allowed(text: str, star: Entry | None, literals: list[Entry]) -> int:
    """Credits every occurrence of the name in `text` to its entry (`literals` longest first); the position of
    the first occurrence no entry allows, or -1."""
    first = -1
    for m in NAME.finditer(text):
        if star is not None:
            star.used = True
            continue
        # a literal covers the occurrence when one of its matches starts at or before it and ends at or after it
        owner = next((e for e in literals
                      if text.find(e.literal, max(0, m.end() - len(e.literal)), m.start() + len(e.literal)) >= 0),
                     None)
        if owner is not None:
            owner.used = True
        elif first < 0:
            first = m.start()
    return first


def excerpt(line: str, at: int) -> str:
    if len(line) <= WIDTH:
        return line
    start = min(max(0, at - LEAD), len(line) - WIDTH)
    return line[start:start + WIDTH]


def scan(path: str, data: bytes, entries: list[Entry]) -> list[str]:
    """The findings of one tracked file; `entries` are those whose glob matches its path."""
    findings: list[str] = []
    star = next((e for e in entries if e.literal is None), None)
    literals = sorted((e for e in entries if e.literal is not None), key=lambda e: -len(e.literal))
    if first_not_allowed(path, star, literals) >= 0:
        findings.append(f"{path}: the path holds the name")
    if not NAME_BYTES.search(data):
        return findings
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        # latin-1 maps every byte to one character, so the name's bytes stay the name and literals still apply
        if first_not_allowed(data.decode("latin-1"), star, literals) >= 0:
            findings.append(f"{path}: not UTF-8, the content holds the name")
        return findings
    for n, line in enumerate(text.split("\n"), start=1):
        line = line.rstrip("\r")
        at = first_not_allowed(line, star, literals)
        if at >= 0:
            findings.append(f"{path}:{n}: {excerpt(line, at)}")
    return findings


def git(cwd: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    top = git(Path.cwd(), "rev-parse", "--show-toplevel")
    if top.returncode != 0:
        print(f"NAME GUARD: not inside a git working tree: {top.stderr.decode(errors='replace').strip()}",
              file=sys.stderr)
        return 2
    root = Path(top.stdout.decode("utf-8", errors="surrogateescape").strip())
    allowlist = root / ALLOWLIST
    if not allowlist.is_file():
        print(f"NAME GUARD: {ALLOWLIST}: allow-list not found at {allowlist}", file=sys.stderr)
        return 2
    raw = allowlist.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        line = raw.count(b"\n", 0, e.start) + 1
        print(f"{ALLOWLIST}:{line}: malformed allow-list: not UTF-8 ({e.reason})", file=sys.stderr)
        return 2
    entries, problems = parse_allowlist(text)
    if problems:
        for p in problems:
            print(p, file=sys.stderr)
        print(f"name guard: {len(problems)} malformed allow-list entr{'y' if len(problems) == 1 else 'ies'}",
              file=sys.stderr)
        return 2
    listed = git(root, "ls-files", "-z")
    if listed.returncode != 0:
        print(f"NAME GUARD: git ls-files failed: {listed.stderr.decode(errors='replace').strip()}", file=sys.stderr)
        return 2
    # an unmerged path is listed once per stage
    paths = list(dict.fromkeys(p for p in listed.stdout.decode("utf-8", errors="surrogateescape").split("\0") if p))
    findings: list[str] = []
    for path in paths:
        full = root / path
        # git stores a symbolic link as its text: scan that, never the target. A tracked path deleted from the
        # working tree, or a submodule, has no content to read; its path still counts.
        data = (os.fsencode(os.readlink(full)) if full.is_symlink()
                else full.read_bytes() if full.is_file() else b"")
        findings += scan(path, data, [e for e in entries if e.pattern.fullmatch(path)])
    findings += [f"{ALLOWLIST}:{e.line}: unused allow-list entry: {e.text}" for e in entries if not e.used]
    for f in findings:
        print(f)
    if findings:
        print(f"name guard: {len(findings)} finding(s)")
        return 1
    print(f"name guard: clean, {len(paths)} tracked files scanned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
