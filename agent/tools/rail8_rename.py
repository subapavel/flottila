#!/usr/bin/env python3
"""The Flottila -> Rail8 rename (BRAND-1, D-148, D-149), as one repeatable tool.

Subcommands, each idempotent (a second run changes nothing) and each with --dry-run:
  moves        commit A: `git mv` of every directory in PATH_MAP, then the path strings in the living documents
  identifiers  commit B: coordinates, packages, env keys, CLI name (IDENTIFIER_MAP)          -- not implemented yet
  wire         commit C: persisted and wire names, one entry per literal (WIRE_MAP)          -- not implemented yet
  translate F  the path map and the identifier map applied to one text file (a parked diff)  -- not implemented yet
  report       only the final report: every remaining occurrence of the old name outside EXCLUDED

--root <dir> limits a subcommand to a subtree of the repository (BRAND-3 runs it on mapping/).
The tool never touches .git/, target/ or a file git does not track. Python 3 standard library only.

Usage (from anywhere inside the repository):
  python3 agent/tools/rail8_rename.py moves --dry-run
  python3 agent/tools/rail8_rename.py moves
  python3 agent/tools/rail8_rename.py report --summary
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------------------------------------------------
# Data. Paths are repository-relative, POSIX, as `git ls-files` prints them.
# ---------------------------------------------------------------------------------------------------------------------

# Commit A (D-149): old -> new, each one `git mv` of the whole entry. Order matters only for the parent pom, which moves
# after the modules so that platform/ exists.
PATH_MAP: list[tuple[str, str]] = [
    ("flottila-api", "platform/api"),
    ("flottila-core", "platform/core"),
    ("flottila-runtime", "platform/runtime"),
    ("flottila-testkit", "platform/testkit"),
    ("flottila-schemas", "platform/schemas"),
    ("flottila-cli", "platform/cli"),
    ("flottila-sim", "platform/sim"),
    ("flottila-archtest", "platform/archtest"),
    ("flottila-components", "platform/components"),
    ("flottila-steps", "platform/steps"),
    ("pom.xml", "platform/pom.xml"),
    ("packages/demo", "examples/demo"),
    ("prompts", "agent/prompts"),
    ("deploy/claude-cloud", "agent/cloud"),
    ("PROGRESS.md", "docs/archive/PROGRESS-until-2026-11-03.md"),
]

# Directories whose tracked entries move one by one into a directory that already exists (bench/ holds run.sh, bench.py,
# RESULTS.md). Every tracked entry of the source must be listed here; the tool refuses to guess an unlisted one.
MERGE_MAP: dict[str, tuple[str, list[str]]] = {
    "flottila-bench": ("bench", ["pom.xml", "src"]),
}

# The old module directory -> its artifactId (for `-pl`). The artifactIds do not change in commit A.
MODULE_ARTIFACT: dict[str, str] = {
    "flottila-api": "flottila-api",
    "flottila-core": "flottila-core",
    "flottila-runtime": "flottila-runtime",
    "flottila-testkit": "flottila-testkit",
    "flottila-schemas": "flottila-schemas",
    "flottila-cli": "flottila-cli",
    "flottila-sim": "flottila-sim",
    "flottila-archtest": "flottila-archtest",
    "flottila-bench": "flottila-bench",
    "flottila-components": "flottila-components",
    "flottila-steps": "flottila-steps",
    "flottila-components/http": "flottila-component-http",
    "flottila-components/mock": "flottila-component-mock",
    "flottila-components/sftp": "flottila-component-sftp",
    "flottila-steps/validate": "flottila-step-validate",
    "flottila-steps/xslt": "flottila-step-xslt",
}
# `-pl <dir>` becomes `-pl :<artifactId>` (the packet's preference; `mvn -pl :flottila-sim -am -q validate` accepted it,
# BRAND-1 A). False would write the new directory instead.
PL_BY_ARTIFACT = True

# Commit B: (old, new), ordered longest first. Filled in commit B.
IDENTIFIER_MAP: list[tuple[str, str]] = []

# Commit C: (file, old literal, new literal), one entry per literal on the scout's wire list. Filled in commit C.
WIRE_MAP: list[tuple[str, str, str]] = []

# Never rewritten, in any commit (BRAND-1 packet "Exclusions"). History keeps the old name as written (D-148).
EXCLUDED: list[str] = [
    "docs/PROGRESS.md",
    "docs/DECISIONS.md",
    "docs/archive/**",
    "docs/findings/**",
    "docs/review/**",
    "agent/packets/**",
    "agent/archive/**",
    "agent/work/**",
    "agent/review/**",
    "agent/patches/**",
    "agent/wip/**",
    "issues/**",
    "bench/RESULTS.md",
    "PROGRESS.md",                      # before commit A; it moves to docs/archive/ unedited
    "deploy/postgres/V*.sql",           # applied migrations stay byte-identical
    "agent/tools/rail8_rename.py",      # this script
]
# In agent/BACKLOG.md every table row whose status cell (the fourth) starts with `done` is history.
BACKLOG = "agent/BACKLOG.md"
# Every github.com/subapavel/flottila URL stays (the owner renames the repository), `flottila-mapping` in one too.
KEPT_URL = re.compile(r"(?:https?://)?github\.com/subapavel/flottila[^\s)\]>`'\"|]*")

# The living documents whose path strings commit A rewrites (paths after the moves). The packet's list, plus the
# documents it does not name that cite a moved path (marked +).
LIVING_DOCS: list[str] = [
    "CLAUDE.md", "AGENTS.md", "README.md", "HANDOVER.md",
    "docs/WORKFLOW.md", "docs/ARCHITECTURE.md", "docs/CORE-REWORK.md", "docs/OPERATIONS-BRIEF.md", "docs/GATE.md",
    "docs/QUICKSTART.md", "docs/PROTOTYPE-VS-ARCHITECTURE.md",
    "agent/NOTES.md", "agent/HANDOFF.md", BACKLOG,
    ".claude/agents/*.md", ".claude/skills/**", ".claude/agent-memory/**",
    "deploy/compose/README.md",          # +
    "agent/cloud/README.md",             # + (was deploy/claude-cloud/README.md)
    "platform/schemas/README.md",        # +
    "agent/prompts/*",                   # +
]

# ---------------------------------------------------------------------------------------------------------------------
# Path-string rules (commit A). Each is (class, pattern, replacement). No rule is broader than the strings it fixes:
# a module name without a following `/` is an artifactId in commit A and stays.
# ---------------------------------------------------------------------------------------------------------------------

_NOT_AFTER = r"(?<![\w.-])"   # not inside a longer name or a dotted/hyphenated word; a preceding `/` is allowed (`../`)


def _pl(match: re.Match) -> str:
    items = []
    for item in match.group(3).split(","):
        bang = "!" if item.startswith("!") else ""
        name = item[len(bang):]
        if name in MODULE_ARTIFACT:
            name = ":" + MODULE_ARTIFACT[name] if PL_BY_ARTIFACT else map_path(name)
        items.append(bang + name)
    return match.group(1) + match.group(2) + ",".join(items)


def _after_root_relative(text: str, start: int) -> bool:
    """A repository-relative `packages/demo` / `prompts/` starts a token or follows `../` or `./`; `/packages/demo`
    (a URL path or a container path) is not a repository path."""
    before = text[max(0, start - 3):start]
    if before.endswith("../") or before.endswith("./"):
        return True
    return start == 0 or not re.match(r"[\w./-]", text[start - 1])


def _root_relative(old: str, new: str):
    pattern = re.compile(re.escape(old))

    def replace(match: re.Match) -> str:
        return new if _after_root_relative(match.string, match.start()) else match.group(0)
    return pattern, replace


PATH_RULES: list[tuple[str, re.Pattern, object]] = [
    ("module dir", re.compile(_NOT_AFTER + r"flottila-(api|core|runtime|testkit|schemas|cli|sim|archtest)/"),
     r"platform/\1/"),
    ("aggregator dir", re.compile(_NOT_AFTER + r"flottila-(components|steps)/(?=http\b|mock\b|sftp\b|validate\b|xslt\b"
                                                r"|pom\.xml|src/|target/|\*|\{)"), r"platform/\1/"),
    ("bench dir", re.compile(_NOT_AFTER + r"flottila-bench/"), "bench/"),
    ("-pl", re.compile(r"(?<![\w-])(-pl(?:\s+|=))(['\"]?)([!\w./,-]+)"), _pl),
    ("demo", *_root_relative("packages/demo", "examples/demo")),
    ("prompts", *_root_relative("prompts/", "agent/prompts/")),
    ("claude-cloud", *_root_relative("deploy/claude-cloud", "agent/cloud")),
]

OLD_NAME = re.compile(r"flottila", re.IGNORECASE)

# ---------------------------------------------------------------------------------------------------------------------


def map_path(path: str) -> str:
    """A repository path under the path map's new layout (longest old prefix wins); unchanged if no entry covers it."""
    entries = list(PATH_MAP) + [(src, dst) for src, (dst, _) in MERGE_MAP.items()]
    for old, new in sorted(entries, key=lambda e: -len(e[0])):
        if path == old or path.startswith(old + "/"):
            return new + path[len(old):]
    return path


