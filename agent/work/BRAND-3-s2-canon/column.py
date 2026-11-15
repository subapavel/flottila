#!/usr/bin/env python3
"""BRAND-3 step 2: a load error's column after the rename, from the file's text alone (not the engine).
A column is 1-based on its line; every renamed literal is `flottila` -> `rail8` (3 characters shorter) and no line
break changes, so the new column is the old one less 3 for each `flottila` on that line of the old file before it.
Usage: python3 -I column.py <path in HEAD's tree> <line> <old column>   (prints: old line text check, new column)"""
import subprocess, sys
path, line, col = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
old = subprocess.run(["git", "show", f"HEAD:{path}"], capture_output=True, text=True, check=True).stdout.split("\n")[line - 1]
before = old[: col - 1]
k = before.lower().count("flottila")
print(f"{path}:{line}:{col} -> {col - 3 * k}  ({k} renamed before it; the line is {len(old)} long; text before: ...{before[-40:]!r})")
