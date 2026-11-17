#!/usr/bin/env python3
"""BRAND-5 acceptance 2: the change is a pure renaming.

For every tracked file that differs from HEAD (paired with its HEAD path by mapping `dev/rail8` back to `io/rail8`),
outside the tool, the guard, its tests and the allow-list: the multiset of its lines after mapping `dev` back to `io`
equals the multiset of its lines before, mapped the same way (so a `dev.rail8` that stood in the file before, as in the
architect's sentences of docs/ARCHITECTURE.md, compares with itself). "Before" is HEAD, except docs/ARCHITECTURE.md,
whose before is the architect's uncommitted text, snapshotted before the tool ran (agent/work/BRAND-5-ARCHITECTURE-before.md).

Prints: files compared; files whose lines differ as a multiset (each with the lines that differ); files whose lines are
the same multiset in another order (import blocks re-sorted); tracked files added or removed.
Usage: python3 -I agent/work/BRAND-5-pure.py <repo>
"""
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
OUTSIDE = {"agent/tools/rail8_rename.py", "ci/name_guard.py", "ci/test_name_guard.py", "ci/name-allowlist.txt"}
BEFORE = {"docs/ARCHITECTURE.md": repo / "agent/work/BRAND-5-ARCHITECTURE-before.md"}
BACK = re.compile(r"(?<![A-Za-z0-9_])dev(\.|/|\\\.|\\\\\.)rail8")
BACK_SEGMENTS = re.compile(r'"dev"(, | / )"rail8"')


def back(text: str) -> str:
    return BACK_SEGMENTS.sub(r'"io"\1"rail8"', BACK.sub(r"io\1rail8", text))


def git(*args: str) -> bytes:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, check=True).stdout


now = [p for p in git("ls-files", "-z").decode().split("\0") if p]
head = set(p for p in git("ls-tree", "-r", "-z", "--name-only", "HEAD").decode().split("\0") if p)
paired = {back(p): p for p in now}
added = sorted(p for b, p in paired.items() if b not in head)
removed = sorted(h for h in head if h not in paired)
compared, unequal, reordered = 0, [], []
for old, new in sorted(paired.items()):
    if old not in head or new in OUTSIDE:
        continue
    file = repo / new
    after = file.read_bytes() if file.is_file() else b""
    before = BEFORE[new].read_bytes() if new in BEFORE else git("show", f"HEAD:{old}")
    if after == before:
        continue
    compared += 1
    a = back(after.decode("utf-8")).split("\n")
    b = back(before.decode("utf-8")).split("\n")
    if a == b:
        continue
    if Counter(a) == Counter(b):
        reordered.append(new)
    else:
        only_after = Counter(a) - Counter(b)
        only_before = Counter(b) - Counter(a)
        unequal.append((new, only_before, only_after))
print(f"files compared (changed, outside {', '.join(sorted(OUTSIDE))}): {compared}")
print(f"lines differ as a multiset after mapping dev back to io: {len(unequal)} file(s)")
for path, ob, oa in unequal:
    print(f"  {path}")
    for line, n in ob.items():
        print(f"    - ({n}) {line[:200]}")
    for line, n in oa.items():
        print(f"    + ({n}) {line[:200]}")
print(f"same lines in another order (import blocks re-sorted): {len(reordered)} file(s)")
for path in reordered:
    print(f"  {path}")
print(f"tracked files added: {len(added)} {added}; removed: {len(removed)} {removed}")
