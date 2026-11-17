#!/usr/bin/env python3
"""Change guard: checks the lines a change adds or modifies, compared with a base commit.

Two checks, each on the added and modified lines only (older lines are left alone), with one exception: in the
folders whose comments have been cleaned (CLEANED below), references in comments are checked in every file, on
every line, whether the change touches it or not.

1. References in comments. A comment or Javadoc in a Java file under platform/ or mapping/, or a comment in a
   TypeScript, JavaScript or CSS file under mapping/ui/, must explain; it must not point at a task or a decision.
   A reference is an upper-case tag, a hyphen and a number, such as D-164, FIX-268, MAP-12b, P0-10 or ADR-0019,
   or a `#` followed by a number, such as #123. Names of standards (UTF-8, SHA-256, ISO-8601 and the like) are not
   references.
2. Hard-coded colours. In CSS, SCSS, TypeScript and JavaScript files (TSX and JSX included) a colour is written
   as a PatternFly token, `var(--pf-t--...)` or `var(--pf-v6-...)`. A hex colour, rgb(), rgba(), hsl(), hsla()
   or a CSS named colour is a finding: in CSS anywhere outside comments, in script files inside string literals
   (a named colour only as the value of a style key such as `color:` or of an SVG `fill=` or `stroke=`).
   Not checked: site/ (the project site does not load PatternFly), test files and e2e/ (tests compare the colours
   the browser computes) and generated code.

The base is `--base <commit>`; the change is the commit `--head` (default HEAD), read from git, so the working
tree does not matter. Findings, one per line: `path:line: <what is wrong>`; the last line counts them.
Exit 0 clean, 1 findings, 2 a usage or git error.

Usage:
  python3 ci/change_guard.py --base origin/main   # from the repository root
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

JAVA_ROOTS = ("platform/", "mapping/")
# Folders whose comments have been cleaned of references: every comment in every file under them is checked, not
# only the lines a change touches. A cleanup adds its folders to this list.
CLEANED = ("platform/api/src/main/", "platform/core/src/main/")
UI_ROOT = "mapping/ui/"
SCRIPT_SUFFIXES = (".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs")
STYLE_SUFFIXES = (".css", ".scss")
COLOUR_SKIPPED = re.compile(r"^site/|(^|/)e2e/|(^|/)generated/|\.(test|spec)\.[a-z]+$")

REFERENCE = re.compile(r"(?<![\w-])([A-Z][A-Z0-9]*-\d{1,4}[a-z]?)(?![\w-])|(?<![\w&#])(#\d+)(?!\w)")
# names of standards and formats that are written like a reference but explain themselves
STANDARDS = {"AES", "CESU", "CRC", "ECMA", "IEC", "IEEE", "ISO", "JSR", "MD", "PKCS", "RFC", "SHA", "TLS", "UTF", "X"}

HEX = re.compile(r"(?<![\w&])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![\w-])")
FUNCTION = re.compile(r"(?<![\w-])(?:rgba?|hsla?)\(", re.IGNORECASE)
NAMED = {
    "aliceblue", "antiquewhite", "aqua", "aquamarine", "azure", "beige", "bisque", "black", "blanchedalmond",
    "blue", "blueviolet", "brown", "burlywood", "cadetblue", "chartreuse", "chocolate", "coral", "cornflowerblue",
    "cornsilk", "crimson", "cyan", "darkblue", "darkcyan", "darkgoldenrod", "darkgray", "darkgreen", "darkgrey",
    "darkkhaki", "darkmagenta", "darkolivegreen", "darkorange", "darkorchid", "darkred", "darksalmon",
    "darkseagreen", "darkslateblue", "darkslategray", "darkslategrey", "darkturquoise", "darkviolet", "deeppink",
    "deepskyblue", "dimgray", "dimgrey", "dodgerblue", "firebrick", "floralwhite", "forestgreen", "fuchsia",
    "gainsboro", "ghostwhite", "gold", "goldenrod", "gray", "green", "greenyellow", "grey", "honeydew", "hotpink",
    "indianred", "indigo", "ivory", "khaki", "lavender", "lavenderblush", "lawngreen", "lemonchiffon", "lightblue",
    "lightcoral", "lightcyan", "lightgoldenrodyellow", "lightgray", "lightgreen", "lightgrey", "lightpink",
    "lightsalmon", "lightseagreen", "lightskyblue", "lightslategray", "lightslategrey", "lightsteelblue",
    "lightyellow", "lime", "limegreen", "linen", "magenta", "maroon", "mediumaquamarine", "mediumblue",
    "mediumorchid", "mediumpurple", "mediumseagreen", "mediumslateblue", "mediumspringgreen", "mediumturquoise",
    "mediumvioletred", "midnightblue", "mintcream", "mistyrose", "moccasin", "navajowhite", "navy", "oldlace",
    "olive", "olivedrab", "orange", "orangered", "orchid", "palegoldenrod", "palegreen", "paleturquoise",
    "palevioletred", "papayawhip", "peachpuff", "peru", "pink", "plum", "powderblue", "purple", "rebeccapurple",
    "red", "rosybrown", "royalblue", "saddlebrown", "salmon", "sandybrown", "seagreen", "seashell", "sienna",
    "silver", "skyblue", "slateblue", "slategray", "slategrey", "snow", "springgreen", "steelblue", "tan", "teal",
    "thistle", "tomato", "turquoise", "violet", "wheat", "white", "whitesmoke", "yellow", "yellowgreen",
}
WORD = re.compile(r"(?<![\w.#-])[A-Za-z]+(?![\w(-])")
# the code before a string literal that makes the string a colour value: a style key or an SVG colour attribute
STYLE_KEY = re.compile(r"(?:(?:[cC]olor|background|fill|stroke|border\w*|outline\w*|[sS]hadow)\s*:"
                       r"|(?:fill|stroke|stopColor|stop-color)\s*=\s*\{?)\s*$")
TOKEN = "var(--pf-t--...) or var(--pf-v6-...)"


def segments(text: str, kind: str) -> dict[int, list[tuple[str, str]]]:
    """Splits a source file into (segment kind, text) per line number; kinds are code, comment and string.

    `kind` is java, script or style. Java knows // and /* */ comments, "..." and '...' literals and \"\"\" text
    blocks; script knows // and /* */ comments, '...', "..." and `...` literals and regular-expression literals;
    style knows /* */ comments and '...' and "..." strings. A '...' or "..." literal ends at the line's end.
    """
    lines: dict[int, list[tuple[str, str]]] = {}
    line, i, n = 1, 0, len(text)
    state, buf, quote = "code", [], ""
    last_code = ""  # the last code character that is not whitespace, to tell a regex literal from a division

    def flush(new_state: str) -> None:
        nonlocal buf, state
        if buf:
            lines.setdefault(line, []).append((segment_kind(state), "".join(buf)))
        buf, state = [], new_state

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            if state in ("line", "string", "regex"):
                flush("code")
            else:
                flush(state)
            line += 1
            i += 1
            continue
        if state == "code":
            if c == "/" and nxt == "/" and kind != "style":
                flush("line"); buf.append("//"); i += 2; continue
            if c == "/" and nxt == "*":
                flush("block"); buf.append("/*"); i += 2; continue
            if kind == "java" and text.startswith('"""', i):
                flush("text"); i += 3; continue
            if c in "'\"" or (c == "`" and kind == "script"):
                flush("template" if c == "`" else "string"); quote = c; i += 1; continue
            if c == "/" and kind == "script" and (last_code == "" or last_code in "(,=:[!&|?{};+-*%~^"):
                flush("regex"); i += 1; continue
            buf.append(c)
            if not c.isspace():
                last_code = c
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                buf.append("*/"); flush("code"); i += 2; continue
            buf.append(c); i += 1; continue
        if state == "line":
            buf.append(c); i += 1; continue
        if state == "regex":
            if c == "\\":
                i += 2; continue
            if c == "/":
                flush("code"); last_code = "/"; i += 1; continue
            i += 1; continue
        # string, template, text block
        if c == "\\":
            buf.append(text[i:i + 2]); i += 2; continue
        if state == "text" and text.startswith('"""', i):
            flush("code"); last_code = '"'; i += 3; continue
        if state in ("string", "template") and c == quote:
            flush("code"); last_code = c; i += 1; continue
        buf.append(c); i += 1
    flush("code")
    return lines


