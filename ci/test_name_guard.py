"""Tests for ci/name_guard.py — the old product name does not come back (BRAND-4, D-148, D-149).

Run: python3 -m unittest ci.test_name_guard  (from the repository root; also run by the CI build job)

Every case builds its own small git repository in a temporary directory, copies the guard to
`ci/name_guard.py` there and runs it as a program from that root (`python3 ci/name_guard.py`), the
way CI runs it. The copied guard and the case's `ci/name-allowlist.txt` stay untracked in the
temporary repository. The last case runs the guard on this repository with the real allow-list.

This file holds the old name as test data: the real allow-list covers it whole
(`ci/test_name_guard.py | * | <reason>`, packet BRAND-4 "The rename's own files").
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GUARD = REPO / "ci" / "name_guard.py"
TIMEOUT = 120


def number_in(n: int, text: str) -> bool:
    """`n` appears in `text` as a whole number (not as a digit of a longer one)."""
    return re.search(rf"(?<!\d){n}(?!\d)", text) is not None


class Repo:
    """A temporary git repository; files are tracked unless `track=False`."""

    def __init__(self, root: Path):
        self.root = root
        self.env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(root),
                    "GIT_CONFIG_NOSYSTEM": "1", "PYTHONUNBUFFERED": "1", "PYTHONIOENCODING": "utf-8"}
        self.git("init", "-q")
        self.allow("# no entries")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True,
                              env=self.env, timeout=TIMEOUT).stdout

    def write(self, path: str, content, *, track: bool = True) -> None:
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            p.write_bytes(content)
        else:
            p.write_text(content, encoding="utf-8")
        if track:
            self.git("add", "-f", "--", path)

    def allow(self, *lines: str) -> None:
        p = self.root / "ci" / "name-allowlist.txt"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("".join(line + "\n" for line in lines), encoding="utf-8")

    def run(self, test: unittest.TestCase):
        """Runs the guard from the repository root; returns (exit code, non-empty output lines, whole output)."""
        test.assertTrue(GUARD.is_file(), f"{GUARD} does not exist")
        shutil.copyfile(GUARD, self.root / "ci" / "name_guard.py")
        done = subprocess.run([sys.executable, "ci/name_guard.py"], cwd=self.root, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, env=self.env, timeout=TIMEOUT)
        out = done.stdout.decode("utf-8", errors="replace")
        test.assertNotIn("Traceback", out, "the guard crashed:\n" + out)
        return done.returncode, [line for line in out.splitlines() if line.strip()], out


class NameGuardTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def assertFindings(self, code, lines, out, count: int):
        """Exit 1, one line per finding, the count on the last line; returns the finding lines."""
        self.assertEqual(1, code, out)
        self.assertEqual(count + 1, len(lines), "one line per finding and a count line:\n" + out)
        self.assertTrue(number_in(count, lines[-1]), f"the last line carries the count {count}:\n" + out)
        return lines[:-1]

    def assertClean(self, code, lines, out, files: int):
        self.assertEqual(0, code, out)
        self.assertEqual(1, len(lines), "a clean run prints one line:\n" + out)
        self.assertTrue(number_in(files, lines[0]), f"the line carries the {files} files scanned:\n" + out)

    # 1 -------------------------------------------------------------------------------------------

    def test_untracked_and_ignored_files_holding_the_name_are_not_findings(self):
        self.repo.write(".gitignore", "build/\n")
        self.repo.write("README.md", "# Rail8\n")
        self.repo.write("notes.txt", "a line\nflottila was here\n", track=False)
        self.repo.write("build/out.txt", "flottila build output\n", track=False)
        self.assertClean(*self.repo.run(self), files=2)

        self.repo.git("add", "--", "notes.txt")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        self.assertEqual(["notes.txt:2: flottila was here"], findings, "the same file, once tracked, is a finding")

        self.repo.git("add", "-f", "--", "build/out.txt")
        findings = self.assertFindings(*self.repo.run(self), count=2)
        self.assertEqual({"notes.txt:2: flottila was here", "build/out.txt:1: flottila build output"}, set(findings),
                         "an ignored file, once tracked, is a finding")

    # 2 -------------------------------------------------------------------------------------------

    def test_the_name_in_any_letter_case_is_a_finding_naming_file_and_line(self):
        self.repo.write("a.txt", "one\ntwo\nname = flottila\n")
        self.repo.write("B.java", "class B {\n// Flottila node\n}\n")
        self.repo.write("env.sh", "export FLOTTILA_NODE_ID=n1\n")
        self.repo.write("src/C.java", "package x;\n\n\n\nimport io.flottila.api.Envelope;\n")
        self.repo.write("mixed.md", "x\ny\nz\nw\nv\nu\nthe fLoTtIlA way\n")
        self.repo.write("clean.md", "Rail8 only\n")
        findings = self.assertFindings(*self.repo.run(self), count=5)
        self.assertEqual({"a.txt:3: name = flottila",
                          "B.java:2: // Flottila node",
                          "env.sh:1: export FLOTTILA_NODE_ID=n1",
                          "src/C.java:5: import io.flottila.api.Envelope;",
                          "mixed.md:7: the fLoTtIlA way"}, set(findings))

    def test_a_finding_carries_at_most_160_characters_of_its_line(self):
        line = "flottila " + "x" * 300
        self.repo.write("long.txt", "first\n" + line + "\n")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        prefix = "long.txt:2: "
        self.assertTrue(findings[0].startswith(prefix), findings[0])
        text = findings[0][len(prefix):]
        self.assertTrue(0 < len(text) <= 160, f"{len(text)} characters of the line printed")
        self.assertIn(text, line)

    # 3 -------------------------------------------------------------------------------------------

    def test_a_tracked_path_holding_the_name_is_a_finding_even_with_clean_content(self):
        self.repo.write("bin/rail8", "#!/bin/sh\necho hi\n")
        self.repo.write("src/io/rail8/A.java", "class A {}\n")
        self.assertClean(*self.repo.run(self), files=2)

        self.repo.git("mv", "bin/rail8", "bin/flottila")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        self.assertIn("bin/flottila", findings[0])
        self.assertIsNone(re.search(r"bin/flottila:\d+:", findings[0]), "the content is clean: no line number")
        self.assertTrue(findings[0].startswith("bin/flottila: "), findings[0])
        self.assertIn("the path holds the name", findings[0], "the finding says it is the path (ruling 4)")
        self.assertNotIn("UTF-8", findings[0], "a path finding is not worded as a not-UTF-8 finding")

        (self.repo.root / "src/io/flottila").mkdir(parents=True)
        self.repo.git("mv", "src/io/rail8/A.java", "src/io/flottila/A.java")
        findings = self.assertFindings(*self.repo.run(self), count=2)
        self.assertEqual(1, sum("bin/flottila" in f for f in findings), findings)
        self.assertEqual(1, sum("src/io/flottila/A.java" in f for f in findings), findings)

    # 4 -------------------------------------------------------------------------------------------

    def test_a_file_that_is_not_utf8_is_scanned_as_bytes_and_reported_by_path_alone(self):
        png = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\xff\xfe\x80\n\xc3\x28tEXtflottila\x00\xff\n"
        self.repo.write("img/logo.png", png)
        self.repo.write("img/clean.png", b"\x89PNG\r\n\x1a\n\xff\xfe\x80\xc3\x28\x00rail8\x00")
        self.assertFindings(*self.repo.run(self), count=1)

        jar = self.repo.root / "lib" / "x.jar"
        jar.parent.mkdir(parents=True)
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_STORED) as z:     # stored: the name once, in the entry's bytes
            z.writestr("A.class", b"\xca\xfe\xba\xbe\xff\xfe\x80\x07Lio/flottila/api/Envelope;\x00\xff")
        self.repo.git("add", "--", "lib/x.jar")
        code, lines, out = self.repo.run(self)
        findings = self.assertFindings(code, lines, out, count=2)
        for path in ("img/logo.png", "lib/x.jar"):
            mine = [f for f in findings if path in f]
            self.assertEqual(1, len(mine), f"{path} reported once:\n{out}")
            self.assertIsNone(re.search(re.escape(path) + r":\d+", mine[0]), f"{path} reported without a line number")
            self.assertTrue(mine[0].startswith(path + ": "), mine[0])
            self.assertIn("not UTF-8", mine[0], "the finding says it is a file that is not UTF-8 (ruling 4)")
            self.assertNotIn("the path holds the name", mine[0], "a not-UTF-8 finding is not worded as a path finding")
        self.assertNotIn("img/clean.png", out, "a binary file without the name is not a finding")

    # 5 -------------------------------------------------------------------------------------------

    def test_a_star_entry_allows_every_occurrence_in_matched_files_path_included(self):
        self.repo.write("docs/archive/a/b.md", "x\nflottila history\n")
        self.repo.write("docs/archive/a/b/c.md", "FLOTTILA deeper\n")
        self.repo.write("docs/archive/flottila/d.md", "the path holds the name, this line does not\n")
        self.repo.write("README.md", "Rail8\n")
        self.assertFindings(*self.repo.run(self), count=3)

        self.repo.allow("# history", "docs/archive/** | * | history keeps the old name (D-148)")
        self.assertClean(*self.repo.run(self), files=4)

    def test_a_single_star_does_not_cross_a_directory(self):
        self.repo.write("deploy/postgres/V1.sql", "-- flottila schema\n")
        self.repo.write("deploy/postgres/x/V1.sql", "select 1;\n-- flottila nested\n")
        self.repo.write("deploy/postgres/V9/old.sql", "-- flottila under a V directory\n")  # V*.sql would match if * crossed /
        self.repo.allow("deploy/postgres/V*.sql | * | applied migrations are byte-identical forever")
        findings = self.assertFindings(*self.repo.run(self), count=2)
        self.assertEqual({"deploy/postgres/x/V1.sql:2: -- flottila nested",
                          "deploy/postgres/V9/old.sql:1: -- flottila under a V directory"}, set(findings))

    # 6 -------------------------------------------------------------------------------------------

    def test_a_literal_entry_allows_only_that_literal_in_the_files_its_glob_matches(self):
        self.repo.write("notes.md", "intro\nsee github.com/subapavel/flottila and flottila-core\n")
        self.repo.write("readme.md", "clone github.com/subapavel/flottila\n")
        self.repo.write("src/A.java", "// github.com/subapavel/flottila\n")
        self.repo.allow("*.md | github.com/subapavel/flottila | the repository's address until the owner renames it")
        findings = self.assertFindings(*self.repo.run(self), count=2)
        self.assertEqual({"notes.md:2: see github.com/subapavel/flottila and flottila-core",
                          "src/A.java:1: // github.com/subapavel/flottila"}, set(findings),
                         "the URL alone (readme.md) is allowed; flottila-core and a file outside the glob are not")

    def test_an_allowed_literal_does_not_allow_a_different_name_sharing_its_prefix(self):
        self.repo.write("x.md", "one\nuses flottila-mapping here\nthree\nuses flottila-mapper here\n")
        self.repo.allow("*.md | flottila-mapping | the mapping repository's name")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        self.assertEqual(["x.md:4: uses flottila-mapper here"], findings)

    def test_an_occurrence_is_credited_to_the_longest_covering_literal_only(self):
        # ruling 6: the shorter literal is listed first, so neither "first in the list" nor "every covering literal" passes
        short = "** | subapavel/flottila | the repository's name until the owner renames it"
        longer = "** | subapavel/flottila-mapping | the mapping repository's name until the owner renames it"
        self.repo.allow("# addresses", short, longer)
        self.repo.write("README.md", "intro\nsee github.com/subapavel/flottila-mapping for the engine\n")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        only = findings[0]   # exactly one finding (assertFindings): nothing else is reported
        self.assertTrue(only.startswith("ci/name-allowlist.txt:2: ") and "unused allow-list entry" in only
                        and only.endswith(short),
                        "only the longer literal occurs: the shorter entry (line 2) is credited with nothing: " + only)

        self.repo.write("docs/x.md", "clone subapavel/flottila\n")
        self.assertClean(*self.repo.run(self), files=2)

    def test_one_finding_per_line_one_per_non_utf8_file_and_a_path_finding_beside_the_line_findings(self):
        # ruling 5 of the first list
        self.repo.write("twice.txt", "first\nflottila and FLOTTILA on one line\n")
        self.repo.write("blob.bin", b"\xff\xfeflottila\x00\x80FLOTTILA\xff")
        self.repo.write("tools/flottila.md", "flottila here\nclean\n")
        findings = self.assertFindings(*self.repo.run(self), count=4)
        self.assertEqual(1, sum(f.startswith("twice.txt") for f in findings), "one finding per line:\n" + "\n".join(findings))
        self.assertIn("twice.txt:2: flottila and FLOTTILA on one line", findings)
        self.assertEqual(1, sum(f.startswith("blob.bin") for f in findings),
                         "one finding per file that is not UTF-8:\n" + "\n".join(findings))
        mine = sorted(f for f in findings if f.startswith("tools/flottila.md"))
        self.assertEqual(2, len(mine), "the path finding and the line finding, each on its own:\n" + "\n".join(findings))
        self.assertIn("tools/flottila.md:1: flottila here", mine)
        self.assertTrue(any(f.startswith("tools/flottila.md: ") and "the path holds the name" in f for f in mine), mine)

    def test_a_star_entry_takes_the_credit_before_a_literal_over_the_same_file(self):
        star = "docs/archive/** | * | history keeps the old name (D-148)"
        literal = "** | subapavel/flottila | the repository's name until the owner renames it"
        self.repo.allow("# star before literal", star, literal)
        self.repo.write("docs/archive/a.md", "see github.com/subapavel/flottila\n")
        self.repo.write("README.md", "Rail8\n")
        findings = self.assertFindings(*self.repo.run(self), count=1)
        self.assertTrue(findings[0].startswith("ci/name-allowlist.txt:3: ") and "unused allow-list entry" in findings[0]
                        and findings[0].endswith(literal),
                        "the * entry took the credit: the literal entry (line 3) is unused: " + findings[0])

        self.repo.write("README.md", "Rail8, cloned from subapavel/flottila\n")
        self.assertClean(*self.repo.run(self), files=2)

    def test_a_literal_covers_a_path_and_the_bytes_of_a_non_utf8_file(self):
        self.repo.write("tools/flottila-m1/run.sh", "echo clean\n")
        self.repo.write("img/p.bin", b"\xff\xfe built by flottila-proto \x80\xff")
        findings = self.assertFindings(*self.repo.run(self), count=2)
        self.assertEqual({"tools/flottila-m1/run.sh", "img/p.bin"}, {f.split(": ")[0] for f in findings}, findings)

        self.repo.allow("tools/** | flottila-m1 | the prototype's name",
                        "img/*.bin | flottila-proto | the prototype's other name")
        self.assertClean(*self.repo.run(self), files=2)   # clean: no finding, so neither entry is unused

    def test_a_tracked_symbolic_link_is_scanned_by_its_link_text_never_its_target(self):
        # ruling 4 of "After review r1": what git stores for a link is its text (os.readlink)
        self.repo.write("notes.txt", "flottila notes\n", track=False)
        (self.repo.root / "docs").mkdir()
        os.symlink("../notes.txt", self.repo.root / "docs" / "current.md")       # clean text, untracked target holds the name
        (self.repo.root / "bin").mkdir()
        os.symlink("../lib/flottila-run.sh", self.repo.root / "bin" / "run")     # the text holds the name, target missing
        self.repo.git("add", "--", "docs/current.md", "bin/run")
        self.repo.write("README.md", "Rail8\n")
        code, lines, out = self.repo.run(self)
        findings = self.assertFindings(code, lines, out, count=1)
        self.assertTrue(findings[0].startswith("bin/run"), "the link whose text holds the name is a finding:\n" + out)
        self.assertNotIn("docs/current.md", out, "a link is never followed to its target")

    def test_an_allow_list_that_is_not_utf8_exits_2_and_names_the_allow_list(self):
        self.repo.write("docs/archive/a.md", "flottila history\n")
        (self.repo.root / "ci" / "name-allowlist.txt").write_bytes(
            b"# list\ndocs/archive/** | * | history\ndocs/** | caf\xff | a byte that is not UTF-8\n")
        code, lines, out = self.repo.run(self)          # run() asserts no traceback
        self.assertEqual(2, code, out)
        self.assertIn("ci/name-allowlist.txt", out)

    # 7 -------------------------------------------------------------------------------------------

    def test_a_malformed_allow_list_entry_exits_2_and_names_its_line(self):
        self.repo.write("docs/archive/a.md", "flottila history\n")
        self.repo.write("deploy/V1.sql", "-- flottila\n")
        head = ["# allow-list for the test", "", "docs/archive/** | * | history",
                "# the line under test follows", "", ""]       # the line under test is line 7

        self.repo.allow(*head, "deploy/V*.sql | * | applied migrations")
        self.assertClean(*self.repo.run(self), files=2)

        for bad in ("deploy/V*.sql | * |",               # no reason
                    "deploy/V*.sql | * |    ",           # a blank reason
                    " | * | applied migrations",         # empty glob
                    "deploy/V*.sql |  | applied migrations",   # empty literal cell (ruling 2, implementer ruling 4)
                    "deploy/V*.sql | *",                 # two cells
                    "deploy/V*.sql"):                    # one cell
            with self.subTest(line=bad):
                self.repo.allow(*head, bad)
                code, lines, out = self.repo.run(self)
                self.assertEqual(2, code, out)
                self.assertTrue(any(line.startswith("ci/name-allowlist.txt:7: ") for line in lines),
                                "the allow-list line is named by path and line number (ruling 2):\n" + out)

    # 8 -------------------------------------------------------------------------------------------

    def test_an_entry_that_allows_nothing_is_an_unused_allow_list_entry_finding(self):
        self.repo.write("README.md", "prototype flottila-m1\n")
        self.repo.write("src/A.java", "class A {}\n")
        used = "*.md | flottila-m1 | the prototype's name"
        cases = {"a glob matching no file": "docs/gone/** | * | removed history",
                 "a glob matching only clean files": "src/*.java | * | old sources",
                 "a literal absent from the tree": "*.md | flottila-proto | the prototype's other name"}
        for what, entry in cases.items():
            with self.subTest(what):
                self.repo.allow(used, entry)
                findings = self.assertFindings(*self.repo.run(self), count=1)
                self.assertIn("unused allow-list entry", findings[0])
                self.assertTrue(findings[0].startswith("ci/name-allowlist.txt:2: ") and findings[0].endswith(entry),
                                "named by path and line number, then the entry's text (ruling 3): " + findings[0])

        self.repo.write("docs/gone/a.md", "flottila history\n")
        self.repo.allow(used, cases["a glob matching no file"])
        self.assertClean(*self.repo.run(self), files=3)

    # 9 -------------------------------------------------------------------------------------------

    def test_a_clean_run_prints_one_line_with_the_number_of_files_scanned(self):
        for i in range(7):
            self.repo.write(f"d{i}/f{i}.txt", f"Rail8 file {i}\n")
        self.assertClean(*self.repo.run(self), files=7)

        self.repo.write("docs/archive/old.md", "flottila history\n")
        self.repo.allow("docs/archive/** | * | history keeps the old name (D-148)")
        self.assertClean(*self.repo.run(self), files=8)

    # 10 ------------------------------------------------------------------------------------------

    def test_the_real_tree_with_the_real_allow_list_is_clean(self):
        self.assertTrue(GUARD.is_file(), f"{GUARD} does not exist")
        done = subprocess.run([sys.executable, "ci/name_guard.py"], cwd=REPO, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=TIMEOUT)
        out = done.stdout.decode("utf-8", errors="replace")
        self.assertEqual(0, done.returncode, out[-4000:])


if __name__ == "__main__":
    unittest.main()
