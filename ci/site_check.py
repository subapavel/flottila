#!/usr/bin/env python3
"""Checks the static website for rail8.dev (BRAND-6) before it is deployed.

Usage:
  python3 ci/site_check.py <dir>

Prints one line per problem, "<file>: <what is wrong>" with <file> relative to <dir>, and exits 1 when there is
one, 0 when there is none (a clean run prints nothing). The rules (the contract is ci/test_site.py):
  1. <dir>/index.html exists.
  2. Every local reference of every HTML and CSS file (href, src, srcset, CSS url() and @import) resolves to a
     regular file inside <dir>; a path ending in "/" names that directory's index.html. A reference starting
     with "#", with "//" or with a URL scheme is not local.
  3. Nothing is loaded from another origin: script src, link href (except rel="canonical"), img src/srcset,
     source src/srcset, iframe src, CSS url() and @import naming http://, https:// or //. Anchors may link out.
  4. No <script> element.
  5. Each HTML file has <html lang>, a non-empty <title> and a viewport meta; index.html has the canonical link
     https://rail8.dev/ and a non-empty description meta; every <img> has an alt attribute.
  6. No file holds the word "replay" in any letter case.
Standard library only, no network.
"""
from __future__ import annotations

import os
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote

CANONICAL = "https://rail8.dev/"
FORBIDDEN = b"replay"
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
CSS_COMMENT = re.compile(r"/\*.*?\*/", re.S)
CSS_URL = re.compile(r"url\(\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\s]*))\s*\)", re.I)
CSS_IMPORT = re.compile(r"@import\s+(?:\"([^\"]*)\"|'([^']*)')", re.I)
# Elements whose reference loads a resource into the page (rule 3), by attribute.
LOADS = {"script": ("src",), "link": ("href",), "img": ("src", "srcset"), "source": ("src", "srcset"),
         "iframe": ("src",)}


def is_local(ref: str) -> bool:
    return not (ref.startswith("#") or ref.startswith("//") or SCHEME.match(ref))


def is_foreign(ref: str) -> bool:
    return ref.lower().startswith(("http://", "https://", "//"))


def srcset_urls(value: str) -> list[str]:
    return [part.split()[0] for part in value.split(",") if part.strip()]


def resolves(root: Path, referrer: Path, ref: str) -> bool:
    """True when the local reference `ref` of the file `referrer` names a regular file inside `root`."""
    path = unquote(re.split(r"[?#]", ref, maxsplit=1)[0])
    if path == "":
        return True  # "?x" or an empty reference names the referring file itself
    base = root if path.startswith("/") else referrer.parent
    target = base / path.lstrip("/")
    if path.endswith("/"):
        target = target / "index.html"
    resolved = Path(os.path.realpath(target))
    real_root = Path(os.path.realpath(root))
    return resolved.is_relative_to(real_root) and resolved.is_file()


class _Html(HTMLParser):
    """The references, elements and head items of one HTML page."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.refs: list[tuple[str, str, str]] = []  # (tag, attribute, reference)
        self.foreign: list[str] = []
        self.scripts = 0
        self.imgs_without_alt: list[str] = []
        self.lang: str | None = None
        self.titles: list[str] = []
        self.viewport = False
        self.canonical: list[str] = []
        self.description: str | None = None
        self.css: list[str] = []
        self._in_title = False
        self._in_style = False

    def handle_starttag(self, tag, attrs):
        a = {name: (value or "") for name, value in attrs}
        rel = a.get("rel", "").lower().split()
        for name in ("href", "src", "srcset"):
            if name in a:
                for ref in (srcset_urls(a[name]) if name == "srcset" else [a[name].strip()]):
                    self.refs.append((tag, name, ref))
                    if name in LOADS.get(tag, ()) and is_foreign(ref) and not (tag == "link" and "canonical" in rel):
                        self.foreign.append(ref)
        if tag == "script":
            self.scripts += 1
        elif tag == "img" and not any(name == "alt" for name, _ in attrs):
            self.imgs_without_alt.append(a.get("src", ""))
        elif tag == "html" and self.lang is None:
            self.lang = a.get("lang", "").strip()
        elif tag == "title":
            self._in_title = True
            self.titles.append("")
        elif tag == "style":
            self._in_style = True
            self.css.append("")
        elif tag == "meta":
            name = a.get("name", "").lower()
            if name == "viewport" and a.get("content", "").strip():
                self.viewport = True
            elif name == "description" and self.description is None:
                self.description = a.get("content", "")
        elif tag == "link" and "canonical" in rel:
            self.canonical.append(a.get("href", ""))
        if "style" in a:
            self.css.append(a["style"])

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "style":
            self._in_style = False

    def handle_data(self, data):
        if self._in_title:
            self.titles[-1] += data
        elif self._in_style:
            self.css[-1] += data


def css_refs(css: str) -> list[str]:
    css = CSS_COMMENT.sub("", css)
    found = [next(g for g in m.groups() if g is not None).strip() for m in CSS_URL.finditer(css)]
    found += [next(g for g in m.groups() if g is not None).strip() for m in CSS_IMPORT.finditer(css)]
    return [ref for ref in found if ref]


def check_css(root: Path, file: Path, rel: str, css: str) -> list[str]:
    out = []
    for ref in css_refs(css):
        if is_foreign(ref):
            out.append(f"{rel}: loads {ref} from another origin")
        elif is_local(ref) and not resolves(root, file, ref):
            out.append(f"{rel}: reference {ref} does not resolve to a file of the site")
    return out


def check_html(root: Path, file: Path, rel: str, text: str) -> list[str]:
    page = _Html()
    page.feed(text)
    page.close()
    out = []
    for tag, name, ref in page.refs:
        if ref and is_local(ref) and not resolves(root, file, ref):
            out.append(f"{rel}: <{tag} {name}> reference {ref} does not resolve to a file of the site")
    out += [f"{rel}: loads {ref} from another origin" for ref in page.foreign]
    out += [f"{rel}: a <script> element; the site has no script"] * page.scripts
    if not page.lang:
        out.append(f"{rel}: <html> has no lang attribute")
    if not any(title.strip() for title in page.titles):
        out.append(f"{rel}: no non-empty <title>")
    if not page.viewport:
        out.append(f"{rel}: no viewport meta")
    if rel == "index.html":
        if CANONICAL not in page.canonical:
            out.append(f"{rel}: no canonical link to {CANONICAL}")
        if not (page.description or "").strip():
            out.append(f"{rel}: no non-empty description meta")
    out += [f"{rel}: <img src=\"{src}\"> has no alt attribute" for src in page.imgs_without_alt]
    for css in page.css:
        out += check_css(root, file, rel, css)
    return out


def problems(root) -> list[str]:
    root = Path(root)
    out = []
    if not (root / "index.html").is_file():
        out.append("index.html: missing")
    files = sorted(p for p in root.rglob("*") if p.is_file()) if root.is_dir() else []
    for file in files:
        rel = file.relative_to(root).as_posix()
        data = file.read_bytes()
        if rel.endswith(".html"):
            out += check_html(root, file, rel, data.decode("utf-8", errors="replace"))
        elif rel.endswith(".css"):
            out += check_css(root, file, rel, data.decode("utf-8", errors="replace"))
        if FORBIDDEN in data.lower():
            out.append(f"{rel}: holds the word \"{FORBIDDEN.decode()}\"")
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: python3 ci/site_check.py <dir>", file=sys.stderr)
        return 2
    found = problems(argv[1])
    for line in found:
        print(line)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