def git(root: Path, *args: str, check: bool = True) -> str:
    out = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if check and out.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def tracked(root: Path) -> list[str]:
    return [p for p in git(root, "ls-files", "-z").split("\0") if p]


def _glob(path: str, pattern: str) -> bool:
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3] + "/")
    return fnmatch.fnmatchcase(path, pattern) and ("/" in pattern or "/" not in path)


def excluded(path: str) -> bool:
    return any(_glob(path, p) for p in EXCLUDED)


def living(path: str) -> bool:
    return any(_glob(path, p) for p in LIVING_DOCS) and not excluded(path)


def in_scope(path: str, scope: str | None) -> bool:
    return scope is None or path == scope or path.startswith(scope.rstrip("/") + "/")


def read_text(file: Path) -> str | None:
    data = file.read_bytes()
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _status_done(line: str) -> bool:
    if not line.startswith("|"):
        return False
    cells = re.split(r"(?<!\\)\|", line)
    return len(cells) > 4 and cells[4].strip().startswith("done") and cells[1].strip() not in ("Id", "")


def _masked(line: str) -> tuple[str, list[str]]:
    kept: list[str] = []

    def hide(m: re.Match) -> str:
        kept.append(m.group(0))
        return f"\0{len(kept) - 1}\0"
    return KEPT_URL.sub(hide, line), kept