def segment_kind(state: str) -> str:
    return {"line": "comment", "block": "comment", "string": "string", "template": "string",
            "text": "string"}.get(state, "code")


def file_kind(path: str) -> str | None:
    if path.endswith(".java"):
        return "java"
    if path.endswith(SCRIPT_SUFFIXES):
        return "script"
    if path.endswith(STYLE_SUFFIXES):
        return "style"
    return None


def comment_checked(path: str, kind: str) -> bool:
    if kind == "java":
        return path.startswith(JAVA_ROOTS)
    return path.startswith(UI_ROOT)


def references(comment: str) -> list[str]:
    found = []
    for m in REFERENCE.finditer(comment):
        tag = m.group(1) or m.group(2)
        if m.group(1) and tag.split("-", 1)[0] in STANDARDS:
            continue
        found.append(tag)
    return found


def colours_in_style(code: str) -> list[str]:
    found = [m.group(0) for m in HEX.finditer(code)]
    found += [m.group(0) + ")" for m in FUNCTION.finditer(code)]
    for decl in re.finditer(r":([^;{}]*)", code):
        found += [w.group(0) for w in WORD.finditer(decl.group(1)) if w.group(0).lower() in NAMED]
    return found


def colours_in_string(value: str, code_before: str) -> list[str]:
    found = [m.group(0) for m in HEX.finditer(value)]
    found += [m.group(0) + ")" for m in FUNCTION.finditer(value)]
    if value.strip().lower() in NAMED and STYLE_KEY.search(code_before):
        found.append(value.strip())
    return found


