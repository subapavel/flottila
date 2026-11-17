"""Tests for ci/change_guard.py: references in comments and hard-coded colours on the lines a change adds.

Run: python3 -m unittest ci.test_change_guard  (from the repository root; also run by the CI build job)

Every case builds a small git repository in a temporary directory with a base commit and a change commit, and
runs the guard there as a program (`python3 <guard> --base <base commit>`), the way CI runs it.
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

GUARD = Path(__file__).resolve().parent / "change_guard.py"
TIMEOUT = 120


class Repo:
    """A temporary git repository with a base commit; `change` writes files and commits them on top."""

    def __init__(self, root: Path, base_files: dict[str, str]):
        self.root = root
        self.env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(root), "GIT_CONFIG_NOSYSTEM": "1",
                    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
                    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com"}
        self.git("init", "-q")
        self.write(base_files)
        self.git("commit", "-q", "--allow-empty", "-m", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True,
                              env=self.env, timeout=TIMEOUT).stdout

    def write(self, files: dict[str, str]) -> None:
        for path, content in files.items():
            p = self.root / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            self.git("add", "--", path)

    def change(self, files: dict[str, str]) -> None:
        self.write(files)
        self.git("commit", "-q", "-m", "change")

    def run(self, *extra: str) -> tuple[int, list[str]]:
        done = subprocess.run([sys.executable, str(GUARD), "--base", self.base, *extra], cwd=self.root,
                              capture_output=True, text=True, env=self.env, timeout=TIMEOUT)
        out = done.stdout + done.stderr
        assert "Traceback" not in out, out
        return done.returncode, [line for line in out.splitlines() if line.strip()]


class ChangeGuardTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def repo(self, base_files: dict[str, str] | None = None) -> Repo:
        return Repo(Path(self.tmp.name), base_files or {})

    def findings(self, repo: Repo, count: int) -> list[str]:
        code, lines = repo.run()
        self.assertEqual(1, code, "\n".join(lines))
        self.assertEqual(count + 1, len(lines), "\n".join(lines))
        self.assertEqual(f"change guard: {count} finding(s)", lines[-1])
        return lines[:-1]

    def assertClean(self, repo: Repo) -> None:
        code, lines = repo.run()
        self.assertEqual(0, code, "\n".join(lines))
        self.assertEqual(1, len(lines), "\n".join(lines))
        self.assertTrue(lines[0].startswith("change guard: clean"), lines[0])

    # references in comments ------------------------------------------------------------------

    def test_every_reference_form_in_a_java_comment_is_a_finding_with_file_and_line(self):
        repo = self.repo()
        repo.change({"platform/core/A.java": "\n".join([
            "package a;",
            "/** Retries the call (D-164). */",
            "class A {",
            "  // see F-55 and FIX-268",
            "  /* MAP-12b, BRAND-3, OPS-2 */",
            "  int x; // P0-10 ISSUE-9",
            "  /**",
            "   * ADR-0019 and #123",
            "   */",
            "}",
        ]) + "\n"})
        self.assertEqual([
            "platform/core/A.java:2: a reference in a comment (D-164): say what the code does and why instead",
            "platform/core/A.java:4: a reference in a comment (F-55, FIX-268): say what the code does and why instead",
            "platform/core/A.java:5: a reference in a comment (MAP-12b, BRAND-3, OPS-2): "
            "say what the code does and why instead",
            "platform/core/A.java:6: a reference in a comment (P0-10, ISSUE-9): say what the code does and why instead",
            "platform/core/A.java:8: a reference in a comment (ADR-0019, #123): say what the code does and why instead",
        ], self.findings(repo, 5))

    def test_only_added_and_modified_lines_are_checked(self):
        old = "package a;\n// old D-1\nclass A {\n  // kept FIX-2\n  int y;\n}\n"
        repo = self.repo({"mapping/engine/A.java": old})
        repo.change({"mapping/engine/A.java": old.replace("int y;", "int y; // new D-3")})
        self.assertEqual(["mapping/engine/A.java:5: a reference in a comment (D-3): "
                          "say what the code does and why instead"], self.findings(repo, 1))

    def test_a_reference_outside_a_comment_or_a_standard_name_is_not_a_finding(self):
        repo = self.repo()
        repo.change({
            "platform/core/A.java": "\n".join([
                "class A {",
                '  String s = "D-164 // F-55 #12";',
                '  String t = """',
                "      /* FIX-268 */",
                '      """;',
                "  char c = '/'; // UTF-8, SHA-256, ISO-8859-1 and RFC-9110; issue tracker id PO-200001",
                "  // {@link B#run} and the entity &#123;",
                "}",
            ]) + "\n",
            "docs/notes.md": "D-164 in a document\n",
            "ci/x.py": "# D-164 in a script outside the checked folders\n",
            "bench/B.java": "// D-164 outside platform/ and mapping/\n",
        })
        self.assertClean(repo)

    def test_comments_in_typescript_and_css_of_the_ui_are_checked(self):
        repo = self.repo()
        repo.change({
            "mapping/ui/src/a.tsx": "\n".join([
                "// the tree (D-12)",
                "const re = /#1/; const u = 'x // #2';",
                "export const A = () => <div>{/* MAP-7 */}</div>;",
                "const d = a / b; // #44",
            ]) + "\n",
            "mapping/ui/src/a.css": "/* spacing of the rows (#157) */\n.a { margin: 0; }\n",
        })
        self.assertEqual([
            "mapping/ui/src/a.css:1: a reference in a comment (#157): say what the code does and why instead",
            "mapping/ui/src/a.tsx:1: a reference in a comment (D-12): say what the code does and why instead",
            "mapping/ui/src/a.tsx:3: a reference in a comment (MAP-7): say what the code does and why instead",
            "mapping/ui/src/a.tsx:4: a reference in a comment (#44): say what the code does and why instead",
        ], self.findings(repo, 4))

    # hard-coded colours ----------------------------------------------------------------------

    def test_hard_coded_colours_in_css_are_findings(self):
        repo = self.repo()
        repo.change({"mapping/ui/src/a.css": "\n".join([
            ".a {",
            "  border: 1px solid #c2cbd4;",
            "  color: rgb(0 0 0);",
            "  background: hsla(0, 0%, 100%, 0.5);",
            "  outline-color: white;",
            "  color: var(--pf-t--global--text--color--regular);",
            "  background: var(--pf-v6-global--BackgroundColor--100);",
            "  border-color: transparent currentColor;",
            "}",
            ".red:hover, #root { color: inherit; }",
            "/* #fff in a comment is not a colour */",
        ]) + "\n"})
        self.assertEqual([
            "mapping/ui/src/a.css:2: a hard-coded colour (#c2cbd4): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
            "mapping/ui/src/a.css:3: a hard-coded colour (rgb()): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
            "mapping/ui/src/a.css:4: a hard-coded colour (hsla()): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
            "mapping/ui/src/a.css:5: a hard-coded colour (white): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
        ], self.findings(repo, 4))

    def test_hard_coded_colours_in_tsx_strings_are_findings(self):
        repo = self.repo()
        repo.change({"mapping/ui/src/a.tsx": "\n".join([
            "const EDGE = '#567187';",
            "const s = { stroke: on ? 'rgba(0,0,0,.5)' : EDGE, color: 'red' };",
            "const t = <svg><path fill=\"white\" /></svg>;",
            "const l = <Label color=\"grey\">grey</Label>;",
            "const v = { color: 'var(--pf-t--global--color--brand--default)' };",
            "const name = 'red';",
            "const ok = `${'#/definitions'}`;",
        ]) + "\n"})
        self.assertEqual([
            "mapping/ui/src/a.tsx:1: a hard-coded colour (#567187): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
            "mapping/ui/src/a.tsx:2: a hard-coded colour (rgba(), red): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
            "mapping/ui/src/a.tsx:3: a hard-coded colour (white): use a PatternFly token, "
            "var(--pf-t--...) or var(--pf-v6-...)",
        ], self.findings(repo, 3))

    def test_colours_in_the_site_tests_and_generated_code_are_not_findings(self):
        repo = self.repo()
        repo.change({
            "site/style.css": ":root { --bg: #ffffff; }\n",
            "mapping/ui/src/a.test.tsx": "expect(x).toHaveStyle('color: rgb(0, 0, 0)');\n",
            "mapping/ui/e2e/a.ts": "const c = '#000000';\n",
            "mapping/ui/src/generated/api.ts": "const c = '#000';\n",
            "platform/A.java": 'class A { String c = "#ffffff"; }\n',
        })
        self.assertClean(repo)

    # the run ---------------------------------------------------------------------------------

    def test_a_deleted_file_and_an_unchanged_repository_are_clean(self):
        repo = self.repo({"platform/A.java": "// D-1\n"})
        repo.git("rm", "-q", "platform/A.java")
        repo.git("commit", "-q", "-m", "delete")
        self.assertClean(repo)

    def test_the_working_tree_is_not_read(self):
        repo = self.repo()
        repo.change({"platform/A.java": "class A {}\n"})
        (repo.root / "platform/A.java").write_text("// D-1\n", encoding="utf-8")
        self.assertClean(repo)

    def test_an_unknown_base_is_a_usage_error(self):
        repo = self.repo()
        done = subprocess.run([sys.executable, str(GUARD), "--base", "no-such-commit"], cwd=repo.root,
                              capture_output=True, text=True, env=repo.env, timeout=TIMEOUT)
        self.assertEqual(2, done.returncode, done.stdout + done.stderr)
        self.assertIn("CHANGE GUARD: git diff", done.stderr)


if __name__ == "__main__":
    unittest.main()