def _unmasked(line: str, kept: list[str]) -> str:
    return re.sub(r"\0(\d+)\0", lambda m: kept[int(m.group(1))], line)


def _apply(name: str, pattern: re.Pattern, replacement, text: str, counts: Counter) -> str:
    """pattern.sub, counting only the matches whose replacement differs from the match."""
    def one(m: re.Match) -> str:
        new = replacement(m) if callable(replacement) else m.expand(replacement)
        if new != m.group(0):
            counts[name] += 1
        return new
    return pattern.sub(one, text)


def rewrite_paths(path: str, text: str, counts: Counter) -> str:
    out = []
    for line in text.split("\n"):
        if path == BACKLOG and _status_done(line):
            out.append(line)
            continue
        masked, kept = _masked(line)
        for name, pattern, replacement in PATH_RULES:
            masked = _apply(name, pattern, replacement, masked, counts)
        out.append(_unmasked(masked, kept))
    return "\n".join(out)


def _snippet(a: str, b: str, context: int = 30) -> str:
    """The changed span of one line, with a little context: `...before[old -> new]after...`."""
    i = 0
    while i < min(len(a), len(b)) and a[i] == b[i]:
        i += 1
    j = 0
    while j < min(len(a), len(b)) - i and a[len(a) - 1 - j] == b[len(b) - 1 - j]:
        j += 1
    return (a[max(0, i - context):i] + "[" + a[i:len(a) - j] + " -> " + b[i:len(b) - j] + "]"
            + a[len(a) - j:len(a) - j + context])


def _count_changes(before: str, after: str) -> int:
    return sum(1 for a, b in zip(before.split("\n"), after.split("\n")) if a != b)


# ---------------------------------------------------------------------------------------------------------------------


def cmd_moves(root: Path, scope: str | None, dry: bool) -> int:
    files = set(tracked(root))
    exists = lambda p: p in files or any(f.startswith(p + "/") for f in files)  # noqa: E731
    moved = 0
    plan: list[tuple[str, str]] = []
    for old, new in PATH_MAP:
        if not (in_scope(old, scope) or in_scope(new, scope)):
            continue
        if exists(old):
            if old == "pom.xml" and exists(new):
                print(f"skip  {old} -> {new}: already moved (the root pom.xml is the new aggregator)")
                continue
            if exists(new):
                raise SystemExit(f"refused: {old} -> {new}: both exist")
            plan.append((old, new))
        elif exists(new):
            print(f"skip  {old} -> {new}: already moved")
        else:
            raise SystemExit(f"refused: {old} -> {new}: neither is tracked")
    for src, (dst, kids) in MERGE_MAP.items():
        if not (in_scope(src, scope) or in_scope(dst, scope)):
            continue
        present = sorted({p[len(src) + 1:].split("/", 1)[0] for p in files if p.startswith(src + "/")})
        unlisted = [k for k in present if k not in kids]
        if unlisted:
            raise SystemExit(f"refused: {src} holds tracked entries the map does not list: {unlisted}")
        for kid in kids:
            old, new = f"{src}/{kid}", f"{dst}/{kid}"
            if exists(old):
                if exists(new):
                    raise SystemExit(f"refused: {old} -> {new}: both exist")
                plan.append((old, new))
            elif exists(new):
                print(f"skip  {old} -> {new}: already moved")
            else:
                raise SystemExit(f"refused: {old} -> {new}: neither is tracked")
    for old, new in plan:
        print(f"git mv {old} {new}")
        if not dry:
            (root / new).parent.mkdir(parents=True, exist_ok=True)
            git(root, "mv", old, new)
            for parent in Path(old).parents:   # an old parent left empty on disk (packages/, flottila-bench/ if empty)
                d = root / parent
                if str(parent) != "." and d.is_dir() and not any(d.iterdir()):
                    d.rmdir()
        moved += 1

    # the path strings in the living documents (read from the tree as it is after the moves, or as it would be)
    rewritten = 0
    counts: Counter = Counter()
    after = {map_path(p) if dry else p for p in tracked(root)}
    for path in sorted(after):
        if not living(path) or not in_scope(path, scope):
            continue
        on_disk = root / (path if not dry else _source_of(path, files))
        if not on_disk.is_file():
            continue
        text = read_text(on_disk)
        if text is None:
            continue
        per_file: Counter = Counter()
        new_text = rewrite_paths(path, text, per_file)
        if new_text == text:
            continue
        lines = _count_changes(text, new_text)
        counts.update(per_file)
        counts["lines"] += lines
        rewritten += 1
        print(f"rewrite {path}: {lines} line(s)")
        if dry:
            for number, (a, b) in enumerate(zip(text.split("\n"), new_text.split("\n")), 1):
                if a != b:
                    print(f"  {number}: {_snippet(a, b)}")
        else:
            on_disk.write_text(new_text, encoding="utf-8")
    print(f"moves: {moved} git mv, {rewritten} file(s) rewritten, {counts['lines']} line(s); "
          + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()) if k != "lines"))
    return 0


