#!/usr/bin/env python3
"""Comment-only check: a change that claims to touch only comments leaves the code exactly as it was.

For every checked file, both versions are reduced to their code: line comments (//), block comments (/* */) and
Javadoc are removed, while string and character literals, Java text blocks, template literals and regular
expression literals are kept as they are, so a // or /* inside them is not taken for a comment. Whitespace
outside literals is normalised: a run of whitespace (a removed comment counts as whitespace) becomes one space,
or nothing when it touches a bracket, a brace, a parenthesis, a semicolon or a comma. The two reductions must
be equal.

Checked files: Java (.java), TypeScript and JavaScript (.ts, .tsx, .js, .jsx, .mjs, .cjs) and CSS (.css). The
files are the ones named on the command line, or else every checked file the change adds, deletes or modifies.
A file that exists on only one side is a finding, so is a rename. When no files are named, every other file the
change touches is a finding too, except Markdown (.md): a comment cleanup changes nothing else. In Java, Unicode
escapes (\\uXXXX) are decoded first, as the compiler does, so an escaped line break cannot end a comment unseen.

The base is `--base <commit>`; the change is the commit `--head`, or the working tree when `--head` is not
given. Findings, one per file: `path: the code differs (base line N, head line M)`, followed by the two lines
where the first difference is; the last line counts them. Exit 0 clean, 1 findings, 2 a usage or git error.

Usage:
  python3 ci/comment_only_check.py --base origin/main                 # the working tree against main
  python3 ci/comment_only_check.py --base origin/main --head HEAD     # the last commit against main
  python3 ci/comment_only_check.py --base origin/main a/B.java c.css  # only these files
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

SCRIPT_SUFFIXES = (".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs")
WIDTH = 160
# a space next to one of these characters never changes what the code means
TIGHT = set("{}()[];,")
# the code character before a / that makes the / start a regular expression literal rather than a division
REGEX_AFTER = set("(,=:[!&|?{};+-*%~^")
# a Java Unicode escape: an odd number of backslashes, one or more u, four hex digits
UNICODE_ESCAPE = re.compile(r"(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})")
DOCS_SUFFIXES = (".md",)
REGEX_AFTER_WORDS = {"return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw",
                     "yield", "await"}


def file_kind(path: str) -> str | None:
    """java, script or style for a checked file, None for any other."""
    if path.endswith(".java"):
        return "java"
    if path.endswith(SCRIPT_SUFFIXES):
        return "script"
    if path.endswith(".css"):
        return "style"
    return None


def reduce(text: str, kind: str) -> tuple[str, list[int]]:
    """The code of `text` without comments and with whitespace normalised, and the source line of each character.

    `kind` is java, script or style. Java knows // and /* */ comments, "..." and '...' literals and \"\"\" text
    blocks; script knows // and /* */ comments, '...', "..." and `...` literals and regular expression literals;
    style knows /* */ comments and '...' and "..." strings. A '...' or "..." literal that is not closed ends at
    the end of its line.
    """
    if kind == "java":
        text = UNICODE_ESCAPE.sub(lambda m: m.group(1) + chr(int(m.group(2), 16)), text)
    out: list[str] = []
    lines: list[int] = []
    pending = False  # whitespace (or a removed comment) seen since the last character written
    line = 1
    i, n = 0, len(text)

    def put(chunk: str, at: int) -> None:
        """Writes code or a literal; a pending space is written first unless it touches a TIGHT character."""
        nonlocal pending
        if pending and out and out[-1] not in TIGHT and chunk[0] not in TIGHT:
            out.append(" ")
            lines.append(at)
        pending = False
        for c in chunk:
            out.append(c)
            lines.append(at)

    def last_word() -> str:
        j = len(out)
        while j > 0 and (out[j - 1].isalnum() or out[j - 1] in "_$"):
            j -= 1
        return "".join(out[j:])

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c.isspace():
            pending = True
            line += c == "\n"
            i += 1
            continue
        if c == "/" and nxt == "/" and kind != "style":
            end = text.find("\n", i)
            i = n if end < 0 else end
            pending = True
            continue
        if c == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += text.count("\n", i, end)
            i = end
            pending = True
            continue
        if kind == "java" and text.startswith('"""', i):
            end = closing(text, i + 3, '"""')
            start_line = line
            literal = text[i:end]
            line += literal.count("\n")
            put(literal, start_line)
            i = end
            continue
        if c in "'\"" or (c == "`" and kind == "script"):
            end = closing(text, i + 1, c, stop_at_newline=c != "`")
            start_line = line
            literal = text[i:end]
            line += literal.count("\n")
            put(literal, start_line)
            i = end
            continue
        if c == "/" and kind == "script" and regex_starts(out, last_word()):
            end = regex_end(text, i + 1)
            put(text[i:end], line)
            i = end
            continue
        put(c, line)
        i += 1
    return "".join(out), lines


