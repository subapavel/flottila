"""Tests for ci/comment_only_check.py: a change that touches only comments leaves the code exactly as it was.

Run: python3 -m unittest ci.test_comment_only_check  (from the repository root; also run by the CI build job)

Every case builds a small git repository in a temporary directory with a base commit and a change, either
committed or left in the working tree, and runs the check there as a program, the way CI runs it.
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECK = Path(__file__).resolve().parent / "comment_only_check.py"
TIMEOUT = 120


class Repo:
    """A temporary git repository with a base commit; `change` writes files and, unless told not to, commits."""

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

    def write(self, files: dict[str, str | None]) -> None:
        for path, content in files.items():
            p = self.root / path
            if content is None:
                self.git("rm", "-q", "--", path)
                continue
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            self.git("add", "--", path)

    def change(self, files: dict[str, str | None], commit: bool = True) -> None:
        self.write(files)
        if commit:
            self.git("commit", "-q", "-m", "change")

    def run(self, *extra: str) -> tuple[int, list[str]]:
        done = subprocess.run([sys.executable, str(CHECK), "--base", self.base, *extra], cwd=self.root,
                              capture_output=True, text=True, env=self.env, timeout=TIMEOUT)
        out = done.stdout + done.stderr
        assert "Traceback" not in out, out
        return done.returncode, [line for line in out.splitlines() if line.strip()]


JAVA = """package a;

/**
 * Old Javadoc (D-12).
 */
final class A {
    // old line comment, see F-55
    private static final String URL = "http://example.com/*not a comment*/"; // trailing
    private static final char SLASH = '/';
    private static final String BLOCK = \"\"\"
        // inside a text block
        /* also inside */
        \"\"\";

    int run(int x) { /* inline */ return x /* why */ + 1; }

