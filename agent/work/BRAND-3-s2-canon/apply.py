#!/usr/bin/env python3
"""BRAND-3 step 2: writes each derived fingerprint (derived.txt, from derive.py) wherever its old value stands in a
tracked file under mapping/ outside the history paths. Usage: python3 -I apply.py <repo> <derived.txt>"""
import re, subprocess, sys
from pathlib import Path
repo, derived = Path(sys.argv[1]), Path(sys.argv[2]).read_text()
pairs = {m[1]: m[2] for m in re.finditer(r"old sha256:([0-9a-f]{64}) \(reproduced\)  new sha256:([0-9a-f]{64})", derived)}
pairs = {o: n for o, n in pairs.items() if o != n}
HISTORY = ("mapping/docs/adr/", "mapping/docs/findings/", "mapping/agent/", "mapping/docs/archive/", "mapping/bench/RESULTS.md",
           "mapping/.claude/", "mapping/.github/", "mapping/AGENTS.md", "mapping/CLAUDE.md", "mapping/deploy/claude-cloud/")
files = subprocess.run(["git", "ls-files", "mapping"], cwd=repo, capture_output=True, text=True).stdout.split()
total = 0
for p in files:
    if p.startswith(HISTORY):
        continue
    f = repo / p
    try:
        s = f.read_text(encoding="utf-8")
    except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
        continue
    n = sum(s.count(o) for o in pairs)
    if n:
        for o, new in pairs.items():
            s = s.replace(o, new)
        f.write_text(s, encoding="utf-8")
        total += n
        print(f"{p}: {n}")
print(f"{len(pairs)} fingerprints, {total} occurrences replaced")