def regex_starts(out: list[str], word: str) -> bool:
    """Whether a / after the code written so far starts a regular expression literal."""
    if not out:
        return True
    if word and word in REGEX_AFTER_WORDS:
        return True
    return out[-1] in REGEX_AFTER


def closing(text: str, i: int, quote: str, stop_at_newline: bool = False) -> int:
    """The index just past the literal whose body starts at `i` and which `quote` closes."""
    n = len(text)
    while i < n:
        if text[i] == "\\":
            i += 2
            continue
        if stop_at_newline and text[i] == "\n":
            return i
        if text.startswith(quote, i):
            return i + len(quote)
        i += 1
    return n


def regex_end(text: str, i: int) -> int:
    """The index just past a regular expression literal whose body starts at `i`, its flags included."""
    n = len(text)
    in_class = False
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if c == "\n":
            return i
        if c == "[":
            in_class = True
        elif c == "]":
            in_class = False
        elif c == "/" and not in_class:
            i += 1
            while i < n and text[i].isalpha():
                i += 1
            return i
        i += 1
    return n


def compare(path: str, base: str | None, head: str | None) -> list[str]:
    """No lines when the code of the two versions is equal, else the finding and the two lines that differ."""
    if base is None and head is None:
        return []
    if base is None:
        return [f"{path}: the file is added, which changes code"]
    if head is None:
        return [f"{path}: the file is deleted, which changes code"]
    kind = file_kind(path)
    assert kind is not None
    old, old_lines = reduce(base, kind)
    new, new_lines = reduce(head, kind)
    if old == new:
        return []
    at = next((k for k in range(min(len(old), len(new))) if old[k] != new[k]), min(len(old), len(new)))
    base_line = old_lines[min(at, len(old_lines) - 1)] if old_lines else 1
    head_line = new_lines[min(at, len(new_lines) - 1)] if new_lines else 1
    return [f"{path}: the code differs (base line {base_line}, head line {head_line})",
            f"  base: {source_line(base, base_line)}",
            f"  head: {source_line(head, head_line)}"]


def source_line(text: str, number: int) -> str:
    rows = text.split("\n")
    row = rows[number - 1].strip() if 0 < number <= len(rows) else ""
    return row if len(row) <= WIDTH else row[:WIDTH] + "..."


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    done = subprocess.run(["git", *args], capture_output=True)
    if check and done.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {done.stderr.decode(errors='replace').strip()}")
    return done


def at_commit(commit: str, path: str) -> str | None:
    """The file's content in `commit`, None when the commit has no such file."""
    if git("cat-file", "-e", f"{commit}:{path}", check=False).returncode != 0:
        return None
    return git("show", f"{commit}:{path}").stdout.decode("utf-8", errors="replace")


def in_tree(path: str) -> str | None:
    """The file's content in the working tree (paths relative to the repository root), None when it is absent."""
    root = git("rev-parse", "--show-toplevel").stdout.decode().strip()
    p = Path(root) / path
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else None


def changed_files(base: str, head: str | None) -> tuple[list[str], list[str]]:
    """The checked files the change adds, deletes or modifies (a rename is a delete and an add), and the other
    files it touches that are not Markdown."""
    args = ["diff", "--name-only", "--no-renames", "-z", base] + ([head] if head else [])
    names = [p for p in git(*args).stdout.decode("utf-8", errors="replace").split("\0") if p]
    checked = sorted(p for p in names if file_kind(p))
    other = sorted(p for p in names if not file_kind(p) and not p.endswith(DOCS_SUFFIXES))
    return checked, other


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="the commit the change is compared with")
    ap.add_argument("--head", help="the commit of the change (default: the working tree)")
    ap.add_argument("paths", nargs="*", help="files to check, relative to the repository root (default: every "
                                             "checked file the change touches)")
    args = ap.parse_args(argv)
    wrong = [p for p in args.paths if file_kind(p) is None]
    if wrong:
        print(f"COMMENT-ONLY CHECK: not a Java, TypeScript, JavaScript or CSS file: {', '.join(wrong)}",
              file=sys.stderr)
        return 2
    try:
        paths, other = (sorted(set(args.paths)), []) if args.paths else changed_files(args.base, args.head)
        findings = [f"{p}: not a Java, TypeScript, JavaScript, CSS or Markdown file, which a comment cleanup "
                    f"does not change" for p in other]
        count = len(other)
        for path in paths:
            head = at_commit(args.head, path) if args.head else in_tree(path)
            lines = compare(path, at_commit(args.base, path), head)
            if lines:
                count += 1
                findings += lines
    except RuntimeError as e:
        print(f"COMMENT-ONLY CHECK: {e}", file=sys.stderr)
        return 2
    for f in findings:
        print(f)
    if count:
        print(f"comment-only check: {count} file(s) with changed code")
        return 1
    print(f"comment-only check: clean, {len(paths)} files checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