def _source_of(new_path: str, files: set[str]) -> str:
    """For a dry run: the tracked path whose move gives `new_path`."""
    for f in files:
        if map_path(f) == new_path:
            return f
    return new_path


def cmd_path_report(root: Path, scope: str | None) -> None:
    """Every line outside the exclusion list on which a path rule would still fire: after `moves`, each is a hand edit
    still to make, or a string that is deliberately not a repository path (say which in the hand-back)."""
    found = 0
    for path in tracked(root):
        if excluded(path) or not in_scope(path, scope):
            continue
        text = read_text(root / path) if (root / path).is_file() else None
        if text is None:
            continue
        for number, line in enumerate(text.split("\n"), 1):
            if path == BACKLOG and _status_done(line):
                continue
            new = rewrite_paths(path, line, Counter())
            if new != line:
                found += 1
                print(f"{path}:{number}: {_snippet(line, new)}")
    print(f"remaining old path strings outside the exclusion list: {found} line(s)")


def cmd_report(root: Path, scope: str | None, summary: bool) -> int:
    hits: dict[str, list[tuple[int, str]]] = defaultdict(list)
    for path in tracked(root):
        if excluded(path) or not in_scope(path, scope) or "/target/" in f"/{path}":
            continue
        file = root / path
        if not file.is_file():
            continue
        text = read_text(file)
        if text is None:
            continue
        for number, line in enumerate(text.split("\n"), 1):
            if path == BACKLOG and _status_done(line):
                continue
            masked, _ = _masked(line)
            if OLD_NAME.search(masked):
                hits[path].append((number, line.strip()))
    total = sum(len(v) for v in hits.values())
    print(f"remaining occurrences of the old name outside the exclusion list: {total} line(s) in {len(hits)} file(s)")
    for path in sorted(hits):
        print(f"{path} ({len(hits[path])})")
        if not summary:
            for number, line in hits[path]:
                print(f"  {number}: {line[:200]}")
    return 0


def not_implemented(name: str) -> int:
    print(f"rail8_rename {name}: not implemented yet (BRAND-1 commit {'C' if name == 'wire' else 'B'})", file=sys.stderr)
    return 2


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    for name in ("moves", "identifiers", "wire", "report"):
        p = sub.add_parser(name)
        p.add_argument("--dry-run", action="store_true", help="print what would change, change nothing")
        p.add_argument("--root", default=None, help="limit to this repository subtree (e.g. mapping)")
        p.add_argument("--summary", action="store_true", help="final report: counts per file, no lines")
        p.add_argument("--paths", action="store_true", help="also list every line where a path rule would still fire")
    t = sub.add_parser("translate")
    t.add_argument("file")
    t.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
    if args.command in ("identifiers", "wire", "translate"):
        return not_implemented(args.command)
    scope = args.root.strip("/") if args.root else None
    if args.command == "moves":
        cmd_moves(root, scope, args.dry_run)
    if args.paths:
        cmd_path_report(root, scope)
    return cmd_report(root, scope, args.summary)


if __name__ == "__main__":
    sys.exit(main())