def check(path: str, text: str, changed: set[int], whole: bool = False) -> list[str]:
    """The findings on the `changed` lines of one file; with `whole`, references in comments on every line."""
    kind = file_kind(path)
    if kind is None:
        return []
    comments = comment_checked(path, kind)
    colours = kind != "java" and not COLOUR_SKIPPED.search(path)
    if not comments and not colours:
        return []
    findings = []
    per_line = segments(text, kind)
    for number in sorted(changed | set(per_line) if whole else changed):
        parts = per_line.get(number, [])
        refs, cols = [], []
        code_before = ""
        colours_here = colours and number in changed
        for seg, value in parts:
            if seg == "comment" and comments and (whole or number in changed):
                refs += references(value)
            elif colours_here and seg == "code" and kind == "style":
                cols += colours_in_style(value)
            elif colours_here and seg == "string" and kind == "script":
                cols += colours_in_string(value, code_before)
            elif colours_here and seg == "string" and kind == "style":
                cols += colours_in_style(value)
            if seg == "code":
                code_before = value
        if refs:
            findings.append(f"{path}:{number}: a reference in a comment ({', '.join(refs)}): "
                            f"say what the code does and why instead")
        if cols:
            findings.append(f"{path}:{number}: a hard-coded colour ({', '.join(cols)}): use a PatternFly token, "
                            f"{TOKEN}")
    return findings


def git(*args: str) -> str:
    done = subprocess.run(["git", *args], capture_output=True)
    if done.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout.decode("utf-8", errors="replace")


def changed_lines(base: str, head: str) -> dict[str, set[int]]:
    """The added and modified line numbers of every file the change adds, modifies or renames, in `head`."""
    diff = git("diff", "--no-color", "--no-ext-diff", "--unified=0", "--find-renames", "--diff-filter=d",
               base, head)
    result: dict[str, set[int]] = {}
    path = None
    for raw in diff.split("\n"):
        if raw.startswith("+++ "):
            path = raw[6:] if raw.startswith("+++ b/") else None
            if path is not None:
                result.setdefault(path, set())
        elif raw.startswith("@@") and path is not None:
            m = re.match(r"@@ -\S+ \+(\d+)(?:,(\d+))? @@", raw)
            if m:
                start, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
                result[path].update(range(start, start + count))
    return {p: lines for p, lines in result.items() if lines}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="the commit the change is compared with")
    ap.add_argument("--head", default="HEAD", help="the commit of the change (default HEAD)")
    args = ap.parse_args(argv)
    try:
        changed = changed_lines(args.base, args.head)
        cleaned = {p for p in git("ls-tree", "-r", "-z", "--name-only", args.head, "--", *CLEANED).split("\0")
                   if p.startswith(CLEANED)}
        findings = []
        for path in sorted(set(changed) | cleaned):
            if file_kind(path) is None:
                continue
            findings += check(path, git("show", f"{args.head}:{path}"), changed.get(path, set()), path in cleaned)
    except RuntimeError as e:
        print(f"CHANGE GUARD: {e}", file=sys.stderr)
        return 2
    for f in findings:
        print(f)
    if findings:
        print(f"change guard: {len(findings)} finding(s)")
        return 1
    print(f"change guard: clean, {len(changed)} changed files and {len(cleaned)} files of cleaned folders checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
