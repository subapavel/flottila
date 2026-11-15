#!/usr/bin/env python3
"""Assembles the static website for rail8.dev (BRAND-6) into a directory that GitHub Pages serves.

Usage:
  python3 ci/site_build.py <out-dir>     (from anywhere; paths are taken from this file's repository)

Copies every file of site/ to the same relative path under <out-dir>, byte for byte, and under <out-dir>/brand/
the files of docs/brand/ that the HTML and CSS files of site/ refer to as "brand/<file>" (relative or
root-relative), and nothing else: the brand files exist once in the repository, in docs/brand/. <out-dir> need
not exist; an existing one must be empty. Exits 0 on success, 1 when a page refers to a brand file that does not
exist or site/ holds a brand/ directory or a CNAME file, 2 on a usage error. Standard library only.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SITE = REPO / "site"
BRAND = REPO / "docs" / "brand"
BRAND_REF = re.compile(r"(?<![A-Za-z0-9_.-])brand/([A-Za-z0-9_.-]+)")


def brand_files(pages: list[Path]) -> set[str]:
    names = set()
    for page in pages:
        names.update(BRAND_REF.findall(page.read_text(encoding="utf-8")))
    return names


def build(out: Path) -> list[str]:
    """Writes the site into `out`; returns the errors (nothing is written when there is one)."""
    if out.exists() and (not out.is_dir() or any(out.iterdir())):
        return [f"{out}: exists and is not an empty directory"]
    sources = sorted(p for p in SITE.rglob("*") if p.is_file())
    errors = [f"site/{p.relative_to(SITE).as_posix()}: site/ holds no brand file and no CNAME; "
              "brand files are read from docs/brand/" for p in sources
              if p.name == "CNAME" or p.relative_to(SITE).parts[0] == "brand"]
    names = sorted(brand_files([p for p in sources if p.suffix in (".html", ".css")]))
    errors += [f"brand/{name}: referred to by a page but not in docs/brand/" for name in names
               if not (BRAND / name).is_file()]
    if errors:
        return errors
    for source in sources:
        target = out / source.relative_to(SITE)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (out / "brand").mkdir(parents=True, exist_ok=True)
    for name in names:
        shutil.copyfile(BRAND / name, out / "brand" / name)
    return []


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: python3 ci/site_build.py <out-dir>", file=sys.stderr)
        return 2
    errors = build(Path(argv[1]))
    for line in errors:
        print(line, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