    void call() {
        go(/* none */);
    }
}
"""


class CommentOnlyCheckTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def repo(self, base_files: dict[str, str] | None = None) -> Repo:
        return Repo(Path(self.tmp.name), base_files or {})

    def assertClean(self, repo: Repo, *extra: str) -> None:
        code, lines = repo.run(*extra)
        self.assertEqual(0, code, "\n".join(lines))
        self.assertEqual(1, len(lines), "\n".join(lines))
        self.assertTrue(lines[0].startswith("comment-only check: clean"), lines[0])

    def findings(self, repo: Repo, files: int, *extra: str) -> list[str]:
        code, lines = repo.run(*extra)
        self.assertEqual(1, code, "\n".join(lines))
        self.assertEqual(f"comment-only check: {files} file(s) with changed code", lines[-1])
        return lines[:-1]

    def test_rewritten_removed_and_added_java_comments_are_clean(self):
        repo = self.repo({"src/A.java": JAVA})
        cleaned = (JAVA.replace("/**\n * Old Javadoc (D-12).\n */\n", "/** Adds one to a number. */\n")
                   .replace("    // old line comment, see F-55\n", "")
                   .replace(" // trailing", "")
                   .replace(" /* inline */", "")
                   .replace(" /* why */", "   ")
                   .replace("go(/* none */);", "go();")
                   .replace("    void call() {", "    /** Calls go with no arguments. */\n    void call() {"))
        repo.change({"src/A.java": cleaned})
        self.assertClean(repo, "--head", "HEAD")

    def test_a_changed_token_is_a_finding_with_both_lines(self):
        repo = self.repo({"src/A.java": JAVA})
        repo.change({"src/A.java": JAVA.replace("+ 1;", "+ 2;")})
        self.assertEqual([
            "src/A.java: the code differs (base line 15, head line 15)",
            "  base: int run(int x) { /* inline */ return x /* why */ + 1; }",
            "  head: int run(int x) { /* inline */ return x /* why */ + 2; }",
        ], self.findings(repo, 1, "--head", "HEAD"))

    def test_comment_markers_inside_literals_are_code(self):
        repo = self.repo({"src/A.java": JAVA, "ui/a.ts": "const u = 'a // b';\nconst r = /\\/*x/g;\n"})
        repo.change({
            "src/A.java": JAVA.replace("/*not a comment*/", "").replace("// inside a text block", ""),
            "ui/a.ts": "const u = 'a ';\nconst r = /\\/g;\n",
        })
        found = self.findings(repo, 2, "--head", "HEAD")
        self.assertEqual("src/A.java: the code differs (base line 8, head line 8)", found[0])
        self.assertIn("ui/a.ts: the code differs (base line 1, head line 1)", found)

    def test_a_text_block_keeps_its_whitespace(self):
        repo = self.repo({"src/A.java": JAVA})
        repo.change({"src/A.java": JAVA.replace("        // inside a text block", "    // inside a text block")})
        self.assertEqual(["src/A.java: the code differs (base line 10, head line 10)"],
                         self.findings(repo, 1, "--head", "HEAD")[:1])

    def test_whitespace_that_separates_words_counts(self):
        repo = self.repo({"src/A.java": "class A { int x = a /* c */ - -b; }\n"})
        repo.change({"src/A.java": "class A { int x = a --b; }\n"})
        self.findings(repo, 1, "--head", "HEAD")

    def test_typescript_and_css_comments_are_clean_and_regex_is_code(self):
        ts = "// header\nconst re = /[/]*/; /* trailing */\nexport const A = () => <div>{/* x */}</div>;\n"
        css = "/* rows */\n.a { margin: 0; /* why */ }\n.b::after { content: '/* kept */'; }\n"
        repo = self.repo({"ui/a.tsx": ts, "ui/a.css": css})
        repo.change({
            "ui/a.tsx": ts.replace("// header\n", "// the list of rows\n").replace(" /* trailing */", ""),
            "ui/a.css": css.replace("/* rows */\n", "").replace(" /* why */", ""),
        })
        self.assertClean(repo, "--head", "HEAD")
        repo.change({"ui/a.css": css.replace("/* kept */", "")})
        self.findings(repo, 1, "--head", "HEAD")

    def test_the_working_tree_is_compared_when_no_head_is_given(self):
        repo = self.repo({"src/A.java": JAVA})
        repo.change({"src/A.java": JAVA.replace("// trailing", "// the address of the example")}, commit=False)
        self.assertClean(repo)
        repo.change({"src/A.java": JAVA.replace("return x", "return -x")}, commit=False)
        self.findings(repo, 1)

    def test_added_deleted_renamed_and_other_files_are_findings_and_markdown_is_not(self):
        repo = self.repo({"src/A.java": JAVA, "src/B.java": "class B {}\n", "docs/a.md": "old\n"})
        repo.change({"src/A.java": None, "src/C.java": "class C {}\n", "docs/a.md": "new\n", "ci/x.py": "x = 1\n"})
        repo.git("mv", "src/B.java", "src/D.java")
        repo.git("commit", "-q", "-m", "rename")
        self.assertEqual([
            "ci/x.py: not a Java, TypeScript, JavaScript, CSS or Markdown file, which a comment cleanup does not change",
            "src/A.java: the file is deleted, which changes code",
            "src/B.java: the file is deleted, which changes code",
            "src/C.java: the file is added, which changes code",
            "src/D.java: the file is added, which changes code",
        ], self.findings(repo, 5, "--head", "HEAD"))

    def test_a_unicode_escape_that_ends_a_java_comment_is_seen(self):
        base = "class A {\n  void f() { g(); }\n}\n"
        repo = self.repo({"src/A.java": base})
        repo.change({"src/A.java": base.replace("g(); }", "g(); // ok \\u000a h(); }")})
        self.findings(repo, 1, "--head", "HEAD")
        repo.change({"src/A.java": base.replace("g(); }", "g(); /* ok *\\u002f h(); /* */ }")})
        self.findings(repo, 1, "--head", "HEAD")

    def test_an_escaped_backslash_before_u_is_not_a_unicode_escape(self):
        base = 'class A {\n  String s = "\\\\u000a";\n}\n'
        repo = self.repo({"src/A.java": base})
        repo.change({"src/A.java": base.replace(";\n", "; // a backslash and u000a\n")})
        self.assertClean(repo, "--head", "HEAD")

    def test_only_the_named_files_are_checked(self):
        repo = self.repo({"src/A.java": JAVA, "src/B.java": "class B {}\n"})
        repo.change({"src/B.java": "class B { int x; }\n"})
        self.assertClean(repo, "--head", "HEAD", "src/A.java")
        self.findings(repo, 1, "--head", "HEAD", "src/B.java")

    def test_a_file_of_another_kind_or_an_unknown_commit_is_a_usage_error(self):
        repo = self.repo()
        self.assertEqual(2, repo.run("--head", "HEAD", "docs/a.md")[0])
        self.assertEqual(2, repo.run("--head", "no-such-commit")[0])


if __name__ == "__main__":
    unittest.main()
